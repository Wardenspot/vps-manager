package ru.nordcam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.nordcam.app.Nav
import ru.nordcam.app.app
import ru.nordcam.app.net.Ups
import ru.nordcam.app.net.UpsDevice
import ru.nordcam.app.net.UpsStatus
import ru.nordcam.app.net.UpsType

private fun upsColor(s: UpsStatus?): Color = when {
    s == null -> C.muted
    !s.ok || s.lowBattery || s.overload -> C.red
    s.onBattery || s.replaceBattery -> C.amber
    else -> C.green
}

private fun upsIcon(s: UpsStatus?): ImageVector = when {
    s == null || !s.ok -> Icons.Default.PowerOff
    s.onBattery || s.lowBattery || s.replaceBattery -> Icons.Default.BatteryAlert
    s.charging -> Icons.Default.BatteryChargingFull
    else -> Icons.Default.BatteryFull
}

fun fmtRuntime(sec: Int): String = when {
    sec < 0 -> "—"
    sec >= 3600 -> "${sec / 3600} ч ${sec / 60 % 60} мин"
    else -> "${sec / 60} мин"
}

/**
 * Полоса внизу главного экрана: заряд, нагрузка и питание каждого ИБП.
 * Пока экран открыт, опрашивает ИБП каждые 15 секунд.
 */
@Composable
fun UpsBar(onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val list by store.ups.collectAsState()
    val status by store.upsStatus.collectAsState()
    val visible = rememberVisible()
    if (list.isEmpty()) return
    LaunchedEffect(list, visible) {
        while (visible) {
            val res = withContext(Dispatchers.IO) { list.associate { it.id to Ups.query(it) } }
            store.upsStatus.value = store.upsStatus.value + res
            delay(15_000)
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        list.forEach { d ->
            val s = status[d.id]
            Row(
                Modifier.background(C.surface, RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(upsIcon(s), null, tint = upsColor(s), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    buildString {
                        append(d.title)
                        if (s == null) append(" · …")
                        else if (!s.ok) append(" · нет связи")
                        else {
                            if (s.charge >= 0) append(" · ${s.charge}%")
                            if (s.load >= 0) append(" · нагрузка ${s.load}%")
                            append(" · ${s.stateText}")
                            if (s.onBattery && s.runtimeSec >= 0) append(" · ~${fmtRuntime(s.runtimeSec)}")
                        }
                    },
                    fontSize = 12.sp, color = if (s?.alarm == true) upsColor(s) else C.text, maxLines = 1,
                )
            }
        }
    }
}

/** Настройка ИБП: список, добавление, проверка. */
@Composable
fun UpsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val list by store.ups.collectAsState()
    val status by store.upsStatus.collectAsState()
    var edit by remember { mutableStateOf<UpsDevice?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(list) {
        while (true) {
            val res = withContext(Dispatchers.IO) { list.associate { it.id to Ups.query(it) } }
            store.upsStatus.value = store.upsStatus.value + res
            delay(10_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Bar("ИБП (бесперебойники)", onBack = { nav.back() }) {
            IconButton(onClick = { edit = UpsDevice() }) { Icon(Icons.Default.Add, "Добавить ИБП") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "Заряд батареи, нагрузка и питание ИБП видны внизу экрана «Просмотр». Когда ИБП переходит на батарею или она садится, " +
                    "приходит тревога — пуш, Telegram и почта (нужно включённое «Наблюдение в фоне»).",
                color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
            )
            if (list.isEmpty()) {
                Empty("ИБП не добавлен", "Подойдёт APC с сетевой картой (напрямую по IP), APC по USB к Synology или компьютеру с apcupsd, и любой ИБП с NUT.") {
                    Button(onClick = { edit = UpsDevice() }) { Text("Добавить ИБП") }
                }
            }
            list.forEach { d ->
                val s = status[d.id]
                Section(d.title) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(upsIcon(s), null, tint = upsColor(s), modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s?.stateText?.replaceFirstChar { it.uppercase() } ?: "Опрашиваю…", fontWeight = FontWeight.SemiBold, color = upsColor(s))
                            Text(listOf(s?.model.orEmpty(), "${d.type.title} · ${d.host}:${d.port}").filter { it.isNotBlank() }.joinToString("\n"), color = C.muted, fontSize = 12.sp)
                        }
                    }
                    if (s != null && s.ok) {
                        Meter("Заряд батареи", s.charge)
                        Meter("Нагрузка", s.load)
                        Text(
                            buildString {
                                append("Время работы от батареи: ${fmtRuntime(s.runtimeSec)}")
                                if (s.inputVolt > 0) append("\nНапряжение в сети: ${s.inputVolt.toInt()} В")
                                if (s.replaceBattery) append("\nИБП просит заменить батарею")
                            },
                            color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    } else if (s != null) {
                        Text(s.error, color = C.red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { edit = d }) { Text("Изменить") }
                        TextButton(onClick = {
                            store.saveUps(list.filterNot { it.id == d.id })
                            store.upsStatus.value = store.upsStatus.value - d.id
                        }) { Icon(Icons.Default.Delete, null, tint = C.muted); Text("Удалить", color = C.muted) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    edit?.let { d0 ->
        var d by remember(d0.id) { mutableStateOf(d0) }
        var busy by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(if (list.any { it.id == d.id }) "ИБП" else "Новый ИБП") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        UpsType.entries.forEach { t ->
                            FilterChip(d.type == t, { d = d.copy(type = t, port = t.port) }, { Text(t.title) })
                        }
                    }
                    Text(d.type.hint, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                    Field(d.name, { d = d.copy(name = it) }, "Название", hint = "ИБП серверная", modifier = Modifier.padding(0.dp))
                    Field(d.host, { d = d.copy(host = it.trim()) }, if (d.type == UpsType.SNMP) "IP сетевой карты ИБП" else "Адрес (IP NAS или компьютера)", hint = "192.168.1.10")
                    Field(d.port.toString(), { d = d.copy(port = it.filter(Char::isDigit).toIntOrNull() ?: d.type.port) }, "Порт", number = true)
                    if (d.type == UpsType.SNMP) {
                        Field(d.community, { d = d.copy(community = it.trim()) }, "SNMP community (пароль на чтение)", hint = "public")
                    }
                    if (d.type == UpsType.NUT) {
                        Field(d.upsName, { d = d.copy(upsName = it.trim()) }, "Имя ИБП на сервере", hint = "ups")
                        Field(d.user, { d = d.copy(user = it.trim()) }, "Логин (необязательно)", hint = "monuser")
                        Field(d.pass, { d = d.copy(pass = it) }, "Пароль", password = true)
                    }
                    if (busy) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Проверяю…", color = C.muted)
                    }
                    if (result.isNotBlank()) Text(result, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = d.host.isNotBlank() && !busy, onClick = {
                    busy = true; result = ""
                    scope.launch {
                        val s = withContext(Dispatchers.IO) { Ups.query(d) }
                        busy = false
                        if (s.ok) {
                            store.saveUps(list.filterNot { it.id == d.id } + d)
                            store.upsStatus.value = store.upsStatus.value + (d.id to s)
                            edit = null
                            toast(ctx, "ИБП подключён: ${s.charge}%, ${s.stateText}")
                        } else {
                            result = s.error
                        }
                    }
                }) { Text("Проверить и сохранить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (d.host.isNotBlank()) store.saveUps(list.filterNot { it.id == d.id } + d)
                    edit = null
                }) { Text("Сохранить без проверки") }
            },
        )
    }
}

@Composable
private fun Meter(label: String, v: Int) {
    if (v < 0) return
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row {
            Text(label, color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("$v%", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        LinearProgressIndicator(
            progress = { v.coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp),
            color = if (label.startsWith("Нагрузка")) (if (v > 85) C.red else C.accent) else (if (v < 30) C.red else C.green),
            trackColor = C.surface2,
        )
    }
}
