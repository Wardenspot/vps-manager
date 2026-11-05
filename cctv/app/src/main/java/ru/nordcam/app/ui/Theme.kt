package ru.nordcam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Набор цветов темы. */
data class Palette(
    val id: String,
    val title: String,
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
)

object Themes {
    val DARK = Palette("dark", "Тёмная", true, Color(0xFF0E1116), Color(0xFF161B22), Color(0xFF1F2630), Color(0xFF2A323D), Color(0xFFE6EDF3), Color(0xFF8B949E))
    val AMOLED = Palette("amoled", "Чёрная", true, Color(0xFF000000), Color(0xFF0D0D0D), Color(0xFF1A1A1A), Color(0xFF262626), Color(0xFFF2F2F2), Color(0xFF8C8C8C))
    val OCEAN = Palette("ocean", "Ночное море", true, Color(0xFF0A1628), Color(0xFF112240), Color(0xFF1A2F55), Color(0xFF233B66), Color(0xFFE6F1FF), Color(0xFF8892B0))
    val FOREST = Palette("forest", "Лес", true, Color(0xFF0D1512), Color(0xFF15211C), Color(0xFF1E2E27), Color(0xFF2A3D34), Color(0xFFE5F2EC), Color(0xFF8BA399))
    val GRAPHITE = Palette("graphite", "Графит", true, Color(0xFF1C1C1E), Color(0xFF2C2C2E), Color(0xFF3A3A3C), Color(0xFF48484A), Color(0xFFF2F2F7), Color(0xFF98989F))
    val LIGHT = Palette("light", "Светлая", false, Color(0xFFF4F6F9), Color(0xFFFFFFFF), Color(0xFFE9EDF2), Color(0xFFDCE1E8), Color(0xFF1B1F24), Color(0xFF66707A))
    val all = listOf(DARK, AMOLED, OCEAN, FOREST, GRAPHITE, LIGHT)

    /** Акцентные цвета: основной и светлый вариант. */
    val accents = listOf(
        Triple("blue", Color(0xFF2F80ED), Color(0xFF56CCF2)),
        Triple("cyan", Color(0xFF0097A7), Color(0xFF4DD0E1)),
        Triple("green", Color(0xFF219653), Color(0xFF6FCF97)),
        Triple("orange", Color(0xFFE67E22), Color(0xFFF2C94C)),
        Triple("purple", Color(0xFF7E57C2), Color(0xFFB39DDB)),
        Triple("red", Color(0xFFD64545), Color(0xFFFF8A80)),
        Triple("pink", Color(0xFFD63384), Color(0xFFF48FB1)),
    )

    fun palette(id: String, systemDark: Boolean): Palette =
        if (id == "system") (if (systemDark) DARK else LIGHT) else all.firstOrNull { it.id == id } ?: DARK
}

/** Цвета интерфейса. Читаются из текущей темы — при её смене экран перерисовывается сам. */
object C {
    var palette by mutableStateOf(Themes.DARK)
    var accentPair by mutableStateOf(Themes.accents[0].second to Themes.accents[0].third)

    val bg: Color get() = palette.bg
    val surface: Color get() = palette.surface
    val surface2: Color get() = palette.surface2
    val line: Color get() = palette.line
    val text: Color get() = palette.text
    val muted: Color get() = palette.muted
    val accent: Color get() = accentPair.first
    val accent2: Color get() = if (palette.dark) accentPair.second else accentPair.first
    val red = Color(0xFFEB5757)
    val green = Color(0xFF27AE60)
    val amber: Color get() = if (palette.dark) Color(0xFFF2C94C) else Color(0xFFB7791F)
}

@Composable
fun NordCamTheme(themeId: String = "dark", accentId: String = "blue", content: @Composable () -> Unit) {
    val p = Themes.palette(themeId, isSystemInDarkTheme())
    val a = Themes.accents.firstOrNull { it.first == accentId } ?: Themes.accents[0]
    C.palette = p
    C.accentPair = a.second to a.third
    val scheme = if (p.dark) {
        darkColorScheme(
            primary = C.accent, onPrimary = Color.White, secondary = C.accent2,
            background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surface2, onSurfaceVariant = p.muted,
            surfaceContainer = p.surface, surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2,
            surfaceContainerLow = p.surface, surfaceContainerLowest = p.bg,
            outline = p.line, outlineVariant = p.line, error = C.red,
        )
    } else {
        lightColorScheme(
            primary = C.accent, onPrimary = Color.White, secondary = C.accent2,
            background = p.bg, onBackground = p.text, surface = p.surface, onSurface = p.text,
            surfaceVariant = p.surface2, onSurfaceVariant = p.muted,
            surfaceContainer = p.surface, surfaceContainerHigh = p.surface2, surfaceContainerHighest = p.surface2,
            surfaceContainerLow = p.surface, surfaceContainerLowest = p.surface,
            outline = p.line, outlineVariant = p.line, error = C.red,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, maxLines = 1) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = C.bg),
    )
}

@Composable
fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (title != null) Text(title.uppercase(), color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp, top = 8.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = C.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
    }
}

@Composable
fun SwitchRow(title: String, sub: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) C.text else C.muted)
            if (sub != null) Text(sub, color = C.muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    password: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    hint: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (hint != null) ({ Text(hint, color = C.muted) }) else null,
        singleLine = singleLine,
        enabled = enabled,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                number -> KeyboardType.Number
                password -> KeyboardType.Password
                else -> KeyboardType.Text
            },
        ),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).background(color, RoundedCornerShape(50)))
}

@Composable
fun Empty(title: String, sub: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, color = C.muted, fontSize = 14.sp)
        if (action != null) {
            Spacer(Modifier.size(8.dp)); action()
        }
    }
}
