package ru.nordkey.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import ru.nordkey.app.BuildConfig
import ru.nordkey.app.net.*
import ru.nordkey.app.util.*

// ───────── мои роутеры ─────────
@Composable
fun MyRoutersScreen(me: Me, nav: Nav, withBar: Boolean) {
    val load = rememberLoad { Api.get("/v1/routers") }
    var linkDialog by remember { mutableStateOf(false) }
    val act = rememberAction()
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Мои роутеры", onBack = if (withBar) nav::back else null, onRefresh = load.reload)
        Loaded(load) { j ->
            val list = j.optJSONArray("routers").objects()
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list.isEmpty()) item {
                    NkCard {
                        TitleHelp("Пока нет роутеров", "no_routers")
                    }
                }
                val mine = list.filter { it.optString("level") != "member" }
                val fam = list.filter { it.optString("level") == "member" }
                if (mine.isNotEmpty() && fam.isNotEmpty()) item { SectionTitle("Мои") }
                items(mine) { r -> RouterRow(r) { nav.go(Scr.Router(r.getString("ip"))) } }
                if (fam.isNotEmpty()) item {
                    TitleHelp("Семейный доступ", "family", Modifier.padding(top = 8.dp, start = 4.dp))
                }
                items(fam) { r -> RouterRow(r, extra = r.str("owner_name")) { nav.go(Scr.Router(r.getString("ip"))) } }
                item {
                    OutlinedButton(onClick = { linkDialog = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить роутер по коду")
                    }
                }
            }
        }
    }
    if (linkDialog) InputDialog(
        "Добавить роутер", "Введите код из бота (например ABCD-EFGH). Код действует 10 минут.", "ABCD-EFGH", "Добавить",
        onDismiss = { linkDialog = false },
    ) { code ->
        linkDialog = false
        act { val r = Api.post("/v1/link", "code" to code); load.reload(); r.optString("message") }
    }
}

@Composable
fun RouterRow(r: JSONObject, extra: String? = null, onClick: () -> Unit) {
    val online = r.optBoolean("online")
    val rtt = r.dbl("rtt")
    val slow = rtt != null && rtt > 120
    NkCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DaysRing(r.optJSONObject("subscription"))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.optString("name"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mono(r.optString("ip").removePrefix("10.66.66."))
                    if (!r.optBoolean("migrated", true)) Text("старый сервер", color = NK.WarnText, fontSize = 12.sp)
                    val fam = r.optInt("family", 0)
                    if (r.optString("level") == "member") FamilyMark("семья")
                    else if (fam > 0) FamilyMark("$fam")
                    extra?.let { Text(it, color = NK.Muted, fontSize = 12.sp, maxLines = 1) }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(when { !online -> NK.Off; slow -> NK.Warn; else -> NK.Ok }, 8)
                    Text(if (!online) "офлайн" else rtt?.let { "${it.toInt()} мс" } ?: "онлайн",
                         color = when { !online -> NK.Muted; slow -> NK.WarnText; else -> NK.Text }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                r.optJSONObject("subscription")?.let { s ->
                    val dev = s.optInt("devices", 0)
                    Text((if (s.optBoolean("active")) "до ${dateRu(s.str("expires_at")).take(5)}" else "нет подписки") + (if (dev > 1) " · ×$dev" else ""),
                         color = if (s.optBoolean("active")) NK.Muted else NK.WarnText, fontSize = 12.sp)
                }
            }
        }
    }
}

// ───────── устройства (телефон/ПК) ─────────
@Composable
fun DevicesScreen(nav: Nav, withBar: Boolean) {
    val load = rememberLoad { Api.get("/v1/devices") }
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Мои устройства", "VPN для телефона и компьютера", onBack = if (withBar) nav::back else null, onRefresh = load.reload)
        Loaded(load) { j ->
            val list = j.optJSONArray("devices").objects()
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    NkCard(bg = NK.AccentSoft, border = NK.Accent) {
                        TitleHelp("Как подключить", "devices")
                        TextButton(onClick = { openUrl(ctx, "https://play.google.com/store/apps/details?id=org.amnezia.awg") }) {
                            Text("Скачать AmneziaWG")
                        }
                    }
                }
                if (list.isEmpty()) item {
                    NkCard {
                        TitleHelp("Пока нет конфигураций", "devices")
                    }
                }
                items(list) { d ->
                    val conf = d.getString("conf")
                    val name = d.optString("name", "NordKey")
                    var showQr by remember { mutableStateOf(false) }
                    NkCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VpnKey, null, tint = NK.Link)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, fontWeight = FontWeight.SemiBold)
                                Mono(d.optString("ip"))
                            }
                        }
                        if (showQr) {
                            val bmp = remember(conf) { qr(conf) }
                            Image(bmp, "QR-код конфигурации", Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { showQr = !showQr }, modifier = Modifier.weight(1f)) { Text(if (showQr) "Скрыть QR" else "Показать QR") }
                            OutlinedButton(onClick = { shareFile(ctx, "$name.conf", conf.toByteArray(), "text/plain", "Открыть в AmneziaWG") },
                                           modifier = Modifier.weight(1f)) { Text("Файлом") }
                        }
                    }
                }
            }
        }
    }
}

// ───────── подписка ─────────
@Composable
fun SubscriptionScreen(nav: Nav, withBar: Boolean) {
    val load = rememberLoad { Api.get("/v1/subscription") }
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Подписка", onBack = if (withBar) nav::back else null, onRefresh = load.reload)
        Loaded(load) { j ->
            val s = j.optJSONObject("subscription")
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val active = s?.optBoolean("active") == true
                val days = s?.optInt("days_left") ?: 0
                val dev = s?.optInt("devices", 0) ?: 0
                StatusHero(
                    color = if (active) (if (days <= 3) NK.Warn else NK.Ok) else NK.Warn,
                    fraction = if (active) (days / 30f).coerceIn(0.05f, 1f) else 0.02f,
                    value = if (active) "$days" else "0", caption = plural(if (active) days else 0, "день", "дня", "дней"),
                    title = if (active) "Подписка активна" else "Подписки нет",
                    lines = listOfNotNull(
                        s?.let { if (active) "до ${dateRu(it.str("expires_at"))}" else "закончилась ${dateRu(it.str("expires_at"))}" },
                        if (s?.optBoolean("trial") == true) "пробный период" else null,
                        if (dev > 1) "Устройств: $dev — продление сразу за все" else null),
                )
                NkButton(if (active) "Продлить" else "Оформить подписку", Modifier.fillMaxWidth()) { openUrl(ctx, j.optString("pay_url")) }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HelpLink("Как оплатить", "subscription"); HelpLink("Семейный доступ", "family")
                }
            }
        }
    }
}

// ───────── поддержка (переписка; отвечает ИИ, сложное — человек) ─────────
@Composable
fun SupportScreen(nav: Nav) {
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val load = rememberLoad { Api.get("/v1/support") }
    val act = rememberAction()
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5_000); load.reload() } }
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Поддержка", "ответим здесь и в Telegram", onBack = nav::back)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val msgs = load.data?.optJSONArray("messages").objects()
            if (msgs.isEmpty()) item {
                TitleHelp("Напишите, что не работает", "support")
            }
            items(msgs) { m ->
                val mine = m.optString("sender") == "client"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    Text(m.optString("text"), color = if (mine) Color.White else NK.Text, fontSize = 15.sp,
                         modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp))
                             .background(if (mine) Brush.linearGradient(listOf(NK.Glow, Color(0xFF2E5BD8))) else Brush.linearGradient(listOf(NK.Surface2, NK.Surface))).padding(12.dp))
                }
            }
            if (msgs.lastOrNull()?.optString("sender") == "client") item { Text("Проверяем…", color = NK.Muted) }
        }
        Row(Modifier.fillMaxWidth().background(NK.Bar).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Сообщение") }, maxLines = 4)
            Spacer(Modifier.width(8.dp))
            Button(enabled = text.isNotBlank() && !sending, onClick = {
                val t = text.trim(); text = ""; sending = true
                act { try { Api.post("/v1/support", "text" to t); load.reload(); null } finally { sending = false } }
            }) { Text("Отправить") }
        }
    }
}

// ───────── устройства, где выполнен вход ─────────
@Composable
fun SessionsScreen(nav: Nav, onLogout: () -> Unit) {
    val load = rememberLoad { Api.get("/v1/me/sessions") }
    val act = rememberAction()
    var confirmAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        NkTopBar("Где выполнен вход", onBack = nav::back, onRefresh = load.reload)
        Loaded(load) { j ->
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(j.optJSONArray("sessions").objects()) { s ->
                    NkCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PhoneAndroid, null, tint = NK.Muted)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.optString("device") + if (s.optBoolean("current")) " · это устройство" else "", fontWeight = FontWeight.SemiBold)
                                Text("активность ${ago(System.currentTimeMillis() / 1000 - s.optLong("last_seen"))} назад", color = NK.Muted, fontSize = 12.sp)
                            }
                            if (!s.optBoolean("current")) TextButton(onClick = {
                                act { Api.post("/v1/me/sessions/revoke", "id" to s.getString("id")); load.reload(); "Устройство отключено" }
                            }) { Text("Выйти", color = NK.Bad) }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = { confirmAll = true }, modifier = Modifier.fillMaxWidth()) { Text("Выйти на всех других устройствах") }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { act { Api.post("/v1/auth/logout"); onLogout(); null } }, modifier = Modifier.fillMaxWidth(),
                           colors = ButtonDefaults.buttonColors(containerColor = NK.BadSoft, contentColor = NK.Bad)) { Text("Выйти на этом устройстве") }
                }
            }
        }
    }
    if (confirmAll) ConfirmDialog("Выйти везде?", "Все другие телефоны, где вы вошли в NordKey, потребуют войти заново.", "Выйти везде",
                                  onDismiss = { confirmAll = false }) {
        confirmAll = false
        act { val r = Api.post("/v1/me/sessions/revoke", "all" to true); load.reload(); "Отключено устройств: ${r.optInt("revoked")}" }
    }
}

// ───────── «Ещё» ─────────
@Composable
fun MoreScreen(me: Me, nav: Nav, onLogout: () -> Unit, reloadMe: () -> Unit) {
    var linkDialog by remember { mutableStateOf(false) }
    val act = rememberAction()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        NkTopBar("Ещё")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NkCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AccountCircle, null, tint = NK.Link, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(me.name.ifBlank { "Аккаунт" }, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(me.roleName, color = NK.Muted, fontSize = 13.sp)
                    }
                }
            }
            if (me.staff) {
                SectionTitle("Работа")
                if (me.can("msg")) MenuItem("Обращения в поддержку", Icons.Default.SupportAgent) { nav.go(Scr.SupportInbox) }
                if (me.can("newclient")) MenuItem("Подключить роутер (мастер)", Icons.Default.Router) { nav.go(Scr.Setup) }
                if (me.can("newclient")) MenuItem("Новый клиент — ссылка на бота", Icons.Default.PersonAdd) { nav.go(Scr.NewClient) }
                if (me.can("bat")) MenuItem("Батник для настройки роутера", Icons.Default.Description) { nav.go(Scr.Bat) }
                if (me.can("broadcast")) MenuItem("Рассылка клиентам", Icons.Default.Campaign) { nav.go(Scr.Broadcast) }
                if (me.can("users.view")) MenuItem("Сотрудники и роли", Icons.Default.Groups) { nav.go(Scr.Users) }
                if (me.can("audit.view")) MenuItem("Журнал действий", Icons.Default.History) { nav.go(Scr.Audit) }
                SectionTitle("Моё")
                MenuItem("Мои роутеры", Icons.Default.Router) { nav.go(Scr.MyRouters) }
                MenuItem("Мои устройства", Icons.Default.PhoneAndroid) { nav.go(Scr.Devices) }
                MenuItem("Моя подписка", Icons.Default.CreditCard) { nav.go(Scr.Subscription) }
            } else {
                MenuItem("Добавить роутер по коду", Icons.Default.Add) { linkDialog = true }
                MenuItem("Поддержка", Icons.Default.SupportAgent) { nav.go(Scr.Support) }
            }
            SectionTitle("Оформление")
            ThemePicker()
            MenuItem("Помощь", Icons.AutoMirrored.Filled.HelpOutline) { nav.go(Scr.Help) }
            MenuItem("Где выполнен вход", Icons.Default.Devices) { nav.go(Scr.Sessions) }
            MenuItem("Выйти", Icons.AutoMirrored.Filled.Logout, danger = true) { act { Api.post("/v1/auth/logout"); onLogout(); null } }
            Text("NordKey ${BuildConfig.VERSION_NAME}", color = NK.Off, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
        }
    }
    if (linkDialog) InputDialog("Добавить роутер", "Введите код из бота (например ABCD-EFGH).", "ABCD-EFGH", "Добавить",
                                onDismiss = { linkDialog = false }) { code ->
        linkDialog = false
        act { val r = Api.post("/v1/link", "code" to code); reloadMe(); r.optString("message") }
    }
}

@Composable
fun MenuItem(title: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    // у каждого пункта свой цвет значка — меню читается с одного взгляда в обеих темах
    val tint = if (danger) NK.Bad else menuTints()[(title.hashCode() and 0x7fffffff) % menuTints().size]
    NkCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = if (NK.dark) 0.16f else 0.12f)),
                contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(12.dp))
            Text(title, color = if (danger) NK.Bad else NK.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = NK.Off)
        }
    }
}

private fun menuTints() = if (NK.dark) listOf(Color(0xFF5CC8FF), Color(0xFF7C9CFF), Color(0xFFB18CFF), Color(0xFF3FE0A0), Color(0xFFFFB547), Color(0xFFFF8FB1), Color(0xFF35E0FF))
    else listOf(Color(0xFF1F6FEB), Color(0xFF4F46E5), Color(0xFF7C3AED), Color(0xFF0E9F6E), Color(0xFFD97706), Color(0xFFDB2777), Color(0xFF0891B2))


/** Маленькая отметка семейного доступа: значок людей и подпись. */
@Composable
fun FamilyMark(text: String) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(NK.FamilySoft).padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Groups, null, tint = NK.Family, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, color = NK.Family, fontSize = 11.sp, maxLines = 1)
    }
}

/** Несколько роутеров одного клиента — одной карточкой: владелец, общая подписка и роутеры списком. */
@Composable
fun RouterGroupCard(owner: String, ownerTg: Long, routers: List<JSONObject>, onOwner: () -> Unit, onRouter: (String) -> Unit) {
    val sub = routers.firstNotNullOfOrNull { it.optJSONObject("subscription") }
    // свёрнута по умолчанию: одна строка как у обычного клиента, роутеры — по нажатию
    var open by rememberSaveable(ownerTg) { mutableStateOf(false) }
    val on = routers.count { it.optBoolean("online") }
    val worst = routers.mapNotNull { r -> r.dbl("rtt")?.takeIf { r.optBoolean("online") } }.maxOrNull()
    NkCard {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            DaysRing(sub)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(owner, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                Text("${routers.size} ${plural(routers.size, "роутер", "роутера", "роутеров")}" +
                     (sub?.optDouble("price_mult", 1.0)?.takeIf { it != 1.0 }?.let { " · ×${multStr(it)}" } ?: ""),
                     color = NK.Muted, fontSize = 12.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(when { on == 0 -> NK.Off; on < routers.size || (worst ?: 0.0) > 120 -> NK.Warn; else -> NK.Ok }, 8)
                    Text(if (on == routers.size) worst?.let { "${it.toInt()} мс" } ?: "онлайн" else "$on из ${routers.size}",
                         color = if (on < routers.size) NK.WarnText else NK.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                sub?.let { s -> Text(if (s.optBoolean("active")) "до ${dateRu(s.str("expires_at")).take(5)}" else "нет подписки",
                                     color = if (s.optBoolean("active")) NK.Muted else NK.WarnText, fontSize = 12.sp) }
            }
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (open) "Свернуть" else "Развернуть", tint = NK.Muted)
        }
        if (open) routers.forEach { r ->
            NkDivider()
            val online = r.optBoolean("online"); val rtt = r.dbl("rtt"); val slow = rtt != null && rtt > 120
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onRouter(r.getString("ip")) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Dot(when { !online -> NK.Off; slow -> NK.Warn; else -> NK.Ok })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.optString("name"), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Mono(r.optString("ip").removePrefix("10.66.66."))
                        if (!r.optBoolean("migrated", true)) Text("старый сервер", color = NK.WarnText, fontSize = 12.sp)
                        r.optInt("family", 0).takeIf { it > 0 }?.let { FamilyMark("$it") }
                    }
                }
                Text(if (!online) "офлайн" else rtt?.let { "${it.toInt()} мс" } ?: "онлайн",
                     color = when { !online -> NK.Muted; slow -> NK.WarnText; else -> NK.Text }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }
        if (open) {
            NkDivider()
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onOwner).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, null, tint = NK.Link, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Аккаунт и подписка", color = NK.Link, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = NK.Off)
            }
        }
    }
}


/** Тема: как в системе / светлая / тёмная. */
@Composable
fun ThemePicker() {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NK.Surface).border(1.dp, NK.Line, RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(Triple(ThemePref.SYSTEM, "Как в системе", Icons.Default.BrightnessAuto), Triple(ThemePref.LIGHT, "Светлая", Icons.Default.LightMode),
               Triple(ThemePref.DARK, "Тёмная", Icons.Default.DarkMode)).forEach { (m, t, ic) ->
            val sel = ThemePref.mode == m
            Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) NK.AccentSoft else NK.Surface)
                       .clickable { ThemePref.set(ctx, m) }.padding(vertical = 10.dp),
                   horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(ic, null, tint = if (sel) NK.Link else NK.Muted, modifier = Modifier.size(20.dp))
                Text(t, color = if (sel) NK.AccentText else NK.Muted, fontSize = 12.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
