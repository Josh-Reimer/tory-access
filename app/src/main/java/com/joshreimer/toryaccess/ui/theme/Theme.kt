package com.joshreimer.toryaccess.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/** Onion purple accent; green = live/secure, amber = clearnet warning. */
object ToryColors {
    val Onion = Color(0xFFB79CFF)
    val OnionDeep = Color(0xFF7C5CDB)
    val Live = Color(0xFF4ADE80)
    val Warn = Color(0xFFFBBF24)
    val Danger = Color(0xFFF87171)
}

private val Dark = darkColorScheme(
    primary = ToryColors.Onion,
    onPrimary = Color(0xFF1E1240),
    primaryContainer = Color(0xFF34266B),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFF8BD5CA),
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF1F3B38),
    onSecondaryContainer = Color(0xFFB8F0E7),
    tertiary = ToryColors.Warn,
    background = Color(0xFF0E0F14),
    onBackground = Color(0xFFE4E1EC),
    surface = Color(0xFF0E0F14),
    onSurface = Color(0xFFE4E1EC),
    surfaceVariant = Color(0xFF23222D),
    onSurfaceVariant = Color(0xFFC8C4D3),
    surfaceContainerLowest = Color(0xFF09090D),
    surfaceContainerLow = Color(0xFF15151C),
    surfaceContainer = Color(0xFF1A1A22),
    surfaceContainerHigh = Color(0xFF22212B),
    surfaceContainerHighest = Color(0xFF2B2A35),
    outline = Color(0xFF55525F),
    outlineVariant = Color(0xFF34323D),
    error = ToryColors.Danger,
)

private val Light = lightColorScheme(
    primary = ToryColors.OnionDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondary = Color(0xFF00695F),
    secondaryContainer = Color(0xFFB8F0E7),
    background = Color(0xFFFCF8FF),
    surface = Color(0xFFFCF8FF),
    surfaceContainerLow = Color(0xFFF6F1FB),
    surfaceContainer = Color(0xFFF0EBF6),
    surfaceContainerHigh = Color(0xFFEAE5F0),
    tertiary = Color(0xFF8A5A00),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

@Composable
fun ToryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Typography(),
        content = content,
    )
}
