package ru.nordkey.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object NK {
    val Bg = Color(0xFF0D1117)
    val Bar = Color(0xFF0F141A)
    val Surface = Color(0xFF161B22)
    val Line = Color(0xFF2A313C)
    val Divider = Color(0xFF222934)
    val Text = Color(0xFFE6EDF3)
    val Muted = Color(0xFF9BA6B4)
    val Accent = Color(0xFF2F6FEB)
    val AccentSoft = Color(0xFF14233F)
    val AccentText = Color(0xFFA9C9FF)
    val Link = Color(0xFF7FB0FF)
    val Ok = Color(0xFF3FD08A)
    val OkSoft = Color(0xFF12261C)
    val OkText = Color(0xFF7EE2B0)
    val Warn = Color(0xFFF2A33A)
    val WarnSoft = Color(0xFF2A1E0E)
    val WarnLine = Color(0xFF5C4317)
    val WarnText = Color(0xFFF5B65A)
    val Bad = Color(0xFFFF7A7A)
    val Off = Color(0xFF6B7684)
}

@Composable
fun NordKeyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF4A86F7), onPrimary = Color.White,
            primaryContainer = NK.AccentSoft, onPrimaryContainer = NK.AccentText,
            secondaryContainer = NK.AccentSoft, onSecondaryContainer = NK.AccentText,
            surfaceContainerHighest = NK.Surface, surfaceContainerLow = NK.Surface, surfaceContainerLowest = NK.Bg,
            inverseSurface = NK.Text, inverseOnSurface = NK.Bg,
            secondary = NK.Link, background = NK.Bg, onBackground = NK.Text,
            surface = NK.Surface, onSurface = NK.Text, surfaceVariant = NK.Surface, onSurfaceVariant = NK.Muted,
            outline = NK.Line, error = NK.Bad, surfaceContainer = NK.Surface, surfaceContainerHigh = NK.Surface,
        ),
        content = content,
    )
}
