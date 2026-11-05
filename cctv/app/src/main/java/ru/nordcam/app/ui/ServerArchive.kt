package ru.nordcam.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.nordcam.app.Nav
import ru.nordcam.app.data.ArchiveServer
import ru.nordcam.app.data.Camera
import ru.nordcam.app.net.Http
import ru.nordcam.app.net.Nvr
import ru.nordcam.app.net.PlayItem
import ru.nordcam.app.net.RemoteEvent
import ru.nordcam.app.net.Segment
import ru.nordcam.app.player.CamPlayer
import ru.nordcam.app.player.PlayState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

private val ruLoc = Locale("ru")
private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

/** Архив с сервера записи (Synology, Frigate): шкала времени с записями и событиями, как в Hik-Connect. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerArchive(nav: Nav, cam: Camera, server: ArchiveServer, at: Long) {
    val scope = rememberCoroutineScope()
    val nvr = remember(server) { Nvr.of(server) }
    val now = System.currentTimeMillis()
    var day by remember { mutableLongStateOf(dayStart(if (at > 0) at else now)) }
    var segs by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var events by remember { mutableStateOf<List<RemoteEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var item by remember { mutableStateOf<PlayItem?>(null) }
    var selected by remember { mutableLongStateOf(if (at > 0) at - 10_000 else now - HOUR) }
    var span by remember { mutableLongStateOf(DAY) }
    var player by remember { mutableStateOf<CamPlayer?>(null) }
    var muted by remember { mutableStateOf(true) }
    var pickDate by remember { mutableStateOf(false) }
    var autoPlayed by remember { mutableStateOf(false) }
    var fixBase by remember { mutableLongStateOf(0L) }
    val zero = remember { MutableStateFlow(0L) }
    val idle = remember { MutableStateFlow(PlayState.CONNECTING) }
    val tFmt = SimpleDateFormat("HH:mm:ss", ruLoc)
    val dayFmt = SimpleDateFormat("EEE, d MMMM yyyy", ruLoc)

    fun play(t: Long) {
        error = ""
        scope.launch {
            try {
                // Если время на другой день — сначала подгружаем записи того дня
                val list = if (t >= day && t < day + DAY) segs else withContext(Dispatchers.IO) {
                    nvr.segments(cam.serverCam, dayStart(t), dayStart(t) + DAY)
                }
                val pi = withContext(Dispatchers.IO) { nvr.play(cam.serverCam, list, t) }
                fixBase = 0L
                item = pi
                selected = pi.base
            } catch (e: Exception) {
                error = e.message ?: "Не удалось открыть запись"
            }
        }
    }

    LaunchedEffect(day, reload, server, cam.serverCam) {
        loading = true; error = ""
        try {
            val (s, e) = withContext(Dispatchers.IO) {
                val s = nvr.segments(cam.serverCam, day, day + DAY)
                val e = try { nvr.events(cam.serverCam, day, day + DAY) } catch (ex: Exception) { emptyList() }
                s to e
            }
            segs = s; events = e
            if (s.isEmpty()) error = "За этот день записей на сервере нет"
            if (at > 0 && !autoPlayed) { autoPlayed = true; play(at - 10_000) }
        } catch (e: Exception) {
            segs = emptyList(); events = emptyList()
            error = e.message ?: "Сервер не отвечает"
        }
        loading = false
    }

    val p = player
    val t by (p?.time ?: zero).collectAsState()
    val len by (p?.length ?: zero).collectAsState()
    val state by (p?.state ?: idle).collectAsState()
    val cur = item
    // Если сервер отдал отрезок целиком, не учтя сдвиг, — перематываем сами
    LaunchedEffect(cur, len) {
        val seg = cur?.segment ?: return@LaunchedEffect
        if (fixBase == 0L && cur.offset > 5_000 && len > 0 && abs(len - (seg.end - seg.start)) < 8_000) {
            player?.player?.setTime(cur.offset)
            fixBase = seg.start
        }
    }
    val wall = if (cur == null) selected else if (fixBase > 0) fixBase + t else cur.base + t
    // Отрезок закончился — включаем следующий
    LaunchedEffect(state, cur) {
        if (state == PlayState.ENDED && cur?.segment != null) {
            segs.firstOrNull { it.start >= cur.segment.end - 1000 && it.start > cur.segment.start }?.let { play(it.start) }
        }
    }

    @Composable
    fun Player(videoMod: Modifier) {
        if (cur != null) {
            CamVideo(
                cam, videoMod, sub = false, audio = true, muted = muted,
                url = cur.url, autoReconnect = false, onPlayer = { player = it },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("▶ ${tFmt.format(Date(wall))}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { play(wall - 60_000) }) { Text("−1м") }
                TextButton(onClick = { play(wall - 10_000) }) { Text("−10с") }
                TextButton(onClick = { play(wall + 10_000) }) { Text("+10с") }
                TextButton(onClick = { play(wall + 60_000) }) { Text("+1м") }
                IconButton(onClick = { muted = !muted }) {
                    Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Звук")
                }
            }
        } else {
            Column(
                videoMod.background(C.surface),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text("Нажмите на шкалу времени или на событие", color = C.muted)
                Text(server.title, color = C.muted, fontSize = 12.sp)
            }
        }
    }

    @Composable
    fun Picker() {
        Section("${server.title} · ${cam.serverCamName.ifBlank { cam.serverCam }}") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { day = dayStart(day - 12 * HOUR) }) { Icon(Icons.Default.ChevronLeft, "Предыдущий день") }
                Text(dayFmt.format(Date(day)), Modifier.weight(1f).clickable { pickDate = true }.padding(8.dp), fontWeight = FontWeight.Medium)
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Обновить", tint = C.muted) }
                IconButton(onClick = { day = dayStart(day + 36 * HOUR) }, enabled = day + DAY <= System.currentTimeMillis()) {
                    Icon(Icons.Default.ChevronRight, "Следующий день")
                }
            }
            Timeline(day, span, segs, events, if (cur != null) wall else selected) { picked -> selected = picked; play(picked) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(DAY to "24 ч", 6 * HOUR to "6 ч", HOUR to "1 ч", 10 * 60_000L to "10 мин").forEach { (sp, label) ->
                    FilterChip(span == sp, { span = sp }, { Text(label) })
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend(C.accent, "запись"); Legend(C.amber, "движение"); Legend(C.red, "объект")
            }
            if (error.isNotBlank()) Text(error, color = C.amber, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            Spacer(Modifier.height(8.dp))
        }
        if (events.isNotEmpty()) {
            Section("События за день: ${events.size}") {
                events.take(150).forEach { e -> EventRow(nvr, e) { play(e.start - 3_000) } }
            }
        } else if (segs.isNotEmpty()) {
            Section("Записи за день: ${segs.size}") {
                segs.reversed().take(150).forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { play(s.start) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Dot(kindColor(s.kind), 10); Spacer(Modifier.width(12.dp))
                        Text("${SimpleDateFormat("HH:mm", ruLoc).format(Date(s.start))} – ${SimpleDateFormat("HH:mm", ruLoc).format(Date(s.end))}", Modifier.weight(1f))
                        Text(fmtDuration(s.end - s.start), color = C.muted, fontSize = 13.sp)
                    }
                    HorizontalDivider(color = C.line)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }

    val screen = rememberWinSize()
    when {
        screen.posture == Posture.TABLETOP -> Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f)) { Player(Modifier.weight(1f).fillMaxWidth()) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Picker() }
        }
        screen.wide || screen.posture == Posture.BOOK || (screen.landscape && screen.short) -> Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(0.62f).fillMaxHeight()) { Player(Modifier.weight(1f).fillMaxWidth()) }
            Column(Modifier.weight(0.38f).fillMaxHeight().verticalScroll(rememberScrollState())) { Picker() }
        }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Player(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Picker()
        }
    }

    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = day + TimeZone.getDefault().getOffset(day))
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { utc ->
                        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utc }
                        day = Calendar.getInstance().apply {
                            clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
                        }.timeInMillis
                    }
                    pickDate = false
                }) { Text("Выбрать") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Отмена") } },
        ) { DatePicker(state = st) }
    }
}

private fun kindColor(k: String) = when (k) {
    "object" -> C.red
    "motion" -> C.amber
    else -> C.accent
}

@Composable
private fun Legend(c: androidx.compose.ui.graphics.Color, t: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(c, 8); Spacer(Modifier.width(4.dp)); Text(t, color = C.muted, fontSize = 11.sp)
    }
}

/**
 * Шкала времени: цветные полосы — где есть запись, засечки сверху — события.
 * Нажатие или перетаскивание выбирает момент. Масштаб — 24 ч … 10 мин вокруг выбранного времени.
 */
@Composable
private fun Timeline(day: Long, span: Long, segs: List<Segment>, events: List<RemoteEvent>, cursor: Long, onPick: (Long) -> Unit) {
    val winStart = if (span >= DAY) day else (cursor - span / 2).coerceIn(day, day + DAY - span)
    var drag by remember { mutableStateOf<Long?>(null) }
    val shown = drag ?: cursor
    val bar = C.surface2
    val segColor = C.accent
    val mot = C.amber
    val obj = C.red
    val mark = C.text
    val grid = C.line
    val labelFmt = SimpleDateFormat(if (span <= HOUR) "HH:mm" else "HH", ruLoc)
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(SimpleDateFormat("HH:mm:ss", ruLoc).format(Date(shown)), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Canvas(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp))
                .pointerInput(winStart, span) {
                    detectTapGestures { o -> onPick(winStart + (o.x / size.width * span).toLong()) }
                }
                .pointerInput(winStart, span) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = winStart + (o.x / size.width * span).toLong() },
                        onDragEnd = { drag?.let(onPick); drag = null },
                        onDragCancel = { drag = null },
                    ) { change, _ ->
                        drag = (winStart + (change.position.x / size.width * span).toLong()).coerceIn(day, day + DAY - 1)
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            fun x(t: Long) = ((t - winStart).toFloat() / span * w)
            drawRect(bar, Offset.Zero, Size(w, h))
            // Сетка часов
            val step = when { span >= DAY -> 3 * HOUR; span >= 6 * HOUR -> HOUR; span >= HOUR -> 10 * 60_000L; else -> 60_000L }
            var g = winStart - (winStart - day) % step
            while (g <= winStart + span) {
                if (g >= winStart) drawLine(grid, Offset(x(g), 0f), Offset(x(g), h), 1f)
                g += step
            }
            segs.forEach { s ->
                if (s.end < winStart || s.start > winStart + span) return@forEach
                val x1 = x(s.start).coerceAtLeast(0f)
                val x2 = x(s.end).coerceAtMost(w)
                val c = when (s.kind) { "object" -> obj; "motion" -> mot; else -> segColor }
                drawRect(c, Offset(x1, h * 0.35f), Size((x2 - x1).coerceAtLeast(1.5f), h * 0.45f))
            }
            events.forEach { e ->
                if (e.start < winStart || e.start > winStart + span) return@forEach
                drawRect(obj, Offset(x(e.start), 0f), Size(2.5f, h * 0.28f))
            }
            val cx = x(shown)
            if (cx in 0f..w) drawRect(mark, Offset(cx - 1.5f, 0f), Size(3f, h))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (i in 0..4) Text(labelFmt.format(Date(winStart + span * i / 4)), color = C.muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun EventRow(nvr: Nvr, e: RemoteEvent, onClick: () -> Unit) {
    val img by produceState<ImageBitmap?>(null, e.id) {
        value = withContext(Dispatchers.IO) { nvr.thumb(e)?.let { Http.decode(it, 240)?.asImageBitmap() } }
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(88.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(C.surface2)) {
            img?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.label, fontWeight = FontWeight.Medium)
            Text(
                SimpleDateFormat("HH:mm:ss", ruLoc).format(Date(e.start)) + " · " + fmtDuration((e.end - e.start).coerceAtLeast(0)),
                color = C.muted, fontSize = 12.sp,
            )
        }
    }
    HorizontalDivider(color = C.line)
}
