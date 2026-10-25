package com.hackquant.steward

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Autonomy(val label: String) { OFF("Off"), ASK("Ask me"), AUTO("Auto") }

/** The fixed set of things the model is allowed to do. Users decide how much freedom each one gets. */
enum class Skill(val id: String, val label: String, val desc: String, val default: Autonomy) {
    REPLY("reply", "Reply", "Draft and send replies in your style", Autonomy.ASK),
    CALENDAR("calendar", "Calendar", "Add plans and meetings to your calendar", Autonomy.AUTO),
    REMINDER("reminder", "Reminders", "Remind you of tasks and deadlines, by time or by place", Autonomy.AUTO),
    REMEMBER("remember", "Memory", "Learn lasting facts about you and your people", Autonomy.ASK),
    MUTE("mute", "Mute", "Silence promotions and noisy senders", Autonomy.ASK),
    BRIEF("brief", "Brief me", "Tell you who asked or said what, when Alfred can't handle it", Autonomy.AUTO);

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

data class Group(
    val id: String,
    val name: String,
    val tone: String,
    val language: String,
    val autonomy: Autonomy,
)

/** Broad kinds of conversation. Each can be handled automatically, with approval, or ignored outright. */
enum class Category(val id: String, val label: String, val desc: String, val default: Autonomy) {
    PERSONAL("personal", "Personal chats", "One-to-one messages from people", Autonomy.AUTO),
    GROUP_CHAT("group", "Group chats", "WhatsApp groups and other group conversations", Autonomy.ASK),
    BUSINESS("business", "Businesses & alerts", "Banks, shops, delivery and other automated senders", Autonomy.ASK),
    UNKNOWN("unknown", "Unknown numbers", "People who aren't saved in your contacts", Autonomy.ASK);

    companion object {
        fun of(msg: Incoming): Category {
            val s = msg.sender.trim()
            return when {
                msg.isGroup -> GROUP_CHAT
                Regex("""^[A-Z]{2}-[A-Z0-9]{3,}(-[A-Z])?$""").matches(s) || Regex("""^\d{3,6}$""").matches(s) -> BUSINESS
                Regex("""^\+?[\d\s()-]{8,}$""").matches(s) -> UNKNOWN
                else -> PERSONAL
            }
        }
    }
}

/** How Alfred treats one WhatsApp (or other) group conversation. */
enum class ChatMode(val label: String, val desc: String) {
    MENTIONS("Only when I'm mentioned", "Alfred reads a message only if it names you. Everything else is skipped before the AI runs."),
    ALL("Every message", "Alfred reads every message in this group."),
    IGNORE("Ignore this group", "Alfred never reads anything from this group."),
}

data class Rule(val id: Long, val text: String, val enabled: Boolean = true)
data class Place(val name: String, val lat: Double, val lng: Double, val radius: Float = 150f)
data class WatchedApp(val pkg: String, val label: String, val enabled: Boolean)

data class Config(
    val userName: String = "",
    val style: String = "Short, friendly, casual messages.",
    val facts: List<String> = emptyList(),
    val skills: Map<Skill, Autonomy> = Skill.entries.associateWith { it.default },
    val groups: List<Group> = DEFAULT_GROUPS,
    val contacts: Map<String, String> = emptyMap(), // sender name -> group id
    val rules: List<Rule> = emptyList(),
    val places: List<Place> = emptyList(),
    val apps: List<WatchedApp> = DEFAULT_APPS,
    val muted: Set<String> = emptySet(),
    val geoReminders: Map<String, String> = emptyMap(), // geofence id -> reminder text
    val paused: Boolean = false,
    val onboarded: Boolean = false,
    val categories: Map<Category, Autonomy> = Category.entries.associateWith { it.default },
    val ignoredSenders: List<String> = emptyList(),
    val ignoredWords: List<String> = emptyList(),
    val introduced: Map<String, Long> = emptyMap(), // sender -> when Alfred last said it's an assistant
    val groupChats: Map<String, ChatMode> = emptyMap(), // group conversation title -> how to treat it
    val aliases: List<String> = emptyList(),            // other names people use for the owner, for mentions
) {
    fun chatMode(chat: String) = groupChats[chat] ?: ChatMode.MENTIONS

    /** The owner's name, a nickname, or an @mention of either appears in the text. */
    fun mentionsOwner(text: String): Boolean = (listOf(userName) + aliases).map { it.trim() }.filter { it.length >= 2 }.any { n ->
        Regex("""(?i)(^|[^\p{L}\p{N}])@?${Regex.escape(n)}($|[^\p{L}\p{N}])""").containsMatchIn(text)
    }

    fun handling(cat: Category) = categories[cat] ?: cat.default

    /** Why Alfred should skip this message entirely, or null if it should handle it. */
    fun ignoreReason(msg: Incoming): String? {
        ignoredSenders.firstOrNull { msg.sender.equals(it, true) || msg.sender.startsWith("$it in ", true) || msg.sender.endsWith(" in $it", true) }
            ?.let { return "You ignore $it" }
        ignoredWords.firstOrNull { msg.text.contains(it, ignoreCase = true) }?.let { return "Contains “$it”" }
        msg.chat?.let { chat ->
            when (chatMode(chat)) {
                ChatMode.IGNORE -> return "You ignore the group $chat"
                ChatMode.MENTIONS -> if (!mentionsOwner(msg.text)) return "Not mentioned in $chat"
                ChatMode.ALL -> {}
            }
        }
        val cat = Category.of(msg)
        if (handling(cat) == Autonomy.OFF) return "You ignore ${cat.label.lowercase()}"
        if (groupOf(msg.sender).autonomy == Autonomy.OFF) return "You ignore ${groupOf(msg.sender).name}"
        return null
    }

    fun groupOf(sender: String): Group =
        groups.firstOrNull { it.id == contacts[sender] } ?: groups.firstOrNull { it.id == OTHERS } ?: DEFAULT_GROUPS.last()

    fun autonomy(skill: Skill) = skills[skill] ?: skill.default

    companion object {
        const val OTHERS = "others"
        val DEFAULT_GROUPS = listOf(
            Group("family", "Family", "warm and caring", "", Autonomy.ASK),
            Group("friends", "Friends", "casual and playful", "", Autonomy.ASK),
            Group("work", "Work", "professional and concise", "English", Autonomy.ASK),
            Group(OTHERS, "Everyone else", "polite and brief", "", Autonomy.ASK),
        )
        val DEFAULT_APPS = listOf(
            WatchedApp("com.whatsapp", "WhatsApp", true),
            WatchedApp("com.whatsapp.w4b", "WhatsApp Business", true),
            WatchedApp("com.google.android.apps.messaging", "Messages", true),
            WatchedApp("com.samsung.android.messaging", "Samsung Messages", true),
            WatchedApp("org.telegram.messenger", "Telegram", true),
            WatchedApp("org.thoughtcrime.securesms", "Signal", true),
        )
    }
}

/** Starting points for how much freedom Alfred gets. Everything stays adjustable afterwards. */
enum class Preset(val title: String, val desc: String) {
    CAREFUL("Careful", "Suggests everything, does nothing until you approve."),
    BALANCED("Balanced", "Handles private things like your calendar and reminders. Asks before messaging anyone."),
    HANDS_OFF("Hands-off", "Takes care of most things, including replying to family and friends.");

    fun apply(c: Config): Config = when (this) {
        CAREFUL -> c.copy(
            skills = Skill.entries.associateWith { Autonomy.ASK },
            groups = c.groups.map { it.copy(autonomy = Autonomy.ASK) },
        )
        BALANCED -> c.copy(
            skills = Skill.entries.associateWith {
                if (it in setOf(Skill.CALENDAR, Skill.REMINDER, Skill.REMEMBER)) Autonomy.AUTO else Autonomy.ASK
            },
            groups = c.groups.map { it.copy(autonomy = Autonomy.ASK) },
        )
        HANDS_OFF -> c.copy(
            skills = Skill.entries.associateWith { Autonomy.AUTO },
            groups = c.groups.map { it.copy(autonomy = if (it.id == "family" || it.id == "friends") Autonomy.AUTO else Autonomy.ASK) },
        )
    }
}

data class Incoming(
    val sender: String,
    val app: String,
    val pkg: String,
    val text: String,
    val isGroup: Boolean = false,
    val chat: String? = null,  // group conversation title, for group messages
    val replyId: Int? = null, // handle for the source notification's inline-reply action
    val key: String? = null,  // source notification key, used to dismiss it
)

enum class Status { DONE, PENDING, DISMISSED, UNDONE, FAILED, BLOCKED }

/** Where a message is in the pipeline while Alfred works on it. */
enum class Stage(val label: String) {
    QUEUED("Waiting in line"),
    THINKING("Reading it with the on-device AI"),
    ACTING("Taking action"),
}

data class Entry(
    val id: Long,
    val skill: Skill,
    val summary: String,
    val status: Status,
    val action: String,      // the model's tool call, as JSON
    val undo: String? = null,
    val why: String? = null, // which setting made Alfred ask, or blocked the action
)

data class Activity(
    val id: Long,
    val time: Long,
    val msg: Incoming,
    val entries: List<Entry>,
    val engine: String,
    val stage: Stage? = null, // null once Alfred has finished with the message
    val stageAt: Long = 0,
    val note: String? = null, // e.g. which of the user's rules the model followed
)

/** Strips WhatsApp's decorations: a leading "~ " for unsaved numbers and a trailing "(5 messages)" count. */
fun cleanName(raw: String): String = raw
    .replace(Regex("""\s*\(\d+ (new )?messages?\)\s*$"""), "")
    .replace(Regex("""^[~\s\u202f\u00a0]+"""), "")
    .replace(Regex("""(\s+in\s+)[~\s\u202f\u00a0]+"""), "$1")
    .trim()

/** Everything Alfred knows and has done. Lives only in the app's private storage. */
object Store {
    private var file: File? = null
    val config = MutableStateFlow(Config())
    val activity = MutableStateFlow<List<Activity>>(emptyList())
    private val history = mutableMapOf<String, ArrayDeque<String>>()
    private var nextId = System.currentTimeMillis()

    @Synchronized fun newId() = ++nextId

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "steward.json")
        runCatching {
            val o = JSONObject(file!!.readText())
            config.value = configFrom(o.getJSONObject("config"))
            activity.value = o.optJSONArray("activity")?.let { a -> List(a.length()) { activityFrom(a.getJSONObject(it)) } } ?: emptyList()
            // Clean up names saved before cleanName existed, and drop the bogus "You" contact.
            config.value = config.value.let { c ->
                c.copy(
                    // "Person in Group" entries came from group chats: list the group, not the person, as a contact.
                    contacts = c.contacts.filterKeys { !it.equals("You", true) }.mapKeys { cleanName(it.key) }
                        .filterKeys { " in " !in it },
                    groupChats = c.contacts.keys.map(::cleanName).filter { " in " in it }
                        .map { it.substringAfterLast(" in ") }.associateWith { c.groupChats[it] ?: ChatMode.MENTIONS } + c.groupChats,
                    ignoredSenders = c.ignoredSenders.map(::cleanName).distinct(),
                    // Mute is only for businesses now; people in group chats muted earlier get their notifications back.
                    muted = c.muted.filterNot { " in " in it }.toSet(),
                )
            }
            // Anything still in progress was interrupted by the app being closed.
            activity.value = activity.value.map { if (it.stage != null) it.copy(stage = null, note = "Interrupted before Alfred finished") else it }
            save()
        }
    }

    @Synchronized
    fun update(f: (Config) -> Config) {
        config.value = f(config.value)
        save()
        Agent.warmSoon()
    }

    @Synchronized
    fun addActivity(a: Activity) {
        activity.value = (listOf(a) + activity.value).take(100)
        save()
    }

    @Synchronized
    fun updateActivity(id: Long, f: (Activity) -> Activity) {
        activity.value = activity.value.map { if (it.id == id) f(it) else it }
        save()
    }

    @Synchronized
    fun updateEntry(activityId: Long, entryId: Long, f: (Entry) -> Entry) {
        activity.value = activity.value.map { a ->
            if (a.id != activityId) a else a.copy(entries = a.entries.map { if (it.id == entryId) f(it) else it })
        }
        save()
    }

    fun clearActivity() = synchronized(this) { activity.value = emptyList(); save() }

    fun forgetEverything() = synchronized(this) {
        config.value = Config()
        activity.value = emptyList()
        history.clear()
        save()
    }

    @Synchronized fun historyFor(sender: String): List<String> = history[sender]?.toList() ?: emptyList()

    @Synchronized
    fun addHistory(sender: String, line: String) {
        val h = history.getOrPut(sender) { ArrayDeque() }
        h.addLast(line)
        while (h.size > 4) h.removeFirst()
    }

    @Synchronized
    private fun save() {
        val o = JSONObject()
            .put("config", configTo(config.value))
            .put("activity", JSONArray(activity.value.map { activityTo(it) }))
        file?.writeText(o.toString())
    }

    // --- JSON mapping ---

    private fun JSONArray.strings() = List(length()) { getString(it) }
    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }

    private fun configTo(c: Config) = JSONObject()
        .put("userName", c.userName)
        .put("style", c.style)
        .put("facts", JSONArray(c.facts))
        .put("skills", JSONObject().apply { c.skills.forEach { (k, v) -> put(k.id, v.name) } })
        .put("groups", JSONArray(c.groups.map {
            JSONObject().put("id", it.id).put("name", it.name).put("tone", it.tone).put("language", it.language)
                .put("autonomy", it.autonomy.name)
        }))
        .put("contacts", JSONObject(c.contacts))
        .put("rules", JSONArray(c.rules.map { JSONObject().put("id", it.id).put("text", it.text).put("enabled", it.enabled) }))
        .put("places", JSONArray(c.places.map {
            JSONObject().put("name", it.name).put("lat", it.lat).put("lng", it.lng).put("radius", it.radius.toDouble())
        }))
        .put("apps", JSONArray(c.apps.map { JSONObject().put("pkg", it.pkg).put("label", it.label).put("enabled", it.enabled) }))
        .put("muted", JSONArray(c.muted.toList()))
        .put("geoReminders", JSONObject(c.geoReminders))
        .put("paused", c.paused)
        .put("onboarded", c.onboarded)
        .put("categories", JSONObject().apply { c.categories.forEach { (k, v) -> put(k.id, v.name) } })
        .put("ignoredSenders", JSONArray(c.ignoredSenders))
        .put("ignoredWords", JSONArray(c.ignoredWords))
        .put("introduced", JSONObject(c.introduced))
        .put("groupChats", JSONObject().apply { c.groupChats.forEach { (k, v) -> put(k, v.name) } })
        .put("aliases", JSONArray(c.aliases))

    private fun configFrom(o: JSONObject): Config {
        val d = Config()
        return Config(
            userName = o.optString("userName", d.userName),
            style = o.optString("style", d.style),
            facts = o.optJSONArray("facts")?.strings() ?: d.facts,
            skills = Skill.entries.associateWith { s ->
                o.optJSONObject("skills")?.optString(s.id)?.let { runCatching { Autonomy.valueOf(it) }.getOrNull() } ?: s.default
            },
            groups = o.optJSONArray("groups")?.objects()?.map {
                Group(it.getString("id"), it.getString("name"), it.optString("tone"), it.optString("language"),
                    runCatching { Autonomy.valueOf(it.getString("autonomy")) }.getOrDefault(Autonomy.ASK))
            } ?: d.groups,
            contacts = o.optJSONObject("contacts")?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } } ?: d.contacts,
            rules = o.optJSONArray("rules")?.objects()?.map { Rule(it.getLong("id"), it.getString("text"), it.optBoolean("enabled", true)) } ?: d.rules,
            places = o.optJSONArray("places")?.objects()?.map {
                Place(it.getString("name"), it.getDouble("lat"), it.getDouble("lng"), it.optDouble("radius", 150.0).toFloat())
            } ?: d.places,
            apps = o.optJSONArray("apps")?.objects()?.map { WatchedApp(it.getString("pkg"), it.getString("label"), it.getBoolean("enabled")) } ?: d.apps,
            muted = o.optJSONArray("muted")?.strings()?.toSet() ?: d.muted,
            geoReminders = o.optJSONObject("geoReminders")?.let { j -> j.keys().asSequence().associateWith { j.getString(it) } } ?: d.geoReminders,
            paused = o.optBoolean("paused", false),
            onboarded = o.optBoolean("onboarded", false),
            categories = Category.entries.associateWith { k ->
                o.optJSONObject("categories")?.optString(k.id)?.let { runCatching { Autonomy.valueOf(it) }.getOrNull() } ?: k.default
            },
            ignoredSenders = o.optJSONArray("ignoredSenders")?.strings() ?: d.ignoredSenders,
            ignoredWords = o.optJSONArray("ignoredWords")?.strings() ?: d.ignoredWords,
            introduced = o.optJSONObject("introduced")?.let { j -> j.keys().asSequence().associateWith { j.getLong(it) } } ?: d.introduced,
            groupChats = o.optJSONObject("groupChats")?.let { j ->
                j.keys().asSequence().associateWith { runCatching { ChatMode.valueOf(j.getString(it)) }.getOrDefault(ChatMode.MENTIONS) }
            } ?: d.groupChats,
            aliases = o.optJSONArray("aliases")?.strings() ?: d.aliases,
        )
    }

    private fun activityTo(a: Activity) = JSONObject()
        .put("id", a.id).put("time", a.time).put("engine", a.engine).put("note", a.note ?: "")
        .put("stage", a.stage?.name ?: "")
        .put("sender", a.msg.sender).put("app", a.msg.app).put("pkg", a.msg.pkg).put("text", a.msg.text)
        .put("chat", a.msg.chat ?: "")
        .put("entries", JSONArray(a.entries.map {
            JSONObject().put("id", it.id).put("skill", it.skill.id).put("summary", it.summary)
                .put("status", it.status.name).put("action", it.action).put("undo", it.undo ?: "").put("why", it.why ?: "")
        }))

    private fun activityFrom(o: JSONObject) = Activity(
        id = o.getLong("id"),
        time = o.getLong("time"),
        // Reply handles don't survive a restart, so restored messages carry no replyId.
        msg = Incoming(o.getString("sender"), o.getString("app"), o.optString("pkg"), o.getString("text"),
            isGroup = o.optString("chat").isNotBlank(), chat = o.optString("chat").ifBlank { null }),
        entries = o.getJSONArray("entries").objects().mapNotNull { e ->
            val skill = Skill.of(e.getString("skill")) ?: return@mapNotNull null
            Entry(e.getLong("id"), skill, e.getString("summary"), Status.valueOf(e.getString("status")),
                e.getString("action"), e.optString("undo").ifBlank { null }, e.optString("why").ifBlank { null })
        },
        engine = o.optString("engine"),
        stage = o.optString("stage").ifBlank { null }?.let { runCatching { Stage.valueOf(it) }.getOrNull() },
        note = o.optString("note").ifBlank { null },
    )
}
