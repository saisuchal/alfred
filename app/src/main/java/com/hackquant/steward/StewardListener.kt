package com.hackquant.steward

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow

/** Watches incoming message notifications from the apps the user picked and hands them to the agent. */
class StewardListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: StewardListener? = null
        val connected = MutableStateFlow(false)
    }

    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 300
    }

    override fun onListenerConnected() {
        instance = this
        connected.value = true
        Store.init(this)
        Llm.load(this) // the app UI may never have been opened since boot
    }

    override fun onListenerDisconnected() {
        instance = null
        connected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val replyAction = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        if (replyAction == null && n.category != Notification.CATEGORY_MESSAGE) return

        val config = Store.config.value
        val app = config.apps.firstOrNull { it.pkg == sbn.packageName }
        if (app == null) {
            // A new messaging app: list it in settings so the user can opt in.
            val label = appLabel(sbn.packageName)
            Store.update { it.copy(apps = it.apps + WatchedApp(sbn.packageName, label, false)) }
            return
        }
        if (!app.enabled || config.paused) return

        val extras = n.extras
        // Messaging-style notifications carry the thread; the last entry is the newest message.
        @Suppress("DEPRECATION")
        val thread = extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.mapNotNull { it as? Bundle }.orEmpty()
        val last = thread.lastOrNull()
        // The owner replied themselves: learn from it and close what Alfred was holding for this person.
        if (last != null && isFromUser(extras, last)) {
            val mine = last.getCharSequence("text")?.toString().orEmpty()
            if (mine.isNotBlank() && !Replies.sentRecently(mine)) {
                val theirs = thread.dropLast(1).lastOrNull { !isFromUser(extras, it) }?.getCharSequence("text")?.toString()
                val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                title?.let(::cleanName)?.takeIf { it.isNotBlank() }?.let { who -> Owner.ownerReplied(this, who, theirs, mine) }
            }
            return
        }
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.let(::cleanName)
        val person = (last?.getCharSequence("sender")?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())?.let(::cleanName) ?: return
        if (person.isBlank() || person.equals("You", ignoreCase = true)) return
        val sender = if (conversation != null && conversation != person) "$person in $conversation" else person
        val text = last?.getCharSequence("text")?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        if (text.isBlank() || Regex("""^\d+ new messages?$""").matches(text)) return
        if (isFromUser(extras, last) || Replies.sentRecently(text)) return

        synchronized(seen) { if (seen.put("$sender|$text", true) != null) return }
        if (isEditOfRecent(sender, text)) return

        if (sender in config.muted || person in config.muted) {
            cancelNotification(sbn.key)
            return
        }
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) || (conversation != null && conversation != person)
        Agent.submit(this, Incoming(
            sender, app.label, sbn.packageName, text,
            isGroup = isGroup,
            chat = if (isGroup) conversation ?: person else null,
            replyId = replyAction?.let { Replies.register(it) },
            key = sbn.key,
        ))
    }

    private val lastBySender = HashMap<String, Pair<String, Long>>()

    /** WhatsApp re-posts an edited message (e.g. a fixed typo). Treat a near-identical one within 30 s as the same. */
    @Synchronized
    private fun isEditOfRecent(sender: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        val prev = lastBySender.put(sender, text to now) ?: return false
        if (now - prev.second > 30_000) return false
        val a = prev.first.lowercase(); val b = text.lowercase()
        val common = a.zip(b).takeWhile { (x, y) -> x == y }.size
        // Only longer messages: short ones like "can you come?" / "can you call?" are genuinely different.
        return minOf(a.length, b.length) >= 20 && common >= minOf(a.length, b.length) * 0.85 && kotlin.math.abs(a.length - b.length) <= 5
    }

    /** After you reply, apps re-post the thread with your message last. That's not an incoming message. */
    private fun isFromUser(extras: Bundle, last: Bundle?): Boolean {
        if (last == null) return false
        val senderName = last.getCharSequence("sender")?.toString()
        @Suppress("DEPRECATION")
        val senderPerson = last.getParcelable<android.app.Person>("sender_person")
        if (senderName == null && senderPerson == null) return true
        @Suppress("DEPRECATION")
        val me = extras.getParcelable<android.app.Person>(Notification.EXTRA_MESSAGING_PERSON)?.name?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()
        val name = senderPerson?.name?.toString() ?: senderName
        return name != null && (name == me || name.equals("You", ignoreCase = true))
    }

    private fun appLabel(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
