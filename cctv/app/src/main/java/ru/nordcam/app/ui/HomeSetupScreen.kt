package ru.nordcam.app.ui

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.nordcam.app.Nav
import ru.nordcam.app.app
import ru.nordcam.app.data.HomeItem
import ru.nordcam.app.net.HaEntity
import ru.nordcam.app.net.HomeAssistant
import ru.nordcam.app.net.Nvr

private val domainTitles = linkedMapOf(
    "climate" to "Отопление и климат", "water_heater" to "Котлы и водонагреватели", "cover" to "Ворота, шторы, двери",
    "lock" to "Замки", "alarm_control_panel" to "Охрана", "switch" to "Реле и розетки", "light" to "Свет",
    "button" to "Кнопки", "input_button" to "Кнопки", "script" to "Сценарии", "scene" to "Сцены",
    "binary_sensor" to "Датчики (открыто, протечка, дым, движение)", "sensor" to "Показания (температура, влажность, энергия)",
    "fan" to "Вентиляция", "valve" to "Краны", "humidifier" to "Увлажнители", "input_boolean" to "Переключатели",
    "number" to "Числа", "input_number" to "Числа", "select" to "Выбор", "input_select" to "Выбор",
    "automation" to "Автоматизации", "siren" to "Сирены",
)

/** Настройка вкладки «Дом»: подключение Home Assistant, выбор устройств, свои HTTP-кнопки. */
@Composable
fun HomeSetupScreen(nav: Nav) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Bar("Настройка «Дома»", onBack = { nav.back() })
        TabRow(selectedTabIndex = tab, containerColor = C.bg) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Home Assistant") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Свои кнопки") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Порядок") })
        }
        when (tab) {
            0 -> HaSetup()
            1 -> HttpButtons()
            else -> HomeOrder()
        }
    }
}

@Composable
private fun HaSetup() {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val scope = rememberCoroutineScope()
    val s by store.settings.collectAsState()
    val items by store.home.collectAsState()
    var url by remember { mutableStateOf(s.haUrl) }
    var token by remember { mutableStateOf(s.haToken) }
    var trust by remember { mutableStateOf(s.haTrust) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var entities by remember { mutableStateOf<List<HaEntity>?>(null) }
    var query by remember { mutableStateOf("") }
    var domainFilter by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<HomeItem?>(null) }
    val areas by store.haAreas.collectAsState()

    fun connect() {
        busy = true; msg = ""
        scope.launch {
            try {
                val ha = HomeAssistant(url, token, trust)
                val (name, list, ar) = withContext(Dispatchers.IO) { Triple(ha.check(), ha.states(), ha.areas()) }
                store.updateSettings { it.copy(haUrl = Nvr.baseUrl(url), haToken = token, haTrust = trust) }
                store.haStates.value = list.associateBy { it.id }
                store.haAreas.value = ar
                entities = list.filter { it.domain in HomeAssistant.supported }.sortedBy { it.name.lowercase() }
                msg = "Подключено: $name, устройств: ${entities?.size}"
            } catch (e: Exception) {
                msg = e.message ?: "Не удалось подключиться"
            }
            busy = false
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Section("Подключение") {
                Text(
                    "Адрес Home Assistant (как в браузере) и долгосрочный токен: в Home Assistant откройте свой профиль → «Безопасность» → " +
                        "«Долгосрочные токены доступа» → «Создать токен». Снаружи — через VPN, Nabu Casa или свой домен.",
                    color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Field(url, { url = it.trim() }, "Адрес", hint = "http://192.168.1.50:8123")
                Field(token, { token = it.trim() }, "Токен", password = true)
                SwitchRow("Доверять сертификату", "Для своего домена с самоподписанным сертификатом", trust) { trust = it }
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { connect() }, enabled = !busy && url.isNotBlank() && token.isNotBlank()) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (s.haReady) "Обновить список" else "Подключить")
                    }
                    if (s.haReady) TextButton(onClick = {
                        store.updateSettings { it.copy(haUrl = "", haToken = "") }
                        store.saveHome(items.filter { it.kind == "http" })
                        entities = null; msg = "Home Assistant отключён"
                    }) { Text("Отключить", color = C.red) }
                }
                if (msg.isNotBlank()) Text(msg, fontSize = 13.sp, color = if (msg.startsWith("Подключено")) C.green else C.red, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }
        }
        val list = entities
        if (list == null) {
            if (s.haReady && items.any { it.kind == "ha" }) item {
                Text("Нажмите «Обновить список», чтобы добавить или убрать устройства.", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
            return@LazyColumn
        }
        item {
            Text(
                "Отметьте, что показывать на вкладке «Дом». ⚙ — название, комната, привязка к камере, тревога и подтверждение.",
                color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Field(query, { query = it }, "Поиск", hint = "котёл, ворота, протечка…")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(domainFilter.isBlank(), { domainFilter = "" }, { Text("Все") })
                list.map { it.domain }.distinct().sortedBy { domainTitles.keys.indexOf(it).let { i -> if (i < 0) 99 else i } }.forEach { d ->
                    FilterChip(domainFilter == d, { domainFilter = d }, { Text(domainTitles[d] ?: d) })
                }
            }
        }
        val shown = list.filter {
            (domainFilter.isBlank() || it.domain == domainFilter) &&
                (query.isBlank() || it.name.contains(query, true) || it.id.contains(query, true) || (areas[it.id]?.contains(query, true) == true))
        }
        items(shown, key = { it.id }) { e ->
            val item = items.firstOrNull { it.kind == "ha" && it.entityId == e.id }
            Row(
                Modifier.fillMaxWidth().clickable {
                    store.saveHome(if (item != null) items - item else items + defaultItem(e))
                }.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(item != null, { on -> store.saveHome(if (!on && item != null) items - item else if (on && item == null) items + defaultItem(e) else items) })
                Column(Modifier.weight(1f)) {
                    Text(e.name, maxLines = 1)
                    Text(listOfNotNull(areas[e.id], domainTitles[e.domain] ?: e.domain, stateText(e)).joinToString(" · "), color = C.muted, fontSize = 12.sp, maxLines = 1)
                }
                if (item != null) IconButton(onClick = { editing = item }) { Icon(Icons.Default.Settings, "Настроить", tint = C.muted) }
            }
            HorizontalDivider(color = C.line)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    editing?.let { ItemDialog(it) { editing = null } }
}

/** Разумные настройки по умолчанию: ворота и замки — с подтверждением, протечка/дым/газ — с тревогой. */
private fun defaultItem(e: HaEntity): HomeItem {
    val dc = e.deviceClass
    return HomeItem(
        kind = "ha", entityId = e.id,
        confirm = e.domain in setOf("lock", "cover", "alarm_control_panel") && dc != "blind" && dc != "curtain" && dc != "shade",
        alert = e.domain == "binary_sensor" && dc in setOf("moisture", "smoke", "gas", "heat", "safety", "tamper") ||
            e.domain == "alarm_control_panel",
    )
}

/** Настройки одного элемента: название, комната, камера, тревога, подтверждение (+ адрес для HTTP-кнопки). */
@Composable
private fun ItemDialog(i0: HomeItem, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val cams by store.cameras.collectAsState()
    var i by remember(i0.id) { mutableStateOf(i0) }
    var camMenu by remember { mutableStateOf(false) }
    val e = store.haStates.value[i.entityId]
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (i.kind == "http") "Своя кнопка" else itemName(i, e)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field(i.name, { i = i.copy(name = it) }, "Название", hint = if (i.kind == "http") "Открыть ворота" else e?.name.orEmpty(), modifier = Modifier.padding(0.dp))
                Field(i.room, { i = i.copy(room = it) }, "Комната / группа", hint = "Двор, Котельная…")
                if (i.kind == "http") {
                    Field(i.url, { i = i.copy(url = it.trim()) }, if (i.urlOff.isBlank()) "Ссылка" else "Ссылка включения", hint = "http://192.168.1.30/relay/0?turn=on", singleLine = false)
                    Field(i.urlOff, { i = i.copy(urlOff = it.trim()) }, "Ссылка выключения (для света и реле)", hint = "http://192.168.1.30/relay/0?turn=off", singleLine = false)
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("GET", "POST").forEach { m -> FilterChip(i.method == m, { i = i.copy(method = m) }, { Text(m) }) }
                    }
                    if (i.method == "POST") Field(i.body, { i = i.copy(body = it) }, "Тело запроса", hint = "{\"state\":\"on\"}", singleLine = false)
                    Field(i.user, { i = i.copy(user = it.trim()) }, "Логин (если нужен)")
                    Field(i.pass, { i = i.copy(pass = it) }, "Пароль", password = true)
                    Text(
                        "Shelly: http://IP/relay/0?turn=on и …?turn=off (свет), для ворот — импульс через «Auto off» в настройках реле. Shelly Dimmer/RGBW: http://IP/light/0?turn=on&brightness=50. Sonoff DIY: POST http://IP:8081/zeroconf/switch. " +
                            "Подойдёт любой контроллер ворот или домофона, который открывается по ссылке.",
                        color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    OutlinedButton(onClick = { camMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("На экране камеры: " + (cams.firstOrNull { it.id == i.cameraId }?.title ?: "нет"), maxLines = 1)
                    }
                    DropdownMenu(expanded = camMenu, onDismissRequest = { camMenu = false }) {
                        DropdownMenuItem(text = { Text("Не показывать") }, onClick = { camMenu = false; i = i.copy(cameraId = "") })
                        cams.forEach { c -> DropdownMenuItem(text = { Text(c.title) }, onClick = { camMenu = false; i = i.copy(cameraId = c.id) }) }
                    }
                }
                SwitchRow("Спрашивать подтверждение", "Для ворот, замков, охраны", i.confirm) { i = i.copy(confirm = it) }
                if (i.kind == "ha") SwitchRow("Тревога при срабатывании", "Пуш, Telegram и почта (нужно «Наблюдение в фоне»)", i.alert) { i = i.copy(alert = it) }
            }
        },
        confirmButton = {
            TextButton(enabled = i.kind != "http" || i.url.startsWith("http"), onClick = {
                val list = store.home.value
                store.saveHome(if (list.any { it.id == i.id }) list.map { if (it.id == i.id) i else it } else list + i)
                onClose()
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (store.home.value.any { it.id == i.id }) TextButton(onClick = { store.saveHome(store.home.value.filterNot { it.id == i.id }); onClose() }) { Text("Убрать", color = C.red) }
                TextButton(onClick = onClose) { Text("Отмена") }
            }
        },
    )
}

@Composable
private fun HttpButtons() {
    val ctx = LocalContext.current
    val items by ctx.app.store.home.collectAsState()
    var editing by remember { mutableStateOf<HomeItem?>(null) }
    val http = items.filter { it.kind == "http" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            "Если Home Assistant нет — ворота, калитку, шлагбаум или домофон можно открывать своей кнопкой: приложение просто " +
                "отправляет запрос на контроллер (Shelly, Sonoff, ESP, реле с веб-интерфейсом).",
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
        )
        Button(onClick = { editing = HomeItem(kind = "http", confirm = true) }, modifier = Modifier.padding(horizontal = 16.dp)) {
            Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Новая кнопка")
        }
        if (http.isNotEmpty()) Section("Кнопки") {
            http.forEach { i ->
                Row(Modifier.fillMaxWidth().clickable { editing = i }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(i.name.ifBlank { "Кнопка" }, fontWeight = FontWeight.Medium)
                        Text("${i.method} ${i.url}", color = C.muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Icon(Icons.Default.Settings, null, tint = C.muted)
                }
                HorizontalDivider(color = C.line)
            }
        }
    }
    editing?.let { ItemDialog(it) { editing = null } }
}

@Composable
private fun HomeOrder() {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val items by store.home.collectAsState()
    val states by store.haStates.collectAsState()
    var editing by remember { mutableStateOf<HomeItem?>(null) }
    if (items.isEmpty()) {
        Empty("Пока пусто", "Добавьте устройства Home Assistant или свои кнопки."); return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { i ->
            val idx = items.indexOf(i)
            Row(Modifier.fillMaxWidth().clickable { editing = i }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(itemName(i, states[i.entityId]), maxLines = 1)
                    Text(itemRoom(i, store.haAreas.value), color = C.muted, fontSize = 12.sp)
                }
                IconButton(enabled = idx > 0, onClick = {
                    val l = items.toMutableList(); l.add(idx - 1, l.removeAt(idx)); store.saveHome(l)
                }) { Icon(Icons.Default.ArrowUpward, "Выше") }
                IconButton(enabled = idx < items.lastIndex, onClick = {
                    val l = items.toMutableList(); l.add(idx + 1, l.removeAt(idx)); store.saveHome(l)
                }) { Icon(Icons.Default.ArrowDownward, "Ниже") }
                IconButton(onClick = { store.saveHome(items - i) }) { Icon(Icons.Default.Delete, "Убрать", tint = C.muted) }
            }
            HorizontalDivider(color = C.line)
        }
    }
    editing?.let { ItemDialog(it) { editing = null } }
}

/** Home Assistant целиком — его родной интерфейс внутри приложения. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HaWebScreen(nav: Nav) {
    val ctx = LocalContext.current
    val s = ctx.app.store.settings.value
    var web by remember { mutableStateOf<WebView?>(null) }
    BackHandler { if (web?.canGoBack() == true) web?.goBack() else nav.back() }
    Column(Modifier.fillMaxSize()) {
        Bar("Home Assistant", onBack = { nav.back() })
        AndroidView(
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = object : WebViewClient() {
                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                            // Только если пользователь сам разрешил самоподписанный сертификат
                            if (s.haTrust) handler?.proceed() else handler?.cancel()
                        }
                    }
                    loadUrl(Nvr.baseUrl(s.haUrl))
                    web = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

