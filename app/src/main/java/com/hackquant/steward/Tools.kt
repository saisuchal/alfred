package com.hackquant.steward

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Inline-reply handles captured from other apps' notifications. */
object Replies {
    private val ids = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, Notification.Action>()

    fun register(action: Notification.Action): Int = ids.getAndIncrement().also { pending[it] = action }

    fun canSend(id: Int?) = id != null && pending.containsKey(id)

    private val recent = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, Long>>()

    /** True if Alfred itself sent this text in the last few minutes. */
    fun sentRecently(text: String): Boolean {
        val now = System.currentTimeMillis()
        recent.removeIf { now - it.second > 5 * 60_000 }
        return recent.any { it.first == text.trim() }
    }

    fun send(ctx: Context, id: Int, text: String): Boolean {
        val action = pending[id] ?: return false
        val inputs = action.remoteInputs ?: return false
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        recent.add(text.trim() to System.currentTimeMillis())
        return runCatching { action.actionIntent.send(ctx, 0, intent) }.isSuccess
    }
}

data class Result(val ok: Boolean, val summary: String, val undo: String? = null, val sent: String? = null)

object Tools {
    private const val CHANNEL = "steward"
    private const val APPROVAL_CHANNEL = "steward_approvals"
    private val pretty = SimpleDateFormat("EEE d MMM, h:mm a", Locale.US)

    private fun time(s: String) = runCatching { Agent.stamp.parse(s)!!.time }.getOrNull()

    /** Human-readable description of a proposed action, before it runs. */
    fun describe(skill: Skill, a: JSONObject): String = when (skill) {
        Skill.REPLY -> "Reply: “${a.optString("text")}”"
        Skill.CALENDAR -> "Add to calendar: ${a.optString("title")}" +
            (time(a.optString("start"))?.let { " · ${pretty.format(it)}" } ?: "") +
            a.optString("location").takeIf { it.isNotBlank() }?.let { " @ $it" }.orEmpty()
        Skill.REMINDER -> "Remind me: ${a.optString("text")}" +
            (a.optString("place").takeIf { it.isNotBlank() }?.let { " · when I reach $it" }
                ?: time(a.optString("at"))?.let { " · ${pretty.format(it)}" } ?: "")
        Skill.REMEMBER -> "Remember: ${a.optString("fact")}"
        Skill.MUTE -> "Mute this sender" + a.optString("reason").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        Skill.BRIEF -> "Tell you: ${a.optString("summary")}"
    }

    fun execute(ctx: Context, msg: Incoming, skill: Skill, a: JSONObject): Result = try {
        when (skill) {
            Skill.REPLY -> reply(ctx, msg, a.optString("text"))
            Skill.CALENDAR -> calendar(ctx, a)
            Skill.REMINDER -> reminder(ctx, a)
            Skill.REMEMBER -> remember(a.optString("fact"))
            Skill.MUTE -> mute(msg)
            Skill.BRIEF -> brief(ctx, msg, a.optString("summary"))
        }
    } catch (e: Exception) {
        Result(false, "${describe(skill, a)} · failed: ${e.message}")
    }

    private fun reply(ctx: Context, msg: Incoming, draft: String): Result {
        if (draft.isBlank()) return Result(false, "Empty reply skipped")
        val id = msg.replyId
        if (id == null || !Replies.canSend(id)) return Result(false, "Couldn't reply: the original notification is gone. Draft: “$draft”")
        val text = disclosed(msg.sender, draft.trim())
        if (!Replies.send(ctx, id, text)) return Result(false, "Couldn't send reply: “$text”")
        Store.update { it.copy(introduced = it.introduced + (msg.sender to System.currentTimeMillis())) }
        return Result(true, "Replied: “$text”", sent = text)
    }

    /** Alfred never passes itself off as the user: every message it sends is signed on its own last line. */
    @Suppress("UNUSED_PARAMETER")
    fun disclosed(sender: String, text: String): String {
        val owner = Store.config.value.userName.trim()
        val signature = if (owner.isNotBlank()) "🤖 Alfred, $owner's AI assistant" else "🤖 Sent by an AI assistant"
        return "$text\n$signature"
    }

    private fun calendar(ctx: Context, a: JSONObject): Result {
        val title = a.optString("title")
        val location = a.optString("location")
        val t = time(a.optString("start")) ?: return Result(false, "Calendar: couldn't understand the time")
        val label = describe(Skill.CALENDAR, a).removePrefix("Add to calendar: ")
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            return Result(false, "Calendar permission needed for: $label")
        val (calId, calName) = calendarId(ctx) ?: return Result(false, "No writable calendar on this phone")
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.DTSTART, t)
            put(CalendarContract.Events.DTEND, t + 60 * 60 * 1000)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.DESCRIPTION, "Added by Alfred")
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
            ?: return Result(false, "Calendar refused the event")
        return Result(true, "Added to $calName: $label", "calendar:$uri")
    }

    /** The calendar to write to: the primary Google calendar if there is one, then any synced one. */
    private fun calendarId(ctx: Context): Pair<Long, String>? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        )
        val sel = "${CalendarContract.Calendars.VISIBLE}=1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val found = mutableListOf<Triple<Long, Int, String>>()
        ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)?.use { c ->
            while (c.moveToNext()) {
                val google = c.getString(2) == "com.google"
                val score = (if (google) 2 else 0) + (if (c.getInt(1) == 1) 1 else 0)
                found += Triple(c.getLong(0), score, c.getString(3) ?: "calendar")
            }
        }
        val best = found.maxByOrNull { it.second } ?: return null
        val name = if (best.third.contains("@")) "your calendar" else best.third
        return best.first to name
    }

    data class Upcoming(val uri: Uri, val title: String, val start: Long, val location: String)

    /** Events Alfred added that haven't happened yet. */
    fun upcoming(ctx: Context): List<Upcoming> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val proj = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.EVENT_LOCATION)
        // Events created before the rename were tagged with the app's old name.
        val sel = "${CalendarContract.Events.DESCRIPTION} IN (?, ?) AND ${CalendarContract.Events.DTSTART}>=? AND ${CalendarContract.Events.DELETED}=0"
        val args = arrayOf("Added by Alfred", "Added by " + "Steward", (System.currentTimeMillis() - 60 * 60_000).toString())
        return runCatching {
            ctx.contentResolver.query(CalendarContract.Events.CONTENT_URI, proj, sel, args, "${CalendarContract.Events.DTSTART} ASC")?.use { c ->
                buildList {
                    while (c.moveToNext() && size < 5) add(Upcoming(
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, c.getLong(0)),
                        c.getString(1) ?: "", c.getLong(2), c.getString(3) ?: "",
                    ))
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun openEvent(ctx: Context, uri: Uri) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun reminder(ctx: Context, a: JSONObject): Result {
        val text = a.optString("text").ifBlank { return Result(false, "Empty reminder skipped") }
        val placeName = a.optString("place")
        if (placeName.isNotBlank()) {
            val place = Store.config.value.places.firstOrNull { it.name.equals(placeName, ignoreCase = true) }
                ?: return Result(false, "Unknown place “$placeName” for: $text")
            val id = "geo-${Store.newId()}"
            if (!Locator.addArrivalReminder(ctx, id, place))
                return Result(false, "Location “all the time” access needed to remind you at ${place.name}")
            Store.update { it.copy(geoReminders = it.geoReminders + (id to text)) }
            return Result(true, "Will remind you when you reach ${place.name}: $text", "geo:$id")
        }
        var t = time(a.optString("at")) ?: return Result(false, "Reminder: couldn't understand the time")
        if (t < System.currentTimeMillis()) t = System.currentTimeMillis() + 60_000
        val nid = (Store.newId() % Int.MAX_VALUE).toInt()
        ctx.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, reminderIntent(ctx, nid, text))
        return Result(true, "Reminder: $text · ${pretty.format(t)}", "alarm:$nid:$text")
    }

    private fun reminderIntent(ctx: Context, nid: Int, text: String) = PendingIntent.getBroadcast(
        ctx, nid,
        Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.REMIND).putExtra("text", text).putExtra("nid", nid),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun remember(fact: String): Result {
        if (fact.isBlank()) return Result(false, "Nothing to remember")
        Store.update { c -> if (c.facts.any { it.equals(fact, true) }) c else c.copy(facts = c.facts + fact) }
        return Result(true, "Remembered: $fact", "fact:$fact")
    }

    /** Lets the owner know what someone asked or said, as a notification and on Home. */
    private fun brief(ctx: Context, msg: Incoming, summary: String): Result {
        val text = summary.ifBlank { "${msg.sender} said: “${msg.text.take(120)}”" }
        notify(ctx, (Store.newId() % Int.MAX_VALUE).toInt(), "Alfred · ${msg.sender}", text)
        return Result(true, "Told you: $text")
    }

    private fun mute(msg: Incoming): Result {
        Store.update { it.copy(muted = it.muted + msg.sender) }
        msg.key?.let { k -> runCatching { StewardListener.instance?.cancelNotification(k) } }
        return Result(true, "Muted ${msg.sender}", "mute:${msg.sender}")
    }

    fun undo(ctx: Context, undo: String): Boolean {
        val (kind, rest) = undo.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return runCatching {
            when (kind) {
                "calendar" -> ctx.contentResolver.delete(Uri.parse(rest), null, null) > 0 ||
                    ctx.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, Uri.parse(rest).lastPathSegment!!.toLong()), null, null) > 0
                "alarm" -> {
                    val (nid, text) = rest.split(":", limit = 2).let { it[0].toInt() to it.getOrElse(1) { "" } }
                    ctx.getSystemService(AlarmManager::class.java).cancel(reminderIntent(ctx, nid, text))
                    true
                }
                "geo" -> {
                    Locator.removeArrivalReminder(ctx, rest)
                    Store.update { it.copy(geoReminders = it.geoReminders - rest) }
                    true
                }
                "fact" -> { Store.update { it.copy(facts = it.facts - rest) }; true }
                "mute" -> { Store.update { it.copy(muted = it.muted - rest) }; true }
                else -> false
            }
        }.getOrDefault(false)
    }

    /** Runs pending actions the user approved. Pass entryId = null to approve all of them. */
    fun approve(ctx: Context, activityId: Long, entryId: Long?, editedText: String? = null) {
        val a = Store.activity.value.firstOrNull { it.id == activityId } ?: return
        a.entries.filter { it.status == Status.PENDING && (entryId == null || it.id == entryId) }.forEach { e ->
            val action = JSONObject(e.action)
            if (editedText != null) action.put("text", editedText)
            val r = execute(ctx, a.msg, e.skill, action)
            r.sent?.let { action.put("text", it) }
            Store.updateEntry(activityId, e.id) {
                it.copy(summary = r.summary, status = if (r.ok) Status.DONE else Status.FAILED, undo = r.undo, action = action.toString())
            }
        }
        refreshApproval(ctx, activityId)
    }

    fun dismiss(ctx: Context, activityId: Long, entryId: Long?) {
        val a = Store.activity.value.firstOrNull { it.id == activityId } ?: return
        a.entries.filter { it.status == Status.PENDING && (entryId == null || it.id == entryId) }.forEach { e ->
            Store.updateEntry(activityId, e.id) { it.copy(status = Status.DISMISSED) }
        }
        refreshApproval(ctx, activityId)
    }

    private fun refreshApproval(ctx: Context, activityId: Long) {
        val a = Store.activity.value.firstOrNull { it.id == activityId } ?: return
        if (a.entries.none { it.status == Status.PENDING })
            ctx.getSystemService(NotificationManager::class.java).cancel(activityId.hashCode())
    }

    fun askApproval(ctx: Context, a: Activity) {
        val pending = a.entries.filter { it.status == Status.PENDING }
        val nid = a.id.hashCode()
        fun action(label: String, act: String) = Notification.Action.Builder(
            null, label,
            PendingIntent.getBroadcast(
                ctx, (nid * 31 + act.hashCode()),
                Intent(ctx, ActionReceiver::class.java).setAction(act).putExtra("activity", a.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
        val body = pending.joinToString("\n") { "• ${it.summary}" }
        notify(ctx, nid, "${a.msg.sender}: “${a.msg.text.take(60)}”", body, APPROVAL_CHANNEL,
            action(if (pending.size > 1) "Approve all" else "Approve", ActionReceiver.APPROVE),
            action("Dismiss", ActionReceiver.DISMISS))
    }

    fun notify(ctx: Context, id: Int, title: String, text: String, channel: String = CHANNEL, vararg actions: Notification.Action) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders and updates", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(APPROVAL_CHANNEL, "Actions waiting for approval", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        actions.forEach { b.addAction(it) }
        runCatching { nm.notify(id, b.build()) }
    }
}
