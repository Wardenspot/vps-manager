package ru.nordcam.app.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.nordcam.app.Nav
import ru.nordcam.app.Screen
import ru.nordcam.app.app
import ru.nordcam.app.data.CamOps
import ru.nordcam.app.data.Camera
import ru.nordcam.app.data.Vendors
import ru.nordcam.app.net.OnvifPreset
import ru.nordcam.app.player.CamPlayer
import ru.nordcam.app.service.RecKind

@Composable
fun FullscreenScreen(nav: Nav, camIdIn: String) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val recorder = ctx.app.recorder
    val cams by store.cameras.collectAsState()
    var camId by remember { mutableStateOf(camIdIn) }
    val cam = cams.firstOrNull { it.id == camId }
    if (cam == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activity = ctx as? Activity
    DisposableEffect(Unit) { onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }

    var hd by remember(camId) { mutableStateOf(true) }
    var muted by remember(camId) { mutableStateOf(true) }
    var fill by remember { mutableStateOf(false) }
    var showPtz by remember(camId) { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var scale by remember(camId) { mutableFloatStateOf(1f) }
    var offset by remember(camId) { mutableStateOf(Offset.Zero) }
    var player by remember { mutableStateOf<CamPlayer?>(null) }
    val rec by recorder.recording.collectAsState()
    val recKind = rec[cam.id]
    var recStart by remember(camId) { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recKind) {
        recStart = if (recKind != null) System.currentTimeMillis() else 0L
        while (recKind != null) { now = System.currentTimeMillis(); delay(1000) }
    }
    val idx = cams.indexOfFirst { it.id == camId }

    fun snapshot() {
        val bmp = player?.snapshot()
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                if (bmp != null) saveSnapshot(ctx, cam.title, bmp)
                else CamOps.snapshot(store, cam)?.let { saveSnapshot(ctx, cam.title, null, it) }
            }
            toast(ctx, if (f != null) "Снимок сохранён в галерею (Pictures/NordCam)" else "Не удалось сделать снимок")
        }
    }

    fun toggleRecord() {
        if (!cam.perms.record) { toast(ctx, "Запись для этой камеры запрещена владельцем"); return }
        scope.launch(Dispatchers.IO) {
            if (recorder.isRecording(cam.id)) {
                val f = recorder.stop(cam.id)
                withContext(Dispatchers.Main) { toast(ctx, if (f != null) "Запись сохранена — раздел «Архив»" else "Запись не удалась: камера не отдала видео") }
            } else {
                val f = recorder.start(cam, RecKind.MANUAL)
                withContext(Dispatchers.Main) { if (f == null) toast(ctx, "Не удалось начать запись") }
            }
        }
    }

    val zoomMod = Modifier.graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)

    @Composable
    fun Video(mod: Modifier) {
        Box(mod.background(Color.Black)) {
            CamVideo(
                cam, Modifier.fillMaxSize(), sub = !hd, audio = cam.audio, muted = muted, fill = fill,
                onPlayer = { player = it }, videoModifier = zoomMod,
            )
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(camId) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            val maxX = size.width * (scale - 1) / 2
                            val maxY = size.height * (scale - 1) / 2
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                        }
                    }
                    .pointerInput(camId) {
                        detectTapGestures(
                            onTap = { controls = !controls },
                            onDoubleTap = {
                                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                            },
                        )
                    },
            )
            if (recKind != null) {
                Row(
                    Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(C.red, 8); Spacer(Modifier.width(6.dp))
                    Text("${recKind.title} ${fmtDuration(now - recStart)}", color = Color.White, fontSize = 12.sp)
                }
            }
            if (scale > 1.01f) {
                Text(
                    "×%.1f".format(scale), color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }

    @Composable
    fun ControlsRow(dark: Boolean) {
        val tint = if (dark) Color.White else C.text
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { hd = !hd }) {
                Text(if (hd) "HD" else "SD", color = if (hd) C.accent2 else tint, fontSize = 15.sp)
            }
            if (cam.audio) Ctl(if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Звук", tint) { muted = !muted }
            Ctl(Icons.Default.CameraAlt, "Снимок", tint) { snapshot() }
            Ctl(if (recKind != null) Icons.Default.Stop else Icons.Default.FiberManualRecord, "Запись", if (recKind != null) C.red else tint) { toggleRecord() }
            Ctl(Icons.Default.AspectRatio, "Вписать/заполнить", if (fill) C.accent2 else tint) { fill = !fill }
            if (landscape && cam.ptz && cam.perms.ptz) Ctl(Icons.Default.OpenWith, "Поворот", if (showPtz) C.accent2 else tint) { showPtz = !showPtz }
            Ctl(if (landscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Во весь экран", tint) {
                activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
    }

    if (landscape) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Video(Modifier.fillMaxSize())
            if (controls) {
                Row(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x77000000)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }) { Icon(Icons.Default.FullscreenExit, "Выход", tint = Color.White) }
                    Text(cam.title, color = Color.White, modifier = Modifier.weight(1f))
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x77000000))) { ControlsRow(true) }
                if (showPtz && cam.ptz && cam.perms.ptz) {
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { PtzPad(cam, compact = true) }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Bar(cam.title, onBack = { nav.back() }) {
            if (cams.size > 1) {
                IconButton(onClick = { camId = cams[(idx - 1 + cams.size) % cams.size].id }) { Icon(Icons.Default.ChevronLeft, "Предыдущая") }
                IconButton(onClick = { camId = cams[(idx + 1) % cams.size].id }) { Icon(Icons.Default.ChevronRight, "Следующая") }
            }
            if (cam.perms.archive) IconButton(onClick = { nav.go(Screen.Playback(cam.id)) }) { Icon(Icons.Default.History, "Архив") }
            if (!cam.shared) IconButton(onClick = { nav.go(Screen.EditCamera(cam.id)) }) { Icon(Icons.Default.Edit, "Настройки") }
        }
        Video(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        ControlsRow(false)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (cam.ptz && cam.perms.ptz) {
                Section("Поворот и зум (PTZ)") { PtzPad(cam, compact = false) }
            } else if (!cam.shared) {
                Text(
                    "Поворот камеры (PTZ) выключен. Если камера поворотная — включите его в настройках камеры.",
                    color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                )
            }
            Section("Камера") {
                Text(
                    buildString {
                        append(Vendors.of(cam.vendor).title)
                        if (cam.group.isNotBlank()) append(" · ${cam.group}")
                        if (!cam.shared) append("\n${cam.host}, канал ${cam.channel}")
                        if (cam.shared && cam.perms.until > 0) {
                            append("\nДоступ до ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("ru")).format(java.util.Date(cam.perms.until))}")
                        }
                    },
                    color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                )
                Text(
                    "Щипок — цифровой зум, двойное касание — приблизить/сбросить. HD — основной поток, SD — лёгкий для мобильного интернета.",
                    color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
    }
    LaunchedEffect(landscape) { if (!landscape) controls = true }
}

@Composable
private fun Ctl(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, desc, tint = tint) }
}

/** Кнопки поворота: держите — камера поворачивается, отпустите — останавливается. */
@Composable
fun PtzPad(cam: Camera, compact: Boolean) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val scope = rememberCoroutineScope()
    var speed by remember { mutableFloatStateOf(0.5f) }
    var profile by remember(cam.id) { mutableStateOf("") }
    var error by remember(cam.id) { mutableStateOf("") }
    var presets by remember(cam.id) { mutableStateOf<List<OnvifPreset>>(emptyList()) }
    var presetMenu by remember { mutableStateOf(false) }
    var savePreset by remember { mutableStateOf(false) }
    val onvif = remember(cam.id) { CamOps.onvif(cam) }

    LaunchedEffect(cam.id) {
        withContext(Dispatchers.IO) {
            try {
                profile = CamOps.ptzProfile(store, cam)
                if (profile.isBlank()) error = "Камера не сообщила профиль ONVIF"
                else presets = try { onvif.presets(profile) } catch (e: Exception) { emptyList() }
            } catch (e: Exception) {
                error = "PTZ недоступен: ${e.message}"
            }
        }
    }

    fun hold(x: Float, y: Float, z: Float): Modifier = Modifier.pointerInput(profile, speed) {
        detectTapGestures(onPress = {
            if (profile.isBlank()) return@detectTapGestures
            val o = onvif
            scope.launch(Dispatchers.IO) {
                try { o.ptzMove(profile, x * speed, y * speed, z * speed) } catch (e: Exception) { error = e.message.orEmpty() }
            }
            tryAwaitRelease()
            scope.launch(Dispatchers.IO) { try { o.ptzStop(profile) } catch (_: Exception) {} }
        })
    }

    @Composable
    fun Btn(icon: ImageVector, desc: String, m: Modifier) {
        val sz = if (compact) 44.dp else 56.dp
        Box(m.size(sz).background(if (compact) Color(0x88000000) else C.surface2, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, desc, tint = Color.White)
        }
    }

    Column(Modifier.padding(if (compact) 0.dp else 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Btn(Icons.Default.KeyboardArrowUp, "Вверх", hold(0f, 1f, 0f))
                Row {
                    Btn(Icons.Default.KeyboardArrowLeft, "Влево", hold(-1f, 0f, 0f))
                    Spacer(Modifier.size(if (compact) 44.dp else 56.dp))
                    Btn(Icons.Default.KeyboardArrowRight, "Вправо", hold(1f, 0f, 0f))
                }
                Btn(Icons.Default.KeyboardArrowDown, "Вниз", hold(0f, -1f, 0f))
            }
            Spacer(Modifier.width(if (compact) 12.dp else 28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(Icons.Default.ZoomIn, "Приблизить", hold(0f, 0f, 1f))
                Btn(Icons.Default.ZoomOut, "Отдалить", hold(0f, 0f, -1f))
            }
        }
        if (!compact) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Скорость", color = C.muted, fontSize = 13.sp)
                Slider(value = speed, onValueChange = { speed = it }, valueRange = 0.1f..1f, modifier = Modifier.weight(1f).padding(start = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { presetMenu = true }, enabled = presets.isNotEmpty()) {
                        Text(if (presets.isEmpty()) "Пресетов нет" else "Пресеты (${presets.size})")
                    }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        presets.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name.ifBlank { "Пресет ${p.token}" }) }, onClick = {
                                presetMenu = false
                                scope.launch(Dispatchers.IO) { try { onvif.gotoPreset(profile, p.token) } catch (e: Exception) { error = e.message.orEmpty() } }
                            })
                        }
                    }
                }
                OutlinedButton(onClick = { savePreset = true }, enabled = profile.isNotBlank() && !cam.shared) { Text("Запомнить точку") }
            }
        }
        if (error.isNotBlank()) Text(error, color = C.amber, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }

    if (savePreset) {
        var name by remember { mutableStateOf("Точка ${presets.size + 1}") }
        AlertDialog(
            onDismissRequest = { savePreset = false },
            title = { Text("Запомнить положение") },
            text = { Field(name, { name = it }, "Название") },
            confirmButton = {
                TextButton(onClick = {
                    savePreset = false
                    scope.launch(Dispatchers.IO) {
                        try {
                            val o = onvif
                            o.setPreset(profile, name)
                            presets = o.presets(profile)
                        } catch (e: Exception) { error = e.message.orEmpty() }
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { savePreset = false }) { Text("Отмена") } },
        )
    }
}
