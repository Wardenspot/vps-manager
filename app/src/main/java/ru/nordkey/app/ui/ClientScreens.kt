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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
                        Text("Пока нет роутеров", fontWeight = FontWeight.SemiBold)
                        Text("Если NordKey настроил вам роутер — откройте персональную ссылку из бота. " +
                             "Если роутер оформлен на другой аккаунт Telegram — получите там код в «🛜 Мой роутер» → «👨‍👩‍👧 Семейный доступ».",
                             color = NK.Muted, fontSize = 14.sp)
                    }
                }
                val mine = list.filter { it.optString("level") != "member" }
                val fam = list.filter { it.optString("level") == "member" }
                if (mine.isNotEmpty() && fam.isNotEmpty()) item { SectionTitle("Мои") }
                items(mine) { r -> RouterRow(r) { nav.go(Scr.Router(r.getString("ip"))) } }
                if (fam.isNotEmpty()) item {
                    SectionTitle("Семейный доступ")
                    Text("Роутеры родных и близких: подписку оплачивает владелец.", color = NK.Muted, fontSize = 13.sp,
                         modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
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
            Dot(when { !online -> NK.Off; slow -> NK.Warn; else -> NK.Ok })
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
                Text(if (!online) "офлайн" else rtt?.let { "${it.toInt()} мс" } ?: "онлайн",
                     color = when { !online -> NK.Muted; slow -> NK.WarnText; else -> NK.Text }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
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
                        Text("Как подключить", fontWeight = FontWeight.SemiBold)
                        Text("1. Установите приложение AmneziaWG.\n2. Нажмите «+» → «Сканировать QR» и наведите на код ниже — " +
                             "или «Поделиться файлом» и откройте его в AmneziaWG.\n3. Включите VPN.",
                             color = NK.AccentText, fontSize = 14.sp)
                        TextButton(onClick = { openUrl(ctx, "https://play.google.com/store/apps/details?id=org.amnezia.awg") }) {
                            Text("Скачать AmneziaWG")
                        }
                    }
                }
                if (list.isEmpty()) item {
                    NkCard {
                        Text("Пока нет конфигураций для телефона или компьютера.", fontWeight = FontWeight.SemiBold)
                        Text("Их выдаёт бот: «📄 Мой конфиг». Для роутера отдельный конфиг не нужен — он уже настроен.",
                             color = NK.Muted, fontSize = 14.sp)
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
                            Text("Не показывайте этот код посторонним — по нему можно подключиться к вашему VPN.", color = NK.WarnText, fontSize = 12.sp)
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
                NkCard(bg = if (active) NK.OkSoft else NK.WarnSoft, border = if (active) NK.Ok else NK.WarnLine) {
                    Text(if (active) "Подписка активна" else "Подписки нет", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                         color = if (active) NK.OkText else NK.WarnText)
                    if (s != null) Text(
                        (if (active) "до ${dateRu(s.str("expires_at"))} · осталось ${s.optInt("days_left")} дн." else "закончилась ${dateRu(s.str("expires_at"))}") +
                            (if (s.optBoolean("trial")) " · пробный период" else ""), color = NK.Text)
                    val dev = s?.optInt("devices", 0) ?: 0
                    if (dev > 1) Text("Устройств в подписке: $dev — продление сразу за все", color = NK.Text, fontSize = 14.sp)
                }
                Button(onClick = { openUrl(ctx, j.optString("pay_url")) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text(if (active) "Продлить" else "Оформить подписку", fontSize = 16.sp)
                }
                Text("Оплата проходит в Telegram-боте NordKey. После оплаты срок здесь обновится сам.", color = NK.Muted, fontSize = 13.sp)
                Text("Ещё одно устройство (второй телефон, компьютер, сервер) — в боте: «📄 Мой конфиг» → «➕ Добавить устройство». " +
                     "Член семьи может видеть ваш роутер со своего Telegram бесплатно: «🛜 Мой роутер» → «👨‍👩‍👧 Семейный доступ».",
                     color = NK.Muted, fontSize = 13.sp)
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
                Text("Опишите проблему — например «не открывается YouTube на телевизоре». Поддержка сразу проверит ваш роутер.", color = NK.Muted)
            }
            items(msgs) { m ->
                val mine = m.optString("sender") == "client"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                    Text(m.optString("text"), color = if (mine) Color.White else NK.Text, fontSize = 15.sp,
                         modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp))
                             .background(if (mine) NK.Accent else NK.Surface).padding(12.dp))
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
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(NK.Surface).border(1.dp, NK.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) NK.Bad else NK.Muted)
        Spacer(Modifier.width(12.dp))
        Text(title, color = if (danger) NK.Bad else NK.Text, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = NK.Off)
    }
}


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
    NkCard {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onOwner), verticalAlignment = Alignment.CenterVertically) {
            Avatar(owner, ownerTg, 34)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(owner, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                Text("${routers.size} ${plural(routers.size, "роутер", "роутера", "роутеров")}" +
                     (sub?.let { s -> " · " + (if (s.optBoolean("active")) "подписка до ${dateRu(s.str("expires_at")).take(5)}" else "нет подписки") +
                         (s.optDouble("price_mult", 1.0).takeIf { it != 1.0 }?.let { " · ×${multStr(it)}" } ?: "") } ?: ""),
                     color = if (sub?.optBoolean("active") == false) NK.WarnText else NK.Muted, fontSize = 12.sp, maxLines = 1)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = NK.Off)
        }
        routers.forEach { r ->
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
