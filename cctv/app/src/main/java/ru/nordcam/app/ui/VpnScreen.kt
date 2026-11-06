package ru.nordcam.app.ui

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.nordcam.app.Nav
import ru.nordcam.app.Screen
import ru.nordcam.app.app
import ru.nordcam.app.vpn.HomeVpn
import ru.nordcam.app.vpn.IkeAuth
import ru.nordcam.app.vpn.VpnAuto
import ru.nordcam.app.vpn.VpnKind
import ru.nordcam.app.vpn.VpnProfile
import ru.nordcam.app.vpn.VpnResult
import ru.nordcam.app.vpn.VpnStatus

// ============================================================== управление подключением

/** Подключение и отключение с показом нужных экранов Android (разрешение VPN, настройки, установка). */
class VpnController(
    val connect: (VpnProfile) -> Unit,
    val disconnect: (VpnProfile) -> Unit,
)

@Composable
fun rememberVpnController(quiet: Boolean = false): VpnController {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { mutableStateOf<Pair<VpnProfile, Int>?>(null) }
    val runner = remember { arrayOfNulls<(VpnProfile, Int) -> Unit>(1) }
    val intentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pending.value?.let { (p, n) -> if (n < 3) runner[0]?.invoke(p, n + 1) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val pp = pending.value
        if (ok && pp != null) runner[0]?.invoke(pp.first, pp.second + 1)
        else if (!ok) toast(ctx, "Без этого разрешения NordCam не сможет включить туннель")
    }
    runner[0] = { p, n ->
        scope.launch {
            when (val r = HomeVpn.connect(ctx, p, n)) {
                VpnResult.Ok -> if (!quiet || n > 0) toast(ctx, "VPN «${p.title}» подключён")
                is VpnResult.NeedIntent -> {
                    pending.value = p to n
                    toast(ctx, r.why)
                    try { intentLauncher.launch(r.intent) } catch (e: Exception) { toast(ctx, "Не удалось открыть: ${r.why}") }
                }
                is VpnResult.NeedPermission -> {
                    pending.value = p to n
                    permLauncher.launch(r.permission)
                }
                is VpnResult.Error -> if (!quiet || n > 0) toast(ctx, r.message)
            }
        }
    }
    return remember {
        VpnController(
            connect = { p -> runner[0]?.invoke(p, 0) },
            disconnect = { p ->
                scope.launch {
                    when (val r = HomeVpn.disconnect(ctx, p)) {
                        is VpnResult.NeedIntent -> try { intentLauncher.launch(r.intent) } catch (_: Exception) {}
                        is VpnResult.Error -> toast(ctx, r.message)
                        else -> {}
                    }
                }
            },
        )
    }
}

/** При каждом открытии приложения: включить VPN по выбранному режиму (дом недоступен / всегда). */
@Composable
fun VpnAutoConnect() {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val ctl = rememberVpnController(quiet = true)
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev != Lifecycle.Event.ON_START) return@LifecycleEventObserver
            val p = store.activeVpn() ?: return@LifecycleEventObserver
            scope.launch {
                HomeVpn.refresh(ctx, p)
                if (HomeVpn.state.value.status == VpnStatus.ON) return@launch
                when (p.auto) {
                    VpnAuto.MANUAL -> {}
                    VpnAuto.OPEN, VpnAuto.ALWAYS -> if (p.kind != VpnKind.SYSTEM) ctl.connect(p)
                    VpnAuto.AWAY -> {
                        val home = HomeVpn.homeReachable(store, p)
                        if (home == false && p.kind != VpnKind.SYSTEM) ctl.connect(p)
                    }
                }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    // Индикатор: раз в 4 секунды сверяем, поднят ли туннель
    val visible = rememberVisible()
    LaunchedEffect(visible) {
        while (visible) {
            HomeVpn.refresh(ctx, store.activeVpn())
            delay(4_000)
        }
    }
}

/** Значок VPN для верхней панели: зелёный — подключено; нажатие — включить/выключить. */
@Composable
fun VpnIndicator(nav: Nav) {
    val ctx = LocalContext.current
    val list by ctx.app.store.vpn.collectAsState()
    if (list.isEmpty()) return
    val st by HomeVpn.state.collectAsState()
    val ctl = rememberVpnController()
    var menu by remember { mutableStateOf(false) }
    val p = ctx.app.store.activeVpn() ?: return
    Box {
        IconButton(onClick = { menu = true }) {
            when (st.status) {
                VpnStatus.CONNECTING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else -> Icon(
                    if (st.status == VpnStatus.ON) Icons.Default.VpnLock else Icons.Default.VpnKey, "VPN",
                    tint = when (st.status) { VpnStatus.ON -> C.green; VpnStatus.ERROR -> C.red; else -> C.muted },
                )
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(if (st.status == VpnStatus.ON) "Отключить «${p.title}»" else "Подключить «${p.title}»") },
                onClick = { menu = false; if (st.status == VpnStatus.ON) ctl.disconnect(p) else ctl.connect(p) },
            )
            androidx.compose.material3.DropdownMenuItem(text = { Text("Настройки VPN") }, onClick = { menu = false; nav.go(Screen.Vpn) })
        }
    }
}

// ============================================================== список профилей

@Composable
fun VpnScreen(nav: Nav) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val list by store.vpn.collectAsState()
    val s by store.settings.collectAsState()
    val st by HomeVpn.state.collectAsState()
    val ctl = rememberVpnController()
    val active = list.firstOrNull { it.id == s.vpnId } ?: list.firstOrNull()
    var phoneVpn by remember { mutableStateOf(false) }
    LaunchedEffect(st) { phoneVpn = HomeVpn.systemVpnActive(ctx) }

    Column(Modifier.fillMaxSize()) {
        Bar("VPN до дома", onBack = { nav.back() }) {
            IconButton(onClick = { nav.go(Screen.EditVpn(null)) }) { Icon(Icons.Default.Add, "Добавить") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "Чтобы смотреть камеры не из дома, NordCam подключается к домашней сети по VPN. Работает и внутри защищённой папки " +
                    "Samsung (Knox) — там у приложений своя сеть, и VPN из основного пространства на них не действует.",
                color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
            )
            if (active != null) {
                Section("Сейчас") {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).background(if (st.status == VpnStatus.ON) C.green.copy(alpha = 0.2f) else C.surface2, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (st.status == VpnStatus.CONNECTING) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.VpnLock, null, tint = if (st.status == VpnStatus.ON) C.green else C.muted)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(active.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                when (st.status) {
                                    VpnStatus.ON -> "Подключено"
                                    VpnStatus.CONNECTING -> "Подключаюсь…"
                                    VpnStatus.ERROR -> st.message
                                    VpnStatus.OFF -> if (phoneVpn) "Отключено (но на телефоне включён другой VPN)" else "Отключено"
                                },
                                color = if (st.status == VpnStatus.ERROR) C.red else C.muted, fontSize = 13.sp,
                            )
                        }
                    }
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (st.status == VpnStatus.ON) OutlinedButton(onClick = { ctl.disconnect(active) }) { Text("Отключить") }
                        else Button(onClick = { ctl.connect(active) }, enabled = st.status != VpnStatus.CONNECTING) { Text("Подключить") }
                    }
                }
            }
            if (list.isEmpty()) {
                Empty("VPN не настроен", "Добавьте подключение: WireGuard, AmneziaWG, IKEv2 с сертификатами, OpenVPN или VPN, уже настроенный в телефоне.") {
                    Button(onClick = { nav.go(Screen.EditVpn(null)) }) { Text("Добавить VPN") }
                }
            } else {
                Section("Подключения (выберите основное)") {
                    list.forEach { p ->
                        Row(
                            Modifier.fillMaxWidth().selectable(p.id == active?.id) { store.updateSettings { it.copy(vpnId = p.id) } }
                                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(p.id == active?.id, { store.updateSettings { it.copy(vpnId = p.id) } })
                            Column(Modifier.weight(1f)) {
                                Text(p.title)
                                Text("${p.kind.short} · ${p.auto.title.lowercase()}", color = C.muted, fontSize = 12.sp)
                            }
                            IconButton(onClick = { nav.go(Screen.EditVpn(p.id)) }) { Icon(Icons.Default.Settings, "Настроить", tint = C.muted) }
                        }
                        HorizontalDivider(color = C.line)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ============================================================== редактор профиля

private fun readUri(ctx: android.content.Context, uri: Uri): ByteArray? = try {
    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
} catch (e: Exception) {
    null
}

/** PEM или DER → base64 DER. */
private fun certToB64(bytes: ByteArray): String {
    val text = String(bytes)
    return if (text.contains("-----BEGIN")) {
        text.substringAfter("-----").substringAfter("-----").substringBefore("-----END").replace(Regex("\\s"), "")
    } else Base64.encodeToString(bytes, Base64.NO_WRAP)
}

@Composable
fun EditVpnScreen(nav: Nav, id: String?) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val scope = rememberCoroutineScope()
    val existing = id?.let { i -> store.vpn.value.firstOrNull { it.id == i } }
    var p by remember { mutableStateOf(existing ?: VpnProfile()) }
    var msg by remember { mutableStateOf("") }
    var ovpnList by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctl = rememberVpnController()

    val wgFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        readUri(ctx, uri)?.let { b ->
            val text = String(b)
            p = p.copy(config = text, name = p.name.ifBlank { uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty() })
            msg = try { HomeVpn.parseWg(text); "Конфиг загружен" } catch (e: Exception) { HomeVpn.configError(e) }
        }
    }
    val ovpnFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        readUri(ctx, uri)?.let { b ->
            val text = String(b)
            p = p.copy(config = text, remoteId = "", remoteName = "", name = p.name.ifBlank { uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty() })
            msg = if (text.contains("remote ") || text.contains("<ca>")) "Файл .ovpn загружен" else "Это не похоже на .ovpn"
        }
    }
    val p12File = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        readUri(ctx, uri)?.let { p = p.copy(p12 = Base64.encodeToString(it, Base64.NO_WRAP)); msg = "Сертификат пользователя выбран" }
    }
    val caFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        readUri(ctx, uri)?.let { p = p.copy(ca = certToB64(it)); msg = "Сертификат сервера (CA) выбран" }
    }
    val qr = rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { text ->
            p = p.copy(config = text)
            msg = try { HomeVpn.parseWg(text); "Конфиг из QR загружен" } catch (e: Exception) { HomeVpn.configError(e) }
        }
    }
    val ovpnAccess = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { msg = "Доступ выдан — нажмите «Выбрать из OpenVPN» ещё раз" }

    fun loadOvpn() {
        scope.launch {
            when (val r = HomeVpn.openVpnProfiles(ctx)) {
                is HomeVpn.OvpnList.Profiles -> {
                    ovpnList = r.list
                    if (r.list.isEmpty()) msg = "В OpenVPN for Android нет профилей — импортируйте .ovpn сюда"
                }
                is HomeVpn.OvpnList.NeedIntent -> ovpnAccess.launch(r.intent)
                is HomeVpn.OvpnList.Error -> msg = r.message
            }
        }
    }

    fun save(): Boolean {
        if (p.kind == VpnKind.WIREGUARD) {
            try { HomeVpn.parseWg(p.config) } catch (e: Exception) { msg = HomeVpn.configError(e); return false }
        }
        if (p.kind == VpnKind.IKEV2 && p.ikeAuth == IkeAuth.CERT && p.p12.isNotBlank()) {
            try { HomeVpn.p12Info(p.p12, p.p12Pass) } catch (e: Exception) { msg = e.message.orEmpty(); return false }
        }
        val list = store.vpn.value
        store.saveVpn(if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p)
        if (store.settings.value.vpnId.isBlank() || list.isEmpty()) store.updateSettings { it.copy(vpnId = p.id) }
        return true
    }

    Column(Modifier.fillMaxSize()) {
        Bar(if (existing == null) "Новое VPN-подключение" else p.title, onBack = { nav.back() }) {
            if (existing != null) TextButton(onClick = { confirmDelete = true }) { Text("Удалить", color = C.red) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Способ") {
                VpnKind.entries.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().selectable(p.kind == k) { p = p.copy(kind = k); msg = "" }.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(p.kind == k, { p = p.copy(kind = k); msg = "" })
                        Text(k.title, fontSize = 14.sp)
                    }
                }
                Text(p.kind.hint, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            }
            Section("Настройки") {
                Field(p.name, { p = p.copy(name = it) }, "Название", hint = "Дом, Дача, Офис…")
                when (p.kind) {
                    VpnKind.WIREGUARD -> {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { wgFile.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Файл .conf") }
                            OutlinedButton(onClick = {
                                qr.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("QR-код конфига WireGuard").setBeepEnabled(false).setOrientationLocked(false))
                            }) { Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(6.dp)); Text("QR") }
                        }
                        androidx.compose.material3.OutlinedTextField(
                            value = p.config, onValueChange = { p = p.copy(config = it) },
                            label = { Text("Конфиг ([Interface] … [Peer] …)") },
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                            minLines = 6,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        SwitchRow("Только для NordCam", "Через VPN идёт только видео и управление домом, остальной интернет — как обычно", p.appOnly) { p = p.copy(appOnly = it) }
                    }
                    VpnKind.IKEV2 -> {
                        Field(p.server, { p = p.copy(server = it.trim()) }, "Сервер", hint = "home.example.ru или IP")
                        Field(p.identity, { p = p.copy(identity = it.trim()) }, "Идентификатор (ID)", hint = "обычно логин или имя устройства")
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            IkeAuth.entries.forEach { a -> FilterChip(p.ikeAuth == a, { p = p.copy(ikeAuth = a) }, { Text(a.title) }) }
                        }
                        when (p.ikeAuth) {
                            IkeAuth.PSK -> Field(p.psk, { p = p.copy(psk = it) }, "Общий ключ (PSK)", password = true)
                            IkeAuth.USER_PASS -> {
                                Field(p.user, { p = p.copy(user = it.trim()) }, "Логин")
                                Field(p.pass, { p = p.copy(pass = it) }, "Пароль", password = true)
                            }
                            IkeAuth.CERT -> {
                                OutlinedButton(onClick = { p12File.launch(arrayOf("*/*")) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                    Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(6.dp))
                                    Text(if (p.p12.isBlank()) "Выбрать сертификат .p12 / .pfx" else "Сертификат выбран ✓")
                                }
                                Field(p.p12Pass, { p = p.copy(p12Pass = it) }, "Пароль от .p12", password = true)
                            }
                        }
                        OutlinedButton(onClick = { caFile.launch(arrayOf("*/*")) }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(6.dp))
                            Text(if (p.ca.isBlank()) "Сертификат сервера (CA), если свой" else "CA выбран ✓")
                        }
                        if (p.ca.isNotBlank()) TextButton(onClick = { p = p.copy(ca = "") }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Убрать CA", color = C.muted) }
                    }
                    VpnKind.OPENVPN -> {
                        val installed = HomeVpn.installed(ctx, HomeVpn.OPENVPN_PKG)
                        if (!installed) Text("«OpenVPN for Android» не установлено — NordCam предложит установить при подключении.", color = C.amber, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { loadOvpn() }, enabled = installed) { Text("Выбрать из OpenVPN") }
                            OutlinedButton(onClick = { ovpnFile.launch(arrayOf("*/*")) }) { Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(6.dp)); Text(".ovpn") }
                        }
                        ovpnList?.let { l ->
                            l.forEach { (uuid, name) ->
                                Row(
                                    Modifier.fillMaxWidth().selectable(p.remoteId == uuid) { p = p.copy(remoteId = uuid, remoteName = name, config = "") }.padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(p.remoteId == uuid, { p = p.copy(remoteId = uuid, remoteName = name, config = "") })
                                    Text(name)
                                }
                            }
                        }
                        Text(
                            when {
                                p.remoteId.isNotBlank() -> "Профиль из OpenVPN for Android: ${p.remoteName}"
                                p.config.isNotBlank() -> "Используется .ovpn, импортированный в NordCam (сертификаты внутри файла)"
                                else -> "Профиль не выбран"
                            },
                            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    VpnKind.APP_WIREGUARD -> {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HomeVpn.wgApps.forEach { (pkg, title) ->
                                FilterChip(p.appPackage == pkg, { p = p.copy(appPackage = pkg) }, {
                                    Text(title + if (HomeVpn.installed(ctx, pkg)) "" else " (нет)")
                                })
                            }
                        }
                        Field(p.remoteName, { p = p.copy(remoteName = it) }, "Имя туннеля в приложении", hint = "home")
                    }
                    VpnKind.SYSTEM -> {
                        OutlinedButton(
                            onClick = { try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) } catch (_: Exception) {} },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        ) { Text("Открыть настройки VPN телефона") }
                    }
                }
                if (msg.isNotBlank()) Text(msg, fontSize = 13.sp, color = if (msg.contains("Ошибка") || msg.contains("не ")) C.amber else C.green, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            Section("Когда подключаться") {
                VpnAuto.entries.forEach { a ->
                    val allowed = p.kind != VpnKind.SYSTEM || a == VpnAuto.MANUAL
                    Row(
                        Modifier.fillMaxWidth().selectable(p.auto == a, enabled = allowed) { p = p.copy(auto = a) }.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(p.auto == a, { p = p.copy(auto = a) }, enabled = allowed)
                        Column {
                            Text(a.title, color = if (allowed) C.text else C.muted)
                            Text(a.hint, color = C.muted, fontSize = 11.sp)
                        }
                    }
                }
                if (p.auto == VpnAuto.AWAY) Field(p.probe, { p = p.copy(probe = it.trim()) }, "Адрес для проверки «я дома»", hint = "пусто — первая камера, или 192.168.1.1:80")
                if (p.kind == VpnKind.SYSTEM) Text("VPN телефона NordCam не может включить сам — только напомнить и открыть настройки.", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (save()) nav.back() }) { Text("Сохранить") }
                OutlinedButton(onClick = { if (save()) ctl.connect(p) }) { Text("Сохранить и подключить") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${p.title}»?") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { withContext(Dispatchers.Default) { HomeVpn.disconnect(ctx, p) } }
                    store.saveVpn(store.vpn.value.filterNot { it.id == p.id })
                    confirmDelete = false; nav.back()
                }) { Text("Удалить", color = C.red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}
