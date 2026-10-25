package com.hackquant.steward.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hackquant.steward.Autonomy
import com.hackquant.steward.Category
import com.hackquant.steward.ChatMode
import com.hackquant.steward.Config
import com.hackquant.steward.Group
import com.hackquant.steward.Skill
import com.hackquant.steward.Store

private val TONES = listOf("Warm", "Casual", "Playful", "Professional", "Brief", "Respectful")
private val LANGS = listOf("Same as theirs", "English", "Hindi", "Hinglish", "Telugu", "Tamil", "Kannada", "Marathi", "Bengali")

private fun Group.summary(): String {
    val acting = when (autonomy) {
        Autonomy.AUTO -> "Replies for you"
        Autonomy.ASK -> "Asks before replying"
        Autonomy.OFF -> "Ignored"
    }
    return "${tone.replaceFirstChar { it.uppercase() }} · $acting"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen() {
    val config by Store.config.collectAsState()
    var editGroup by remember { mutableStateOf<String?>(null) }
    var pickFor by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editCategory by remember { mutableStateOf<Category?>(null) }
    var ignoring by remember { mutableStateOf(false) }
    var editChat by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { PageHeader("People", "Who Alfred handles, how it talks to them, and how much it may do.") }
        item { Overline("Message types") }
        item {
            Panel {
                Category.entries.forEachIndexed { i, cat ->
                    if (i > 0) Divider()
                    val level = config.handling(cat)
                    ListRow(cat.label, cat.desc, leading = { IconBadge(cat.icon) }, onClick = { editCategory = cat },
                        trailing = { Pill(level.human("Ignore"), color = if (level == Autonomy.OFF) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) })
                }
            }
        }
        item { Overline("Circles") }
        item {
            Panel {
                config.groups.forEachIndexed { i, g ->
                    if (i > 0) Divider()
                    val members = config.contacts.count { it.value == g.id }
                    ListRow(g.name, g.summary(), leading = { Avatar(g.name) }, onClick = { editGroup = g.id },
                        trailing = { if (members > 0) Pill("$members") })
                }
                Divider()
                ListRow("New circle", leading = { IconBadge(Icons.Filled.Add) }, trailing = null, onClick = { creating = true })
            }
        }
        item { Overline("People · ${config.contacts.size}") }
        item {
            Panel {
                if (config.contacts.isEmpty()) EmptyState(Icons.Filled.Person, "No one yet",
                    "People appear here after they message you one-to-one. Put them in a circle so Alfred knows how to treat them.")
                config.contacts.keys.sorted().forEachIndexed { i, name ->
                    if (i > 0) Divider()
                    val g = config.groupOf(name)
                    ListRow(name, if (name in config.muted) "Muted" else null, leading = { Avatar(name, 36.dp) },
                        trailing = { Pill(g.name) }, onClick = { pickFor = name })
                }
            }
        }
        item { Overline("Group chats · ${config.groupChats.size}") }
        item {
            Panel {
                if (config.groupChats.isEmpty()) EmptyState(Icons.Filled.Email, "No group chats yet",
                    "Groups appear here after their first message. By default Alfred only reads a group message when it mentions you.")
                config.groupChats.keys.sorted().forEachIndexed { i, chat ->
                    if (i > 0) Divider()
                    val mode = config.chatMode(chat)
                    ListRow(chat, mode.label, leading = { Avatar(chat, 36.dp) }, onClick = { editChat = chat },
                        trailing = { if (mode == ChatMode.IGNORE) Pill("Ignored", color = MaterialTheme.colorScheme.error) })
                }
            }
        }
        item { Overline("Ignored") }
        item {
            Panel {
                if (config.ignoredSenders.isEmpty() && config.ignoredWords.isEmpty())
                    ListRow("Nothing ignored", "Alfred skips these people and words completely.", trailing = null)
                config.ignoredSenders.forEach { who ->
                    ListRow(who, "Person", leading = { Avatar(who, 36.dp) }, trailing = {
                        TextButton(onClick = { Store.update { it.copy(ignoredSenders = it.ignoredSenders - who) } }) { Text("Remove") }
                    })
                    Divider()
                }
                config.ignoredWords.forEach { w ->
                    ListRow("“$w”", "Messages containing this", leading = { IconBadge(Icons.Filled.Close, size = 36.dp) }, trailing = {
                        TextButton(onClick = { Store.update { it.copy(ignoredWords = it.ignoredWords - w) } }) { Text("Remove") }
                    })
                    Divider()
                }
                ListRow("Ignore a person or word", leading = { IconBadge(Icons.Filled.Add) }, trailing = null, onClick = { ignoring = true })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    editCategory?.let { cat -> CategorySheet(cat, config.handling(cat)) { editCategory = null } }
    editChat?.let { chat -> ChatSheet(chat, config) { editChat = null } }
    if (ignoring) IgnoreSheet(config) { ignoring = false }
    editGroup?.let { id ->
        val g = config.groups.firstOrNull { it.id == id }
        if (g == null) editGroup = null else GroupSheet(g) { editGroup = null }
    }
    pickFor?.let { name -> ContactSheet(name, config) { pickFor = null } }
    if (creating) NewGroupSheet { creating = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSheet(g: Group, close: () -> Unit) {
    fun save(f: (Group) -> Group) = Store.update { c -> c.copy(groups = c.groups.map { if (it.id == g.id) f(it) else it }) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding()) {
            SheetTitle(g.name, "How Alfred handles people in this circle.")
            Label("Tone")
            ChoiceChips(TONES, TONES.firstOrNull { it.equals(g.tone, true) } ?: g.tone.replaceFirstChar { it.uppercase() }) { v ->
                save { it.copy(tone = v.lowercase()) }
            }
            Label("Reply in")
            ChoiceChips(LANGS, g.language.ifBlank { LANGS.first() }) { v -> save { it.copy(language = if (v == LANGS.first()) "" else v) } }
            Label("When they message you")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceCard("Reply for me", "Alfred sends replies on its own.", g.autonomy == Autonomy.AUTO) {
                    save { it.copy(autonomy = Autonomy.AUTO) }
                    // Picking this for a group is a clear signal; don't let the global Reply skill silently hold it back.
                    Store.update { c -> if (c.autonomy(Skill.REPLY) == Autonomy.ASK) c.copy(skills = c.skills + (Skill.REPLY to Autonomy.AUTO)) else c }
                }
                ChoiceCard("Ask me first", "Drafts a reply and waits for your OK.", g.autonomy == Autonomy.ASK) { save { it.copy(autonomy = Autonomy.ASK) } }
                ChoiceCard("Leave them alone", "Alfred ignores everyone in this circle.", g.autonomy == Autonomy.OFF) { save { it.copy(autonomy = Autonomy.OFF) } }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (g.id != Config.OTHERS) TextButton(onClick = {
                    Store.update { c -> c.copy(groups = c.groups - g, contacts = c.contacts.mapValues { (_, v) -> if (v == g.id) Config.OTHERS else v }) }
                    close()
                }) { Text("Delete circle", color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.weight(1f))
                Button(onClick = close) { Text("Done") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContactSheet(name: String, config: Config, close: () -> Unit) {
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 8.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, 48.dp)
                Spacer(Modifier.padding(6.dp))
                SheetTitle(name, "Which circle is this?")
            }
            val current = config.groupOf(name)
            config.groups.forEach { g ->
                ListRow(g.name, g.summary(), leading = { Avatar(g.name, 36.dp) },
                    trailing = { if (g.id == current.id) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { Store.update { it.copy(contacts = it.contacts + (name to g.id)) }; close() })
            }
            if (name in config.muted) TextButton(onClick = { Store.update { it.copy(muted = it.muted - name) }; close() },
                modifier = Modifier.padding(horizontal = 8.dp)) { Text("Unmute $name") }
            val ignored = config.ignoredSenders.any { it.equals(name, true) }
            TextButton(modifier = Modifier.padding(horizontal = 8.dp), onClick = {
                Store.update {
                    if (ignored) it.copy(ignoredSenders = it.ignoredSenders.filterNot { s -> s.equals(name, true) })
                    else it.copy(ignoredSenders = it.ignoredSenders + name)
                }
                close()
            }) {
                Text(if (ignored) "Stop ignoring $name" else "Ignore $name",
                    color = if (ignored) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewGroupSheet(close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetTitle("New circle", "For example Gym, College, Clients or Neighbours.")
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Circle name") }, singleLine = true)
            Button(enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 16.dp), onClick = {
                val g = Group("g${Store.newId()}", name.trim(), "casual", "", Autonomy.ASK)
                Store.update { it.copy(groups = it.groups + g) }
                close()
            }) { Text("Create") }
        }
    }
}

@Composable
fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))

val Category.icon
    get() = when (this) {
        Category.PERSONAL -> Icons.Filled.Face
        Category.GROUP_CHAT -> Icons.Filled.Email
        Category.BUSINESS -> Icons.Filled.ShoppingCart
        Category.UNKNOWN -> Icons.Filled.Phone
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySheet(cat: Category, level: Autonomy, close: () -> Unit) {
    fun set(a: Autonomy) { Store.update { it.copy(categories = it.categories + (cat to a)) }; close() }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetTitle(cat.label, cat.desc)
            ChoiceCard("Handle normally", "Follows your Skills and People settings.", level == Autonomy.AUTO) { set(Autonomy.AUTO) }
            ChoiceCard("Always ask first", "Alfred only suggests. Nothing happens without your OK.", level == Autonomy.ASK) { set(Autonomy.ASK) }
            ChoiceCard("Ignore", "Alfred skips these messages completely. The AI never reads them.", level == Autonomy.OFF) { set(Autonomy.OFF) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IgnoreSheet(config: Config, close: () -> Unit) {
    var kind by remember { mutableStateOf("Person") }
    var value by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetTitle("Ignore", "Alfred will skip these completely.")
            ChoiceChips(listOf("Person", "Word or phrase"), kind) { kind = it; value = "" }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(if (kind == "Person") "Name" else "e.g. OTP, Good morning, sale") })
            if (kind == "Person") {
                val suggestions = config.contacts.keys
                    .filter { c -> config.ignoredSenders.none { it.equals(c, true) } && c.contains(value, true) }
                    .sorted().take(6)
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    ChoiceChips(suggestions, value) { value = it }
                }
            }
            Button(enabled = value.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 16.dp), onClick = {
                val v = value.trim()
                Store.update { if (kind == "Person") it.copy(ignoredSenders = it.ignoredSenders + v) else it.copy(ignoredWords = it.ignoredWords + v) }
                close()
            }) { Text("Ignore") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSheet(chat: String, config: Config, close: () -> Unit) {
    val mode = config.chatMode(chat)
    fun set(m: ChatMode) { Store.update { it.copy(groupChats = it.groupChats + (chat to m)) }; close() }
    val names = (listOf(config.userName) + config.aliases).filter { it.isNotBlank() }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(chat, 48.dp)
                Spacer(Modifier.padding(6.dp))
                SheetTitle(chat, "Group chat")
            }
            ChatMode.entries.forEach { m -> ChoiceCard(m.label, m.desc, mode == m) { set(m) } }
            Hint(if (names.isEmpty()) "Add your name in Settings → Profile so Alfred can spot mentions."
                 else "Counts as a mention: ${names.joinToString()}. Add nicknames in Settings → Profile.")
        }
    }
}
