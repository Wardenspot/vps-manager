package ru.nordkey.app.ui.staff

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
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

/** Статус инцидента: подпись и цвета. */
private data class St(val label: String, val fg: Color, val bg: Color)

private fun status(s: String) = when (s) {
    "need_approval" -> St("Нужно решение", NK.AccentText, NK.AccentSoft)
    "escalated" -> St("Сложный случай", NK.WarnText, NK.WarnSoft)
    "open" -> St("Наблюдаю", NK.WarnText, NK.WarnSoft)
    "auto_fixed" -> St("Починил сам", NK.OkText, NK.OkSoft)
    "fixed" -> St("Исправлено", NK.OkText, NK.OkSoft)
    "resolved" -> St("Прошло само", NK.Muted, NK.Surface)
    else -> St("Закрыто", NK.Muted, NK.Surface)
}

@Composable
fun AutopilotScreen(me: Me, nav: Nav) {
    val load = rememberLoad { Api.get("/v1/staff/guard") }
    val act = rememberAction()
    LaunchedEffect(Unit) { while (true) { delay(30_000); load.reload() } }
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Автопилот", "следит за сервером и роутерами, чинит простое сам", onRefresh = load.reload)
        Loaded(load) { j ->
            val s = j.optJSONObject("settings") ?: JSONObject()
            val list = j.optJSONArray("incidents").objects()
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    NkCard {
                        val canSet = me.can("routers.admin")
                        listOf("enabled" to "Автопилот включён", "auto_fix" to "Чинить простое сам", "ai_support" to "ИИ отвечает клиентам в поддержке",
                               "ai_triage" to "ИИ разбирает сложные случаи", "auto_sites" to "Сам пускать заблокированные сайты через VPN",
                               "notify_sites" to "Сообщать клиенту, что сайт включён", "notify" to "Сообщать мне в Telegram").forEach { (k, t) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t, Modifier.weight(1f))
                                Switch(checked = s.optBoolean(k, true), enabled = canSet, onCheckedChange = { v ->
                                    act { Api.post("/v1/staff/guard/settings", k to v); load.reload(); null }
                                })
                            }
                        }
                        Text("Сам: перезапуск служб сервера, чистка диска, починка DNS на роутере, WARP. " +
                             "По кнопке: перезагрузка роутера, изменения маршрутов и DNS. Никогда: удаление, оплаты, роли.",
                             color = NK.Muted, fontSize = 12.sp)
                    }
                }
                val active = list.filter { it.optString("status") in setOf("need_approval", "escalated", "open") }
                val done = list - active.toSet()
                if (active.isEmpty()) item {
                    NkCard(bg = NK.OkSoft, border = NK.Ok) { Text("Всё спокойно — ничего не требует внимания", color = NK.OkText, fontWeight = FontWeight.SemiBold) }
                }
                if (active.isNotEmpty()) item { SectionTitle("Требует внимания") }
                items(active) { IncidentRow(it) { nav.go(Scr.Incident(it.optLong("id"))) } }
                if (done.isNotEmpty()) item { SectionTitle("За последние 3 дня") }
                items(done) { IncidentRow(it) { nav.go(Scr.Incident(it.optLong("id"))) } }
            }
        }
    }
}

@Composable
private fun IncidentRow(i: JSONObject, onClick: () -> Unit) {
    val st = status(i.optString("status"))
    NkCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(i.optString("title"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(st.label, st.fg, st.bg)
            Text(hm(i.optLong("updated")), color = NK.Muted, fontSize = 12.sp)
            i.str("ip")?.let { Mono(it) }
        }
    }
}

@Composable
fun IncidentScreen(id: Long, me: Me, nav: Nav) {
    val load = rememberLoad(id) { Api.get("/v1/staff/guard/incidents/$id") }
    val act = rememberAction()
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var job by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Инцидент", onBack = nav::back, onRefresh = load.reload)
        Loaded(load) { i ->
            val st = status(i.optString("status"))
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(i.optString("title"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Chip(st.label, st.fg, st.bg)
                    Text("с ${hm(i.optLong("created"))}", color = NK.Muted, fontSize = 12.sp)
                }
                i.str("detail")?.let { Text(it, color = NK.Text, fontSize = 15.sp) }
                i.str("ip")?.let { ip -> OutlinedButton(onClick = { nav.go(Scr.Router(ip)) }) { Text("Открыть роутер $ip") } }

                val actions = i.optJSONArray("actions").objects()
                if (actions.isNotEmpty()) {
                    SectionTitle("Что можно сделать")
                    actions.forEachIndexed { n, a ->
                        val done = a.optBoolean("done")
                        Button(
                            onClick = { confirm = n to a.optString("title") }, enabled = !done && me.can("routers.act"),
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                        ) { Text((if (done) "✓ " else "") + a.optString("title"), fontSize = 14.sp) }
                    }
                }
                i.str("prompt")?.let { p ->
                    NkCard(bg = NK.WarnSoft, border = NK.WarnLine) {
                        Text("Сложный случай", fontWeight = FontWeight.SemiBold, color = NK.WarnText)
                        Text("Автопилот собрал всё, что выяснил. Скопируйте запрос и отправьте его Claude — там будут шаги для сервера.",
                             color = NK.Text, fontSize = 14.sp)
                        Button(onClick = { copy(ctx, p); toast(ctx, "Запрос скопирован") }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Скопировать запрос для Claude")
                        }
                    }
                }
                SectionTitle("Что происходило")
                NkCard {
                    i.optJSONArray("log").objects().reversed().forEach { l ->
                        Column(Modifier.padding(vertical = 3.dp)) {
                            Text(hm(l.optLong("ts")), color = NK.Muted, fontSize = 11.sp)
                            Text(l.optString("text"), fontSize = 14.sp)
                        }
                    }
                }
                if (i.optString("status") in setOf("open", "need_approval", "escalated") && me.can("routers.act"))
                    TextButton(onClick = { act { Api.post("/v1/staff/guard/incidents/$id/dismiss"); load.reload(); "Закрыто" } }) {
                        Text("Закрыть — разобрался сам", color = NK.Muted)
                    }
            }
        }
    }
    confirm?.let { (n, title) ->
        ConfirmDialog("Выполнить?", title, "Выполнить", onDismiss = { confirm = null }) {
            confirm = null
            act { job = Api.post("/v1/staff/guard/incidents/$id/approve", "n" to n).getString("job"); null }
        }
    }
    job?.let { JobDialog(it, "Выполняю") { job = null; load.reload() } }
}

fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("NordKey", text))
}

/** «Глубокая проверка сайта»: автопилот разбирает и создаёт инцидент с выводами. */
@Composable
fun InvestigateDialog(ip: String, nav: Nav, onClose: () -> Unit) {
    var domain by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    val d = domain
    if (d == null) {
        InputDialog("Глубокая проверка сайта", "Автопилот проверит маршрут, DNS, блокировку рекламы и нагрузку роутера — как мы разбирали Авито.",
                    "avito.ru", "Проверить", onDismiss = onClose) { domain = it }
        return
    }
    LaunchedEffect(d) {
        try {
            status = "Проверяю… это 1–2 минуты"
            val jid = Api.post("/v1/staff/guard/investigate", "ip" to ip, "domain" to d).getString("job")
            while (true) {
                delay(3000)
                val j = Api.get("/v1/jobs/$jid")
                if (j.optString("status") == "running") continue
                val inc = j.optJSONObject("result")?.optLong("incident") ?: 0L
                onClose()
                if (inc > 0) nav.go(Scr.Incident(inc)) else status = "Не получилось: ${j.optString("output")}"
                break
            }
        } catch (e: Exception) { status = e.message ?: "Ошибка" }
    }
    AlertDialog(
        onDismissRequest = onClose, containerColor = NK.Surface, title = { Text("Проверка $d") },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text(status, color = NK.Muted)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Свернуть") } },
    )
}
