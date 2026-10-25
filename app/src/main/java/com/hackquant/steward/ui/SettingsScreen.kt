package com.hackquant.steward.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hackquant.steward.Autonomy
import com.hackquant.steward.Llm
import com.hackquant.steward.Locator
import com.hackquant.steward.Place
import com.hackquant.steward.Skill
import com.hackquant.steward.Store
import kotlin.concurrent.thread

/** Permission state and requests, provided by the activity. */
class Perms(
    val notificationAccess: Boolean,
    val calendar: Boolean,
    val location: Boolean,
    val backgroundLocation: Boolean,
    val openNotificationAccess: () -> Unit,
    val requestCalendar: () -> Unit,
    val requestLocation: () -> Unit,
    val requestBackgroundLocation: () -> Unit,
)

private enum class Page { PROFILE, SKILLS, MEMORY, PLACES, APPS, PRIVACY }

@Composable
fun SettingsScreen(p: Perms, resetKey: Int = 0) {
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    // Tapping the Settings tab again returns to the menu.
    androidx.compose.runtime.LaunchedEffect(resetKey) { page = null }
    BackHandler(page != null) { page = null }
    val back = { page = null }
    when (page) {
        null -> SettingsHome(p) { page = it }
        Page.PROFILE -> ProfilePage(back)
        Page.SKILLS -> SkillsPage(back)
        Page.MEMORY -> MemoryPage(back)
        Page.PLACES -> PlacesPage(p, back)
        Page.APPS -> AppsPage(back)
        Page.PRIVACY -> PrivacyPage(p, back)
    }
}

@Composable
private fun SettingsHome(p: Perms, open: (Page) -> Unit) {
    val c by Store.config.collectAsState()
    val autoCount = Skill.entries.count { c.autonomy(it) == Autonomy.AUTO }
    val offCount = Skill.entries.count { c.autonomy(it) == Autonomy.OFF }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { PageHeader("Settings") }
        item { Overline("You") }
        item {
            Panel {
                ListRow(c.userName.ifBlank { "Your profile" }, c.style, leading = { Avatar(c.userName.ifBlank { "You" }) }, onClick = { open(Page.PROFILE) })
                Divider()
                ListRow("Memory", "${c.facts.size} things Alfred knows", leading = { IconBadge(Icons.Filled.Star) }, onClick = { open(Page.MEMORY) })
                Divider()
                ListRow("Places", if (c.places.isEmpty()) "Add Home, Office…" else c.places.joinToString { it.name },
                    leading = { IconBadge(Icons.Filled.LocationOn) }, onClick = { open(Page.PLACES) })
            }
        }
        item { Overline("Alfred") }
        item {
            Panel {
                ListRow("What it can do", "$autoCount automatic · ${Skill.entries.size - autoCount - offCount} ask first · $offCount off",
                    leading = { IconBadge(Icons.Filled.Build) }, onClick = { open(Page.SKILLS) })
                Divider()
                ListRow("Apps it watches", c.apps.filter { it.enabled }.joinToString { it.label }.ifBlank { "None" },
                    leading = { IconBadge(Icons.Filled.Notifications) }, onClick = { open(Page.APPS) })
                Divider()
                ListRow("Privacy & engine", if (Llm.ready()) "On-device · ${Llm.modelName}" else "Model not ready",
                    leading = { IconBadge(Icons.Filled.Lock) }, onClick = { open(Page.PRIVACY) })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SubPage(title: String, subtitle: String?, back: () -> Unit, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        // Pinned bar so the way back is always visible, however far the page scrolls.
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Settings") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (subtitle != null) item { Hint(subtitle, Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
            item { Spacer(Modifier.height(12.dp)) }
            content()
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private val STYLE_OPTIONS = listOf("Short", "Casual", "Friendly", "Formal", "Hinglish", "Lots of emoji", "No emoji", "Lowercase")

@Composable
private fun ProfilePage(back: () -> Unit) {
    val c by Store.config.collectAsState()
    SubPage("Profile", "Alfred writes as you, so it should sound like you.", back) {
        item {
            OutlinedTextField(c.userName, { v -> Store.update { it.copy(userName = v) } }, Modifier.fillMaxWidth(),
                label = { Text("Your first name") }, singleLine = true)
        }
        item { Label("Other names people use for you") }
        item { Hint("Used to spot when a group chat mentions you, e.g. a nickname or how your name is saved in others' phones.") }
        item {
            var alias by remember { mutableStateOf("") }
            Column {
                c.aliases.forEach { a ->
                    ListRow(a, trailing = {
                        IconButton(onClick = { Store.update { it.copy(aliases = it.aliases - a) } }) {
                            Icon(Icons.Filled.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(alias, { alias = it }, Modifier.weight(1f), placeholder = { Text("e.g. Such, Suchu") }, singleLine = true)
                    IconButton(enabled = alias.isNotBlank(), onClick = { Store.update { it.copy(aliases = it.aliases + alias.trim()) }; alias = "" }) {
                        Icon(Icons.Filled.Add, "Add")
                    }
                }
            }
        }
        item { Label("How you text") }
        item {
            val picked = c.style.split(", ").filter { it in STYLE_OPTIONS }.toSet()
            MultiChips(STYLE_OPTIONS, picked) { s ->
                val next = if (s in picked) picked - s else picked + s
                Store.update { it.copy(style = next.joinToString(", ").ifBlank { "Short, friendly, casual messages." }) }
            }
        }
    }
}

@Composable
private fun SkillsPage(back: () -> Unit) {
    val c by Store.config.collectAsState()
    SubPage("What it can do", "Automatic: just does it. Ask first: suggests, you approve. Never: switched off.", back) {
        Skill.entries.forEach { s ->
            item {
                Panel(Modifier.padding(bottom = 10.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(s.icon, size = 36.dp)
                            Spacer(Modifier.padding(6.dp))
                            Column {
                                Text(s.label, style = MaterialTheme.typography.titleMedium)
                                Hint(s.desc)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        AutonomyChoice(c.autonomy(s), { v -> Store.update { it.copy(skills = it.skills + (s to v)) } })
                    }
                }
            }
        }
        item { Hint("Replies also follow each circle's setting in People.", Modifier.padding(horizontal = 4.dp)) }
    }
}

@Composable
private fun MemoryPage(back: () -> Unit) {
    val c by Store.config.collectAsState()
    var fact by remember { mutableStateOf("") }
    SubPage("Memory", "Things Alfred uses to act for you. It adds to this as it learns, and you can remove anything.", back) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(fact, { fact = it }, Modifier.weight(1f), placeholder = { Text("e.g. Deliveries go to Gate 2") }, singleLine = true)
                IconButton(enabled = fact.isNotBlank(), onClick = { Store.update { it.copy(facts = it.facts + fact.trim()) }; fact = "" }) {
                    Icon(Icons.Filled.Add, "Add")
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Panel {
                if (c.facts.isEmpty()) EmptyState(Icons.Filled.Star, "Nothing yet", "Add things like your address, your partner's name, or your working hours.")
                c.facts.forEachIndexed { i, f ->
                    if (i > 0) Divider()
                    ListRow(f, trailing = {
                        IconButton(onClick = { Store.update { it.copy(facts = it.facts - f) } }) {
                            Icon(Icons.Filled.Close, "Forget", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    })
                }
            }
        }
    }
}

@Composable
private fun PlacesPage(p: Perms, back: () -> Unit) {
    val ctx = LocalContext.current
    val c by Store.config.collectAsState()
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    SubPage("Places", "Alfred knows when you're at these places, and can remind you when you arrive.", back) {
        if (!p.location) item {
            Panel { ListRow("Location is off", "Needed to save places", trailing = { TextButton(onClick = p.requestLocation) { Text("Allow") } }) }
        } else if (!p.backgroundLocation) item {
            Panel {
                ListRow("Allow all the time", "For arrival reminders while Alfred runs in the background",
                    trailing = { TextButton(onClick = p.requestBackgroundLocation) { Text("Allow") } })
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Panel {
                if (c.places.isEmpty()) EmptyState(Icons.Filled.LocationOn, "No places yet", "Go somewhere you spend time, like home, and save it below.")
                c.places.forEachIndexed { i, pl ->
                    if (i > 0) Divider()
                    ListRow(pl.name, "Within ${pl.radius.toInt()} m", leading = { IconBadge(Icons.Filled.LocationOn, size = 32.dp) }, trailing = {
                        IconButton(onClick = { Store.update { it.copy(places = it.places - pl) } }) {
                            Icon(Icons.Filled.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    })
                }
            }
        }
        item { Label("Save where you are now") }
        item { ChoiceChips(listOf("Home", "Office", "Gym", "College"), name) { name = it } }
        item {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, Modifier.weight(1f), placeholder = { Text("Or type a name") }, singleLine = true)
                Spacer(Modifier.padding(4.dp))
                FilledTonalButton(enabled = p.location && name.isNotBlank(), onClick = {
                    val n = name.trim()
                    status = "Finding you…"
                    thread {
                        val loc = Locator.current(ctx)
                        if (loc == null) status = "Couldn't get a fix. Is location turned on?"
                        else {
                            Store.update { cc -> cc.copy(places = cc.places.filter { it.name != n } + Place(n, loc.latitude, loc.longitude)) }
                            status = "Saved $n"; name = ""
                        }
                    }
                }) { Text("Save") }
            }
        }
        status?.let { item { Hint(it, Modifier.padding(top = 8.dp, start = 4.dp)) } }
    }
}

@Composable
private fun AppsPage(back: () -> Unit) {
    val c by Store.config.collectAsState()
    SubPage("Apps it watches", "Alfred only reads notifications from apps switched on here. New messaging apps show up switched off.", back) {
        item {
            Panel {
                c.apps.forEachIndexed { i, app ->
                    if (i > 0) Divider()
                    ListRow(app.label, leading = { AppIcon(app.pkg, 32.dp) }, trailing = {
                        Switch(checked = app.enabled, onCheckedChange = { v ->
                            Store.update { cc -> cc.copy(apps = cc.apps.map { if (it.pkg == app.pkg) it.copy(enabled = v) else it }) }
                        })
                    })
                }
            }
        }
    }
}

@Composable
private fun PrivacyPage(p: Perms, back: () -> Unit) {
    val ctx = LocalContext.current
    val model by Llm.status.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    SubPage("Privacy & engine", null, back) {
        item {
            Panel {
                ListRow("Runs on this phone", "Messages, memory and location are processed here and never uploaded.",
                    leading = { IconBadge(Icons.Filled.Lock) }, trailing = null)
                Divider()
                ListRow("AI model", model, leading = { IconBadge(Icons.Filled.Face) },
                    trailing = if (Llm.ready()) null else ({ TextButton(onClick = { Llm.load(ctx) }) { Text("Retry") } }))
            }
        }
        item { Overline("Permissions") }
        item {
            Panel {
                PermRow("Message notifications", p.notificationAccess, p.openNotificationAccess)
                Divider(); PermRow("Calendar", p.calendar, p.requestCalendar)
                Divider(); PermRow("Location", p.location, p.requestLocation)
                Divider(); PermRow("Location in background", p.backgroundLocation, p.requestBackgroundLocation)
            }
        }
        item { Overline("Your data") }
        item {
            Panel {
                ListRow("Clear activity", "Removes the history on the Home screen", trailing = null, onClick = { Store.clearActivity() })
                Divider()
                ListRow("Forget everything", "Erases memory, rules, people, places and history", trailing = null, onClick = { confirm = true })
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Forget everything?") },
        text = { Text("This erases everything Alfred knows and has done, and starts setup again.") },
        confirmButton = { Button(onClick = { Store.forgetEverything(); confirm = false }) { Text("Erase") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
private fun PermRow(label: String, on: Boolean, fix: () -> Unit) {
    ListRow(label, if (on) "Allowed" else "Not allowed", trailing = {
        if (on) Pill("On", color = LocalExtra.current.success) else TextButton(onClick = fix) { Text("Allow") }
    })
}
