package com.hackquant.steward.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hackquant.steward.Activity
import com.hackquant.steward.Config
import com.hackquant.steward.Entry
import com.hackquant.steward.Group
import com.hackquant.steward.Owner
import com.hackquant.steward.Status
import com.hackquant.steward.Store
import kotlin.concurrent.thread

private fun age(t: Long): String {
    val m = ((System.currentTimeMillis() - t) / 60_000).toInt()
    return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 24 * 60 -> "${m / 60} h ago"; else -> "${m / 1440} d ago" }
}

/** Your status, shown to people when they message. Tap to change. */
@Composable
fun StatusChip(config: Config, onClick: () -> Unit) {
    val status = Owner.statusLine(config)
    val extra = LocalExtra.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (status != null) extra.attentionContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (status != null) extra.attention else extra.success))
        Spacer(Modifier.width(8.dp))
        Text(status ?: "Available · set status", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusSheet(close: () -> Unit) {
    var picked by remember { mutableStateOf(Owner.STATUSES.first()) }
    var custom by remember { mutableStateOf("") }
    val durations = listOf("30 min" to 30, "1 hour" to 60, "2 hours" to 120, "Until I change it" to null)
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            SheetTitle("Your status", "Alfred tells people this when they message, so they know when to expect you.")
            ChoiceChips(Owner.STATUSES, if (custom.isBlank()) picked else null) { picked = it; custom = "" }
            OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth().padding(top = 12.dp), singleLine = true,
                placeholder = { Text("Or type one, e.g. Travelling") })
            Label("For how long")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                durations.forEach { (label, minutes) ->
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        Owner.setStatus(custom.trim().ifBlank { picked }, minutes); close()
                    }) { Text(label) }
                }
            }
            TextButton(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = { Owner.clearStatus(); close() }) { Text("I'm available") }
        }
    }
}

/** Plain-language instructions to Alfred. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskAlfred(lastContact: String?) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun run(q: String) {
        if (q.isBlank() || busy) return
        busy = true; answer = null
        thread { val r = Owner.command(ctx, q); answer = r; busy = false }
        text = ""
    }
    Surface(Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Face, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                Text("Ask Alfred", style = MaterialTheme.typography.titleMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("e.g. tell ${lastContact ?: "Praveen"} I'll be 10 min late") })
                IconButton(enabled = text.isNotBlank() && !busy, onClick = { run(text) }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("What's pending?", "I'm in a meeting for 1 hour", "I'm back").forEach { s ->
                    TextButton(onClick = { run(s) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                        Text(s, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (busy) Hint("Working on it…")
            answer?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp))
            }
        }
    }
}

/** Something a person is waiting on. Answer in a tap and Alfred writes, sends and books it. */
@Composable
fun WaitingCard(a: Activity, e: Entry) {
    val ctx = LocalContext.current
    var typing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val urgent = Owner.priority(a, e) == Owner.Priority.URGENT
    val extra = LocalExtra.current
    fun answer(text: String) {
        busy = true
        thread { result = Owner.answer(ctx, a.id, text); busy = false }
    }
    Surface(Modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large,
        color = if (urgent) extra.attentionContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(a.msg.sender, 36.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.msg.sender, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Hint("waiting ${age(a.time)}" + (if (e.undo == "followed") " · followed up" else ""))
                }
                if (urgent) Pill("Urgent", color = extra.attention, bg = MaterialTheme.colorScheme.surface)
            }
            Text(e.summary.removePrefix("Told you: "), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("“${a.msg.text}”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (result == null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Owner.quickAnswers(a.msg).forEach { q ->
                    FilledTonalButton(enabled = !busy, onClick = { answer(q) }) { Text(q) }
                }
                TextButton(enabled = !busy, onClick = { typing = true }) { Text("Reply") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { Store.updateEntry(a.id, e.id) { it.copy(status = Status.DISMISSED) }; Owner.cancelBrief(ctx, a.id) }) {
                    Icon(Icons.Filled.Close, "Got it", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (busy) Hint("Alfred is writing to ${a.msg.sender}…")
            result?.let { Text(it, color = extra.success, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    if (typing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typing = false },
            title = { Text("Your answer to ${a.msg.sender}") },
            text = {
                Column {
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2, placeholder = { Text("A few words is enough, e.g. “yes, 8 works”") })
                    Hint("Alfred turns this into a proper message and signs it.", Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { Button(enabled = text.isNotBlank(), onClick = { typing = false; answer(text) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { typing = false }) { Text("Cancel") } },
        )
    }
}

/** Low-priority briefings, collected instead of interrupting. */
@Composable
fun FyiPanel(items: List<Pair<Activity, Entry>>) {
    var open by remember { mutableStateOf(false) }
    Panel {
        ListRow("${items.size} for your information", if (open) "Tap to hide" else items.first().second.summary.removePrefix("Told you: "),
            leading = { IconBadge(Icons.Filled.Face, size = 36.dp) }, trailing = null, onClick = { open = !open })
        if (open) {
            items.forEach { (a, e) ->
                Divider()
                ListRow(e.summary.removePrefix("Told you: "), "${a.msg.sender} · ${age(a.time)}", leading = { Avatar(a.msg.sender, 32.dp) },
                    trailing = { TextButton(onClick = { Store.updateEntry(a.id, e.id) { it.copy(status = Status.DISMISSED) } }) { Text("Got it") } })
            }
            Divider()
            TextButton(modifier = Modifier.padding(horizontal = 8.dp), onClick = {
                items.forEach { (a, e) -> Store.updateEntry(a.id, e.id) { it.copy(status = Status.DISMISSED) } }
            }) { Text("Clear all") }
        }
    }
}

/** After repeated approvals, offer to let Alfred handle a circle's replies. */
@Composable
fun SuggestionCard(g: Group, count: Int) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("You've approved $count of Alfred's replies to ${g.name}", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("Let Alfred send replies to ${g.name} on its own? Anything about your plans, money or time still waits for you.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Owner.acceptSuggestion(g) }) { Text("Let Alfred handle it") }
                TextButton(onClick = { Owner.declineSuggestion(g) }) { Text("Not now") }
            }
        }
    }
}
