package ru.nordkey.app.ui.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import ru.nordkey.app.net.*
import ru.nordkey.app.ui.*
import ru.nordkey.app.util.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun time(ts: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ts * 1000))

// ───────── главная ─────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(me: Me, nav: Nav) {
    val load = rememberLoad { Api.get("/v1/staff/dashboard") }
    Column(Modifier.fillMaxSize()) {
        NkTopBar("NordKey", "srv1 · Стокгольм · AmneziaWG", onRefresh = load.reload)
        Loaded(load) { d ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val s = d.getJSONObject("server")
                val services = d.optJSONObject("services") ?: JSONObject()
                val down = services.keys().asSequence().filter { services.optString(it) != "active" }.toList()
                NkCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(if (down.isEmpty()) NK.Ok else NK.Warn)
                        Spacer(Modifier.width(8.dp))
                        Text(if (down.isEmpty()) "Сервер в норме" else "Есть проблемы", fontWeight = FontWeight.SemiBold)
                    }
                    Row {
                        listOf("CPU" to "${s.optDouble("cpu").toInt()}%", "Память" to "${s.optDouble("ram_pct").toInt()}%",
                               "Трафик" to (s.dbl("net_tx_rate")?.let { "${(it * 8 / 1_000_000).toInt()} Мб/с" } ?: "—"),
                               "Работает" to ago(s.optLong("uptime"))).forEach { (k, v) ->
                            Column(Modifier.weight(1f)) {
                                Text(k, color = NK.Muted, fontSize = 12.sp)
                                Text(v, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        services.keys().asSequence().forEach { k ->
                            val ok = services.optString(k) == "active"
                            Chip(k, if (ok) NK.OkText else NK.WarnText, if (ok) NK.OkSoft else NK.WarnSoft)
                        }
                        val warp = d.optJSONObject("warp")
                        Chip("WARP · Google ${if (warp?.optBoolean("on") == true) "✓" else "выкл"}")
                    }
                }
                val r = d.getJSONObject("routers")
                val alerts = d.optJSONArray("alerts").objects()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tile("роутеров онлайн", "${r.optInt("online")}/${r.optInt("total")}", Modifier.weight(1f))
                    Tile("перенесено", "${r.optInt("migrated")}/${r.optInt("total")}", Modifier.weight(1f))
                    Tile("подписок", d.intOrNull("subscriptions_active")?.toString() ?: "—", Modifier.weight(1f))
                }
                if (alerts.isNotEmpty()) {
                    SectionTitle("Тревоги")
                    alerts.forEach { a ->
                        NkCard(bg = NK.WarnSoft, border = NK.WarnLine, onClick = a.str("ip")?.let { ip -> { nav.go(Scr.Router(ip)) } }) {
                            Text(a.str("name") ?: "Сервер", fontWeight = FontWeight.SemiBold)
                            Text(a.optString("text"), color = NK.Text, fontSize = 14.sp)
                        }
                    }
                }
                SectionTitle("Последние события")
                NkCard {
                    val ev = d.optJSONArray("events").objects()
                    if (ev.isEmpty()) Text("Пока событий нет", color = NK.Muted)
                    ev.take(15).forEach { e ->
                        Row(Modifier.clickable(enabled = e.str("ip") != null) { e.str("ip")?.let { nav.go(Scr.Router(it)) } }.padding(vertical = 4.dp)) {
                            Dot(when (e.optString("kind")) { "offline" -> NK.Warn; "online" -> NK.Ok; else -> NK.Link }, 8)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(e.optString("text"), fontSize = 14.sp)
                                Text("${e.str("ip") ?: "сервер"} · ${time(e.optLong("ts"))}", color = NK.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ───────── клиенты ─────────
@Composable
fun ClientsScreen(me: Me, nav: Nav) {
    val load = rememberLoad { Api.get("/v1/staff/clients") }
    var q by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Клиенты", onRefresh = load.reload)
        Loaded(load) { j ->
            val all = j.optJSONArray("clients").objects()
            val shown = all.filter { c ->
                val okQ = q.isBlank() || listOf("name", "ip", "tg_name").any { c.optString(it).contains(q, ignoreCase = true) }
                val okF = when (filter) {
                    "problems" -> c.optBoolean("online") && (c.dbl("rtt") ?: 0.0) > 120 || c.optJSONObject("subscription")?.optBoolean("active") == false
                    "offline" -> !c.optBoolean("online") && c.optBoolean("migrated")
                    "old" -> !c.optBoolean("migrated")
                    else -> true
                }
                okQ && okF
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Имя, IP или @ник") },
                                  leadingIcon = { Icon(Icons.Default.Search, null) })
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fun count(f: String) = all.count { c -> when (f) {
                        "problems" -> c.optBoolean("online") && (c.dbl("rtt") ?: 0.0) > 120 || c.optJSONObject("subscription")?.optBoolean("active") == false
                        "offline" -> !c.optBoolean("online") && c.optBoolean("migrated"); "old" -> !c.optBoolean("migrated"); else -> true } }
                    listOf("all" to "Все", "problems" to "Проблемы", "offline" to "Офлайн", "old" to "На старом").forEach { (k, t) ->
                        FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text("$t ${count(k)}", fontSize = 12.sp) })
                    }
                }
            }
            var accounts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
            LaunchedEffect(q) {
                accounts = emptyList()
                if (q.trim().length >= 3) {
                    kotlinx.coroutines.delay(400)
                    accounts = try { Api.get("/v1/staff/find?q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8")).optJSONArray("found").objects() } catch (e: Exception) { emptyList() }
                }
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown) { c -> RouterRow(c) { nav.go(Scr.Router(c.getString("ip"))) } }
                if (accounts.isNotEmpty()) item { SectionTitle("Аккаунты в боте") }
                items(accounts) { a ->
                    NkCard(onClick = { nav.go(Scr.Account(a.optLong("tg_id"))) }) {
                        Text((a.str("name") ?: "Без имени") + (a.str("username")?.let { "  @$it" } ?: ""), fontWeight = FontWeight.SemiBold)
                        val s = a.optJSONObject("subscription")
                        Text("ID ${a.optLong("tg_id")} · " + (if (s?.optBoolean("active") == true) "подписка до ${dateRu(s.str("expires_at"))}" else "без подписки") +
                             (a.optJSONArray("routers")?.takeIf { it.length() > 0 }?.let { " · роутер ${it.getString(0)}" } ?: ""), color = NK.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

// ───────── сотрудники и роли ─────────
@Composable
fun UsersScreen(me: Me, nav: Nav) {
    val load = rememberLoad { Api.get("/v1/staff/accounts?staff=1") }
    val act = rememberAction()
    val ctx = LocalContext.current
    var invite by remember { mutableStateOf(false) }
    var roleFor by remember { mutableStateOf<JSONObject?>(null) }
    val roles = listOf("admin" to "Админ", "tech" to "Техник", "support" to "Поддержка")
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Сотрудники и роли", onBack = nav::back, onRefresh = load.reload)
        Loaded(load) { j ->
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    NkCard(bg = NK.AccentSoft, border = NK.Accent) {
                        Text("Роли", fontWeight = FontWeight.SemiBold)
                        Text("Админ — всё, кроме ролей.\nТехник — роутеры, новые клиенты, батники (без денег и рассылок).\n" +
                             "Поддержка — видит клиентов и пишет им.", color = NK.AccentText, fontSize = 13.sp)
                    }
                }
                items(j.optJSONArray("accounts").objects()) { a ->
                    NkCard(onClick = if (me.can("users.manage") && a.optString("role") != "owner") ({ roleFor = a }) else null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Badge, null, tint = NK.Link)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.str("name") ?: "ID ${a.optLong("tg_id")}", fontWeight = FontWeight.SemiBold)
                                Text("${a.optString("role_name")}${a.str("username")?.let { " · @$it" } ?: ""} · устройств: ${a.optInt("sessions")}",
                                     color = NK.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                if (me.can("users.manage")) item {
                    Button(onClick = { invite = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("Пригласить сотрудника")
                    }
                }
            }
        }
    }
    if (invite) AlertDialog(
        onDismissRequest = { invite = false }, containerColor = NK.Surface, title = { Text("Пригласить") },
        text = { Text("Человек откроет ссылку в Telegram, и роль привяжется к его аккаунту. Ссылка действует 48 часов, один раз.", color = NK.Muted) },
        confirmButton = {
            Column {
                roles.forEach { (k, t) ->
                    TextButton(onClick = {
                        invite = false
                        act { val r = Api.post("/v1/staff/invites", "role" to k); shareText(ctx, "Приглашение в NordKey (${r.optString("role_name")}): ${r.optString("bot_url")}"); null }
                    }) { Text(t) }
                }
                TextButton(onClick = { invite = false }) { Text("Отмена") }
            }
        },
    )
    roleFor?.let { a ->
        AlertDialog(
            onDismissRequest = { roleFor = null }, containerColor = NK.Surface,
            title = { Text(a.optString("name", "Сотрудник")) },
            text = { Text("Сейчас: ${a.optString("role_name")}. Новая роль:", color = NK.Muted) },
            confirmButton = {
                Column {
                    (roles + ("client" to "Снять роль (обычный клиент)")).forEach { (k, t) ->
                        TextButton(onClick = {
                            roleFor = null
                            act { Api.post("/v1/staff/accounts/${a.optLong("tg_id")}/role", "role" to k); load.reload(); "Готово" }
                        }) { Text(t, color = if (k == "client") NK.Bad else NK.Link) }
                    }
                    TextButton(onClick = { roleFor = null }) { Text("Отмена") }
                }
            },
        )
    }
}

// ───────── журнал ─────────
@Composable
fun AuditScreen(nav: Nav) {
    val load = rememberLoad { Api.get("/v1/staff/audit?limit=300") }
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Журнал действий", onBack = nav::back, onRefresh = load.reload)
        Loaded(load) { j ->
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(j.optJSONArray("audit").objects()) { a ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(NK.Surface).padding(10.dp)) {
                        Row {
                            Text(a.optString("action"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                 color = if (a.optInt("ok", 1) == 1) NK.Text else NK.Bad, modifier = Modifier.weight(1f))
                            Text(time(a.optLong("ts")), color = NK.Muted, fontSize = 12.sp)
                        }
                        Text("${a.optString("role")} ${a.optLong("tg_id")} · ${a.optString("target")} ${a.optString("detail")}".trim(),
                             color = NK.Muted, fontSize = 12.sp, maxLines = 3)
                    }
                }
            }
        }
    }
}

// ───────── батник ─────────
@Composable
fun BatScreen(nav: Nav) {
    val ctx = LocalContext.current
    val act = rememberAction()
    var router by rememberSaveable { mutableStateOf("") }
    var clientIp by rememberSaveable { mutableStateOf("") }
    val routerOk = router.isBlank() || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(router.trim())
    val q = if (router.isBlank()) "" else "?router=${router.trim()}"
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Батник для настройки", onBack = nav::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NkCard {
                Text("Один файл для всех случаев", fontWeight = FontWeight.SemiBold)
                Text("Запускается на компьютере, подключённом к роутеру Keenetic: старых клиентов переносит на новый сервер, " +
                     "новых — подключает (нужен код администратора). После настройки новый клиент получает персональную ссылку на бота.",
                     color = NK.Muted, fontSize = 14.sp)
            }
            OutlinedTextField(router, { router = it }, Modifier.fillMaxWidth(), singleLine = true, isError = !routerOk,
                              label = { Text("Адрес роутера (необязательно)") }, placeholder = { Text("192.168.1.1") },
                              supportingText = { Text("Только если у роутера нестандартный адрес") })
            Button(enabled = routerOk, modifier = Modifier.fillMaxWidth(), onClick = {
                act { Api.post("/v1/staff/bat/send", "to" to "me", "router" to router.trim().ifBlank { null }); "Отправлено вам в Telegram" }
            }) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(6.dp)); Text("Себе в Telegram") }
            OutlinedButton(enabled = routerOk, modifier = Modifier.fillMaxWidth(), onClick = {
                act { shareFile(ctx, "NordKey_setup.bat", Api.bytes("/v1/staff/bat$q"), title = "Отправить батник"); null }
            }) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Поделиться (WhatsApp, почта…)") }
            OutlinedButton(enabled = routerOk, modifier = Modifier.fillMaxWidth(), onClick = {
                act { saveToDownloads(ctx, "NordKey_setup.bat", Api.bytes("/v1/staff/bat$q")) }
            }) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Сохранить на телефон") }
            SectionTitle("Клиенту через бота")
            OutlinedTextField(clientIp, { clientIp = it }, Modifier.fillMaxWidth(), singleLine = true,
                              label = { Text("Адрес клиента в VPN") }, placeholder = { Text("10.66.66.19") })
            OutlinedButton(enabled = routerOk && Regex("""10\.66\.66\.\d{1,3}""").matches(clientIp.trim()), modifier = Modifier.fillMaxWidth(), onClick = {
                act { Api.post("/v1/staff/bat/send", "ip" to clientIp.trim(), "to" to "client", "router" to router.trim().ifBlank { null }); "Батник отправлен клиенту" }
            }) { Text("Отправить клиенту в Telegram") }
        }
    }
}

// ───────── рассылка ─────────
@Composable
fun BroadcastScreen(nav: Nav) {
    val act = rememberAction()
    var target by rememberSaveable { mutableStateOf("not_migrated") }
    var text by rememberSaveable { mutableStateOf("") }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val targets = listOf("not_migrated" to "Ещё на старом сервере", "routers" to "Все клиенты с роутером",
                         "expiring" to "Подписка кончается за 3 дня", "all" to "Все пользователи бота")
    LaunchedEffect(target) { preview = null; try { preview = Api.post("/v1/staff/broadcast", "target" to target, "dry" to true) } catch (_: Exception) {} }
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Рассылка", onBack = nav::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            targets.forEach { (k, t) ->
                Row(Modifier.fillMaxWidth().clickable { target = k }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = target == k, onClick = { target = k }); Text(t)
                }
            }
            Text(preview?.let { "Получателей: ${it.optInt("count")}" } ?: "Считаю получателей…", color = NK.Muted)
            preview?.optJSONArray("recipients").objects().take(30).joinToString(", ") { it.str("name") ?: it.optLong("tg_id").toString() }
                .takeIf { it.isNotBlank() }?.let { Text(it, color = NK.Muted, fontSize = 12.sp) }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 5, label = { Text("Текст сообщения") })
            Button(enabled = text.isNotBlank() && (preview?.optInt("count") ?: 0) > 0, modifier = Modifier.fillMaxWidth(),
                   onClick = { confirm = true }) { Text("Отправить") }
        }
    }
    if (confirm) ConfirmDialog("Отправить рассылку?", "Сообщение уйдёт ${preview?.optInt("count")} получателям от бота NordKey.", "Отправить",
                               onDismiss = { confirm = false }) {
        confirm = false
        act { val r = Api.post("/v1/staff/broadcast", "target" to target, "text" to text, "dry" to false); text = ""; "Рассылка запущена: ${r.optInt("count")}" }
    }
}

// ───────── новый клиент ─────────
@Composable
fun NewClientScreen(nav: Nav) {
    val load = rememberLoad { Api.get("/v1/staff/clients") }
    val ctx = LocalContext.current
    val act = rememberAction()
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Новый клиент", onBack = nav::back, onRefresh = load.reload)
        Loaded(load) { j ->
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    NkCard(bg = NK.AccentSoft, border = NK.Accent) {
                        Text("Как подключить нового клиента", fontWeight = FontWeight.SemiBold)
                        Text("1. Отправьте батник (Ещё → Батник) и запустите его у клиента — роутер настроится сам.\n" +
                             "2. Скрипт сам выдаст персональную ссылку на бота с пробным днём.\n" +
                             "3. Если ссылка потерялась — выберите роутер ниже, и приложение сделает новую.",
                             color = NK.AccentText, fontSize = 13.sp)
                    }
                }
                items(j.optJSONArray("clients").objects().filter { it.opt("owner_tg") == null || it.isNull("owner_tg") }) { c ->
                    RouterRow(c, extra = "без Telegram") {
                        act {
                            val x = Api.post("/v1/staff/claim", "ip" to c.getString("ip"))
                            shareText(ctx, "Ваша персональная ссылка NordKey (пробный период ${x.optInt("trial_hours")} ч): ${x.getString("url")}"); null
                        }
                    }
                }
            }
        }
    }
}

// ───────── ИИ-оператор ─────────
private data class AiMsg(val mine: Boolean, val text: String, val done: List<JSONObject> = emptyList(),
                         val pending: List<JSONObject> = emptyList(), val escalated: Long = 0)

private val TOOL_RU = mapOf(
    "site_route" to "маршрут сайта", "fix_router" to "починка роутера", "dns_split" to "российский DNS для сайта",
    "google_via_vpn" to "Google через VPN", "restart_tunnel" to "перезапуск туннеля")

private val QUICK = listOf(
    "Что сейчас горит?" to "Что сейчас горит? Разберись и почини, что можно.",
    "Кто офлайн?" to "Какие роутеры сейчас не на связи?",
    "Кончаются подписки" to "У кого подписка кончается в ближайшие 3 дня?",
    "Перенести подписку" to "Перенеси подписку и конфиги с @ на @",
    "Не работает сайт" to "У клиента  не работает сайт . Разберись и почини.",
    "Продлить подписку" to "Продли подписку клиенту @ на 30 дней",
    "Найти клиента" to "Найди клиента ",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(nav: Nav, withBar: Boolean) {
    val act = rememberAction()
    val msgs = remember { mutableStateListOf<AiMsg>() }
    var text by rememberSaveable { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<JSONObject?>(null) }
    val doneIds = remember { mutableStateListOf<String>() }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size, thinking) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size) }

    fun send(q: String) {
        val history = org.json.JSONArray().apply {
            msgs.takeLast(12).forEach { put(obj("role" to (if (it.mine) "user" else "model"), "text" to it.text.take(1500))) }
        }
        msgs.add(AiMsg(true, q)); thinking = true
        act {
            try {
                val r = Api.post("/v1/staff/ai/chat", "question" to q, "history" to history)
                msgs.add(AiMsg(false, r.str("answer") ?: "…", r.optJSONArray("done").objects(), r.optJSONArray("pending").objects(),
                               r.optLong("escalated", 0)))
                null
            } catch (e: Exception) {
                msgs.add(AiMsg(false, "⚠️ ${e.message}")); null
            } finally { thinking = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        NkTopBar("ИИ-оператор", "сам проверяет и чинит; деньги и сообщения — по кнопке", onBack = if (withBar) nav::back else null) {
            if (msgs.isNotEmpty()) TextButton(onClick = { msgs.clear() }) { Text("Новый чат") }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (msgs.isEmpty()) item {
                NkCard {
                    Text("Что умею", fontWeight = FontWeight.SemiBold)
                    Text("• найти клиента по @нику, имени или адресу\n• проверить и починить роутер, сайт, DNS\n" +
                         "• пустить сайт через VPN или напрямую\n• перенести или продлить подписку, написать клиенту — по кнопке\n" +
                         "• сложное — передам в Автопилот с готовым запросом для Claude", color = NK.Muted, fontSize = 14.sp)
                }
            }
            items(msgs) { m ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.End else Alignment.Start,
                       verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(m.text, color = if (m.mine) androidx.compose.ui.graphics.Color.White else NK.Text, fontSize = 15.sp,
                         modifier = Modifier.widthIn(max = 330.dp).clip(RoundedCornerShape(16.dp))
                             .background(if (m.mine) NK.Accent else NK.Surface).padding(12.dp))
                    m.done.forEach { d ->
                        Chip((if (d.optBoolean("ok")) "✓ сделал: " else "✗ не вышло: ") + (TOOL_RU[d.optString("tool")] ?: d.optString("tool")),
                             if (d.optBoolean("ok")) NK.OkText else NK.WarnText, if (d.optBoolean("ok")) NK.OkSoft else NK.WarnSoft)
                    }
                    m.pending.forEach { p ->
                        val pid = p.optString("id")
                        NkCard(bg = NK.AccentSoft, border = NK.Accent, modifier = Modifier.widthIn(max = 330.dp)) {
                            Text("Нужно ваше подтверждение", color = NK.AccentText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(p.optString("title"), fontWeight = FontWeight.SemiBold)
                            p.optJSONObject("preview")?.let { Text(transferSummary(it), color = NK.Muted, fontSize = 13.sp) }
                            if (pid in doneIds) Text("✓ Выполнено", color = NK.OkText)
                            else Button(onClick = { confirm = p }, modifier = Modifier.fillMaxWidth()) { Text("Выполнить") }
                        }
                    }
                    if (m.escalated > 0) OutlinedButton(onClick = { nav.go(Scr.Incident(m.escalated)) }) { Text("Открыть в Автопилоте") }
                }
            }
            if (thinking) item { Text("Проверяю… (до минуты, если нужно спросить роутер)", color = NK.Muted) }
        }
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(QUICK) { (label, prompt) ->
                AssistChip(onClick = {
                    if (prompt.endsWith("?") || prompt.endsWith(".")) send(prompt) else text = prompt
                }, label = { Text(label, fontSize = 12.sp) })
            }
        }
        Row(Modifier.fillMaxWidth().background(NK.Bar).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Что сделать? Например: перенеси подписку…") }, maxLines = 4)
            Spacer(Modifier.width(8.dp))
            FilledIconButton(enabled = text.isNotBlank() && !thinking, onClick = { val q = text.trim(); text = ""; send(q) }) {
                Icon(Icons.Default.Send, "Отправить")
            }
        }
    }
    confirm?.let { p ->
        ConfirmDialog("Выполнить?", p.optString("title") + (p.optJSONObject("preview")?.let { "\n\n" + transferSummary(it) } ?: ""), "Выполнить",
                      onDismiss = { confirm = null }) {
            confirm = null
            act {
                val r = Api.post("/v1/staff/ai/approve", "id" to p.optString("id"))
                doneIds.add(p.optString("id"))
                val res = r.optJSONObject("result")
                msgs.add(AiMsg(false, "✅ ${r.optString("title")}" + (res?.str("expires_at")?.let { " — до ${dateRu(it)}" } ?: "")))
                null
            }
        }
    }
}
