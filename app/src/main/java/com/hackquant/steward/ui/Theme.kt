package com.hackquant.steward.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colors outside the Material scheme: the "needs you" accent and per-skill tints. */
data class Extra(val attention: Color, val onAttention: Color, val attentionContainer: Color, val success: Color, val subtle: Color)

val LocalExtra = staticCompositionLocalOf { Extra(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified) }

private val Light = lightColorScheme(
    primary = Color(0xFF4338CA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E4FF),
    onPrimaryContainer = Color(0xFF1E1A6B),
    secondary = Color(0xFF5B5A6E),
    secondaryContainer = Color(0xFFECEAF3),
    onSecondaryContainer = Color(0xFF26252F),
    background = Color(0xFFF6F5F1),
    onBackground = Color(0xFF17171C),
    surface = Color(0xFFF6F5F1),
    onSurface = Color(0xFF17171C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEFEEEA),
    surfaceContainerHigh = Color(0xFFE9E8E3),
    surfaceVariant = Color(0xFFE9E8E3),
    onSurfaceVariant = Color(0xFF63636E),
    outline = Color(0xFFC9C8C2),
    outlineVariant = Color(0xFFE2E1DB),
    error = Color(0xFFB42318),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA5A1FF),
    onPrimary = Color(0xFF1B1663),
    primaryContainer = Color(0xFF2E2A7A),
    onPrimaryContainer = Color(0xFFE3E1FF),
    secondary = Color(0xFFC6C5D6),
    secondaryContainer = Color(0xFF2A2A33),
    onSecondaryContainer = Color(0xFFE4E3EE),
    background = Color(0xFF0E0E11),
    onBackground = Color(0xFFEDEDF2),
    surface = Color(0xFF0E0E11),
    onSurface = Color(0xFFEDEDF2),
    surfaceContainerLowest = Color(0xFF131317),
    surfaceContainerLow = Color(0xFF17171C),
    surfaceContainer = Color(0xFF1C1C22),
    surfaceContainerHigh = Color(0xFF24242B),
    surfaceVariant = Color(0xFF24242B),
    onSurfaceVariant = Color(0xFF9D9DAA),
    outline = Color(0xFF45454F),
    outlineVariant = Color(0xFF2C2C34),
    error = Color(0xFFFF8A80),
)

private val LightExtra = Extra(Color(0xFFB45309), Color.White, Color(0xFFFFF1DC), Color(0xFF15803D), Color(0xFFEFEEEA))
private val DarkExtra = Extra(Color(0xFFFBBF5A), Color(0xFF2B1B00), Color(0xFF3A2A12), Color(0xFF6EE7A0), Color(0xFF1C1C22))

private val Type = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    )
}

private val ShapeSet = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun StewardTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalExtra provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, shapes = ShapeSet, content = content)
    }
}
