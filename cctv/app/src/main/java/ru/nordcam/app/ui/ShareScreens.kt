package ru.nordcam.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.nordcam.app.Nav
import ru.nordcam.app.app
import ru.nordcam.app.data.Camera
import ru.nordcam.app.data.Perms
import ru.nordcam.app.share.ShareCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun copy(ctx: Context, t: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NordCam", t))
    toast(ctx, "Скопировано")
}

@Composable
fun ShareScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cams by ctx.app.store.cameras.collectAsState()
    val own = cams.filter { !it.shared }
    val selected = remember { mutableStateListOf<String>() }
    var live by remember { mutableStateOf(true) }
    var archive by remember { mutableStateOf(true) }
    var ptz by remember { mutableStateOf(false) }
    var record by remember { mutableStateOf(true) }
    var days by remember { mutableIntStateOf(0) }
    var password by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Bar("Поделиться камерами", onBack = { nav.back() })
        if (own.isEmpty()) {
            Empty("Нечем делиться", "Сначала добавьте свои камеры."); return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (code.isNotBlank()) {
                Section("Код доступа готов") {
                    val bmp = qr
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(), "QR-код",
                            Modifier.padding(16.dp).fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).padding(8.dp),
                        )
                    } else {
                        Text("Камер слишком много для одного QR — отправьте код текстом.", color = C.amber, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                    }
                    Text(
                        "Получатель открывает NordCam → «Камеры» → значок QR и сканирует этот код (или вставляет текст). Пароль сообщите отдельно — например, звонком.",
                        color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { shareText(ctx, "Доступ к камерам NordCam${if (from.isNotBlank()) " от $from" else ""}:\n${ShareCodec.link(code)}\n\nКод: $code") }) {
                            Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Отправить")
                        }
                        OutlinedButton(onClick = { copy(ctx, code) }) {
                            Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Копировать")
                        }
                    }
                }
            }
            Section("Какие камеры") {
                own.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (c.id in selected) selected.remove(c.id) else selected.add(c.id); code = "" }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(c.id in selected, { if (it) selected.add(c.id) else selected.remove(c.id); code = "" })
                        Column {
                            Text(c.title)
                            if (c.group.isNotBlank()) Text(c.group, color = C.muted, fontSize = 12.sp)
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Выбрать все", color = C.accent2, modifier = Modifier.clickable { selected.clear(); selected.addAll(own.map { it.id }); code = "" }.padding(4.dp))
                }
            }
            Section("Что можно") {
                SwitchRow("Смотреть онлайн", null, live) { live = it; code = "" }
                SwitchRow("Смотреть архив", null, archive) { archive = it; code = "" }
                SwitchRow("Поворачивать камеру (PTZ)", null, ptz) { ptz = it; code = "" }
                SwitchRow("Записывать на свой телефон", null, record) { record = it; code = "" }
                Text("Срок доступа", modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "Бессрочно", 1 to "1 день", 7 to "Неделя", 30 to "Месяц").forEach { (d, t) ->
                        FilterChip(days == d, { days = d; code = "" }, { Text(t) })
                    }
                }
            }
            Section("Защита кода") {
                Field(password, { password = it; code = "" }, "Пароль для получателя (от 4 символов)", password = true)
                Field(from, { from = it }, "Ваше имя (увидит получатель)", hint = "необязательно")
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Warning, null, tint = C.amber, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Получатель подключается к камерам напрямую, поэтому логин и пароль камеры попадают в код в зашифрованном виде. Права и срок соблюдает приложение, но надёжнее всего — завести на камере или регистраторе отдельного пользователя только с просмотром и делиться им.",
                        color = C.muted, fontSize = 12.sp,
                    )
                }
            }
            Button(
                enabled = selected.isNotEmpty() && password.length >= 4 && !busy,
                modifier = Modifier.padding(16.dp),
                onClick = {
                    busy = true
                    val list = own.filter { it.id in selected }
                    val perms = Perms(
                        live = live, archive = archive, ptz = ptz, record = record,
                        until = if (days == 0) 0 else System.currentTimeMillis() + days * 86_400_000L,
                    )
                    scope.launch {
                        val (c, q) = withContext(Dispatchers.Default) {
                            val c = ShareCodec.encode(list, perms, password, from.trim())
                            c to ShareCodec.qr(ShareCodec.link(c))
                        }
                        code = c; qr = q; busy = false
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Создать код доступа (${selected.size})")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ImportScreen(nav: Nav, initialCode: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf(initialCode) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ShareCodec.Decoded?>(null) }
    val scan = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { t -> ShareCodec.extract(t)?.let { code = it; error = "" } ?: run { error = "Этот QR-код не от NordCam" } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Bar("Добавить по коду", onBack = { nav.back() })
        val res = result
        if (res != null) {
            Section("Вам открыт доступ${if (res.from.isNotBlank()) " от ${res.from}" else ""}") {
                res.cams.forEach { c -> CamPermRow(c) }
            }
            Button(onClick = {
                ctx.app.store.upsertAll(res.cams)
                toast(ctx, "Добавлено камер: ${res.cams.size}")
                nav.home("live")
            }, modifier = Modifier.padding(16.dp)) { Text("Добавить ${res.cams.size}") }
            return@Column
        }
        Text(
            "Отсканируйте QR-код, который вам прислали, или вставьте код текстом. Затем введите пароль, который вам сообщили.",
            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
        )
        OutlinedButton(onClick = {
            scan.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Наведите на QR-код NordCam")
                    .setBeepEnabled(false).setOrientationLocked(false),
            )
        }, modifier = Modifier.padding(horizontal = 16.dp)) {
            Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Сканировать QR")
        }
        Field(code, { code = it; error = "" }, "Код доступа", singleLine = false, hint = "NC1.…")
        Field(password, { password = it; error = "" }, "Пароль", password = true)
        if (error.isNotBlank()) Text(error, color = C.red, modifier = Modifier.padding(horizontal = 16.dp))
        Button(
            enabled = code.isNotBlank() && password.isNotBlank() && !busy,
            modifier = Modifier.padding(16.dp),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        result = withContext(Dispatchers.Default) { ShareCodec.decode(code, password) }
                        if (result?.cams.isNullOrEmpty()) { error = "В коде нет камер"; result = null }
                    } catch (e: Exception) {
                        error = e.message ?: "Не удалось прочитать код"
                    }
                    busy = false
                }
            },
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Открыть")
        }
    }
}

@Composable
private fun CamPermRow(c: Camera) {
    val p = c.perms
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(c.title, fontWeight = FontWeight.Medium)
        Text(
            listOfNotNull(
                if (p.live) "онлайн" else null,
                if (p.archive) "архив" else null,
                if (p.ptz) "поворот" else null,
                if (p.record) "запись" else null,
            ).joinToString(", ") +
                if (p.until > 0) " · до ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(p.until))}" else " · бессрочно",
            color = C.muted, fontSize = 13.sp,
        )
    }
}
