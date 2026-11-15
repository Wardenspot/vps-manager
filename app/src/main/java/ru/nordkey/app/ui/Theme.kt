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
    bg = Color(0xFF070B14), bar = Color(0xFF0C1424), surface = Color(0xFF111A2E), surface2 = Color(0xFF17223C),
    line = Color(0xFF22304D), divider = Color(0xFF1B2742), text = Color(0xFFEAF1FF), muted = Color(0xFF8E9BB5),
    accent = Color(0xFF3B7BFF), accentSoft = Color(0xFF14264A), accentText = Color(0xFFA9D8FF), link = Color(0xFF5CC8FF),
    ok = Color(0xFF3FE0A0), okSoft = Color(0xFF0E2A25), okText = Color(0xFF7EEBC0),
    warn = Color(0xFFFFB547), warnSoft = Color(0xFF2A1F10), warnLine = Color(0xFF5C4317), warnText = Color(0xFFFFC266), warnLabel = Color(0xFFE9C891),
    bad = Color(0xFFFF6B7A), badSoft = Color(0xFF3A1A24), off = Color(0xFF5E6A82),
    family = Color(0xFFC4A7FF), familySoft = Color(0xFF231C42),
)

val LightPalette = Palette(
    bg = Color(0xFFF3F6FC), bar = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), surface2 = Color(0xFFF2F5FB),
    line = Color(0xFFE1E7F2), divider = Color(0xFFEDF0F6), text = Color(0xFF0F172A), muted = Color(0xFF5B6576),
    accent = Color(0xFF2F6FEB), accentSoft = Color(0xFFE6EEFF), accentText = Color(0xFF1E4FC2), link = Color(0xFF1F6FEB),
    ok = Color(0xFF12A96A), okSoft = Color(0xFFE5F7EF), okText = Color(0xFF0E8A57),
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
    /** Неоновый акцент нового дизайна (начало градиента кнопок и подсветки). */
    val Cyan get() = if (dark) Color(0xFF35E0FF) else Color(0xFF0EA5E9)
    val Glow get() = if (dark) Color(0xFF3B7BFF) else Color(0xFF2F6FEB)
    /** Градиент главных кнопок и выбранных элементов. */
    val Grad get() = listOf(Cyan, Glow)
    /** Текст на градиентной кнопке. */
    val OnGrad get() = if (dark) Color(0xFF041022) else Color.White
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
        primary = Color(0xFF3B8BFF), onPrimary = Color.White,
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
