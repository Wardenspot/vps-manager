package ru.nordcam.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import ru.nordcam.app.data.ArchiveServer
import ru.nordcam.app.data.Camera
import ru.nordcam.app.data.ServerType
import ru.nordcam.app.data.Store
import ru.nordcam.app.data.Vendors
import ru.nordcam.app.net.Nvr
import ru.nordcam.app.net.RemoteCam

/** Список серверов записи: Synology Surveillance Station, Frigate. */
@Composable
fun ServersScreen(nav: Nav) {
    val ctx = LocalContext.current
    val servers by ctx.app.store.servers.collectAsState()
    val cams by ctx.app.store.cameras.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Bar("Серверы записи", onBack = { nav.back() }) {
            IconButton(onClick = { nav.go(Screen.EditServer(null)) }) { Icon(Icons.Default.Add, "Добавить сервер") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "Подключите NAS или видеорегистратор, который пишет архив, — и смотрите записи по шкале времени прямо в NordCam. " +
                    "Камеры с сервера можно сразу добавить в приложение.",
                color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
            )
            if (servers.isEmpty()) {
                Empty("Серверов пока нет", "Поддерживаются Synology Surveillance Station и Frigate.") {
                    Button(onClick = { nav.go(Screen.EditServer(null)) }) { Text("Подключить сервер") }
                }
            } else {
                Section {
                    servers.forEach { s ->
                        val linked = cams.count { it.serverId == s.id }
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.go(Screen.EditServer(s.id)) }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Storage, null, tint = C.accent2)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.title)
                                Text("${s.type.title} · ${s.url} · камер: $linked", color = C.muted, fontSize = 12.sp, maxLines = 1)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = C.muted)
                        }
                        HorizontalDivider(color = C.line)
                    }
                }
            }
        }
    }
}

private fun importCamera(store: Store, server: ArchiveServer, rc: RemoteCam): Camera {
    val liveHost = try { java.net.URI(rc.live).host.orEmpty() } catch (e: Exception) { "" }
    val host = liveHost.ifBlank { rc.ip }
    val c = Camera(
        name = rc.name,
        group = server.title,
        vendor = Vendors.GENERIC.id,
        host = host,
        user = "",
        password = "",
        mainUrl = rc.live,
        serverId = server.id,
        serverCam = rc.id,
        serverCamName = rc.name,
    )
    store.upsert(c)
    return c
}

@Composable
fun EditServerScreen(nav: Nav, id: String?) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val scope = rememberCoroutineScope()
    val cams by store.cameras.collectAsState()
    val existing = id?.let { store.server(it) }
    var srv by remember { mutableStateOf(existing ?: ArchiveServer()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf<List<RemoteCam>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val saved = store.server(srv.id) != null

    fun connect() {
        busy = true; error = ""
        val s = srv.copy(url = Nvr.baseUrl(srv.url))
        srv = s
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { Nvr.of(s).cameras() }
                store.upsertServer(s)
                remote = list
                if (list.isEmpty()) error = "Подключились, но камер на сервере нет"
            } catch (e: Exception) {
                error = e.message ?: "Не удалось подключиться"
            }
            busy = false
        }
    }

    fun link(cam: Camera, rc: RemoteCam?) {
        store.upsert(cam.copy(serverId = if (rc == null) "" else srv.id, serverCam = rc?.id.orEmpty(), serverCamName = rc?.name.orEmpty()))
    }

    Column(Modifier.fillMaxSize()) {
        Bar(if (existing == null) "Новый сервер записи" else srv.title, onBack = { nav.back() }) {
            if (saved) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Удалить", tint = C.muted) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Тип") {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServerType.entries.forEach { t ->
                        FilterChip(srv.type == t, { srv = srv.copy(type = t); remote = null }, { Text(t.title.substringBefore(" Surveillance")) })
                    }
                }
                Text(srv.type.hint, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Section("Подключение") {
                Field(srv.name, { srv = srv.copy(name = it) }, "Название", hint = if (srv.type == ServerType.SYNOLOGY) "Например: NAS офис" else "Например: Frigate дом")
                Field(
                    srv.url, { srv = srv.copy(url = it.trim()) }, "Адрес",
                    hint = if (srv.type == ServerType.SYNOLOGY) "https://192.168.1.10:5001" else "http://192.168.1.20:5000",
                )
                Field(srv.user, { srv = srv.copy(user = it.trim()) }, if (srv.type == ServerType.FRIGATE) "Логин (если включён вход)" else "Пользователь DSM")
                Field(srv.password, { srv = srv.copy(password = it) }, "Пароль", password = true)
                SwitchRow("Доверять сертификату сервера", "Нужно для NAS с самоподписанным сертификатом (порт 5001)", srv.trustAll) { srv = srv.copy(trustAll = it) }
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { connect() }, enabled = !busy && srv.url.isNotBlank()) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(if (saved) "Сохранить и обновить камеры" else "Подключить")
                    }
                }
                if (error.isNotBlank()) Text(error, color = C.red, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }

            val list = remote
            if (list != null && list.isNotEmpty()) {
                val unlinked = list.filter { rc -> cams.none { it.serverId == srv.id && it.serverCam == rc.id } }
                Section("Камеры на сервере: ${list.size}") {
                    Text(
                        "Свяжите камеры NordCam с камерами сервера — тогда в «Архиве» появится вкладка с записями сервера. " +
                            "Камеры, которых ещё нет в приложении, можно добавить: живое видео пойдёт через сервер.",
                        color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = unlinked.isNotEmpty(), onClick = {
                            var n = 0
                            unlinked.forEach { rc ->
                                val match = cams.firstOrNull { c ->
                                    c.serverId.isBlank() && ((rc.ip.isNotBlank() && c.host.equals(rc.ip, true)) || c.title.equals(rc.name, true))
                                }
                                if (match != null) { link(match, rc); n++ }
                            }
                            toast(ctx, if (n > 0) "Связано автоматически: $n" else "Совпадений по IP и названию не нашлось")
                        }) { Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text("Связать сам") }
                        OutlinedButton(enabled = unlinked.isNotEmpty(), onClick = {
                            unlinked.forEach { importCamera(store, srv, it) }
                            toast(ctx, "Добавлено камер: ${unlinked.size}")
                        }) { Text("Добавить все") }
                    }
                    Spacer(Modifier.height(4.dp))
                    list.forEach { rc -> RemoteCamRow(srv, rc, cams, onLink = { c -> link(c, rc) }, onUnlink = { c -> link(c, null) }, onImport = { importCamera(store, srv, rc); toast(ctx, "«${rc.name}» добавлена") }) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить сервер «${srv.title}»?") },
            text = { Text("Камеры останутся в приложении, но вкладка архива этого сервера пропадёт.") },
            confirmButton = { TextButton(onClick = { store.deleteServer(srv.id); confirmDelete = false; nav.back() }) { Text("Удалить", color = C.red) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun RemoteCamRow(srv: ArchiveServer, rc: RemoteCam, cams: List<Camera>, onLink: (Camera) -> Unit, onUnlink: (Camera) -> Unit, onImport: () -> Unit) {
    val linked = cams.firstOrNull { it.serverId == srv.id && it.serverCam == rc.id }
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Videocam, null, tint = if (linked != null) C.green else C.muted)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(rc.name, fontWeight = FontWeight.Medium)
            Text(
                if (linked != null) "Связана с «${linked.title}»" else listOf(rc.ip, "не связана").filter { it.isNotBlank() }.joinToString(" · "),
                color = if (linked != null) C.green else C.muted, fontSize = 12.sp,
            )
        }
        if (linked == null) {
            Box {
                TextButton(onClick = { menu = true }) { Text("Связать") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Добавить как новую камеру") }, onClick = { menu = false; onImport() })
                    cams.filter { it.serverId.isBlank() && !it.shared }.forEach { c ->
                        DropdownMenuItem(text = { Text("С «${c.title}»") }, onClick = { menu = false; onLink(c) })
                    }
                }
            }
        } else {
            TextButton(onClick = { onUnlink(linked) }) { Text("Отвязать", color = C.muted) }
        }
    }
    HorizontalDivider(color = C.line)
}
