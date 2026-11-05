package ru.nordcam.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.nordcam.app.Nav
import ru.nordcam.app.Screen
import ru.nordcam.app.app
import ru.nordcam.app.data.Camera
import ru.nordcam.app.data.Vendors
import ru.nordcam.app.player.CamPlayer
import ru.nordcam.app.service.RecKind
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val ru = Locale("ru")

fun dayStart(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
fun ArchiveTab(nav: Nav) {
    val ctx = LocalContext.current
    val cams by ctx.app.store.cameras.collectAsState()
    val list = cams.filter { it.perms.archive && !it.perms.expired }
    Column(Modifier.fillMaxSize()) {
        Bar("Архив")
        if (list.isEmpty()) {
            Empty("Нет камер", "Добавьте камеру, чтобы смотреть записи.")
            return@Column
        }
        LazyColumn {
            item {
                Text(
                    "Записи с карты памяти камеры или диска регистратора, а также сделанные этим телефоном.",
                    color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(list, key = { it.id }) { c ->
                val local = remember(c.id) { ctx.app.store.cameraRecDir(c.id).listFiles()?.count { it.isFile } ?: 0 }
                Row(
                    Modifier.fillMaxWidth().clickable { nav.go(Screen.Playback(c.id, tab = if (Vendors.hasPlayback(c)) 0 else 1)) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Videocam, null, tint = C.accent2)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.title)
                        Text(
                            listOfNotNull(
                                c.group.ifBlank { null },
                                if (Vendors.hasPlayback(c)) "архив на камере" else null,
                                if (local > 0) "на телефоне: $local" else null,
                            ).joinToString(" · ").ifBlank { "записей нет" },
                            color = C.muted, fontSize = 13.sp,
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = C.muted)
                }
                HorizontalDivider(color = C.line)
            }
        }
    }
}

@Composable
fun PlaybackScreen(nav: Nav, camId: String, at: Long, tabIn: Int) {
    val ctx = LocalContext.current
    val cams by ctx.app.store.cameras.collectAsState()
    val cam = cams.firstOrNull { it.id == camId }
    if (cam == null) {
        LaunchedEffect(Unit) { nav.back() }; return
    }
    var tab by remember { mutableIntStateOf(if (at > 0) 0 else tabIn) }
    Column(Modifier.fillMaxSize()) {
        Bar("Архив: ${cam.title}", onBack = { nav.back() })
        TabRow(selectedTabIndex = tab, containerColor = C.bg) {
            Tab(tab == 0, { tab = 0 }, text = { Text("На камере") })
            Tab(tab == 1, { tab = 1 }, text = { Text("На телефоне") })
        }
        if (tab == 0) RemoteArchive(nav, cam, at) else LocalArchive(nav, cam)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteArchive(nav: Nav, cam: Camera, at: Long) {
    if (!Vendors.hasPlayback(cam)) {
        Empty(
            "Архив камеры не настроен",
            "Для Hikvision и Dahua он работает сразу. Для других камер впишите шаблон адреса архива в настройках камеры.",
        ) {
            if (!cam.shared) OutlinedButton(onClick = { nav.go(Screen.EditCamera(cam.id)) }) { Text("Открыть настройки") }
        }
        return
    }
    val initial = if (at > 0) at - 10_000 else System.currentTimeMillis() - 3600_000
    var day by remember { mutableLongStateOf(dayStart(initial)) }
    var minute by remember { mutableFloatStateOf(((initial - dayStart(initial)) / 60_000).toFloat()) }
    var start by remember { mutableLongStateOf(if (at > 0) initial else 0L) }
    var muted by remember { mutableStateOf(true) }
    var player by remember { mutableStateOf<CamPlayer?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    val zero = remember { kotlinx.coroutines.flow.MutableStateFlow(0L) }
    val dayFmt = SimpleDateFormat("EEE, d MMMM yyyy", ru)
    val tFmt = SimpleDateFormat("HH:mm:ss", ru)

    fun playAt(t: Long) {
        start = t
        day = dayStart(t)
        minute = ((t - day) / 60_000).toFloat()
    }

    @Composable
    fun Player(videoMod: Modifier) {
        if (start > 0) {
            val url = Vendors.playbackUrl(cam, Date(start), Date(start + 6 * 3600_000L))
            CamVideo(
                cam, videoMod, sub = false, audio = cam.audio, muted = muted,
                url = url, autoReconnect = false, onPlayer = { player = it },
            )
            val t by (player?.time ?: zero).collectAsState()
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("▶ ${tFmt.format(Date(start + t))}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { playAt(start + t - 60_000) }) { Text("−1м") }
                TextButton(onClick = { playAt(start + t - 10_000) }) { Text("−10с") }
                TextButton(onClick = { playAt(start + t + 10_000) }) { Text("+10с") }
                TextButton(onClick = { playAt(start + t + 60_000) }) { Text("+1м") }
                IconButton(onClick = { muted = !muted }) {
                    Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Звук")
                }
            }
        } else {
            Column(
                videoMod.background(C.surface),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) { Text("Выберите день и время", color = C.muted) }
        }
    }

    @Composable
    fun Picker() {
        Section("Когда смотреть") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { day = dayStart(day - 12 * 3600_000L) }) { Icon(Icons.Default.ChevronLeft, "Предыдущий день") }
                Text(
                    dayFmt.format(Date(day)), modifier = Modifier.weight(1f).clickable { pickDate = true }.padding(8.dp),
                    fontWeight = FontWeight.Medium,
                )
                IconButton(onClick = { day = dayStart(day + 36 * 3600_000L) }, enabled = day + 86_400_000L <= System.currentTimeMillis()) {
                    Icon(Icons.Default.ChevronRight, "Следующий день")
                }
            }
            val m = minute.toInt()
            Text("%02d:%02d".format(m / 60, m % 60), fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
            Slider(value = minute, onValueChange = { minute = it }, valueRange = 0f..1439f, modifier = Modifier.padding(horizontal = 12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("00", "06", "12", "18", "24").forEach { Text(it, color = C.muted, fontSize = 11.sp) }
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { playAt(day + minute.toLong() * 60_000) }) { Text("Смотреть") }
                OutlinedButton(onClick = { playAt(System.currentTimeMillis() - 5 * 60_000) }) { Text("5 мин назад") }
            }
        }
        Text(
            "Записи читаются с карты памяти камеры или диска регистратора. Если видео не идёт — проверьте, что запись на камере включена и время на ней верное.",
            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp),
        )
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
                        val local = Calendar.getInstance().apply {
                            clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
                        }
                        day = local.timeInMillis
                    }
                    pickDate = false
                }) { Text("Выбрать") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Отмена") } },
        ) { DatePicker(state = st) }
    }
}

@Composable
private fun LocalArchive(nav: Nav, cam: Camera) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val files = remember(version) {
        ctx.app.store.cameraRecDir(cam.id).listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedByDescending { it.lastModified() }.orEmpty()
    }
    var del by remember { mutableStateOf<File?>(null) }
    if (files.isEmpty()) {
        Empty(
            "Записей на телефоне нет",
            "Нажмите ● при просмотре камеры или включите запись по движению в настройках камеры.",
        )
        return
    }
    val dFmt = SimpleDateFormat("d MMMM, EEEE", ru)
    val tFmt = SimpleDateFormat("HH:mm:ss", ru)
    LazyColumn(Modifier.fillMaxSize()) {
        var lastDay = -1L
        files.forEach { f ->
            val d = dayStart(f.lastModified())
            if (d != lastDay) {
                lastDay = d
                item(key = "d$d") {
                    Text(dFmt.format(Date(d)), color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
                }
            }
            item(key = f.absolutePath) {
                Row(
                    Modifier.fillMaxWidth().clickable { nav.go(Screen.LocalPlay(f.absolutePath, cam.id)) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val kind = RecKind.ofFile(f)
                    Dot(when (kind) { RecKind.MOTION -> C.amber; RecKind.CONTINUOUS -> C.accent2; else -> C.red }, 10)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(tFmt.format(Date(nameTime(f) ?: f.lastModified())))
                        Text("${kind.title} · ${fmtSize(f.length())}", color = C.muted, fontSize = 13.sp)
                    }
                    IconButton(onClick = { shareFile(ctx, f, "video/*") }) { Icon(Icons.Default.Share, "Отправить", tint = C.muted) }
                    IconButton(onClick = { del = f }) { Icon(Icons.Default.Delete, "Удалить", tint = C.muted) }
                }
            }
        }
    }
    del?.let { f ->
        AlertDialog(
            onDismissRequest = { del = null },
            title = { Text("Удалить запись?") },
            confirmButton = { TextButton(onClick = { f.delete(); del = null; version++ }) { Text("Удалить", color = C.red) } },
            dismissButton = { TextButton(onClick = { del = null }) { Text("Отмена") } },
        )
    }
}

/** Время начала записи из имени файла (yyyy-MM-dd_HH-mm-ss_…). */
fun nameTime(f: File): Long? = try {
    SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).parse(f.name.take(19))?.time
} catch (e: Exception) {
    null
}

@Composable
fun LocalPlayerScreen(nav: Nav, path: String, camId: String) {
    val ctx = LocalContext.current
    val f = remember(path) { File(path) }
    val cam = ctx.app.store.camera(camId) ?: Camera(id = "local", name = f.name)
    var player by remember { mutableStateOf<CamPlayer?>(null) }
    var paused by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var rate by remember { mutableFloatStateOf(1f) }
    var fill by remember { mutableStateOf(false) }
    val tFmt = SimpleDateFormat("d MMM HH:mm:ss", ru)
    val begin = nameTime(f) ?: f.lastModified()

    Column(Modifier.fillMaxSize()) {
        Bar(cam.title, onBack = { nav.back() }) {
            IconButton(onClick = { shareFile(ctx, f, "video/*") }) { Icon(Icons.Default.Share, "Отправить") }
            IconButton(onClick = { toast(ctx, if (exportVideo(ctx, f)) "Сохранено в Movies/NordCam" else "Не удалось сохранить") }) {
                Icon(Icons.Default.Download, "В галерею")
            }
        }
        if (!f.exists()) {
            Empty("Файл не найден", "Возможно, запись удалена из-за лимита места."); return@Column
        }
        val screen = rememberWinSize()
        val big = screen.wide || screen.landscape || screen.posture != Posture.NORMAL
        CamVideo(
            cam, if (big) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(16f / 9f), audio = true, muted = muted, fill = fill,
            url = Uri.fromFile(f).toString(), autoReconnect = false, onPlayer = { player = it },
        )
        val p = player
        if (p != null) {
            val t by p.time.collectAsState()
            val len by p.length.collectAsState()
            Text(
                "${tFmt.format(Date(begin + t))}   ${fmtDuration(t)} / ${fmtDuration(len)}",
                color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Slider(
                value = if (len > 0) (t.toFloat() / len).coerceIn(0f, 1f) else 0f,
                onValueChange = { v -> if (len > 0) p.player.setTime((v * len).toLong()) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { p.player.setTime((t - 10_000).coerceAtLeast(0)) }) { Text("−10с") }
                IconButton(onClick = {
                    if (paused) p.player.play() else p.player.pause()
                    paused = !paused
                }) { Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, "Пауза") }
                TextButton(onClick = { p.player.setTime(t + 10_000) }) { Text("+10с") }
                IconButton(onClick = { muted = !muted }) {
                    Icon(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Звук")
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.5f, 1f, 2f, 4f, 8f).forEach { r ->
                    FilterChip(selected = rate == r, onClick = { rate = r; p.player.setRate(r) }, label = { Text(if (r < 1) "½×" else "${r.toInt()}×") })
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.padding(horizontal = 12.dp)) {
                FilterChip(selected = fill, onClick = { fill = !fill }, label = { Text("Заполнить экран") })
            }
        }
    }
}
