package ru.nordkey.app.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Набор цветов одной темы. */
data class Palette(
    val bg: Color, val bar: Color, val surface: Color, val surface2: Color, val line: Color, val divider: Color,
    val text: Color, val muted: Color, val accent: Color, val accentSoft: Color, val accentText: Color, val link: Color,
    val ok: Color, val okSoft: Color, val okText: Color, val warn: Color, val warnSoft: Color, val warnLine: Color, val warnText: Color,
    val warnLabel: Color, val bad: Color, val badSoft: Color, val off: Color, val family: Color, val familySoft: Color,
)

val DarkPalette = Palette(
    bg = Color(0xFF0D1117), bar = Color(0xFF0F141A), surface = Color(0xFF161B22), surface2 = Color(0xFF1C232D),
    line = Color(0xFF2A313C), divider = Color(0xFF222934), text = Color(0xFFE6EDF3), muted = Color(0xFF9BA6B4),
    accent = Color(0xFF2F6FEB), accentSoft = Color(0xFF14233F), accentText = Color(0xFFA9C9FF), link = Color(0xFF7FB0FF),
    ok = Color(0xFF3FD08A), okSoft = Color(0xFF12261C), okText = Color(0xFF7EE2B0),
    warn = Color(0xFFF2A33A), warnSoft = Color(0xFF2A1E0E), warnLine = Color(0xFF5C4317), warnText = Color(0xFFF5B65A), warnLabel = Color(0xFFE9C891),
    bad = Color(0xFFFF7A7A), badSoft = Color(0xFF3A1F22), off = Color(0xFF6B7684),
    family = Color(0xFFC4A7FF), familySoft = Color(0xFF261D3D),
)

val LightPalette = Palette(
    bg = Color(0xFFF4F6FA), bar = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF0F3F8),
    line = Color(0xFFE1E6EE), divider = Color(0xFFECEFF4), text = Color(0xFF111827), muted = Color(0xFF5B6576),
    accent = Color(0xFF2F6FEB), accentSoft = Color(0xFFE7EFFE), accentText = Color(0xFF1E4FC2), link = Color(0xFF2563EB),
    ok = Color(0xFF16A34A), okSoft = Color(0xFFE7F6EC), okText = Color(0xFF15803D),
    warn = Color(0xFFD97706), warnSoft = Color(0xFFFFF5E3), warnLine = Color(0xFFF1D29E), warnText = Color(0xFFB45309), warnLabel = Color(0xFF9A5B12),
    bad = Color(0xFFDC2626), badSoft = Color(0xFFFDECEC), off = Color(0xFF9AA3AF),
    family = Color(0xFF7C3AED), familySoft = Color(0xFFF1EAFE),
)

/** Цвета приложения. Значения берутся из текущей темы — экраны перерисовываются сами при её смене. */
object NK {
    var palette by mutableStateOf(DarkPalette)
    val dark get() = palette === DarkPalette
    val Bg get() = palette.bg
    val Bar get() = palette.bar
    val Surface get() = palette.surface
    val Surface2 get() = palette.surface2
    val Line get() = palette.line
    val Divider get() = palette.divider
    val Text get() = palette.text
    val Muted get() = palette.muted
    val Accent get() = palette.accent
    val AccentSoft get() = palette.accentSoft
    val AccentText get() = palette.accentText
    val Link get() = palette.link
    val Ok get() = palette.ok
    val OkSoft get() = palette.okSoft
    val OkText get() = palette.okText
    val Warn get() = palette.warn
    val WarnSoft get() = palette.warnSoft
    val WarnLine get() = palette.warnLine
    val WarnText get() = palette.warnText
    val WarnLabel get() = palette.warnLabel
    val Bad get() = palette.bad
    val BadSoft get() = palette.badSoft
    val Off get() = palette.off
    val Family get() = palette.family
    val FamilySoft get() = palette.familySoft
}

/** Выбор темы: как в системе / светлая / тёмная. Хранится в обычных настройках приложения. */
object ThemePref {
    const val SYSTEM = "system"; const val LIGHT = "light"; const val DARK = "dark"
    var mode by mutableStateOf(SYSTEM)
    fun load(ctx: Context) {
        mode = try { ctx.getSharedPreferences("nk_ui", Context.MODE_PRIVATE).getString("theme", SYSTEM) ?: SYSTEM } catch (e: Exception) { SYSTEM }
    }
    fun set(ctx: Context, m: String) {
        mode = m
        try { ctx.getSharedPreferences("nk_ui", Context.MODE_PRIVATE).edit().putString("theme", m).apply() } catch (e: Exception) {}
    }
}

@Composable
fun NordKeyTheme(dark: Boolean = when (ThemePref.mode) { ThemePref.LIGHT -> false; ThemePref.DARK -> true; else -> isSystemInDarkTheme() },
                 content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    NK.palette = p
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFF4A86F7), onPrimary = Color.White,
        primaryContainer = p.accentSoft, onPrimaryContainer = p.accentText,
        secondaryContainer = p.accentSoft, onSecondaryContainer = p.accentText,
        surfaceContainerHighest = p.surface2, surfaceContainerLow = p.surface, surfaceContainerLowest = p.bg,
        inverseSurface = p.text, inverseOnSurface = p.bg,
        secondary = p.link, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.muted,
        outline = p.line, outlineVariant = p.divider, error = p.bad, surfaceContainer = p.surface, surfaceContainerHigh = p.surface2,
    ) else lightColorScheme(
        primary = p.accent, onPrimary = Color.White,
        primaryContainer = p.accentSoft, onPrimaryContainer = p.accentText,
        secondaryContainer = p.accentSoft, onSecondaryContainer = p.accentText,
        surfaceContainerHighest = p.surface2, surfaceContainerLow = p.surface, surfaceContainerLowest = p.bg,
        inverseSurface = p.text, inverseOnSurface = p.bg,
        secondary = p.link, background = p.bg, onBackground = p.text,
        surface = p.surface, onSurface = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.muted,
        outline = p.line, outlineVariant = p.divider, error = p.bad, surfaceContainer = p.surface, surfaceContainerHigh = p.surface2,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
