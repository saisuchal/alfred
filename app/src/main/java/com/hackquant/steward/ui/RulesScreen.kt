package com.hackquant.steward.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.hackquant.steward.Agent
import com.hackquant.steward.Decision
import com.hackquant.steward.Incoming
import com.hackquant.steward.Plan
import com.hackquant.steward.Rule
import com.hackquant.steward.Store
import com.hackquant.steward.Tools

private val IDEAS = listOf(
    "If my manager messages after 9pm, don't reply. Remind me at 9am the next day.",
    "Never mute messages from banks or anything with an OTP.",
    "Reply to family in Telugu.",
    "When I'm at Office, don't reply to friends. Remind me at 7pm instead.",
    "If anyone asks for money, never reply. Remind me to call them.",
    "Ignore group chats unless someone mentions my name.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen() {
    val config by Store.config.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var testing by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            PageHeader("Rules", "Tell Alfred how you like things done, in your own words.", action = {
                FilledTonalButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("New")
                }
            })
        }
        item { Spacer(Modifier.height(12.dp)) }
        item {
            Panel {
                ListRow("Try a message", "See what Alfred would do, without doing it", leading = { IconBadge(Icons.Filled.PlayArrow) },
                    onClick = { testing = true })
            }
        }
        item { Overline(if (config.rules.isEmpty()) "Your rules" else "Your rules · ${config.rules.count { it.enabled }} on") }
        item {
            Panel {
                if (config.rules.isEmpty()) EmptyState(Icons.Filled.Build, "No rules yet",
                    "Rules shape how Alfred handles messages. Tap New to write one or start from an idea.")
                config.rules.forEachIndexed { i, r ->
                    if (i > 0) Divider()
                    Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
                        Text("“${r.text}”", Modifier.weight(1f).clickable { editing = r }.padding(top = 6.dp), style = MaterialTheme.typography.bodyLarge,
                            color = if (r.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = r.enabled, onCheckedChange = { v ->
                            Store.update { c -> c.copy(rules = c.rules.map { if (it.id == r.id) it.copy(enabled = v) else it }) }
                        })
                    }
                    Row(Modifier.padding(start = 4.dp)) {
                        TextButton(onClick = { editing = r }) { Text("Edit") }
                        TextButton(onClick = { Store.update { c -> c.copy(rules = c.rules - r) } }) {
                            Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {
            Hint("Rules guide the AI, and your Skills and People settings always have the final say. " +
                "For example, a rule can't make Alfred reply to a group you've set to Leave them alone.",
                Modifier.padding(horizontal = 4.dp, vertical = 16.dp))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (adding) NewRuleSheet(config.rules.map { it.text }) { adding = false }
    editing?.let { r -> EditRuleSheet(r) { editing = null } }
    if (testing) TestSheet { testing = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditRuleSheet(rule: Rule, close: () -> Unit) {
    var text by remember(rule.id) { mutableStateOf(rule.text) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().imePadding()) {
            SheetTitle("Edit rule", "Changes apply to the next message.")
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3)
            Button(
                enabled = text.isNotBlank() && text.trim() != rule.text,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                onClick = {
                    Store.update { c -> c.copy(rules = c.rules.map { if (it.id == rule.id) it.copy(text = text.trim()) else it }) }
                    close()
                },
            ) { Text("Save") }
            TextButton(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), onClick = {
                Store.update { c -> c.copy(rules = c.rules.filterNot { it.id == rule.id }) }
                close()
            }) { Text("Delete rule", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewRuleSheet(existing: List<String>, close: () -> Unit) {
    var text by remember { mutableStateOf("") }
    fun add(t: String) {
        Store.update { it.copy(rules = it.rules + Rule(Store.newId(), t.trim())) }
        close()
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().imePadding()) {
            SheetTitle("New rule", "Write it the way you'd tell a person.")
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3,
                placeholder = { Text("e.g. If my landlord messages, remind me in the evening", fontStyle = FontStyle.Italic) })
            Button(enabled = text.isNotBlank(), onClick = { add(text) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Add rule") }
            val ideas = IDEAS.filter { it !in existing }
            if (ideas.isNotEmpty()) {
                Label("Or start from an idea")
                Panel {
                    ideas.forEachIndexed { i, idea ->
                        if (i > 0) Divider()
                        ListRow(idea, trailing = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) }, onClick = { add(idea) })
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestSheet(close: () -> Unit) {
    val ctx = LocalContext.current
    val config by Store.config.collectAsState()
    var from by remember { mutableStateOf(config.contacts.keys.firstOrNull() ?: "") }
    var text by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<Plan?>(null) }

    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).navigationBarsPadding().imePadding()) {
            SheetTitle("Try a message", "Nothing is sent or saved.")
            OutlinedTextField(from, { from = it }, Modifier.fillMaxWidth(), label = { Text("From") }, singleLine = true,
                supportingText = { Text("Uses this person's group settings: ${config.groupOf(from.trim()).name}") })
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 4.dp), label = { Text("Their message") }, minLines = 2)
            Button(enabled = !running && from.isNotBlank() && text.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp), onClick = {
                running = true; plan = null
                Agent.preview(ctx, Incoming(from.trim(), "Test", "", text.trim())) { plan = it; running = false }
            }) { Text(if (running) "Thinking…" else "See what Alfred would do") }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            plan?.let { p ->
                Label("Alfred would")
                Panel {
                    if (p.proposals.isEmpty()) ListRow("Do nothing", "Nothing here needs action", trailing = null)
                    p.proposals.forEachIndexed { i, pr ->
                        if (i > 0) Divider()
                        val verdict = when (pr.decision) {
                            Decision.AUTO -> "Automatically"
                            Decision.ASK -> "Would ask you · ${pr.why}"
                            Decision.BLOCKED -> "Blocked · ${pr.why}"
                        }
                        ListRow(Tools.describe(pr.skill, pr.action), verdict, leading = { IconBadge(pr.skill.icon, size = 32.dp) }, trailing = null)
                    }
                }
                p.note?.let { Hint(it, Modifier.padding(top = 8.dp)) }
                Hint(p.engine, Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
