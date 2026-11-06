package ru.nordcam.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Garage
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.nordcam.app.Nav
import ru.nordcam.app.Screen
import ru.nordcam.app.app
import ru.nordcam.app.data.HomeItem
import ru.nordcam.app.data.Store
import ru.nordcam.app.net.HaEntity
import ru.nordcam.app.net.HttpAction

// ============================================================== данные и действия

/** Опрос Home Assistant раз в 5 секунд, пока экран виден. */
@Composable
fun HaRefresher(force: Int = 0) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val s by store.settings.collectAsState()
    val visible = rememberVisible()
    LaunchedEffect(s.haUrl, s.haToken, s.haTrust, visible, force) {
        val ha = store.ha() ?: return@LaunchedEffect
        if (store.haAreas.value.isEmpty()) store.haAreas.value = withContext(Dispatchers.IO) { ha.areas() }
        while (visible) {
            try {
                val list = withContext(Dispatchers.IO) { ha.states() }
                store.haStates.value = list.associateBy { it.id }
            } catch (_: Exception) {
            }
            delay(5_000)
        }
    }
}

fun itemName(i: HomeItem, e: HaEntity?): String = i.name.ifBlank { e?.name ?: i.entityId.ifBlank { "Кнопка" } }

fun itemRoom(i: HomeItem, areas: Map<String, String>): String =
    i.room.ifBlank { if (i.kind == "http") "Кнопки" else areas[i.entityId] ?: "Без комнаты" }

/** Вызов службы Home Assistant с обновлением состояния. Возвращает текст ошибки или null. */
private suspend fun haCall(store: Store, domain: String, service: String, data: JSONObject): String? {
    val ha = store.ha() ?: return "Home Assistant не подключён"
    return withContext(Dispatchers.IO) {
        try {
            ha.call(domain, service, data)
            delay(700)
            try { store.haStates.value = ha.states().associateBy { it.id } } catch (_: Exception) {}
            null
        } catch (e: Exception) {
            e.message ?: "Ошибка"
        }
    }
}

private fun icon(i: HomeItem, e: HaEntity?): ImageVector {
    if (i.kind == "http") return Icons.Default.SmartButton
    val d = e?.domain ?: i.entityId.substringBefore('.')
    val dc = e?.deviceClass.orEmpty()
    return when (d) {
        "climate", "water_heater" -> Icons.Default.Thermostat
        "cover" -> if (dc == "door") Icons.Default.DoorFront else Icons.Default.Garage
        "lock" -> if (e?.state == "unlocked") Icons.Default.LockOpen else Icons.Default.Lock
        "light" -> Icons.Default.Lightbulb
        "fan" -> Icons.Default.Air
        "alarm_control_panel" -> Icons.Default.Security
        "script", "scene", "automation" -> Icons.Default.PlayArrow
        "button", "input_button" -> Icons.Default.SmartButton
        "binary_sensor" -> when (dc) {
            "moisture" -> Icons.Default.WaterDrop
            "smoke", "gas", "heat" -> Icons.Default.LocalFireDepartment
            "door", "window", "opening", "garage_door" -> Icons.Default.DoorFront
            else -> Icons.Default.Sensors
        }
        "sensor" -> if (dc == "temperature") Icons.Default.Thermostat else if (dc == "power" || dc == "energy") Icons.Default.Bolt else Icons.Default.Sensors
        else -> Icons.Default.ToggleOn
    }
}

/** Состояние словами по-русски. */
fun stateText(e: HaEntity?): String {
    if (e == null) return "нет данных"
    if (e.state == "unavailable") return "недоступно"
    if (e.state == "unknown") return "неизвестно"
    val on = e.state == "on"
    return when (e.domain) {
        "binary_sensor" -> when (e.deviceClass) {
            "door", "window", "opening", "garage_door" -> if (on) "открыто" else "закрыто"
            "moisture" -> if (on) "ПРОТЕЧКА!" else "сухо"
            "smoke" -> if (on) "ДЫМ!" else "норма"
            "gas" -> if (on) "ГАЗ!" else "норма"
            "motion", "occupancy", "presence" -> if (on) "движение" else "нет движения"
            "problem" -> if (on) "проблема" else "норма"
            "connectivity" -> if (on) "в сети" else "нет связи"
            "battery" -> if (on) "батарея садится" else "норма"
            "lock" -> if (on) "открыт" else "закрыт"
            else -> if (on) "вкл" else "выкл"
        }
        "cover", "valve" -> when (e.state) {
            "open" -> "открыто"; "closed" -> "закрыто"; "opening" -> "открывается…"; "closing" -> "закрывается…"; else -> e.state
        }
        "lock" -> when (e.state) {
            "locked" -> "закрыт"; "unlocked" -> "открыт"; "locking" -> "закрывается…"; "unlocking" -> "открывается…"; "jammed" -> "заклинило!"; else -> e.state
        }
        "alarm_control_panel" -> when (e.state) {
            "disarmed" -> "снято с охраны"; "armed_home" -> "охрана (дома)"; "armed_away" -> "охрана"; "armed_night" -> "охрана (ночь)"
            "arming" -> "ставится на охрану…"; "pending" -> "задержка…"; "triggered" -> "ТРЕВОГА!"; else -> e.state
        }
        "climate" -> {
            val cur = e.attrs.optDouble("current_temperature", Double.NaN)
            val mode = when (e.state) { "heat" -> "нагрев"; "cool" -> "охлаждение"; "auto" -> "авто"; "off" -> "выкл"; "heat_cool" -> "авто"; "dry" -> "осушение"; "fan_only" -> "вентиляция"; else -> e.state }
            (if (!cur.isNaN()) "%.1f°  ·  ".format(cur) else "") + mode
        }
        "water_heater" -> {
            val cur = e.attrs.optDouble("current_temperature", Double.NaN)
            (if (!cur.isNaN()) "%.0f°  ·  ".format(cur) else "") + e.state
        }
        "sensor", "number", "input_number" -> {
            val v = e.state.toDoubleOrNull()
            val num = if (v != null) (if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)) else e.state
            "$num ${e.unit}".trim()
        }
        "switch", "light", "fan", "input_boolean", "siren", "automation", "humidifier" -> if (on) "вкл" else "выкл"
        "button", "input_button", "script", "scene" -> "нажать"
        else -> e.state
    }
}

private fun isAlarmState(e: HaEntity?): Boolean = e != null && (
    (e.domain == "binary_sensor" && e.state == "on" && e.deviceClass in setOf("moisture", "smoke", "gas", "heat", "problem", "safety", "tamper")) ||
        (e.domain == "alarm_control_panel" && e.state == "triggered") || (e.domain == "lock" && e.state == "jammed")
    )

// ============================================================== вкладка «Дом»

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeTab(nav: Nav) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val items by store.home.collectAsState()
    val states by store.haStates.collectAsState()
    val areas by store.haAreas.collectAsState()
    val s by store.settings.collectAsState()
    var force by remember { mutableIntStateOf(0) }
    HaRefresher(force)
    val screen = rememberWinSize()
    val cardW: Dp = if (screen.wide) 220.dp else ((screen.widthDp - 40) / 2).dp

    Column(Modifier.fillMaxSize()) {
        Bar("Дом") {
            VpnIndicator(nav)
            IconButton(onClick = { force++ }) { Icon(Icons.Default.Refresh, "Обновить") }
            if (s.haReady) IconButton(onClick = { nav.go(Screen.HaWeb) }) { Icon(Icons.Default.Language, "Home Assistant целиком") }
            IconButton(onClick = { nav.go(Screen.HomeSetup) }) { Icon(Icons.Default.Edit, "Настроить") }
        }
        if (items.isEmpty()) {
            Empty(
                "Умный дом",
                "Отопление, ворота, домофон, свет, датчики и охрана — рядом с камерами. Подключите Home Assistant или добавьте свои кнопки (Shelly, Sonoff, реле).",
            ) { Button(onClick = { nav.go(Screen.HomeSetup) }) { Text("Настроить") } }
            UpsBar { nav.go(Screen.Ups) }
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            items.groupBy { itemRoom(it, areas) }.forEach { (room, list) ->
                Text(room.uppercase(), color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { i -> HomeCard(i, states[i.entityId], Modifier.width(cardW)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        UpsBar { nav.go(Screen.Ups) }
    }
}

/** Карточка устройства: иконка, название, состояние и кнопки под тип устройства. */
@Composable
fun HomeCard(i: HomeItem, e: HaEntity?, modifier: Modifier = Modifier) {
    val alarm = isAlarmState(e)
    val active = e != null && e.state in setOf("on", "open", "unlocked", "heat", "auto", "heat_cool", "cool", "armed_away", "armed_home", "armed_night", "triggered")
    Card(
        modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (alarm) C.red.copy(alpha = 0.18f) else C.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).background(if (alarm) C.red else if (active) C.accent.copy(alpha = 0.25f) else C.surface2, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon(i, e), null, tint = if (alarm) Color.White else if (active) C.accent2 else C.muted, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(itemName(i, e), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (i.kind == "ha") Text(stateText(e), fontSize = 12.sp, color = if (alarm) C.red else C.muted, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            HomeControls(i, e)
        }
    }
}

/** Кнопки управления под тип устройства. Используется и на вкладке «Дом», и на экране камеры. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeControls(i: HomeItem, e: HaEntity?) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var codeFor by remember { mutableStateOf<String?>(null) }

    fun act(domain: String, service: String, extra: JSONObject = JSONObject()) {
        busy = true
        scope.launch {
            val err = haCall(store, domain, service, extra.put("entity_id", i.entityId))
            busy = false
            if (err != null) toast(ctx, err)
        }
    }
    fun guarded(question: String, f: () -> Unit) { if (i.confirm) ask = question to f else f() }

    if (i.kind == "http") {
        fun http(off: Boolean) {
            busy = true
            scope.launch {
                val err = withContext(Dispatchers.IO) { HttpAction.run(i, off) }
                busy = false
                toast(ctx, err ?: "Готово")
            }
        }
        if (i.urlOff.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(enabled = !busy, onClick = { guarded("Включить «${itemName(i, null)}»?") { http(false) } }, modifier = Modifier.weight(1f)) { Text("Вкл") }
            OutlinedButton(enabled = !busy, onClick = { guarded("Выключить «${itemName(i, null)}»?") { http(true) } }, modifier = Modifier.weight(1f)) { Text("Выкл") }
        } else FilledTonalButton(enabled = !busy, onClick = { guarded("${itemName(i, null)}?") { http(false) } }, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "…" else "Выполнить")
        }
    } else if (e != null && !e.unavailable) {
        val d = e.domain
        when (d) {
            "light" -> LightControls(e, busy) { service, data -> act(d, service, data) }
            "switch", "fan", "input_boolean", "siren", "automation", "humidifier" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Switch(checked = e.state == "on", enabled = !busy, onCheckedChange = { on ->
                    guarded(if (on) "Включить «${itemName(i, e)}»?" else "Выключить «${itemName(i, e)}»?") { act(d, if (on) "turn_on" else "turn_off") }
                })
            }
            "button", "input_button" -> FilledTonalButton(enabled = !busy, onClick = { guarded("Нажать «${itemName(i, e)}»?") { act(d, "press") } }, modifier = Modifier.fillMaxWidth()) { Text("Нажать") }
            "script", "scene" -> FilledTonalButton(enabled = !busy, onClick = { guarded("Запустить «${itemName(i, e)}»?") { act(d, "turn_on") } }, modifier = Modifier.fillMaxWidth()) { Text("Запустить") }
            "cover", "valve" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val p = if (d == "valve") "_valve" else "_cover"
                FilledTonalButton(onClick = { guarded("Открыть «${itemName(i, e)}»?") { act(d, "open$p") } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Откр.", fontSize = 12.sp) }
                OutlinedButton(onClick = { act(d, "stop$p") }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Стоп", fontSize = 12.sp) }
                FilledTonalButton(onClick = { guarded("Закрыть «${itemName(i, e)}»?") { act(d, "close$p") } }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Закр.", fontSize = 12.sp) }
            }
            "lock" -> FilledTonalButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                if (e.state == "locked") ask = "Открыть замок «${itemName(i, e)}»?" to { act(d, "unlock") } else act(d, "lock")
            }) { Text(if (e.state == "locked") "Открыть" else "Закрыть") }
            "climate", "water_heater" -> {
                val target = e.attrs.optDouble("temperature", Double.NaN)
                val step = e.attrs.optDouble("target_temp_step", if (d == "water_heater") 1.0 else 0.5)
                val min = e.attrs.optDouble("min_temp", 5.0)
                val max = e.attrs.optDouble("max_temp", 35.0)
                if (!target.isNaN()) Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { act(d, "set_temperature", JSONObject().put("temperature", (target - step).coerceAtLeast(min))) }, enabled = !busy) { Text("−") }
                    Text("%.1f°".format(target), fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(horizontal = 6.dp), maxLines = 1)
                    OutlinedButton(onClick = { act(d, "set_temperature", JSONObject().put("temperature", (target + step).coerceAtMost(max))) }, enabled = !busy) { Text("+") }
                }
                val modes = e.attrs.optJSONArray("hvac_modes") ?: e.attrs.optJSONArray("operation_list") ?: JSONArray()
                if (modes.length() > 1) {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { open = true }) { Text("Режим: ${stateText(e).substringAfter("·").trim()}", fontSize = 12.sp) }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            for (k in 0 until modes.length()) {
                                val m = modes.optString(k)
                                DropdownMenuItem(text = { Text(stateText(HaEntity(e.id, m, e.attrs)).substringAfter("·").trim()) }, onClick = {
                                    open = false
                                    if (d == "climate") act(d, "set_hvac_mode", JSONObject().put("hvac_mode", m))
                                    else act(d, "set_operation_mode", JSONObject().put("operation_mode", m))
                                })
                            }
                        }
                    }
                }
            }
            "alarm_control_panel" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val needCode = !e.attrs.isNull("code_format") && e.attrs.optString("code_format").isNotBlank()
                fun arm(service: String) { if (needCode) codeFor = service else guarded("Выполнить?") { act(d, service) } }
                if (e.state == "disarmed") {
                    FilledTonalButton(onClick = { arm("alarm_arm_away") }, enabled = !busy) { Text("Охрана", fontSize = 12.sp) }
                    OutlinedButton(onClick = { arm("alarm_arm_home") }, enabled = !busy) { Text("Дома", fontSize = 12.sp) }
                } else {
                    FilledTonalButton(onClick = { arm("alarm_disarm") }, enabled = !busy) { Text("Снять", fontSize = 12.sp) }
                }
            }
            "number", "input_number" -> {
                val v = e.state.toDoubleOrNull()
                val step = e.attrs.optDouble("step", 1.0)
                if (v != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { act(d, "set_value", JSONObject().put("value", (v - step).coerceAtLeast(e.attrs.optDouble("min", v - step)))) }, enabled = !busy) { Text("−") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { act(d, "set_value", JSONObject().put("value", (v + step).coerceAtMost(e.attrs.optDouble("max", v + step)))) }, enabled = !busy) { Text("+") }
                }
            }
            "select", "input_select" -> {
                val opts = e.attrs.optJSONArray("options") ?: JSONArray()
                var open by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(e.state, maxLines = 1) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (k in 0 until opts.length()) {
                            val o = opts.optString(k)
                            DropdownMenuItem(text = { Text(o) }, onClick = { open = false; act(d, "select_option", JSONObject().put("option", o)) })
                        }
                    }
                }
            }
            else -> {}
        }
    }

    ask?.let { (q, f) ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(q) },
            confirmButton = { TextButton(onClick = { ask = null; f() }) { Text("Да") } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Отмена") } },
        )
    }
    codeFor?.let { service ->
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { codeFor = null },
            title = { Text("Код охраны") },
            text = { Field(code, { code = it }, "Код", password = true) },
            confirmButton = { TextButton(onClick = { codeFor = null; act("alarm_control_panel", service, JSONObject().put("code", code)) }) { Text("ОК") } },
            dismissButton = { TextButton(onClick = { codeFor = null }) { Text("Отмена") } },
        )
    }
}

/** Кнопки умного дома, привязанные к камере, — на экране камеры (открыть ворота, калитку…). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraHomeActions(camId: String) {
    val ctx = LocalContext.current
    val store = ctx.app.store
    val items by store.home.collectAsState()
    val states by store.haStates.collectAsState()
    val mine = items.filter { it.cameraId == camId }
    if (mine.isEmpty()) return
    HaRefresher()
    Section("Управление") {
        mine.forEach { i ->
            val e = states[i.entityId]
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon(i, e), null, tint = C.accent2, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(itemName(i, e), fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    if (i.kind == "ha") Text(stateText(e), color = C.muted, fontSize = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                HomeControls(i, e)
            }
        }
    }
}

/** Есть ли что показать на вкладке «Дом» — тогда она появляется в нижнем меню. */
fun homeEnabled(ctx: Context): Boolean = ctx.app.store.home.value.isNotEmpty() || ctx.app.store.settings.value.haReady

/** Свет: вкл/выкл, яркость, цвет и тёплый/холодный белый — что умеет лампа. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LightControls(e: HaEntity, busy: Boolean, call: (String, JSONObject) -> Unit) {
    val modes = e.attrs.optJSONArray("supported_color_modes")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
    val on = e.state == "on"
    val dimmable = modes.any { it != "onoff" } || e.attrs.has("brightness")
    val color = modes.any { it in setOf("hs", "xy", "rgb", "rgbw", "rgbww") }
    val temp = modes.contains("color_temp") || modes.any { it in setOf("rgbww") }
    val bri = if (on) e.attrs.optInt("brightness", 255) else 0
    var pos by remember(e.state, bri) { androidx.compose.runtime.mutableFloatStateOf(bri / 255f) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (on && dimmable) "${(bri * 100 / 255)}%" else "", color = C.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Switch(checked = on, enabled = !busy, onCheckedChange = { v -> call(if (v) "turn_on" else "turn_off", JSONObject()) })
        }
        if (dimmable) {
            androidx.compose.material3.Slider(
                value = pos, onValueChange = { pos = it },
                onValueChangeFinished = {
                    val pct = (pos * 100).toInt()
                    if (pct <= 0) call("turn_off", JSONObject()) else call("turn_on", JSONObject().put("brightness_pct", pct.coerceIn(1, 100)))
                },
                enabled = !busy,
            )
        }
        if (temp || color) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (temp) {
                listOf(Triple("Тёплый", 2700, Color(0xFFFFB46B)), Triple("Нейтр.", 4000, Color(0xFFFFE4C4)), Triple("Холодный", 6500, Color(0xFFDDE8FF))).forEach { (t, k, c) ->
                    Swatch(c, t, busy) { call("turn_on", JSONObject().put("color_temp_kelvin", k)) }
                }
            }
            if (color) {
                listOf(
                    Color(0xFFFF3B30) to intArrayOf(255, 40, 30), Color(0xFFFF9500) to intArrayOf(255, 140, 0),
                    Color(0xFFFFE600) to intArrayOf(255, 230, 0), Color(0xFF34C759) to intArrayOf(40, 220, 80),
                    Color(0xFF00C7FF) to intArrayOf(0, 200, 255), Color(0xFF2F6BFF) to intArrayOf(40, 90, 255),
                    Color(0xFFAF52DE) to intArrayOf(175, 80, 222), Color(0xFFFF2D92) to intArrayOf(255, 45, 146),
                ).forEach { (c, rgb) ->
                    Swatch(c, null, busy) { call("turn_on", JSONObject().put("rgb_color", org.json.JSONArray().put(rgb[0]).put(rgb[1]).put(rgb[2]))) }
                }
            }
        }
    }
}

@Composable
private fun Swatch(c: Color, label: String?, busy: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(28.dp).then(if (label == null) Modifier.width(28.dp) else Modifier)
            .background(c, RoundedCornerShape(14.dp))
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = if (label == null) 0.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) Text(label, fontSize = 11.sp, color = Color(0xFF1B1F24))
    }
}
