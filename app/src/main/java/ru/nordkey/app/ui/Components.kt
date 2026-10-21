package ru.nordkey.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.nordkey.app.net.ApiException

/** Загрузка данных с сервера: состояние + перезагрузка. */
class Load<T>(val data: T?, val error: String?, val loading: Boolean, val reload: () -> Unit)

@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Load<T> {
    var data by remember(*keys) { mutableStateOf<T?>(null) }
    var error by remember(*keys) { mutableStateOf<String?>(null) }
    var loading by remember(*keys) { mutableStateOf(true) }
    var tick by remember(*keys) { mutableIntStateOf(0) }
    LaunchedEffect(*keys, tick) {
        loading = true
        try {
            data = block(); error = null
        } catch (e: ApiException) {
            error = e.message
        } catch (e: Exception) {
            error = e.message ?: "Ошибка"
        }
        loading = false
    }
    return Load(data, error, loading) { tick++ }
}

/** Выполнить действие на сервере с подсказкой о результате. */
@Composable
fun rememberAction(): (suspend () -> String?) -> Unit {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return { block ->
        scope.launch {
            try {
                block()?.let { toast(ctx, it) }
            } catch (e: Exception) {
                toast(ctx, e.message ?: "Ошибка")
            }
        }
    }
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

@Composable
fun <T> Loaded(load: Load<T>, content: @Composable (T) -> Unit) {
    val d = load.data
    when {
        d != null -> content(d)
        load.loading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = NK.Link)
        }
        else -> ErrorBox(load.error ?: "Ошибка", load.reload)
    }
}

@Composable
fun ErrorBox(msg: String, onRetry: () -> Unit) {
    NkCard(Modifier.padding(16.dp)) {
        Text(msg, color = NK.Text, fontSize = 15.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) { Text("Повторить") }
    }
}

@Composable
fun NkCard(modifier: Modifier = Modifier, border: Color = NK.Line, bg: Color = NK.Surface, onClick: (() -> Unit)? = null,
           content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun Dot(color: Color, size: Int = 10) = Box(Modifier.size(size.dp).clip(CircleShape).background(color))

@Composable
fun Tile(label: String, value: String, modifier: Modifier = Modifier, warn: Boolean = false, ok: Boolean = false) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (warn) NK.WarnSoft else NK.Surface)
            .border(1.dp, if (warn) NK.WarnLine else NK.Line, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, color = if (warn) Color(0xFFE9C891) else NK.Muted, fontSize = 11.sp, maxLines = 1)
        Text(value, color = when { warn -> NK.WarnText; ok -> NK.OkText; else -> NK.Text },
             fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Chip(text: String, color: Color = NK.OkText, bg: Color = NK.OkSoft) {
    Text(text, color = color, fontSize = 12.sp,
         modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, color = NK.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(top = 6.dp, start = 4.dp))

@Composable
fun Mono(text: String, color: Color = NK.Muted, size: Int = 12) =
    Text(text, color = color, fontSize = size.sp, fontFamily = FontFamily.Monospace)

@Composable
fun ActionTile(label: String, icon: ImageVector, modifier: Modifier = Modifier, primary: Boolean = false,
               risky: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (primary) NK.AccentSoft else NK.Surface)
            .border(1.dp, when { primary -> NK.Accent; risky -> NK.WarnLine; else -> NK.Line }, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = when { !enabled -> NK.Off; primary -> NK.Link; risky -> NK.Warn; else -> NK.Muted },
             modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (enabled) NK.Text else NK.Off, fontSize = 12.sp, lineHeight = 15.sp,
             textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NkTopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, onRefresh: (() -> Unit)? = null,
             actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = NK.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        },
        actions = {
            actions()
            if (onRefresh != null) IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Обновить") }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = NK.Bg, titleContentColor = NK.Text,
                                                   navigationIconContentColor = NK.Text, actionIconContentColor = NK.Text),
    )
}

/** Диалог с одним полем ввода. */
@Composable
fun InputDialog(title: String, hint: String, placeholder: String = "", confirm: String = "OK", initial: String = "",
                multiline: Boolean = false, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(hint, color = NK.Muted, fontSize = 14.sp)
                OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text(placeholder) },
                                  singleLine = !multiline, minLines = if (multiline) 4 else 1, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = NK.Surface,
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text, color = NK.Muted) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        containerColor = NK.Surface,
    )
}

@Composable
fun InfoDialog(title: String, text: String, onDismiss: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(text, color = NK.Text); extra?.invoke() } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        containerColor = NK.Surface,
    )
}

fun ago(sec: Long?): String = when {
    sec == null -> "—"
    sec < 60 -> "$sec с"
    sec < 3600 -> "${sec / 60} мин"
    sec < 86400 -> "${sec / 3600} ч"
    else -> "${sec / 86400} д"
}

fun dateRu(iso: String?): String {
    if (iso.isNullOrBlank() || iso.length < 10) return "—"
    return "${iso.substring(8, 10)}.${iso.substring(5, 7)}.${iso.substring(0, 4)}"
}
