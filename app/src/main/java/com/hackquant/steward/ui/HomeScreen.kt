package com.hackquant.steward.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.material3.CircularProgressIndicator
import com.hackquant.steward.Stage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hackquant.steward.Activity
import com.hackquant.steward.Entry
import com.hackquant.steward.Llm
import com.hackquant.steward.Rule
import com.hackquant.steward.Skill
import com.hackquant.steward.Status
import com.hackquant.steward.Store
import com.hackquant.steward.Tools
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.concurrent.thread

private val clock = SimpleDateFormat("h:mm a", Locale.US)
private val dayClock = SimpleDateFormat("EEE h:mm a", Locale.US)

private fun whenLabel(t: Long): String {
    val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    val day = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_YEAR)
    return if (day == today) clock.format(t) else dayClock.format(t)
}

private fun greeting(name: String): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val part = when { h < 12 -> "Good morning"; h < 17 -> "Good afternoon"; else -> "Good evening" }
    return if (name.isBlank()) part else "$part, $name"
}

@Composable
fun HomeScreen(perms: Perms, openSettings: () -> Unit) {
    val acts by Store.activity.collectAsState()
    val config by Store.config.collectAsState()
    val model by Llm.status.collectAsState()
    val pending = acts.filter { a -> a.entries.any { it.status == Status.PENDING } }
    // One row per person, most recent conversation first. Activities are stored newest first.
    val conversations = acts.groupBy { it.msg.sender }.entries.sortedByDescending { it.value.first().time }
    val ctx = LocalContext.current
    val upcoming by produceState(emptyList<Tools.Upcoming>(), acts) { value = withContext(Dispatchers.IO) { Tools.upcoming(ctx) } }
    val startOfDay = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis
    val today = acts.filter { it.time >= startOfDay }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(greeting(config.userName), Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                StatusPill(!config.paused) { Store.update { it.copy(paused = !it.paused) } }
            }
        }
        item {
            Summary(
                handled = today.size,
                actions = today.sumOf { a -> a.entries.count { it.status == Status.DONE } },
                waiting = pending.sumOf { a -> a.entries.count { it.status == Status.PENDING } },
                engine = if (Llm.ready()) "Private AI on this phone · ${Llm.modelName}" else model,
            )
        }
        if (!perms.notificationAccess) item {
            Nudge("Alfred can't see new messages yet", "Allow", perms.openNotificationAccess)
        } else if (config.paused) item {
            Nudge("Paused. New messages are being ignored.", "Resume") { Store.update { it.copy(paused = false) } }
        }

        val live = acts.filter { it.stage != null }.sortedBy { it.time }
        if (live.isNotEmpty()) {
            item { Overline("Working on it · ${live.size}") }
            items(live, key = { "l${it.id}" }) { a -> LiveCard(a, position = live.indexOf(a)) }
        }

        if (pending.isNotEmpty()) {
            item { Overline("Needs you · ${pending.sumOf { a -> a.entries.count { it.status == Status.PENDING } }}") }
            items(pending, key = { "p${it.id}" }) { PendingCard(it) }
        }

        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60_000
        val briefs = acts.filter { it.time >= dayAgo }.flatMap { a ->
            a.entries.filter { it.skill == Skill.BRIEF && it.status == Status.DONE }.map { a to it }
        }
        if (briefs.isNotEmpty()) {
            item { Overline("Briefings · ${briefs.size}") }
            item {
                Panel {
                    briefs.forEachIndexed { i, (a, e) ->
                        if (i > 0) Divider()
                        ListRow(e.summary.removePrefix("Told you: "), "${a.msg.sender} · ${whenLabel(a.time)}",
                            leading = { Avatar(a.msg.sender, 36.dp) },
                            trailing = { TextButton(onClick = { Store.updateEntry(a.id, e.id) { it.copy(status = Status.DISMISSED) } }) { Text("Got it") } })
                    }
                }
            }
        }

        if (upcoming.isNotEmpty()) {
            item { Overline("Coming up · added by Alfred") }
            item {
                Panel {
                    upcoming.forEachIndexed { i, ev ->
                        if (i > 0) Divider()
                        ListRow(ev.title, dayClock.format(ev.start) + if (ev.location.isNotBlank()) " · ${ev.location}" else "",
                            leading = { IconBadge(Icons.Filled.DateRange, size = 36.dp) }, onClick = { Tools.openEvent(ctx, ev.uri) })
                    }
                }
            }
        }

        item { Overline("Conversations") }
        if (conversations.isEmpty()) item {
            Panel {
                EmptyState(Icons.Filled.CheckCircle, "All quiet",
                    "When someone messages you in a watched app, the conversation and what Alfred did show up here.")
            }
        } else item {
            Panel {
                conversations.forEachIndexed { i, (sender, list) ->
                    if (i > 0) Divider()
                    ConversationRow(sender, list)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun StatusPill(active: Boolean, onToggle: () -> Unit) {
    val extra = LocalExtra.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) extra.success else MaterialTheme.colorScheme.onSurfaceVariant))
        Spacer(Modifier.width(8.dp))
        Text(if (active) "Active" else "Paused", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun Summary(handled: Int, actions: Int, waiting: Int, engine: String) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(20.dp)) {
            Text("TODAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
            Spacer(Modifier.height(10.dp))
            Row {
                Stat(handled, "messages", Modifier.weight(1f))
                Stat(actions, "actions taken", Modifier.weight(1f))
                Stat(waiting, "need you", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                Spacer(Modifier.width(6.dp))
                Text(engine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Stat(n: Int, label: String, modifier: Modifier) {
    Column(modifier) {
        Text("$n", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
    }
}

@Composable
private fun Nudge(text: String, action: String, onClick: () -> Unit) {
    val extra = LocalExtra.current
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = extra.attentionContainer) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = extra.attention, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClick) { Text(action, color = extra.attention) }
        }
    }
}

@Composable
private fun Sender(a: Activity) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(a.msg.sender)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.msg.sender, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(a.msg.pkg, 14.dp)
                if (a.msg.pkg.isNotBlank()) Spacer(Modifier.width(6.dp))
                Hint("${a.msg.app} · ${whenLabel(a.time)}")
            }
        }
    }
}

@Composable
private fun Bubble(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun PendingCard(a: Activity) {
    val ctx = LocalContext.current
    val waiting = a.entries.filter { it.status == Status.PENDING }
    Surface(Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp)) {
            Sender(a)
            Bubble(a.msg.text)
            Spacer(Modifier.height(14.dp))
            Text("Alfred suggests", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            waiting.forEach { e -> Suggestion(a, e) }
            if (waiting.size > 1) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { thread { Tools.approve(ctx, a.id, null) } }) { Text("Approve all") }
                TextButton(onClick = { Tools.dismiss(ctx, a.id, null) }) { Text("Skip all") }
            }
        }
    }
}

@Composable
private fun Suggestion(a: Activity, e: Entry) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val sends = e.skill == Skill.REPLY

    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
        IconBadge(e.skill.icon, size = 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.summary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            e.why?.let { Text("Asking because $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { thread { Tools.approve(ctx, a.id, e.id) } },
                    contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(if (sends) "Send" else "Do it") }
                if (sends) TextButton(onClick = { editing = true }) { Text("Edit") }
                TextButton(onClick = { Tools.dismiss(ctx, a.id, e.id) }) { Text("Skip") }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Never ${e.skill.label.lowercase()} for ${a.msg.sender}") }, onClick = {
                            menu = false
                            Tools.dismiss(ctx, a.id, e.id)
                            val rule = "Never use the ${e.skill.id} tool for messages from ${a.msg.sender}."
                            Store.update { it.copy(rules = it.rules + Rule(Store.newId(), rule)) }
                        })
                        DropdownMenuItem(text = { Text("Ignore ${a.msg.sender}") }, onClick = {
                            menu = false
                            Tools.dismiss(ctx, a.id, null)
                            Store.update { it.copy(ignoredSenders = it.ignoredSenders + a.msg.sender) }
                        })
                    }
                }
            }
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(org.json.JSONObject(e.action).optString("text")) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Reply to ${a.msg.sender}") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3)
                    Hint("Sent as: ${Tools.disclosed(a.msg.sender, text.trim())}", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { Button(onClick = { editing = false; thread { Tools.approve(ctx, a.id, e.id, text) } }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConversationRow(sender: String, newestFirst: List<Activity>) {
    var open by rememberSaveable(sender) { mutableStateOf(false) }
    val latest = newestFirst.first()
    val sentBySteward = newestFirst.sumOf { a -> a.entries.count { it.status == Status.DONE } }
    val waiting = newestFirst.sumOf { a -> a.entries.count { it.status == Status.PENDING } }

    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(sender, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(sender, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(latest.msg.pkg, 13.dp)
                    if (latest.msg.pkg.isNotBlank()) Spacer(Modifier.width(5.dp))
                    Text(latest.stage?.let { "Alfred: ${it.label.lowercase()}…" } ?: latest.msg.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (latest.stage != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Hint(whenLabel(latest.time))
                Spacer(Modifier.height(4.dp))
                when {
                    waiting > 0 -> Pill("$waiting waiting", color = LocalExtra.current.attention, bg = LocalExtra.current.attentionContainer)
                    sentBySteward > 0 -> Pill("$sentBySteward done")
                }
            }
        }
        if (open) ConversationThread(newestFirst.reversed())
    }
}

/** Their messages on the left, what Alfred did on the right, oldest at the top. Scrolls inside the row. */
@Composable
private fun ConversationThread(oldestFirst: List<Activity>) {
    val scroll = rememberScrollState()
    LaunchedEffect(oldestFirst.size) { scroll.scrollTo(scroll.maxValue) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(horizontal = 12.dp)
            .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .verticalScroll(scroll).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        oldestFirst.forEach { a ->
            Theirs(a.msg.text, whenLabel(a.time))
            a.entries.forEach { e -> Ours(a, e) }
            a.stage?.let { st ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Hint("${st.label}…")
                }
            }
            a.note?.let { n ->
                Text(n, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Theirs(text: String, time: String) {
    Column(Modifier.fillMaxWidth(0.85f)) {
        Text(text, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 8.dp))
        Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp))
    }
}

@Composable
private fun Ours(a: Activity, e: Entry) {
    val ctx = LocalContext.current
    val extra = LocalExtra.current
    val reply = if (e.skill == Skill.REPLY) org.json.JSONObject(e.action).optString("text") else ""
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (e.skill == Skill.REPLY && e.status == Status.DONE && reply.isNotBlank()) {
            // A message Alfred actually sent, styled like your own chat bubble.
            Text(reply, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.fillMaxWidth(0.85f).wrapContentWidth(Alignment.End)
                    .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                    .background(MaterialTheme.colorScheme.primary).padding(horizontal = 12.dp, vertical = 8.dp))
            Text("Sent by Alfred", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp, top = 2.dp))
        } else {
            val (label, tint) = when (e.status) {
                Status.DONE -> "Done" to extra.success
                Status.PENDING -> "Waiting for you" to extra.attention
                Status.FAILED -> "Didn't work" to MaterialTheme.colorScheme.error
                Status.DISMISSED -> "Skipped" to MaterialTheme.colorScheme.onSurfaceVariant
                Status.UNDONE -> "Undone" to MaterialTheme.colorScheme.onSurfaceVariant
                Status.BLOCKED -> "Not done" to MaterialTheme.colorScheme.error
            }
            Row(
                Modifier.fillMaxWidth(0.9f).wrapContentWidth(Alignment.End).clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(e.skill.icon, null, Modifier.size(16.dp), tint = tint)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(e.summary, style = MaterialTheme.typography.bodySmall)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
                    e.why?.let { Text(if (e.status == Status.BLOCKED) "Blocked: $it" else "Asked because: $it",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                val undo = e.undo
                if (e.status == Status.DONE && undo != null) {
                    if (undo.startsWith("calendar:")) TextButton(onClick = { Tools.openEvent(ctx, android.net.Uri.parse(undo.removePrefix("calendar:"))) }) { Text("Open") }
                    TextButton(onClick = {
                        thread { if (Tools.undo(ctx, undo)) Store.updateEntry(a.id, e.id) { it.copy(status = Status.UNDONE) } }
                    }) { Text("Undo") }
                }
            }
        }
    }
}

/** A message Alfred is working on right now, with a live stage and timer. */
@Composable
private fun LiveCard(a: Activity, position: Int) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(500) }
    }
    val stage = a.stage ?: return
    val secs = ((now - a.stageAt).coerceAtLeast(0) / 1000)
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp)) {
            Sender(a)
            Bubble(a.msg.text)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stage.label, style = MaterialTheme.typography.titleSmall)
                    Hint(when (stage) {
                        Stage.QUEUED -> if (position > 0) "$position message${if (position > 1) "s" else ""} ahead" else "Starting…"
                        Stage.THINKING -> "Private, on this phone · ${secs}s"
                        Stage.ACTING -> "Applying your settings"
                    })
                }
            }
            // Three-step progress: queued, thinking, acting.
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stage.entries.forEach { st ->
                    Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(
                        if (st.ordinal <= stage.ordinal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh))
                }
            }
        }
    }
}
