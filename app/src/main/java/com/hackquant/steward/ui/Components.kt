package com.hackquant.steward.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.hackquant.steward.Autonomy
import com.hackquant.steward.Skill
import kotlin.math.absoluteValue

val Skill.icon: ImageVector
    get() = when (this) {
        Skill.REPLY -> Icons.AutoMirrored.Filled.Send
        Skill.CALENDAR -> Icons.Filled.DateRange
        Skill.REMINDER -> Icons.Filled.Notifications
        Skill.REMEMBER -> Icons.Filled.Star
        Skill.MUTE -> Icons.Filled.Close
        Skill.BRIEF -> Icons.Filled.Info
    }

/** How each autonomy level reads in the UI. */
fun Autonomy.human(offLabel: String = "Never") = when (this) {
    Autonomy.OFF -> offLabel
    Autonomy.ASK -> "Ask first"
    Autonomy.AUTO -> "Automatic"
}

private val avatarColors = listOf(
    Color(0xFF6366F1), Color(0xFF0EA5E9), Color(0xFF10B981), Color(0xFFF59E0B),
    Color(0xFFEF4444), Color(0xFFEC4899), Color(0xFF8B5CF6), Color(0xFF14B8A6),
)

@Composable
fun Avatar(name: String, size: Dp = 40.dp) {
    val color = avatarColors[name.hashCode().absoluteValue % avatarColors.size]
    val initials = name.split(' ', '-', '.').filter { it.isNotBlank() && it[0].isLetterOrDigit() }.take(2)
        .joinToString("") { it.take(1).uppercase() }.ifBlank { "?" }
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(initials, color = color, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AppIcon(pkg: String, size: Dp = 16.dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(64, 64).asImageBitmap() }.getOrNull()
    }
    if (bmp != null) Image(bmp, null, Modifier.size(size).clip(CircleShape))
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 36.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size / 2))
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, bg: Color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier) = Text(
    text.uppercase(), modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp),
    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A rounded group of rows, like iOS/Android settings lists. */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(content = content)
    }
}

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

@Composable
fun Divider() = Box(
    Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant)
)

/** Tap-to-pick options instead of free-text boxes. */
@Composable
fun ChoiceChips(options: List<String>, selected: String?, onSelect: (String) -> Unit) =
    MultiChips(options, setOfNotNull(selected), onSelect)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiChips(options: List<String>, selected: Set<String>, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = selected.any { it.equals(o, ignoreCase = true) }
            val shape = RoundedCornerShape(50)
            Row(
                Modifier.clip(shape)
                    .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .border(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (on) { Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(6.dp)) }
                Text(o, style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** A large selectable option with a title and explanation. */
@Composable
fun ChoiceCard(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
        Text(desc, style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AutonomyChoice(value: Autonomy, onChange: (Autonomy) -> Unit, offLabel: String = "Never") {
    ChoiceChips(Autonomy.entries.map { it.human(offLabel) }, value.human(offLabel)) { picked ->
        Autonomy.entries.firstOrNull { it.human(offLabel) == picked }?.let(onChange)
    }
}

@Composable
fun PageHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            action?.invoke()
        }
        if (subtitle != null) Hint(subtitle, Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon, size = 56.dp)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun SheetTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) Hint(subtitle)
    }
}
