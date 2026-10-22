package ru.nordkey.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import ru.nordkey.app.net.*
import ru.nordkey.app.util.shareText

private sealed interface RDialog {
    data class Ask(val action: String) : RDialog
    data class CheckResult(val r: JSONObject) : RDialog
    data class Text(val title: String, val body: String) : RDialog
    data object Share : RDialog
    data class Code(val r: JSONObject) : RDialog
    data object Msg : RDialog
    data object Extend : RDialog
    data class Job(val id: String, val title: String) : RDialog
    data class RemoveAccess(val tg: Long, val name: String) : RDialog
    data object DnsSplit : RDialog
    data object Reboot : RDialog
}

@Composable
fun RouterScreen(ip: String, me: Me, nav: Nav) {
    val load = rememberLoad(ip) { Api.get("/v1/routers/$ip") }
    var hours by remember { mutableIntStateOf(24) }
    val hist = rememberLoad(ip, hours) { Api.get("/v1/routers/$ip/history?hours=$hours") }
    var live by remember { mutableStateOf<JSONObject?>(null) }
    var dialog by remember { mutableStateOf<RDialog?>(null) }
    var busy by remember { mutableStateOf(false) }
    var investigate by remember { mutableStateOf(false) }
    val act = rememberAction()
    val ctx = LocalContext.current

    fun site(action: String, domain: String) = act {
        busy = true
        try {
            val r = Api.post("/v1/routers/$ip/site", "action" to action, "domain" to domain)
            if (action == "check") { dialog = RDialog.CheckResult(r); null }
            else if (!r.optBoolean("ok")) r.optString("error", "Не получилось")
            else {
                val warn = r.optJSONArray("warn")?.let { a -> (0 until a.length()).joinToString("\n") { a.getString(it) } }.orEmpty()
                (if (action == "vpn") "✅ $domain теперь через VPN" else if (action == "direct") "✅ $domain теперь напрямую" else "✅ Убрано") +
                    (if (warn.isNotBlank()) "\n$warn" else "")
            }
        } finally { busy = false }
    }

    Column(Modifier.fillMaxSize()) {
        val j = load.data
        NkTopBar(j?.optString("name") ?: ip, subtitle = ip, onBack = nav::back, onRefresh = { load.reload(); hist.reload() })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = NK.Link)
        Loaded(load) { r ->
            val level = r.optString("level")
            val staff = level == "staff"
            val canWrite = !staff || me.can("routers.act")
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // состояние
                val online = r.optBoolean("online")
                val rtt = r.dbl("rtt")
                val sub = r.optJSONObject("subscription")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("Туннель", if (online) ago(r.optLong("handshake_ago")) else "офлайн", Modifier.weight(1f), ok = online, warn = !online)
                    Tile("Пинг", rtt?.let { "${it.toInt()} мс" } ?: "—", Modifier.weight(1f), warn = rtt != null && rtt > 120)
                    Tile("Устройств", live?.intOrNull("devices")?.toString() ?: "—", Modifier.weight(1f))
                    Tile("Подписка", sub?.let { if (it.optBoolean("active")) dateRu(it.str("expires_at")).take(5) else "нет" } ?: "—",
                         Modifier.weight(1f), warn = sub != null && !sub.optBoolean("active"))
                }
                if (!r.optBoolean("migrated", true)) NkCard(bg = NK.WarnSoft, border = NK.WarnLine) {
                    Text("Роутер ещё на старом сервере — часть функций недоступна, пока его не перенесут.", color = NK.WarnText, fontSize = 14.sp)
                }
                if (live == null) TextButton(onClick = {
                    act { busy = true; try { val l = Api.get("/v1/routers/$ip/live"); if (l.optBoolean("ok")) { live = l; null } else l.optString("error", "Роутер не ответил") } finally { busy = false } }
                }) { Text("Спросить роутер: устройства, загрузка") }
                live?.let { l ->
                    Text("Роутер: загрузка ${l.optInt("cpu")}% · работает ${ago(l.optLong("uptime"))} · устройств в сети ${l.optInt("devices")}",
                         color = NK.Muted, fontSize = 13.sp)
                }

                // график пинга
                NkCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Пинг до сервера", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        listOf(24 to "24 ч", 168 to "7 д", 336 to "14 д").forEach { (h, t) ->
                            FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text(t, fontSize = 12.sp) },
                                       modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    PingChart(hist.data)
                }

                // действия
                SectionTitle("Сайты")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile("Проверить сайт", Icons.Default.TravelExplore, Modifier.weight(1f), primary = true) { dialog = RDialog.Ask("check") }
                    ActionTile("Сайт через VPN", Icons.Default.Lock, Modifier.weight(1f), enabled = canWrite) { dialog = RDialog.Ask("vpn") }
                    ActionTile("Сайт напрямую", Icons.Default.Public, Modifier.weight(1f), enabled = canWrite) { dialog = RDialog.Ask("direct") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile("Мои сайты", Icons.AutoMirrored.Filled.List, Modifier.weight(1f)) {
                        act {
                            busy = true
                            try {
                                val l = Api.get("/v1/routers/$ip/sites")
                                if (!l.optBoolean("ok")) l.optString("error") else {
                                    fun lst(k: String) = l.optJSONArray(k)?.let { a -> (0 until a.length()).joinToString("\n") { "• " + a.getString(it) } }.orEmpty()
                                    dialog = RDialog.Text("Сайты на роутере",
                                        "Через VPN:\n${lst("vpn").ifBlank { "—" }}\n\nНапрямую:\n${lst("direct").ifBlank { "—" }}"); null
                                }
                            } finally { busy = false }
                        }
                    }
                    ActionTile("Починить", Icons.Default.Build, Modifier.weight(1f), primary = true, enabled = canWrite) {
                        act { val x = Api.post("/v1/routers/$ip/fix"); dialog = RDialog.Job(x.getString("job"), "Починка роутера"); null }
                    }
                    if (level == "owner" || (staff && me.can("routers.admin")))
                        ActionTile("Поделиться", Icons.Default.GroupAdd, Modifier.weight(1f)) { dialog = RDialog.Share }
                    else Spacer(Modifier.weight(1f))
                }

                // игровой режим
                r.optJSONObject("game")?.takeIf { it.optBoolean("available") }?.let { g ->
                    var on by remember(g) { mutableStateOf(g.optBoolean("on")) }
                    NkCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Игровой режим", fontWeight = FontWeight.SemiBold)
                                Text("подскажет пустить игровой сервер напрямую, если через VPN высокий пинг", color = NK.Muted, fontSize = 12.sp)
                            }
                            Switch(checked = on, enabled = canWrite, onCheckedChange = { v ->
                                act { val x = Api.post("/v1/routers/$ip/game", "on" to v); on = x.optBoolean("on"); if (on) "Игровой режим включён" else "Выключен" }
                            })
                        }
                    }
                }

                // для сотрудников
                if (staff) {
                    SectionTitle("Для сотрудников")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionTile("Google через VPN", Icons.Default.Language, Modifier.weight(1f), enabled = me.can("routers.admin")) {
                            act { val x = Api.post("/v1/staff/routers/$ip/google"); dialog = RDialog.Job(x.getString("job"), "Сети Google через VPN"); null }
                        }
                        ActionTile("Перезапустить туннель", Icons.Default.RestartAlt, Modifier.weight(1f), risky = true, enabled = me.can("routers.act")) {
                            act { busy = true; try { Api.post("/v1/staff/agent/routers/$ip/restart"); "Туннель перезапускается" } finally { busy = false } }
                        }
                        ActionTile("Лог роутера", Icons.Default.Article, Modifier.weight(1f)) {
                            act { busy = true; try { dialog = RDialog.Text("Лог роутера", pretty(Api.get("/v1/staff/agent/routers/$ip/log"))); null } finally { busy = false } }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionTile("Wi-Fi", Icons.Default.Wifi, Modifier.weight(1f)) {
                            act { busy = true; try { dialog = RDialog.Text("Wi-Fi", pretty(Api.get("/v1/staff/agent/routers/$ip/wifi"))); null } finally { busy = false } }
                        }
                        ActionTile("Написать клиенту", Icons.Default.Send, Modifier.weight(1f), enabled = me.can("msg")) { dialog = RDialog.Msg }
                        ActionTile("Продлить подписку", Icons.Default.EventRepeat, Modifier.weight(1f), enabled = me.can("subs.edit")) { dialog = RDialog.Extend }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionTile("Российский DNS для сайта", Icons.Default.Dns, Modifier.weight(1f), enabled = me.can("routers.act")) { dialog = RDialog.DnsSplit }
                        ActionTile("Перезагрузить роутер", Icons.Default.PowerSettingsNew, Modifier.weight(1f), risky = true,
                                   enabled = me.can("routers.admin")) { dialog = RDialog.Reboot }
                        r.optLong("owner_tg").takeIf { it > 0 }?.let { owner ->
                            ActionTile("Аккаунт клиента", Icons.Default.Person, Modifier.weight(1f)) { nav.go(Scr.Account(owner)) }
                        } ?: Spacer(Modifier.weight(1f))
                    }
                    OutlinedButton(onClick = { investigate = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.ManageSearch, null); Spacer(Modifier.width(6.dp)); Text("Глубокая проверка сайта (автопилот)")
                    }
                    if (me.can("newclient")) OutlinedButton(onClick = {
                        act { val x = Api.post("/v1/staff/claim", "ip" to ip); shareText(ctx, "Ваша персональная ссылка NordKey (пробный период ${x.optInt("trial_hours")} ч): ${x.getString("url")}"); null }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Персональная ссылка на бота для клиента") }
                }

                // кто имеет доступ
                r.optJSONArray("access")?.objects()?.takeIf { it.isNotEmpty() }?.let { acc ->
                    SectionTitle("Доступ к роутеру")
                    NkCard {
                        acc.forEach { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (a.optString("level") == "owner") Icons.Default.Person else Icons.Default.PersonOutline, null, tint = NK.Muted)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.str("name") ?: a.str("username")?.let { "@$it" } ?: "ID ${a.optLong("tg_id")}")
                                    Text(when { a.optBoolean("main") -> "основной владелец"; a.optString("level") == "owner" -> "полный доступ"; else -> "участник" },
                                         color = NK.Muted, fontSize = 12.sp)
                                }
                                if (!a.optBoolean("main")) TextButton(onClick = { dialog = RDialog.RemoveAccess(a.optLong("tg_id"), a.optString("name")) }) {
                                    Text("Убрать", color = NK.Bad)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (investigate) ru.nordkey.app.ui.staff.InvestigateDialog(ip, nav) { investigate = false }
    when (val d = dialog) {
        null -> {}
        is RDialog.Ask -> InputDialog(
            title = mapOf("check" to "Проверить сайт", "vpn" to "Сайт через VPN", "direct" to "Сайт напрямую")[d.action]!!,
            hint = mapOf("check" to "Какой сайт не открывается?", "vpn" to "Этот сайт и все его поддомены пойдут через VPN.",
                         "direct" to "Этот сайт пойдёт мимо VPN, через обычный интернет (например, банки и госуслуги).")[d.action]!!,
            placeholder = "example.com", confirm = if (d.action == "check") "Проверить" else "Готово",
            onDismiss = { dialog = null },
        ) { dom -> dialog = null; site(d.action, dom) }
        is RDialog.CheckResult -> {
            val r = d.r
            val dom = r.optString("domain")
            val text = if (!r.optBoolean("ok")) r.optString("error", "Не получилось проверить")
            else if (!r.optBoolean("resolved")) "Сайт $dom не найден — проверьте адрес."
            else buildString {
                append("Сайт $dom идёт ").append(if (r.optString("via") == "vpn") "через VPN" else "напрямую (без VPN)")
                r.str("how")?.let { append(" — $it") }
                append(".\n")
                val loss = r.intOrNull("loss")
                append(if (loss == null || loss >= 100) "Сайт не отвечает на проверку связи (это не всегда значит, что он не работает)."
                       else "Отвечает: потери $loss%, пинг ${r.dbl("rtt")?.toInt() ?: "—"} мс.")
            }
            InfoDialog("Проверка: $dom", text, onDismiss = { dialog = null }) {
                if (r.optBoolean("resolved")) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.optString("via") != "vpn") Button(onClick = { dialog = null; site("vpn", dom) }) { Text("Через VPN") }
                    if (r.optString("via") == "vpn") OutlinedButton(onClick = { dialog = null; site("direct", dom) }) { Text("Напрямую") }
                }
            }
        }
        is RDialog.Text -> InfoDialog(d.title, "", onDismiss = { dialog = null }) {
            SelectionContainer {
                Text(d.body, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = NK.Text,
                     modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
            }
        }
        RDialog.Share -> AlertDialog(
            onDismissRequest = { dialog = null }, containerColor = NK.Surface,
            title = { Text("Поделиться роутером") },
            text = { Text("Для кого код? Он действует ${if (load.data?.optString("level") == "staff") "сутки" else "10 минут"} и срабатывает один раз.", color = NK.Muted) },
            confirmButton = {
                Column {
                    TextButton(onClick = { act { dialog = RDialog.Code(Api.post("/v1/routers/$ip/share", "level" to "owner")); null } }) { Text("Свой второй аккаунт / полный доступ") }
                    TextButton(onClick = { act { dialog = RDialog.Code(Api.post("/v1/routers/$ip/share", "level" to "member")); null } }) { Text("Член семьи / участник") }
                    TextButton(onClick = { dialog = null }) { Text("Отмена") }
                }
            },
        )
        is RDialog.Code -> InfoDialog("Код доступа", "", onDismiss = { dialog = null; load.reload() }) {
            Text(d.r.optString("code"), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = NK.Link)
            Text("Введите его в приложении NordKey на другом аккаунте («Добавить роутер по коду») или откройте ссылку в Telegram с этого аккаунта.",
                 color = NK.Muted, fontSize = 14.sp)
            Button(onClick = {
                shareText(ctx, "Код доступа к роутеру NordKey: ${d.r.optString("code")}\nИли откройте ссылку в Telegram: ${d.r.optString("bot_url")}")
            }) { Text("Отправить") }
        }
        RDialog.Msg -> InputDialog("Сообщение клиенту", "Придёт от бота NordKey.", multiline = true, confirm = "Отправить",
                                   onDismiss = { dialog = null }) { t ->
            dialog = null; act { Api.post("/v1/staff/msg", "ip" to ip, "text" to t); "Отправлено" }
        }
        RDialog.Extend -> InputDialog("Продлить подписку", "На сколько дней?", "30", "Продлить", onDismiss = { dialog = null }) { t ->
            dialog = null
            act {
                val days = t.toIntOrNull() ?: return@act "Нужно число дней"
                val x = Api.post("/v1/staff/subscription/extend", "ip" to ip, "days" to days); load.reload()
                "Продлено до ${dateRu(x.str("expires_at"))}"
            }
        }
        is RDialog.Job -> JobDialog(d.id, d.title) { dialog = null; load.reload() }
        RDialog.DnsSplit -> InputDialog("Российский DNS для сайта", "Для этого сайта роутер будет спрашивать DNS Яндекса напрямую — сайт отдаст ближние " +
                                        "российские серверы (помогает, когда российский сайт медленный или без картинок, как было с Авито).",
                                        "avito.ru", "Включить", onDismiss = { dialog = null }) { dom ->
            dialog = null
            act { busy = true; try { val x = Api.post("/v1/staff/routers/$ip/dnssplit", "domain" to dom); if (x.optBoolean("ok")) "✅ Готово: $dom через российский DNS" else x.optString("error") } finally { busy = false } }
        }
        RDialog.Reboot -> ConfirmDialog("Перезагрузить роутер?", "Интернет у клиента пропадёт на 1–2 минуты.", "Перезагрузить", onDismiss = { dialog = null }) {
            dialog = null
            act { Api.post("/v1/staff/routers/$ip/reboot"); "Роутер перезагружается" }
        }
        is RDialog.RemoveAccess -> ConfirmDialog("Убрать доступ?", "${d.name.ifBlank { "Этот аккаунт" }} больше не увидит роутер в приложении.",
                                                 "Убрать", onDismiss = { dialog = null }) {
            dialog = null; act { Api.post("/v1/routers/$ip/access/remove", "tg_id" to d.tg); load.reload(); "Доступ убран" }
        }
    }
}

@Composable
fun JobDialog(id: String, title: String, onDone: () -> Unit) {
    var job by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(id) {
        while (true) {
            try { job = Api.get("/v1/jobs/$id") } catch (_: Exception) {}
            if (job?.optString("status") != "running" && job != null) break
            delay(3000)
        }
    }
    val st = job?.optString("status") ?: "running"
    AlertDialog(
        onDismissRequest = { if (st != "running") onDone() }, containerColor = NK.Surface,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (st) {
                    "running" -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                        Text("Сервер работает с роутером, это может занять несколько минут…", color = NK.Muted)
                    }
                    "done" -> Text("✅ Готово", color = NK.OkText, fontWeight = FontWeight.SemiBold)
                    else -> Text("❌ Не получилось", color = NK.Bad, fontWeight = FontWeight.SemiBold)
                }
                job?.str("output")?.let {
                    Text(it.takeLast(1500), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = NK.Muted,
                         modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(if (st == "running") "Свернуть" else "Закрыть") } },
    )
}

@Composable
fun PingChart(data: JSONObject?) {
    val pts = data?.optJSONArray("points").objects().map { it.dbl("rtt") }
    if (pts.isEmpty()) {
        Text("Данных пока нет — сервер собирает их каждые 5 минут.", color = NK.Muted, fontSize = 13.sp)
        return
    }
    val max = (pts.filterNotNull().maxOrNull() ?: 100.0).coerceAtLeast(80.0)
    val avg = pts.filterNotNull().let { if (it.isEmpty()) null else it.sorted()[it.size / 2] }
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        val w = size.width / pts.size
        pts.forEachIndexed { i, v ->
            val h = if (v == null) 3f else (v / max * size.height).toFloat().coerceAtLeast(3f)
            drawRoundRect(
                color = when { v == null -> NK.Off; v > 120 -> NK.Warn; else -> androidx.compose.ui.graphics.Color(0xFF3B6FD4) },
                topLeft = Offset(i * w + w * 0.15f, size.height - h), size = Size(w * 0.7f, h), cornerRadius = CornerRadius(3f, 3f),
            )
        }
    }
    Text("обычно ≈ ${avg?.toInt() ?: "—"} мс · максимум ${pts.filterNotNull().maxOrNull()?.toInt() ?: "—"} мс", color = NK.Muted, fontSize = 12.sp)
}

fun pretty(j: JSONObject): String {
    // Частые форматы агента: {"log": "..."} или {"lines": [...]}; иначе — JSON целиком
    j.str("log")?.let { return it }
    j.optJSONArray("lines")?.let { a -> return (0 until a.length()).joinToString("\n") { a.optString(it) } }
    return j.toString(2)
}
