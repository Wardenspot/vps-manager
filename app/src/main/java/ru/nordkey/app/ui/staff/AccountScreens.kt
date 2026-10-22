package ru.nordkey.app.ui.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import ru.nordkey.app.net.*
import ru.nordkey.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun hm(ts: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ts * 1000))

/** Текст сводки «что будет перенесено». */
fun transferSummary(p: JSONObject): String {
    val from = p.optJSONObject("from"); val to = p.optJSONObject("to")
    fun who(a: JSONObject?) = a?.let { (it.str("name") ?: "") + (it.str("username")?.let { u -> " @$u" } ?: "") + " (ID ${it.optLong("tg_id")})" } ?: "?"
    val cfg = p.optJSONArray("configs").objects().joinToString(", ") { "#${it.optInt("id")} ${it.optString("client_ip")}" }
    val warn = p.optJSONArray("warnings")?.let { a -> (0 until a.length()).joinToString("\n") { "⚠️ " + a.getString(it) } }.orEmpty()
    return "Откуда: ${who(from)}\nКуда: ${who(to)}\n\nКонфиги: ${cfg.ifBlank { "нет" }}\nПодписка до: ${dateRu(p.str("expires_at"))}" +
        (if (warn.isNotBlank()) "\n\n$warn" else "") + "\n\nVPN у клиента не прервётся. Перед переносом сервер сделает копию базы."
}

@Composable
fun AccountScreen(tg: Long, me: Me, nav: Nav) {
    val load = rememberLoad(tg) { Api.get("/v1/staff/accounts/$tg/info") }
    val act = rememberAction()
    var dialog by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    var reply by remember { mutableStateOf("") }
    LaunchedEffect(tg) { while (true) { delay(15_000); load.reload() } }
    Column(Modifier.fillMaxSize()) {
        val j = load.data
        NkTopBar(j?.str("name") ?: "Клиент", j?.str("username")?.let { "@$it · ID $tg" } ?: "ID $tg", onBack = nav::back, onRefresh = load.reload)
        Loaded(load) { a ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val s = a.optJSONObject("subscription")
                val active = s?.optBoolean("active") == true
                NkCard(bg = if (active) NK.OkSoft else NK.WarnSoft, border = if (active) NK.Ok else NK.WarnLine) {
                    Text(if (active) "Подписка до ${dateRu(s?.str("expires_at"))}" else if (s != null) "Подписка закончилась ${dateRu(s.str("expires_at"))}" else "Подписки нет",
                         fontWeight = FontWeight.Bold, color = if (active) NK.OkText else NK.WarnText)
                    if (s?.optBoolean("trial") == true) Text("пробный период", color = NK.Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (me.can("subs.edit")) Button(onClick = { dialog = "extend" }, Modifier.weight(1f)) { Text("Продлить") }
                        if (me.can("subs.edit")) OutlinedButton(onClick = { dialog = "transfer" }, Modifier.weight(1f)) { Text("Перенести") }
                    }
                }
                val routers = a.optJSONArray("routers")?.let { r -> (0 until r.length()).map { r.getString(it) } }.orEmpty()
                if (routers.isNotEmpty()) {
                    SectionTitle("Роутеры")
                    routers.forEach { ip -> OutlinedButton(onClick = { nav.go(Scr.Router(ip)) }, Modifier.fillMaxWidth()) { Text("Роутер $ip") } }
                }
                val cfgs = a.optJSONArray("configs").objects()
                if (cfgs.isNotEmpty()) {
                    SectionTitle("Конфиги")
                    cfgs.forEach { c ->
                        NkCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (c.optString("kind") == "router") Icons.Default.Router else Icons.Default.PhoneAndroid, null, tint = NK.Muted)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("#${c.optInt("id")} · ${c.optString("ip")}", fontWeight = FontWeight.SemiBold)
                                    Text((if (c.optString("kind") == "router") "роутер" else "телефон/ПК") + if (c.optInt("active") == 1) "" else " · выключен",
                                         color = NK.Muted, fontSize = 12.sp)
                                }
                                if (c.optString("kind") == "phone" && c.optInt("active") == 1 && me.can("msg"))
                                    TextButton(onClick = { act { Api.post("/v1/staff/configs/${c.optInt("id")}/send"); "Конфиг отправлен клиенту в Telegram" } }) { Text("Отправить") }
                            }
                        }
                    }
                }
                SectionTitle("Переписка с поддержкой")
                NkCard {
                    val msgs = a.optJSONArray("support").objects()
                    if (msgs.isEmpty()) Text("Обращений не было", color = NK.Muted)
                    msgs.forEach { m -> Bubble(m) }
                    if (me.can("msg")) {
                        OutlinedTextField(reply, { reply = it }, Modifier.fillMaxWidth(), placeholder = { Text("Ответ клиенту (придёт в Telegram)") }, maxLines = 4)
                        Button(enabled = reply.isNotBlank(), onClick = {
                            val t = reply; reply = ""
                            act { Api.post("/v1/staff/support/$tg/reply", "text" to t); load.reload(); "Отправлено" }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Ответить") }
                    }
                }
                a.optJSONArray("payments").objects().takeIf { it.isNotEmpty() }?.let { pays ->
                    SectionTitle("Платежи")
                    NkCard { pays.forEach { p -> Text("${dateRu(p.str("created_at"))} · ${p.optInt("amount")} ₽ · ${p.optString("plan")} · ${p.optString("status")}", fontSize = 13.sp) } }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    when (dialog) {
        "extend" -> InputDialog("Продлить подписку", "На сколько дней?", "30", "Продлить", onDismiss = { dialog = null }) { t ->
            dialog = null
            act {
                val d = t.toIntOrNull() ?: return@act "Нужно число дней"
                val r = Api.post("/v1/staff/subscription/extend", "tg_id" to tg, "days" to d); load.reload(); "Продлено до ${dateRu(r.str("expires_at"))}"
            }
        }
        "transfer" -> InputDialog("Перенести подписку", "На какой аккаунт? Человек должен хотя бы раз нажать «Старт» в боте.", "@ник или ID", "Дальше",
                                  onDismiss = { dialog = null }) { dst ->
            dialog = null
            act { preview = Pair(dst, Api.post("/v1/staff/transfer", "from" to tg.toString(), "to" to dst, "dry" to true)); null }
        }
    }
    preview?.let { (dst, p) ->
        ConfirmDialog("Перенести?", transferSummary(p), "Перенести", onDismiss = { preview = null }) {
            preview = null
            act {
                val r = Api.post("/v1/staff/transfer", "from" to tg.toString(), "to" to dst, "dry" to false)
                nav.back(); "Перенесено: конфигов ${r.optInt("moved_configs")}, подписок ${r.optInt("moved_subscriptions")}"
            }
        }
    }
}

@Composable
fun Bubble(m: JSONObject) {
    val sender = m.optString("sender")
    val mine = sender == "client"
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.Start else Alignment.End) {
        Text(when (sender) { "client" -> "Клиент"; "ai" -> "🤖 ИИ"; else -> "Сотрудник" } + " · " + hm(m.optLong("ts")),
             color = NK.Muted, fontSize = 11.sp)
        Text(m.optString("text"), fontSize = 14.sp, color = if (mine) NK.Text else Color.White,
             modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(12.dp))
                 .background(when (sender) { "client" -> NK.Bg; "ai" -> NK.AccentSoft; else -> NK.Accent }).padding(10.dp))
    }
}

/** Входящие обращения в поддержку (последнее сообщение каждого клиента). */
@Composable
fun SupportInboxScreen(nav: Nav) {
    val load = rememberLoad { Api.get("/v1/staff/support") }
    LaunchedEffect(Unit) { while (true) { delay(20_000); load.reload() } }
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Обращения в поддержку", "ИИ отвечает сам, сложное — в Автопилот", onBack = nav::back, onRefresh = load.reload)
        Loaded(load) { j ->
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val list = j.optJSONArray("threads").objects()
                if (list.isEmpty()) item { Text("Обращений пока нет", color = NK.Muted) }
                items(list) { t ->
                    NkCard(onClick = { nav.go(Scr.Account(t.optLong("tg_id"))) }) {
                        Row {
                            Text(t.str("name") ?: t.str("username")?.let { "@$it" } ?: "ID ${t.optLong("tg_id")}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(hm(t.optLong("ts")), color = NK.Muted, fontSize = 12.sp)
                        }
                        Text((when (t.optString("sender")) { "client" -> "⏳ "; "ai" -> "🤖 "; else -> "✅ " }) + t.optString("text"),
                             color = NK.Muted, fontSize = 13.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}
