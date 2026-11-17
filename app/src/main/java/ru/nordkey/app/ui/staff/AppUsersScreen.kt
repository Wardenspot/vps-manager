package ru.nordkey.app.ui.staff

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import ru.nordkey.app.net.*
import ru.nordkey.app.ui.*

/** «Был(а) в сети»: сейчас / N мин / сегодня 14:10 / вчера / 12.11. */
fun seenRu(ts: Long, now: Long = System.currentTimeMillis() / 1000): String {
    if (ts <= 0) return "—"
    val d = now - ts
    if (d < 300) return "сейчас"
    if (d < 3600) return "${d / 60} мин назад"
    val cal = java.util.Calendar.getInstance()
    val today = cal.apply { timeInMillis = now * 1000; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis / 1000
    val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru")).format(java.util.Date(ts * 1000))
    return when {
        ts >= today -> "сегодня $t"
        ts >= today - 86400 -> "вчера $t"
        else -> java.text.SimpleDateFormat("dd.MM", java.util.Locale("ru")).format(java.util.Date(ts * 1000))
    }
}

/** Все, кто входил в приложение: телефоны и компьютеры, последний вход, подписка, есть ли роутер. */
@Composable
fun AppUsersScreen(nav: Nav) {
    val load = rememberLoad { Api.get("/v1/staff/appusers") }
    var q by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Пользователи приложения", onBack = nav::back, onRefresh = load.reload)
        Loaded(load) { j ->
            val all = j.optJSONArray("users").objects()
            val pc = { u: JSONObject -> u.optJSONArray("devices").objects().any { it.optBoolean("pc") } }
            val phone = { u: JSONObject -> u.optJSONArray("devices").objects().any { !it.optBoolean("pc") } }
            val noRouter = { u: JSONObject -> (u.optJSONArray("routers")?.length() ?: 0) == 0 }
            val F = mapOf<String, (JSONObject) -> Boolean>("all" to { true }, "pc" to pc, "phone" to phone, "norouter" to noRouter)
            val ql = q.trim().lowercase().trimStart('@')
            val shown = all.filter { u -> F[filter]!!(u) && (ql.isEmpty() || listOf("name", "username").any { (u.str(it) ?: "").lowercase().contains(ql) } ||
                                                              u.optJSONArray("devices").objects().any { it.optString("device").lowercase().contains(ql) }) }
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Имя, @ник или устройство") },
                                      leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(14.dp))
                }
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("all" to "Все", "pc" to "Компьютер", "phone" to "Телефон", "norouter" to "Без роутера").forEach { (k, t) ->
                            FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text("$t ${all.count(F[k]!!)}", fontSize = 12.sp) })
                        }
                    }
                }
                items(shown, key = { it.optLong("tg_id") }) { u -> AppUserRow(u) { nav.go(Scr.Account(u.optLong("tg_id"))) } }
                if (shown.isEmpty()) item { Text("Никого не нашлось", color = NK.Muted, modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}

@Composable
fun AppUserRow(u: JSONObject, onClick: () -> Unit) {
    val devs = u.optJSONArray("devices").objects()
    val routers = u.optJSONArray("routers")?.length() ?: 0
    val seen = u.optLong("last_seen")
    val fresh = System.currentTimeMillis() / 1000 - seen < 900
    NkCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DaysRing(u.optJSONObject("subscription"))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(u.str("name") ?: u.str("username")?.let { "@$it" } ?: "ID ${u.optLong("tg_id")}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(u.str("username")?.let { "@$it" }, u.optString("role_name").takeIf { u.optString("role") != "client" })
                         .joinToString(" · ").ifEmpty { "клиент" }, color = NK.Muted, fontSize = 12.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (fresh) Dot(NK.Ok, 8)
                    Text(seenRu(seen), color = if (fresh) NK.OkText else NK.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(if (routers > 0) "$routers ${plural(routers, "роутер", "роутера", "роутеров")}" else "без роутера",
                     color = if (routers > 0) NK.Muted else NK.Link, fontSize = 12.sp)
            }
        }
        // устройства: значок ПК/телефона + модель
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            devs.forEach { d ->
                val isPc = d.optBoolean("pc")
                Row(Modifier.clip(RoundedCornerShape(50)).background(NK.Link.copy(alpha = 0.10f)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (isPc) Icons.Default.Computer else Icons.Default.PhoneAndroid, null, tint = NK.Link, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(d.optString("device").removePrefix("ПК ").take(22), color = NK.Text, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}
