package ru.nordcam.app.ui

import android.content.Context
import android.net.wifi.WifiManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.nordcam.app.Nav
import ru.nordcam.app.Screen
import ru.nordcam.app.app
import ru.nordcam.app.data.CamOps
import ru.nordcam.app.data.Camera
import ru.nordcam.app.data.MotionMode
import ru.nordcam.app.data.RecordMode
import ru.nordcam.app.data.Vendor
import ru.nordcam.app.data.Vendors
import ru.nordcam.app.net.Discovered
import ru.nordcam.app.net.Onvif
import ru.nordcam.app.net.OnvifInfo
import ru.nordcam.app.net.OnvifProfile

@Composable
fun EditCameraScreen(nav: Nav, camId: String?) {
    val ctx = LocalContext.current
    val existing = camId?.let { ctx.app.store.camera(it) }
    if (existing != null) {
        Column(Modifier.fillMaxSize()) {
            Bar(existing.title, onBack = { nav.back() })
            CameraForm(nav, existing, isNew = false)
        }
        return
    }
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Bar("Добавить камеру", onBack = { nav.back() })
        ScrollableTabRow(selectedTabIndex = tab, containerColor = C.bg, edgePadding = 8.dp) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Поиск в сети") })
            Tab(tab == 1, { tab = 1 }, text = { Text("По адресу") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Регистратор") })
            Tab(tab == 3, { tab = 3 }, text = { Text("По коду") })
        }
        when (tab) {
            0 -> DiscoverPane(nav)
            1 -> CameraForm(nav, Camera(), isNew = true)
            2 -> NvrPane(nav)
            else -> {
                Empty("Камеры от другого человека", "Вам прислали QR-код или код доступа NordCam? Добавьте камеры по нему.") {
                    Button(onClick = { nav.back(); nav.go(Screen.Import()) }) { Text("Ввести или отсканировать код") }
                }
            }
        }
    }
}

// ============================================================== поиск ONVIF

@Composable
private fun DiscoverPane(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<Discovered>() }
    var connect by remember { mutableStateOf<Discovered?>(null) }
    val known = ctx.app.store.cameras.value.map { it.host }.toSet()

    fun search() {
        busy = true
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lock = wifi.createMulticastLock("nordcam-discovery").apply { setReferenceCounted(false) }
                try {
                    lock.acquire()
                    Onvif.discover()
                } catch (e: Exception) {
                    emptyList()
                } finally {
                    try { lock.release() } catch (_: Exception) {}
                }
            }
            found.clear(); found.addAll(list.sortedBy { it.host })
            busy = false; searched = true
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Телефон должен быть в той же Wi-Fi сети, что и камеры. В камере должен быть включён ONVIF (у Hikvision — «Интеграция → ONVIF», там же создаётся пользователь).",
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
        )
        Button(onClick = { search() }, enabled = !busy, modifier = Modifier.padding(horizontal = 16.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, null)
            Spacer(Modifier.width(8.dp)); Text(if (busy) "Ищу…" else "Искать камеры")
        }
        if (searched && found.isEmpty()) {
            Text(
                "Ничего не найдено. Проверьте ONVIF в камере или добавьте её «По адресу».",
                color = C.amber, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
            )
        }
        if (found.isNotEmpty()) {
            Section("Найдено: ${found.size}") {
                found.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable { connect = d }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Videocam, null, tint = C.accent2)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(listOf(d.name, d.hardware).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "Камера ONVIF" })
                            Text("${d.host}:${d.port}${if (d.host in known) " · уже добавлена" else ""}", color = C.muted, fontSize = 13.sp)
                        }
                    }
                    HorizontalDivider(color = C.line)
                }
            }
        }
        Section("Камера через интернет") {
            Text(
                "Если камера в другом месте — откройте вкладку «По адресу», впишите внешний IP или домен (DDNS) и нажмите «Получить по ONVIF». На роутере должны быть проброшены порты RTSP (554) и ONVIF/HTTP (обычно 80), либо используйте VPN.",
                color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
            )
        }
    }

    connect?.let { d ->
        OnvifConnectDialog(nav, d.host, d.port, d.name.ifBlank { d.hardware }) { connect = null }
    }
}

private fun vendorFor(info: OnvifInfo?): Vendor {
    val m = (info?.manufacturer.orEmpty() + " " + info?.model.orEmpty()).lowercase()
    return when {
        m.contains("hikvision") || m.contains("hiwatch") || m.contains("ezviz") -> Vendors.HIKVISION
        m.contains("dahua") || m.contains("imou") || m.contains("rvi") -> Vendors.DAHUA
        m.contains("uniview") || m.contains("unv") -> Vendors.UNIVIEW
        m.contains("reolink") -> Vendors.REOLINK
        m.contains("tp-link") || m.contains("tapo") || m.contains("vigi") -> Vendors.TAPO
        else -> Vendors.ONVIF
    }
}

/** Подключение по ONVIF: логин, список каналов, добавление выбранных. */
@Composable
private fun OnvifConnectDialog(nav: Nav, host: String, port: Int, title: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf("admin") }
    var pass by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<OnvifInfo?>(null) }
    var chans by remember { mutableStateOf<List<Triple<OnvifProfile, OnvifProfile?, String>>>(emptyList()) }
    val checked = remember { mutableStateListOf<Int>() }

    fun load() {
        busy = true; error = ""
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val o = Onvif(host, port, user, pass)
                    val i = try { o.info() } catch (e: Exception) { if (e.message?.contains("логин") == true) throw e else null }
                    val list = CamOps.channels(o.profiles()).map { (m, s) ->
                        Triple(m, s, o.streamUri(m.token) + "\n" + (s?.let { o.streamUri(it.token) } ?: ""))
                    }
                    info = i
                    chans = list
                }
                checked.clear(); checked.addAll(chans.indices)
                if (chans.isEmpty()) error = "Камера не сообщила ни одного видеопотока"
            } catch (e: Exception) {
                error = e.message ?: "Не удалось подключиться"
            }
            busy = false
        }
    }

    fun add() {
        val vendor = vendorFor(info)
        val base = info?.model?.ifBlank { null } ?: title.ifBlank { host }
        val cams = chans.withIndex().filter { it.index in checked }.map { (i, t) ->
            val (main, sub, uris) = t
            val (mainUri, subUri) = uris.split("\n").let { it[0] to it.getOrElse(1) { "" } }
            Camera(
                name = if (chans.size > 1) "$base — канал ${i + 1}" else base,
                group = group.trim(),
                vendor = vendor.id,
                host = host,
                rtspPort = CamOps.portOf(mainUri, 554),
                httpPort = port,
                onvifPort = port,
                user = user,
                password = pass,
                channel = i + 1,
                mainUrl = CamOps.toTemplate(mainUri, rtsp = true),
                subUrl = if (subUri.isNotBlank()) CamOps.toTemplate(subUri, rtsp = true) else "",
                onvifProfile = main.token,
                onvifProfileSub = sub?.token.orEmpty(),
                ptz = main.hasPtz,
            )
        }
        ctx.app.store.upsertAll(cams)
        toast(ctx, "Добавлено камер: ${cams.size}")
        onClose()
        nav.home(0)
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (chans.isEmpty()) "Вход в камеру $host" else (info?.let { "${it.manufacturer} ${it.model}".trim() } ?: host)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (chans.isEmpty()) {
                    Field(user, { user = it }, "Логин", modifier = Modifier.padding(0.dp))
                    Field(pass, { pass = it }, "Пароль", password = true)
                } else {
                    Text("Выберите, что добавить:", color = C.muted, fontSize = 13.sp)
                    chans.forEachIndexed { i, (m, s, _) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { if (i in checked) checked.remove(i) else checked.add(i) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(i in checked, { if (it) checked.add(i) else checked.remove(i) })
                            Column {
                                Text(if (chans.size > 1) "Канал ${i + 1}" else "Камера")
                                Text(
                                    "${m.width}×${m.height} ${m.encoding}${s?.let { " + ${it.width}×${it.height}" } ?: ""}${if (m.hasPtz) " · PTZ" else ""}",
                                    color = C.muted, fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    Field(group, { group = it }, "Объект (дом, офис…)", hint = "необязательно")
                }
                if (busy) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Подключаюсь…", color = C.muted)
                }
                if (error.isNotBlank()) Text(error, color = C.red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            if (chans.isEmpty()) TextButton(onClick = { load() }, enabled = !busy) { Text("Подключиться") }
            else TextButton(onClick = { add() }, enabled = checked.isNotEmpty()) { Text("Добавить (${checked.size})") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } },
    )
}

// ============================================================== регистратор

@Composable
private fun NvrPane(nav: Nav) {
    val ctx = LocalContext.current
    var c by remember { mutableStateOf(Camera(vendor = Vendors.HIKVISION.id)) }
    var count by remember { mutableStateOf("4") }
    var onvifDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Регистратор (NVR/DVR) добавляется сразу всеми каналами — как в Hik-Connect. Архив будет браться с его диска.",
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
        )
        VendorPicker(c.vendor, listOf(Vendors.HIKVISION, Vendors.DAHUA, Vendors.UNIVIEW, Vendors.XMEYE)) { c = c.copy(vendor = it) }
        Field(c.name, { c = c.copy(name = it) }, "Название", hint = "Например: Склад")
        Field(c.group, { c = c.copy(group = it) }, "Объект", hint = "необязательно")
        Field(c.host, { c = c.copy(host = it.trim()) }, "IP-адрес или домен")
        Row {
            Field(c.rtspPort.toString(), { c = c.copy(rtspPort = it.toIntOrNull() ?: 554) }, "RTSP порт", Modifier.weight(1f), number = true)
            Field(c.httpPort.toString(), { c = c.copy(httpPort = it.toIntOrNull() ?: 80, onvifPort = it.toIntOrNull() ?: 80) }, "HTTP порт", Modifier.weight(1f), number = true)
        }
        Field(c.user, { c = c.copy(user = it) }, "Логин")
        Field(c.password, { c = c.copy(password = it) }, "Пароль", password = true)
        Field(count, { count = it.filter(Char::isDigit).take(3) }, "Количество каналов", number = true)
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = c.host.isNotBlank() && (count.toIntOrNull() ?: 0) in 1..128,
                onClick = {
                    val n = count.toInt()
                    val base = c.name.ifBlank { c.host }
                    ctx.app.store.upsertAll((1..n).map { ch -> c.copy(id = java.util.UUID.randomUUID().toString(), name = "$base — канал $ch", channel = ch) })
                    toast(ctx, "Добавлено каналов: $n")
                    nav.home(0)
                },
            ) { Text("Добавить каналы") }
            OutlinedButton(enabled = c.host.isNotBlank(), onClick = { onvifDialog = true }) { Text("Найти по ONVIF") }
        }
    }
    if (onvifDialog) OnvifConnectDialog(nav, c.host, c.httpPort, c.name) { onvifDialog = false }
}

// ============================================================== форма камеры

@Composable
private fun VendorPicker(selected: String, options: List<Vendor> = Vendors.all, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Производитель", color = C.muted, fontSize = 11.sp)
                Text(Vendors.of(selected).title, color = C.text)
            }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { v ->
                DropdownMenuItem(text = { Text(v.title) }, onClick = { open = false; onPick(v.id) })
            }
        }
    }
}

@Composable
fun CameraForm(nav: Nav, initial: Camera, isNew: Boolean) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val scope = rememberCoroutineScope()
    var c by remember(initial.id) { mutableStateOf(initial) }
    var advanced by remember { mutableStateOf(initial.mainUrl.isNotBlank() && initial.vendor != Vendors.ONVIF.id) }
    var preview by remember { mutableStateOf(false) }
    var unlinked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val v = Vendors.of(c.vendor)
    val shared = c.shared

    fun onvifFill() {
        busy = true; msg = ""
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) {
                    val o = Onvif(c.host.trim(), c.onvifPort, c.user, c.password)
                    val info = try { o.info() } catch (e: Exception) { null }
                    val chans = CamOps.channels(o.profiles())
                    val pick = chans.getOrNull(c.channel - 1) ?: chans.first()
                    val main = o.streamUri(pick.first.token)
                    val sub = pick.second?.let { o.streamUri(it.token) }.orEmpty()
                    Triple(info, pick, main to sub)
                }
                val (info, pick, uris) = res
                val vendor = vendorFor(info)
                c = c.copy(
                    vendor = if (c.vendor == Vendors.GENERIC.id || vendor != Vendors.ONVIF) vendor.id else c.vendor,
                    name = c.name.ifBlank { info?.model.orEmpty() },
                    mainUrl = CamOps.toTemplate(uris.first, true),
                    subUrl = if (uris.second.isNotBlank()) CamOps.toTemplate(uris.second, true) else "",
                    rtspPort = CamOps.portOf(uris.first, c.rtspPort),
                    onvifProfile = pick.first.token,
                    onvifProfileSub = pick.second?.token.orEmpty(),
                    ptz = pick.first.hasPtz || c.ptz,
                )
                msg = "Получено от камеры${info?.let { ": ${it.manufacturer} ${it.model}" } ?: ""}. Нажмите «Проверить»."
            } catch (e: Exception) {
                msg = "ONVIF: ${e.message}"
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (preview) {
            CamVideo(c, Modifier.fillMaxWidth().aspectRatio(16f / 9f), sub = false, audio = false)
            Text(
                Vendors.liveUrl(c, false).replace(Regex("password=[^&]*"), "password=***"),
                color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Section("Основное") {
            Field(c.name, { c = c.copy(name = it) }, "Название", hint = "Например: Двор")
            Field(c.group, { c = c.copy(group = it) }, "Объект (дом, офис, магазин)", hint = "для группировки, необязательно")
        }
        if (!shared) {
            Section("Подключение") {
                VendorPicker(c.vendor) { c = c.copy(vendor = it) }
                if (v.hint.isNotBlank()) Text(v.hint, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                if (c.vendor == Vendors.GENERIC.id) {
                    Field(c.mainUrl, { c = c.copy(mainUrl = it.trim()) }, "Адрес потока (RTSP/HTTP)", hint = "rtsp://192.168.1.10:554/stream1")
                    Field(c.subUrl, { c = c.copy(subUrl = it.trim()) }, "Лёгкий поток (необязательно)")
                }
                Field(c.host, { c = c.copy(host = it.trim()) }, "IP-адрес или домен", hint = "192.168.1.64 или mycam.ddns.net")
                Row {
                    Field(c.rtspPort.toString(), { c = c.copy(rtspPort = it.toIntOrNull() ?: 554) }, "RTSP", Modifier.weight(1f), number = true)
                    Field(c.httpPort.toString(), { c = c.copy(httpPort = it.toIntOrNull() ?: 80) }, "HTTP", Modifier.weight(1f), number = true)
                    Field(c.onvifPort.toString(), { c = c.copy(onvifPort = it.toIntOrNull() ?: 80) }, "ONVIF", Modifier.weight(1f), number = true)
                }
                Field(c.user, { c = c.copy(user = it) }, "Логин")
                Field(c.password, { c = c.copy(password = it) }, "Пароль", password = true)
                Field(c.channel.toString(), { c = c.copy(channel = it.toIntOrNull()?.coerceAtLeast(1) ?: 1) }, "Канал (для регистратора)", number = true)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { preview = !preview }, enabled = Vendors.liveUrl(c, false).isNotBlank()) {
                        Text(if (preview) "Скрыть видео" else "Проверить")
                    }
                    OutlinedButton(onClick = { onvifFill() }, enabled = !busy && c.host.isNotBlank()) {
                        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Получить по ONVIF")
                    }
                }
                if (msg.isNotBlank()) Text(msg, color = if (msg.startsWith("ONVIF")) C.amber else C.green, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                SwitchRow("Поворотная камера (PTZ)", "Управление поворотом и зумом по ONVIF", c.ptz) { c = c.copy(ptz = it) }
                SwitchRow("Звук", null, c.audio) { c = c.copy(audio = it) }
                SwitchRow("RTSP через TCP", "Надёжнее через интернет и мобильную сеть", c.tcp) { c = c.copy(tcp = it) }
                Row(
                    Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Точные адреса потоков, снимка и архива", modifier = Modifier.weight(1f))
                    Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = C.muted)
                }
                if (advanced) {
                    Text(
                        "Пусто — берётся шаблон производителя. Можно использовать {host} {rtsp} {http} {user} {pass} {ch} {ch2}, для архива — {start} и {end}.",
                        color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Field(c.mainUrl, { c = c.copy(mainUrl = it.trim()) }, "Основной поток", hint = v.main)
                    Field(c.subUrl, { c = c.copy(subUrl = it.trim()) }, "Лёгкий поток", hint = v.sub)
                    Field(c.snapshotUrl, { c = c.copy(snapshotUrl = it.trim()) }, "Снимок (JPEG по HTTP)", hint = v.snapshot)
                    Field(c.playbackUrl, { c = c.copy(playbackUrl = it.trim()) }, "Архив", hint = v.playback, singleLine = false)
                }
            }
        }
        if (!shared) {
            val srv = store.servers.value.firstOrNull { it.id == c.serverId }
            Section("Архив на сервере записи") {
                Text(
                    if (srv != null && c.serverCam.isNotBlank()) "Связана с «${c.serverCamName.ifBlank { c.serverCam }}» на ${srv.title}"
                    else "Не связана. Если архив пишет Synology Surveillance Station или Frigate — подключите сервер, и записи появятся в «Архиве».",
                    color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.go(if (srv != null) Screen.EditServer(srv.id) else Screen.Servers) }) { Text(if (srv != null) "Сервер" else "Серверы записи") }
                    if (srv != null) TextButton(onClick = { unlinked = true; c = c.copy(serverId = "", serverCam = "", serverCamName = "") }) { Text("Отвязать", color = C.muted) }
                }
            }
        }
        if (!isNew || !shared) AlarmSection(c) { c = it }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = shared || c.host.isNotBlank() || c.mainUrl.isNotBlank(),
                onClick = {
                    val host = if (c.host.isBlank() && c.mainUrl.isNotBlank()) {
                        try { java.net.URI(c.mainUrl).host.orEmpty() } catch (e: Exception) { "" }
                    } else c.host
                    // Связь с сервером записи могла поменяться на экране сервера — берём актуальную
                    val cur = store.camera(c.id)
                    val link = if (cur != null && !unlinked) c.copy(serverId = cur.serverId, serverCam = cur.serverCam, serverCamName = cur.serverCamName) else c
                    store.upsert(link.copy(host = host))
                    if (c.motion != MotionMode.OFF || c.record == RecordMode.CONTINUOUS) {
                        if (!store.settings.value.monitoring) toast(ctx, "Сохранено. Включите «Наблюдение в фоне» в разделе «Ещё»")
                        else toast(ctx, "Сохранено")
                    } else toast(ctx, "Сохранено")
                    if (isNew) nav.home(0) else nav.back()
                },
            ) { Text(if (isNew) "Добавить" else "Сохранить") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AlarmSection(c: Camera, onChange: (Camera) -> Unit) {
    Section("Тревоги и запись на телефон") {
        Text("Детекция движения", fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        MotionMode.entries.forEach { m ->
            Row(
                Modifier.fillMaxWidth().selectable(c.motion == m) { onChange(c.copy(motion = m)) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(c.motion == m, { onChange(c.copy(motion = m)) })
                Text(m.title)
            }
        }
        when (c.motion) {
            MotionMode.ONVIF -> Text(
                "Камера сама определяет движение, пересечение линии, людей и машины — как в Hik-Connect. Включите нужные детекторы в настройках самой камеры.",
                color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )
            MotionMode.SNAPSHOT -> {
                Text(
                    "Телефон каждые 1–2 секунды берёт снимок и сравнивает с предыдущим. Подходит для камер без ONVIF-событий.",
                    color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Чувствительность ${c.sensitivity}", fontSize = 13.sp, modifier = Modifier.width(150.dp))
                    Slider(value = c.sensitivity.toFloat(), onValueChange = { onChange(c.copy(sensitivity = it.toInt())) }, valueRange = 1f..100f, modifier = Modifier.weight(1f))
                }
            }
            MotionMode.OFF -> {}
        }
        SwitchRow("Уведомления", "Пуш со снимком при тревоге", c.notify, enabled = c.motion != MotionMode.OFF) { onChange(c.copy(notify = it)) }
        HorizontalDivider(color = C.line, modifier = Modifier.padding(vertical = 4.dp))
        Text("Запись на телефон", fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        RecordMode.entries.forEach { r ->
            val enabled = c.perms.record && (r != RecordMode.MOTION || c.motion != MotionMode.OFF)
            Row(
                Modifier.fillMaxWidth().selectable(c.record == r, enabled = enabled) { onChange(c.copy(record = r)) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(c.record == r, { onChange(c.copy(record = r)) }, enabled = enabled)
                Text(r.title, color = if (enabled) C.text else C.muted)
            }
        }
        Text(
            "Запись идёт, пока включено «Наблюдение в фоне». Постоянная запись расходует батарею и трафик — лучше держать телефон на зарядке и в Wi-Fi.",
            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        )
    }
}
