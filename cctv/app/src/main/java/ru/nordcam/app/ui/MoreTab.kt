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
