package ru.nordcam.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.FilterChip
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import ru.nordcam.app.net.Mail
import ru.nordcam.app.net.MailPresets
import ru.nordcam.app.net.MailSecurity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.nordcam.app.BuildConfig
import ru.nordcam.app.Nav
import ru.nordcam.app.Screen
import ru.nordcam.app.app
import ru.nordcam.app.net.Telegram
import ru.nordcam.app.service.MonitorService

private fun batteryIgnored(ctx: Context): Boolean =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

@SuppressLint("BatteryLife")
private fun askBattery(ctx: Context) {
    try {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
    } catch (e: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

@Composable
fun MoreTab(nav: Nav) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val s by store.settings.collectAsState()
    val cams by store.cameras.collectAsState()
    val scope = rememberCoroutineScope()
    var used by remember { mutableLongStateOf(0L) }
    var pinDialog by remember { mutableStateOf(false) }
    var battery by remember { mutableStateOf(batteryIgnored(ctx)) }
    LaunchedEffect(Unit) {
        used = withContext(Dispatchers.IO) { store.recordingsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) toast(ctx, "Без разрешения на уведомления тревоги не будут видны")
    }

    fun setMonitoring(on: Boolean) {
        if (on && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        store.updateSettings { it.copy(monitoring = on) }
        MonitorService.sync(ctx)
        if (on && !batteryIgnored(ctx)) askBattery(ctx)
    }

    val watched = cams.count { it.motion != ru.nordcam.app.data.MotionMode.OFF || it.record == ru.nordcam.app.data.RecordMode.CONTINUOUS }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Bar("Ещё")
        Section("Оформление") { ThemePicker(s.theme, s.accent) { t, a -> store.updateSettings { it.copy(theme = t, accent = a) } } }
        Section("Наблюдение") {
            SwitchRow(
                "Наблюдение в фоне",
                if (watched == 0) "Ни у одной камеры не включены детекция или запись" else "Камер с детекцией или записью: $watched",
                s.monitoring,
            ) { setMonitoring(it) }
            if (s.monitoring && !battery) {
                Row(
                    Modifier.fillMaxWidth().clickable { askBattery(ctx); battery = batteryIgnored(ctx) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.BatteryAlert, null, tint = C.amber)
                    Spacer(Modifier.width(10.dp))
                    Text("Разрешите работу в фоне без ограничений батареи — иначе телефон может «усыпить» наблюдение.", fontSize = 13.sp)
                }
            }
            Num("Тревога не чаще, чем раз в (сек)", s.cooldownSec) { v -> store.updateSettings { it.copy(cooldownSec = v.coerceIn(0, 3600)) } }
        }
        Section("Запись на телефон") {
            Num("Ролик по движению после остановки (сек)", s.clipSeconds) { v -> store.updateSettings { it.copy(clipSeconds = v.coerceIn(5, 600)) } }
            Num("Отрезок постоянной записи (мин)", s.segmentMinutes) { v -> store.updateSettings { it.copy(segmentMinutes = v.coerceIn(1, 60)) } }
            Num("Место под записи (МБ)", s.storageMb) { v -> store.updateSettings { it.copy(storageMb = v.coerceIn(200, 512_000)) } }
            Text(
                "Занято: ${fmtSize(used)}. Когда место закончится, самые старые записи удаляются автоматически.",
                color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        Section("Архив на серверах") {
            NavRow(Icons.Default.Storage, "Серверы записи", "Synology Surveillance Station, Frigate — архив по шкале времени") { nav.go(Screen.Servers) }
        }
        Section("Доступ для других") {
            NavRow(Icons.Default.QrCode2, "Поделиться камерами", "Код или QR с правами и сроком доступа") { nav.go(Screen.Share) }
            HorizontalDivider(color = C.line)
            NavRow(Icons.Default.QrCodeScanner, "Добавить по коду доступа", "Камеры, которыми поделились с вами") { nav.go(Screen.Import()) }
        }
        Section("Уведомления в Telegram") {
            Text(
                "Необязательно. Создайте бота у @BotFather, вставьте токен и свой chat id (узнать — у @userinfobot). Тревоги со снимками будут приходить и туда.",
                color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            var token by remember { mutableStateOf(s.tgToken) }
            var chat by remember { mutableStateOf(s.tgChat) }
            Field(token, { token = it.trim() }, "Токен бота", password = true)
            Field(chat, { chat = it.trim() }, "Chat ID", number = false)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(onClick = {
                    store.updateSettings { it.copy(tgToken = token, tgChat = chat) }
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Telegram.send(token, chat, "✅ NordCam: уведомления подключены", null) }
                        toast(ctx, if (ok) "Сохранено, проверочное сообщение отправлено" else "Сохранено, но отправить не удалось — проверьте токен, chat id и интернет")
                    }
                }) { Text("Сохранить и проверить") }
            }
        }
        Section("Уведомления на почту") { MailSettings() }
        Section("Защита приложения") {
            SwitchRow("PIN-код при входе", if (s.pin.isBlank()) "Выключен" else "Включён", s.pin.isNotBlank()) { on ->
                if (on) pinDialog = true else store.updateSettings { it.copy(pin = "", biometric = false) }
            }
            val act = ctx as? FragmentActivity
            if (s.pin.isNotBlank() && act != null && biometricAvailable(act)) {
                SwitchRow("Вход по отпечатку / лицу", null, s.biometric) { on -> store.updateSettings { it.copy(biometric = on) } }
            }
            if (s.pin.isNotBlank()) {
                Num("Блокировать после сворачивания через (сек)", s.autoRelockSec) { v -> store.updateSettings { it.copy(autoRelockSec = v.coerceIn(0, 3600)) } }
            }
        }
        Text(
            "NordCam ${BuildConfig.VERSION_NAME}. Видео идёт напрямую с камер — без чужих облаков. Пароли камер хранятся на телефоне в зашифрованном виде.",
            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp),
        )
        Spacer(Modifier.height(24.dp))
    }

    if (pinDialog) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinDialog = false },
            title = { Text("Новый PIN-код") },
            text = {
                Column {
                    Field(p1, { p1 = it.filter(Char::isDigit).take(8) }, "PIN (4–8 цифр)", number = true, password = true)
                    Field(p2, { p2 = it.filter(Char::isDigit).take(8) }, "Повторите PIN", number = true, password = true)
                    if (p2.isNotEmpty() && p1 != p2) Text("PIN не совпадает", color = C.red, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = p1.length >= 4 && p1 == p2, onClick = {
                    store.updateSettings { it.copy(pin = p1) }
                    pinDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { pinDialog = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = C.accent2)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, color = C.muted, fontSize = 13.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = C.muted)
    }
}

/** Числовое поле, сохраняется при вводе. */
@Composable
private fun Num(label: String, value: Int, onSave: (Int) -> Unit) {
    var t by remember(label) { mutableStateOf(value.toString()) }
    Field(t, { v ->
        t = v.filter(Char::isDigit).take(6)
        t.toIntOrNull()?.let(onSave)
    }, label, number = true)
}

@Composable
private fun ThemePicker(theme: String, accent: String, onPick: (String, String) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeCard("system", "Как в системе", if (isSystemInDarkTheme()) Themes.DARK else Themes.LIGHT, theme == "system") { onPick("system", accent) }
            Themes.all.forEach { p -> ThemeCard(p.id, p.title, p, theme == p.id) { onPick(p.id, accent) } }
        }
        Text("Цвет акцента", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 8.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Themes.accents.forEach { (id, main, _) ->
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(main)
                        .border(if (id == accent) 3.dp else 0.dp, if (id == accent) C.text else Color.Transparent, CircleShape)
                        .clickable { onPick(theme, id) },
                    contentAlignment = Alignment.Center,
                ) { if (id == accent) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

@Composable
private fun ThemeCard(id: String, title: String, p: Palette, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Column(
            Modifier.size(width = 76.dp, height = 104.dp).clip(RoundedCornerShape(12.dp)).background(p.bg)
                .border(if (selected) 2.dp else 1.dp, if (selected) C.accent else C.line, RoundedCornerShape(12.dp))
                .padding(7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // Мини-превью: сетка камер и нижнее меню
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.weight(1f).height(22.dp).background(p.surface2, RoundedCornerShape(3.dp)))
                Box(Modifier.weight(1f).height(22.dp).background(p.surface2, RoundedCornerShape(3.dp)))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.weight(1f).height(22.dp).background(p.surface2, RoundedCornerShape(3.dp)))
                Box(Modifier.weight(1f).height(22.dp).background(C.accent, RoundedCornerShape(3.dp)))
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(12.dp).background(p.surface, RoundedCornerShape(3.dp)))
        }
        Text(title, fontSize = 12.sp, color = if (selected) C.accent2 else C.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Настройки почты: готовые сервисы или свой почтовый сервер. */
@Composable
private fun MailSettings() {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val scope = rememberCoroutineScope()
    val s = store.settings.value
    val presetOf = { h: String -> MailPresets.all.firstOrNull { it.host == h } }
    var own by remember { mutableStateOf(s.mailHost.isNotBlank() && presetOf(s.mailHost) == null) }
    var mHost by remember { mutableStateOf(s.mailHost) }
    var mPort by remember { mutableStateOf(s.mailPort.toString()) }
    var mSec by remember { mutableStateOf(MailSecurity.of(s.mailSecurity, s.mailPort)) }
    var mUser by remember { mutableStateOf(s.mailUser) }
    var mPass by remember { mutableStateOf(s.mailPass) }
    var mFrom by remember { mutableStateOf(s.mailFrom) }
    var mTo by remember { mutableStateOf(s.mailTo) }
    var mTrust by remember { mutableStateOf(s.mailTrust) }
    var mBusy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    val preset = if (own) null else presetOf(mHost)

    Text(
        "Тревоги со снимком приходят письмом. Почта работает даже там, где Telegram блокируют.",
        color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MailPresets.all.forEach { pr ->
            FilterChip(!own && mHost == pr.host, {
                own = false; mHost = pr.host; mPort = pr.port.toString(); mSec = MailSecurity.SSL; mTrust = false; mFrom = ""
            }, { Text(pr.title) })
        }
        FilterChip(own, {
            if (!own) { own = true; if (presetOf(mHost) != null) mHost = "" }
        }, { Text("Свой сервер") })
    }
    if (preset != null) Text(preset.hint, color = C.amber, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

    if (own) {
        Text(
            "Свой почтовый сервер (Postfix, Exim, Mailcow, iRedMail, Exchange или почта на хостинге). Адрес и порт SMTP — в настройках сервера или у хостинга.",
            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row {
            Field(mHost, { mHost = it.trim() }, "SMTP-сервер", Modifier.weight(2f), hint = "mail.вашдомен.ru")
            Field(mPort, { mPort = it.filter(Char::isDigit).take(5) }, "Порт", Modifier.weight(1f), number = true)
        }
        Text("Шифрование", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MailSecurity.entries.forEach { sec ->
                FilterChip(mSec == sec, {
                    // Порт меняем сам, только если он был стандартным
                    if (mPort.isBlank() || mPort.toIntOrNull()?.let { p -> MailSecurity.entries.any { it.port == p } } == true) mPort = sec.port.toString()
                    mSec = sec
                }, { Text("${sec.title} · ${sec.port}") })
            }
        }
        if (mSec == MailSecurity.NONE) {
            Text("Без шифрования пароль идёт открытым текстом — годится только внутри своей сети или VPN.", color = C.amber, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        Field(mUser, { mUser = it.trim() }, "Логин", hint = "обычно полный адрес ящика; пусто — без входа")
        Field(mPass, { mPass = it }, "Пароль", password = true)
        Field(mFrom, { mFrom = it.trim() }, "Адрес отправителя", hint = "если отличается от логина")
        if (mSec != MailSecurity.NONE) {
            SwitchRow("Доверять сертификату сервера", "Для своего сервера с самоподписанным сертификатом", mTrust) { mTrust = it }
        }
    } else {
        Field(mUser, { mUser = it.trim() }, "Ящик-отправитель", hint = "camera@yandex.ru")
        Field(mPass, { mPass = it.trim() }, "Пароль приложения", password = true)
    }
    Field(mTo, { mTo = it.trim() }, "Кому присылать", hint = "можно несколько через запятую")
    if (result.isNotBlank()) Text(result, color = if (ok) C.green else C.red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !mBusy && mHost.isNotBlank(), onClick = {
            val port = mPort.toIntOrNull() ?: mSec.port
            store.updateSettings {
                it.copy(
                    mailHost = mHost, mailPort = port, mailSecurity = mSec.name, mailUser = mUser, mailPass = mPass,
                    mailFrom = if (own) mFrom else "", mailTo = mTo, mailTrust = own && mTrust,
                )
            }
            mBusy = true; result = ""
            scope.launch {
                val err = withContext(Dispatchers.IO) {
                    Mail.send(store.settings.value.mailConfig(), "NordCam: проверка уведомлений", "Почта подключена — сюда будут приходить тревоги со снимками.", null)
                }
                mBusy = false
                ok = err == null
                result = err ?: "Сохранено, проверочное письмо отправлено — проверьте ящик (и папку «Спам»)."
            }
        }) { Text(if (mBusy) "Отправляю…" else "Сохранить и проверить") }
        if (s.mailReady) TextButton(onClick = {
            store.updateSettings { it.copy(mailHost = "", mailPass = "", mailTo = "") }
            mHost = ""; mPass = ""; mTo = ""; result = ""
        }) { Text("Отключить", color = C.red) }
    }
}
