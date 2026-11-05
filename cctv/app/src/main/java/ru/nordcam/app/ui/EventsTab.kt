package ru.nordcam.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.nordcam.app.Nav
import ru.nordcam.app.Screen
import ru.nordcam.app.app
import ru.nordcam.app.data.Event
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun thumb(path: String, maxSide: Int = 320): ImageBitmap? {
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            if (path.isBlank() || !File(path).exists()) null else {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, o)
                var s = 1
                while (o.outWidth / (s * 2) >= maxSide) s *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }
        }
    }
    return bmp
}

@Composable
fun EventsTab(nav: Nav) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val events by store.events.collectAsState()
    val cams by store.cameras.collectAsState()
    var filter by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Event?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val settings by store.settings.collectAsState()
    val list = events.filter { filter.isBlank() || it.cameraId == filter }
    val dFmt = SimpleDateFormat("d MMMM", Locale("ru"))
    val tFmt = SimpleDateFormat("HH:mm:ss", Locale("ru"))

    Column(Modifier.fillMaxSize()) {
        Bar("События") {
            IconButton(onClick = { store.markAllSeen() }) { Icon(Icons.Default.DoneAll, "Все просмотрены") }
            IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteSweep, "Очистить") }
        }
        if (!settings.monitoring) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).background(C.surface, RoundedCornerShape(12.dp))
                    .clickable { nav.home("more") }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.NotificationsOff, null, tint = C.amber)
                Spacer(Modifier.width(10.dp))
                Text("Наблюдение выключено — новые события не приходят. Включить в «Ещё».", fontSize = 13.sp)
            }
        }
        val withEvents = cams.filter { c -> events.any { it.cameraId == c.id } }
        if (withEvents.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
            ) {
                item { FilterChip(filter.isBlank(), { filter = "" }, { Text("Все") }) }
                items(withEvents) { c -> FilterChip(filter == c.id, { filter = c.id }, { Text(c.title) }) }
            }
        }
        if (list.isEmpty()) {
            Empty(
                "Событий нет",
                "Включите детекцию движения в настройках камеры и наблюдение в разделе «Ещё» — сюда будут приходить тревоги со снимками.",
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            var lastDay = ""
            list.forEach { e ->
                val d = dFmt.format(Date(e.time))
                if (d != lastDay) {
                    lastDay = d
                    item(key = "d$d") { Text(d, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
                }
                item(key = e.id) {
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            open = e
                            if (!e.seen) store.updateEvent(e.id) { it.copy(seen = true) }
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val img = thumb(e.snapshot, 240)
                        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(C.surface2)) {
                            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!e.seen) { Dot(C.accent2, 8); Spacer(Modifier.width(6.dp)) }
                                Text(e.kind, fontWeight = if (e.seen) FontWeight.Normal else FontWeight.SemiBold)
                            }
                            Text(e.cameraName, color = C.muted, fontSize = 13.sp)
                            Text(tFmt.format(Date(e.time)) + if (e.clip.isNotBlank()) " · есть запись" else "", color = C.muted, fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = C.line)
                }
            }
        }
    }

    open?.let { e ->
        val img = thumb(e.snapshot, 1280)
        val cam = cams.firstOrNull { it.id == e.cameraId }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text("${e.kind} · ${e.cameraName}") },
            text = {
                Column {
                    Text(SimpleDateFormat("d MMMM yyyy, HH:mm:ss", Locale("ru")).format(Date(e.time)), color = C.muted)
                    Spacer(Modifier.height(10.dp))
                    if (img != null) Image(img, null, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth)
                    else Text("Снимок не получен", color = C.muted)
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (e.clip.isNotBlank() && File(e.clip).exists()) {
                            OutlinedButton(onClick = { open = null; nav.go(Screen.LocalPlay(e.clip, e.cameraId)) }, modifier = Modifier.fillMaxWidth()) { Text("Смотреть запись с телефона") }
                        }
                        if (cam != null && cam.perms.archive) {
                            OutlinedButton(onClick = { open = null; nav.go(Screen.Playback(cam.id, at = e.time)) }, modifier = Modifier.fillMaxWidth()) { Text("Архив камеры с этого момента") }
                        }
                        if (cam != null && cam.perms.live) {
                            OutlinedButton(onClick = { open = null; nav.go(Screen.Live(cam.id)) }, modifier = Modifier.fillMaxWidth()) { Text("Смотреть сейчас") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Закрыть") } },
            dismissButton = if (e.snapshot.isNotBlank()) ({
                TextButton(onClick = { shareFile(ctx, File(e.snapshot), "image/jpeg") }) { Text("Отправить снимок") }
            }) else null,
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить журнал событий?") },
            text = { Text("Снимки событий будут удалены. Видеозаписи останутся в архиве.") },
            confirmButton = { TextButton(onClick = { store.clearEvents(); confirmClear = false }) { Text("Очистить", color = C.red) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

