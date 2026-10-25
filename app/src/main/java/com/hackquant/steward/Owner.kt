package com.hackquant.steward

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Everything that happens on the owner's side of Alfred: their status and calendar, answering a briefing,
 * follow-ups, how loudly to interrupt them, learning from how they reply, and "Ask Alfred" commands.
 */
object Owner {
    private val clock = SimpleDateFormat("h:mm a", Locale.US)
    private const val URGENT_CHANNEL = "alfred_urgent"
    private const val DECISION_CHANNEL = "alfred_decisions"
    private const val FYI_CHANNEL = "alfred_fyi"
    private const val FYI_ID = 424242
    const val ANSWER_KEY = "owner_answer"

    private fun now() = System.currentTimeMillis()
    private fun owner(c: Config) = c.userName.ifBlank { "They" }

    // ---------------- Status ----------------

    val STATUSES = listOf("In a meeting", "Driving", "Busy", "At the gym", "Sleeping", "On leave")

    /** The owner's current status, or null once it has expired. */
    fun status(c: Config): String? =
        c.status.takeIf { it.isNotBlank() && (c.statusUntil == 0L || c.statusUntil > now()) }

    fun statusLine(c: Config): String? =
        status(c)?.let { s -> if (c.statusUntil > 0) "$s until ${clock.format(c.statusUntil)}" else s }

    fun setStatus(text: String, minutes: Int?) =
        Store.update { it.copy(status = text, statusUntil = minutes?.let { m -> now() + m * 60_000L } ?: 0L) }

    fun clearStatus() = Store.update { it.copy(status = "", statusUntil = 0L) }

    // ---------------- Calendar ----------------

    private fun canReadCalendar(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Busy blocks between two times, from every calendar on the phone. Titles are never exposed. */
    fun busyBlocks(ctx: Context, from: Long, to: Long): List<Pair<Long, Long>>? {
        if (!canReadCalendar(ctx)) return null
        return runCatching {
            CalendarContract.Instances.query(
                ctx.contentResolver,
                arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                from, to,
            )?.use { c ->
                buildList { while (c.moveToNext()) if (c.getInt(2) == 0) add(c.getLong(0) to c.getLong(1)) }
            } ?: emptyList()
        }.getOrNull()
    }

    /** "busy 3:00 PM–4:00 PM, 6:00 PM–7:00 PM" for the rest of today, for the model's context. */
    fun agendaToday(ctx: Context): String? {
        val end = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }.timeInMillis
        val blocks = busyBlocks(ctx, now(), end) ?: return null
        if (blocks.isEmpty()) return "free for the rest of today"
        return "busy " + blocks.sortedBy { it.first }.take(5).joinToString { "${clock.format(it.first)}–${clock.format(it.second)}" }
    }

    /** True if nothing is on the calendar for an hour from [t]; null if the calendar can't be read. */
    fun freeAt(ctx: Context, t: Long): Boolean? = busyBlocks(ctx, t, t + 60 * 60_000L)?.isEmpty()

    // ---------------- What the sender hears right away ----------------

    /**
     * The acknowledgement sent while the owner decides. It shares only status and free/busy, never
     * event details or location, and promises nothing beyond passing the message on.
     */
    fun acknowledgement(ctx: Context, c: Config, msg: Incoming): String {
        val who = owner(c)
        status(c)?.let { s ->
            val until = if (c.statusUntil > 0) " until ${clock.format(c.statusUntil)}" else ""
            return "$who is ${s.lowercase()}$until. I've passed this on, they'll get back to you 🙂"
        }
        Agent.parseWhen(msg.text.lowercase())?.let { cal ->
            when (freeAt(ctx, cal.timeInMillis)) {
                true -> return "$who's calendar looks free then. I'll confirm with them and get back to you 🙂"
                false -> return "$who has something on at that time. I've passed this on, they'll get back to you 🙂"
                null -> {}
            }
        }
        return "Got it! I've passed this on to $who, they'll get back to you soon 🙂"
    }

    // ---------------- Answering a briefing ----------------

    fun quickAnswers(msg: Incoming): List<String> =
        if (Agent.isAboutPlans(msg.text)) listOf("Works for me", "Can't make it") else listOf("Yes", "No")

    private val AFFIRMATIVE = Regex("""(?i)^\s*(yes|yeah|yep|yup|sure|ok(ay)?|works|fine|done|cool|haan|ha|great|👍)""")

    /**
     * The owner's answer (a quick button or a few typed words) becomes a proper message from Alfred,
     * gets sent, and closes the open item. An affirmative answer to a dated plan also books it.
     * Blocking: call from a background thread. Returns what happened, for the UI.
     */
    fun answer(ctx: Context, activityId: Long, ownerAnswer: String): String {
        Store.init(ctx)
        val c = Store.config.value
        val a = Store.activity.value.firstOrNull { it.id == activityId } ?: return "That conversation is gone"
        val msg = a.msg
        val text = compose(c, msg, ownerAnswer.trim())
        val sent = Tools.sendReply(ctx, msg, text)
        val added = mutableListOf(
            Entry(Store.newId(), Skill.REPLY, sent.summary, if (sent.ok) Status.DONE else Status.FAILED,
                JSONObject().put("tool", "reply").put("text", sent.sent ?: text).toString()),
        )
        val booked = AFFIRMATIVE.containsMatchIn(ownerAnswer) &&
            Agent.parseWhen(msg.text.lowercase())?.let { cal ->
                val r = Tools.execute(ctx, msg, Skill.CALENDAR, JSONObject().put("tool", "calendar")
                    .put("title", "Plan with ${msg.sender.substringBefore(" in ")}")
                    .put("start", Agent.stamp.format(cal.time)))
                added += Entry(Store.newId(), Skill.CALENDAR, r.summary, if (r.ok) Status.DONE else Status.FAILED, "{}", r.undo)
                r.ok
            } == true
        // The owner has decided: briefs are answered and any drafts Alfred was holding are superseded.
        Store.updateActivity(activityId) { act ->
            act.copy(entries = act.entries.map { e ->
                when {
                    e.skill == Skill.BRIEF && e.status == Status.DONE -> e.copy(status = Status.ANSWERED)
                    e.status == Status.PENDING -> e.copy(status = Status.DISMISSED)
                    else -> e
                }
            } + added)
        }
        learn(msg.sender, msg.text, ownerAnswer)
        cancelBrief(ctx, activityId)
        ctx.getSystemService(NotificationManager::class.java).cancel(activityId.hashCode())
        return when {
            !sent.ok -> "Couldn't reach ${msg.sender}: ${sent.summary}"
            booked -> "Sent to ${msg.sender} and added to your calendar"
            else -> "Sent to ${msg.sender}"
        }
    }

    /** Turns "yes 8" into "Suchal says 8 works for him 👍". Falls back to a plain relay. */
    private fun compose(c: Config, msg: Incoming, ownerAnswer: String): String {
        val who = owner(c)
        val fallback = "$who says: $ownerAnswer"
        if (!Llm.ready()) return fallback
        val system = "You are Alfred, $who's AI assistant. $who has answered a message from ${msg.sender}. " +
            "Write the one short, friendly message Alfred sends back, passing on $who's answer in the third person (\"$who says…\"). " +
            "Keep $who's meaning exactly, add nothing new, at most 20 words. Output only the message."
        val user = "${msg.sender} wrote: \"${msg.text.take(300)}\"\n$who's answer: \"$ownerAnswer\""
        val out = runCatching { Llm.generateOnce(system, user) }.getOrNull()
            ?.lineSequence()?.map { it.trim().removeSurrounding("\"") }?.firstOrNull { it.isNotBlank() }
        return out?.takeIf { it.length in 3..220 && !it.contains("{") } ?: fallback
    }

    // ---------------- Learning from the owner ----------------

    /** Keeps how the owner actually replied, and closes anything Alfred was holding for that person. */
    fun learn(sender: String, theirs: String, mine: String) {
        if (mine.isBlank()) return
        Store.update { c -> c.copy(examples = (c.examples + Example(sender, theirs.take(200), mine.take(200), now())).takeLast(40)) }
    }

    /** The owner replied themselves in WhatsApp: open items for that person are done. */
    fun ownerReplied(ctx: Context, sender: String, theirs: String?, mine: String) {
        learn(sender, theirs ?: "", mine)
        Store.activity.value.filter { it.msg.sender == sender }.forEach { a ->
            if (a.entries.any { it.skill == Skill.BRIEF && it.status == Status.DONE || it.status == Status.PENDING }) {
                Store.updateActivity(a.id) { act ->
                    act.copy(entries = act.entries.map { e ->
                        when {
                            e.skill == Skill.BRIEF && e.status == Status.DONE -> e.copy(status = Status.ANSWERED)
                            e.status == Status.PENDING && e.skill == Skill.REPLY -> e.copy(status = Status.DISMISSED)
                            else -> e
                        }
                    })
                }
                cancelBrief(ctx, a.id)
            }
        }
    }

    /** A few real exchanges with this person (or their circle), so drafts sound like the owner. */
    fun examplesFor(c: Config, sender: String): List<Example> {
        val own = c.examples.filter { it.sender == sender }.takeLast(2)
        if (own.isNotEmpty()) return own
        val circle = c.groupOf(sender).id
        return c.examples.filter { c.groupOf(it.sender).id == circle }.takeLast(1)
    }

    /** Counts approvals per circle; after three, Home suggests letting Alfred reply on its own. */
    fun noteApproval(circleId: String) =
        Store.update { it.copy(approvals = it.approvals + (circleId to ((it.approvals[circleId] ?: 0) + 1))) }

    fun autonomySuggestion(c: Config): Group? =
        c.groups.firstOrNull { (c.approvals[it.id] ?: 0) >= 3 && it.autonomy == Autonomy.ASK }

    fun acceptSuggestion(g: Group) = Store.update { c ->
        c.copy(groups = c.groups.map { if (it.id == g.id) it.copy(autonomy = Autonomy.AUTO) else it },
            skills = if (c.autonomy(Skill.REPLY) == Autonomy.ASK) c.skills + (Skill.REPLY to Autonomy.AUTO) else c.skills)
    }

    fun declineSuggestion(g: Group) = Store.update { it.copy(approvals = it.approvals + (g.id to -100)) }

    // ---------------- Interrupt by priority ----------------

    enum class Priority { URGENT, DECISION, FYI }

    fun priority(a: Activity, e: Entry): Priority = when {
        e.summary.contains("⚠️") -> Priority.URGENT
        a.entries.any { it.status == Status.PENDING } || a.msg.text.contains('?') || Agent.isAboutPlans(a.msg.text) ||
            Agent.isSensitive(a.msg.text) || a.intent in setOf("question", "availability", "plan", "request", "money") -> Priority.DECISION
        else -> Priority.FYI
    }

    private fun channels(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).apply {
        createNotificationChannel(NotificationChannel(URGENT_CHANNEL, "Urgent messages", NotificationManager.IMPORTANCE_HIGH))
        createNotificationChannel(NotificationChannel(DECISION_CHANNEL, "Needs your answer", NotificationManager.IMPORTANCE_HIGH))
        createNotificationChannel(NotificationChannel(FYI_CHANNEL, "For your information", NotificationManager.IMPORTANCE_LOW))
    }

    private fun briefId(activityId: Long) = activityId.hashCode() + 7

    fun cancelBrief(ctx: Context, activityId: Long) =
        ctx.getSystemService(NotificationManager::class.java).cancel(briefId(activityId))

    /** Urgent and decisions interrupt with answer buttons; FYI collects quietly in one summary. */
    fun postBrief(ctx: Context, a: Activity, e: Entry) {
        val nm = channels(ctx)
        val text = e.summary.removePrefix("Told you: ")
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        when (val p = priority(a, e)) {
            Priority.FYI -> {
                val fyi = openBriefs().filter { (act, en) -> priority(act, en) == Priority.FYI }
                val body = fyi.take(6).joinToString("\n") { (_, en) -> "• " + en.summary.removePrefix("Told you: ") }
                nm.notify(FYI_ID, Notification.Builder(ctx, FYI_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle("Alfred · ${fyi.size} for your information")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(body))
                    .setContentIntent(open).setAutoCancel(true).build())
            }
            else -> {
                val canAnswer = Replies.canSend(a.msg.replyId)
                val b = Notification.Builder(ctx, if (p == Priority.URGENT) URGENT_CHANNEL else DECISION_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle((if (p == Priority.URGENT) "⚠️ " else "") + "Alfred · ${a.msg.sender}")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText("$text\n“${a.msg.text.take(160)}”"))
                    .setContentIntent(open).setAutoCancel(true)
                if (canAnswer) {
                    quickAnswers(a.msg).forEachIndexed { i, label ->
                        b.addAction(Notification.Action.Builder(null, label, answerIntent(ctx, a.id, label, i)).build())
                    }
                    val input = RemoteInput.Builder(ANSWER_KEY).setLabel("Your answer to ${a.msg.sender}").build()
                    b.addAction(Notification.Action.Builder(null, "Reply", answerIntent(ctx, a.id, null, 9, mutable = true))
                        .addRemoteInput(input).build())
                }
                nm.notify(briefId(a.id), b.build())
                scheduleFollowUp(ctx, a.id)
            }
        }
    }

    private fun answerIntent(ctx: Context, activityId: Long, answer: String?, slot: Int, mutable: Boolean = false): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ANSWER).putExtra("activity", activityId)
        if (answer != null) i.putExtra("answer", answer)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(ctx, (activityId.hashCode() * 13 + slot), i, flags)
    }

    /** Open items: briefings the owner hasn't answered or dismissed yet, newest first. */
    fun openBriefs(): List<Pair<Activity, Entry>> = Store.activity.value.flatMap { a ->
        a.entries.filter { it.skill == Skill.BRIEF && it.status == Status.DONE }.map { a to it }
    }

    // ---------------- Follow-ups ----------------

    const val FOLLOW_UP_MINUTES = 30

    private fun scheduleFollowUp(ctx: Context, activityId: Long) {
        val pi = PendingIntent.getBroadcast(ctx, activityId.hashCode() * 17,
            Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.FOLLOWUP).putExtra("activity", activityId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        ctx.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now() + FOLLOW_UP_MINUTES * 60_000L, pi)
    }

    /** Still unanswered: tell the sender it's being handled, and nudge the owner once. */
    fun followUp(ctx: Context, activityId: Long) {
        Store.init(ctx)
        val a = Store.activity.value.firstOrNull { it.id == activityId } ?: return
        val e = a.entries.firstOrNull { it.skill == Skill.BRIEF && it.status == Status.DONE && it.undo != "followed" }
            ?: a.entries.firstOrNull { it.status == Status.PENDING && it.undo != "followed" } ?: return
        val c = Store.config.value
        val nudge = Tools.sendReply(ctx, a.msg, "Still checking with ${owner(c)}, thanks for waiting 🙂")
        Store.updateActivity(activityId) { act ->
            act.copy(entries = act.entries.map { if (it.id == e.id) it.copy(undo = "followed") else it } +
                listOfNotNull(if (nudge.ok) Entry(Store.newId(), Skill.REPLY, nudge.summary, Status.DONE,
                    JSONObject().put("tool", "reply").put("text", nudge.sent).toString()) else null))
        }
        Store.activity.value.firstOrNull { it.id == activityId }?.let { fresh ->
            postBrief(ctx, fresh, e.copy(summary = "Still waiting (${FOLLOW_UP_MINUTES} min) · " + e.summary.removePrefix("Told you: ")))
        }
    }

    // ---------------- Ask Alfred ----------------

    /** Plain-language commands from the owner. Blocking: call from a background thread. */
    fun command(ctx: Context, input: String): String {
        Store.init(ctx)
        val text = input.trim()
        val c = Store.config.value
        Regex("""(?i)^(tell|message|text|reply to|ask)\s+(.+)$""").find(text)?.let { m ->
            val rest = m.groupValues[2].trim()
            // Match the longest known name at the start, so "Tara 09 ..." isn't taken as "Tara".
            val people = Store.activity.value.filter { it.msg.chat == null }.map { it.msg.sender }.distinct().sortedByDescending { it.length }
            val name = people.firstOrNull { rest.lowercase().startsWith(it.lowercase() + " ") }
                ?: people.firstOrNull { rest.lowercase().startsWith(it.substringBefore(" ").lowercase() + " ") }
                ?: return "I haven't seen a message from “${rest.substringBefore(" ")}” yet. I can only message people after they message you."
            val matched = if (rest.lowercase().startsWith(name.lowercase() + " ")) name else name.substringBefore(" ")
            val content = rest.substring(matched.length).trim().removePrefix("that ").removePrefix("to ").trim()
            val target = Store.activity.value.first { it.msg.chat == null && it.msg.sender == name }
            if (!Replies.canSend(target.msg.replyId))
                return "I can't reach ${target.msg.sender} right now. Android only lets me reply while their latest message notification is open."
            val msgText = composeOutgoing(c, target.msg, content)
            val r = Tools.sendReply(ctx, target.msg, msgText)
            Store.updateActivity(target.id) { act ->
                act.copy(entries = act.entries + Entry(Store.newId(), Skill.REPLY, r.summary, if (r.ok) Status.DONE else Status.FAILED,
                    JSONObject().put("tool", "reply").put("text", r.sent ?: msgText).toString()))
            }
            return if (r.ok) "Sent to ${target.msg.sender}: “$msgText”" else r.summary
        }
        if (Regex("""(?i)(pending|waiting|what did .* want|summary|digest|catch me up|anything (new|important))""").containsMatchIn(text)) {
            val open = openBriefs()
            val approvals = Store.activity.value.sumOf { a -> a.entries.count { it.status == Status.PENDING } }
            if (open.isEmpty() && approvals == 0) return "Nothing waiting on you. All caught up."
            return buildString {
                if (open.isNotEmpty()) append("Waiting on you:\n").append(open.take(6).joinToString("\n") { (_, e) -> "• " + e.summary.removePrefix("Told you: ") })
                if (approvals > 0) append(if (isEmpty()) "" else "\n").append("$approvals suggestion${if (approvals > 1) "s" else ""} to approve on Home.")
            }
        }
        Regex("""(?i)^(i'?m |i am |set status( to)? )?(back|available|free now|clear status)$""").find(text)?.let {
            clearStatus(); return "Status cleared. I'll tell people you're around."
        }
        Regex("""(?i)^(i'?m |i am |set status( to)? )(.+?)(\s+(for|till|until)\s+(.+))?$""").find(text)?.let { m ->
            val s = m.groupValues[3].trim().replaceFirstChar { it.uppercase() }
            val minutes = parseDuration(m.groupValues[6])
            setStatus(s, minutes)
            return "Status set: $s" + (minutes?.let { " for ${it} min" } ?: "") + ". I'll mention it when people message."
        }
        return "I can tell someone something (“tell Praveen I'll be 10 min late”), catch you up (“what's pending?”) or set your status (“I'm in a meeting for 1 hour”)."
    }

    private fun parseDuration(s: String): Int? {
        if (s.isBlank()) return null
        Regex("""(?i)(\d+)\s*(h|hr|hrs|hour|hours)""").find(s)?.let { return it.groupValues[1].toInt() * 60 }
        Regex("""(?i)(\d+)\s*(m|min|mins|minute|minutes)""").find(s)?.let { return it.groupValues[1].toInt() }
        if (Regex("""(?i)an? hour""").containsMatchIn(s)) return 60
        Agent.parseWhen("today " + s.lowercase())?.let { cal -> return ((cal.timeInMillis - now()) / 60_000).toInt().takeIf { it > 0 } }
        return null
    }

    private fun composeOutgoing(c: Config, msg: Incoming, content: String): String {
        val who = owner(c)
        val fallback = "$who says: $content"
        if (!Llm.ready()) return fallback
        val system = "You are Alfred, $who's AI assistant. $who asked you to pass a message to ${msg.sender}. " +
            "Write it as one short friendly message in the third person (\"$who says…\"), keeping the meaning exactly, at most 25 words. Output only the message."
        val out = runCatching { Llm.generateOnce(system, "Message from $who: \"$content\"") }.getOrNull()
            ?.lineSequence()?.map { it.trim().removeSurrounding("\"") }?.firstOrNull { it.isNotBlank() }
        return out?.takeIf { it.length in 3..220 && !it.contains("{") } ?: fallback
    }
}
