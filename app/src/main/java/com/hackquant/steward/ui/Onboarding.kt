package com.hackquant.steward.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.hackquant.steward.Preset
import com.hackquant.steward.Store

private val STYLES = listOf("Short", "Casual", "Friendly", "Formal", "Hinglish", "Lots of emoji", "No emoji", "Lowercase")

@Composable
fun Onboarding(perms: Perms) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val last = 5
    BackHandler(step > 0) { step-- }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        // Progress
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(last + 1) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape)
                    .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 40.dp)) {
            when (step) {
                0 -> Welcome()
                1 -> PermissionStep(Icons.Filled.Notifications, "See new messages",
                    "Alfred reads message notifications from the apps you choose, so it can act on them. It can't open your chats or see older messages.",
                    perms.notificationAccess)
                2 -> PermissionStep(Icons.Filled.DateRange, "Your calendar",
                    "So it can add plans like \"dinner tomorrow at 8\" straight to your calendar.", perms.calendar)
                3 -> PermissionStep(Icons.Filled.LocationOn, "Where you are",
                    "Optional. Lets Alfred know when you're home or at work, and remind you when you arrive somewhere. Your location stays on this phone and is never shared with anyone.",
                    perms.location)
                4 -> AboutYou()
                else -> PresetStep()
            }
        }
        // Bottom actions
        val next: () -> Unit = { step++ }
        val (label, primary, secondary) = when (step) {
            0 -> Triple<String, () -> Unit, Pair<String, () -> Unit>?>("Get started", next, null)
            1 -> if (perms.notificationAccess) Triple("Continue", next, null)
                 else Triple("Open settings", perms.openNotificationAccess, null)
            2 -> if (perms.calendar) Triple("Continue", next, null)
                 else Triple("Allow calendar", perms.requestCalendar, "Skip" to next)
            3 -> if (perms.location) Triple("Continue", next, null)
                 else Triple("Allow location", perms.requestLocation, "Not now" to next)
            4 -> Triple("Continue", next, null)
            else -> Triple<String, () -> Unit, Pair<String, () -> Unit>?>("Start Alfred", { Store.update { it.copy(onboarded = true) } }, null)
        }
        // Advance automatically once a permission is granted.
        LaunchedEffect(perms.notificationAccess, perms.calendar, perms.location, step) {
            if ((step == 1 && perms.notificationAccess) || (step == 2 && perms.calendar) || (step == 3 && perms.location)) step++
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = primary, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
            if (secondary != null) TextButton(onClick = secondary.second, modifier = Modifier.padding(top = 4.dp)) { Text(secondary.first) }
            else Spacer(Modifier.height(52.dp))
        }
    }
}

@Composable
private fun Hero(icon: ImageVector, title: String, body: String) {
    IconBadge(icon, size = 64.dp)
    Spacer(Modifier.height(24.dp))
    Text(title, style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(12.dp))
    Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Welcome() {
    Hero(Icons.Filled.Face, "Meet Alfred", "Your discreet butler for messages. Plans, reminders and quick replies, handled quietly while you get on with your day.")
    Spacer(Modifier.height(32.dp))
    Promise(Icons.Filled.Lock, "Private by design", "The AI runs on this phone. Your messages are never uploaded.")
    Promise(Icons.Filled.Settings, "You're in control", "Decide what it can do, and for whom.")
    Promise(Icons.Filled.CheckCircle, "Nothing without your say", "Start careful. Give it more freedom when you trust it.")
}

@Composable
private fun Promise(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp).padding(top = 2.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Hint(body)
        }
    }
}

@Composable
private fun PermissionStep(icon: ImageVector, title: String, body: String, granted: Boolean) {
    Hero(icon, title, body)
    if (granted) {
        Spacer(Modifier.height(20.dp))
        Pill("✓  Allowed", color = LocalExtra.current.success)
    }
}

@Composable
private fun AboutYou() {
    val config by Store.config.collectAsState()
    var picked by remember { mutableStateOf(config.style.split(", ").filter { it in STYLES }.toSet()) }
    Hero(Icons.Filled.Face, "How do you text?", "Alfred writes replies as you. Pick everything that sounds like you.")
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(config.userName, { v -> Store.update { it.copy(userName = v) } }, Modifier.fillMaxWidth(),
        label = { Text("Your first name") }, singleLine = true)
    Spacer(Modifier.height(20.dp))
    MultiChips(STYLES, picked) { s ->
        picked = if (s in picked) picked - s else picked + s
        Store.update { it.copy(style = picked.joinToString(", ").ifBlank { "Short, friendly, casual messages." }) }
    }
}

@Composable
private fun PresetStep() {
    var choice by rememberSaveable { mutableStateOf(Preset.BALANCED) }
    LaunchedEffect(choice) { Store.update { choice.apply(it) } }
    Hero(Icons.Filled.Settings, "How hands-on should it be?", "You can fine-tune this later for each skill and each group of people.")
    Spacer(Modifier.height(24.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Preset.entries.forEach { p -> ChoiceCard(p.title, p.desc, choice == p) { choice = p } }
    }
}
