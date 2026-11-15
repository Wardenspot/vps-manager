package ru.nordkey.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import ru.nordkey.app.BuildConfig
import ru.nordkey.app.net.Api
import ru.nordkey.app.ui.staff.*
import ru.nordkey.app.util.openUrl

/** Экраны поверх вкладок. */
sealed interface Scr {
    data class Router(val ip: String) : Scr
    data object MyRouters : Scr
    data object Devices : Scr
    data object Subscription : Scr
    data object Support : Scr
    data object Sessions : Scr
    data object Users : Scr
    data object Audit : Scr
    data object Bat : Scr
    data object Broadcast : Scr
    data object NewClient : Scr
    data object Assistant : Scr
    data class Incident(val id: Long) : Scr
    data class Account(val tg: Long) : Scr
    data object SupportInbox : Scr
    data object Setup : Scr
    data object Help : Scr
}

class Nav(private val stack: SnapshotStateList<Scr>) {
    fun go(s: Scr) { stack.add(s) }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
}

/** Права текущего пользователя. */
class Me(val json: JSONObject) {
    private val acc = json.getJSONObject("account")
    val role: String = acc.optString("role")
    val roleName: String = acc.optString("role_name")
    val name: String = acc.optString("name")
    val tgId: Long = acc.optLong("tg_id")
    private val perms = acc.optJSONArray("perms").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet() }
    fun can(p: String) = p in perms
    val staff get() = can("clients.view")
}

data class Tab(val title: String, val icon: ImageVector, val content: @Composable (Nav, Me) -> Unit)

@Composable
fun HomeScreen(onLogout: () -> Unit) {
    val meLoad = rememberLoad { Api.get("/v1/me") }
    val stack = remember { mutableStateListOf<Scr>() }
    val nav = remember { Nav(stack) }
    BackHandler(enabled = stack.isNotEmpty()) { nav.back() }

    Box(Modifier.fillMaxSize().nkBackground()) {
        Loaded(meLoad) { json ->
            val me = remember(json) { Me(json) }
            val top = stack.lastOrNull()
            if (top != null) {
                when (top) {
                    is Scr.Router -> RouterScreen(top.ip, me, nav)
                    Scr.MyRouters -> MyRoutersScreen(me, nav, withBar = true)
                    Scr.Devices -> DevicesScreen(nav, withBar = true)
                    Scr.Subscription -> SubscriptionScreen(nav, withBar = true)
                    Scr.Support -> SupportScreen(nav)
                    Scr.Sessions -> SessionsScreen(nav, onLogout)
                    Scr.Users -> UsersScreen(me, nav)
                    Scr.Audit -> AuditScreen(nav)
                    Scr.Bat -> BatScreen(nav)
                    Scr.Broadcast -> BroadcastScreen(nav)
                    Scr.NewClient -> NewClientScreen(nav)
                    Scr.Assistant -> AssistantScreen(nav, withBar = true)
                    is Scr.Incident -> IncidentScreen(top.id, me, nav)
                    is Scr.Account -> AccountScreen(top.tg, me, nav)
                    Scr.SupportInbox -> SupportInboxScreen(nav)
                    Scr.Setup -> ru.nordkey.app.ui.setup.SetupWizardScreen(onBack = nav::back)
                    Scr.Help -> HelpScreen(me, nav)
                }
            } else {
                Tabs(me, json, nav, onLogout, meLoad.reload)
            }
        }
    }
}

@Composable
private fun Tabs(me: Me, json: JSONObject, nav: Nav, onLogout: () -> Unit, reloadMe: () -> Unit) {
    val tabs = buildList {
        if (me.staff) {
            add(Tab("Главная", Icons.Default.Home) { n, m -> DashboardScreen(m, n) })
            add(Tab("Клиенты", Icons.Default.Router) { n, m -> ClientsScreen(m, n) })
            add(Tab("Автопилот", Icons.Default.SmartToy) { n, m -> AutopilotScreen(m, n) })
            if (me.can("ai")) add(Tab("ИИ", Icons.Default.AutoAwesome) { n, _ -> AssistantScreen(n, withBar = false) })
        } else {
            add(Tab("Роутеры", Icons.Default.Router) { n, m -> MyRoutersScreen(m, n, withBar = false) })
            add(Tab("Устройства", Icons.Default.PhoneAndroid) { n, _ -> DevicesScreen(n, withBar = false) })
            add(Tab("Подписка", Icons.Default.CreditCard) { n, _ -> SubscriptionScreen(n, withBar = false) })
        }
        add(Tab("Ещё", Icons.Default.MoreHoriz) { n, m -> MoreScreen(m, n, onLogout, reloadMe) })
    }
    var sel by rememberSaveable { mutableIntStateOf(0) }
    if (sel >= tabs.size) sel = 0
    Scaffold(
        containerColor = Color.Transparent,
        topBar = { UpdateBanner(json) },
        bottomBar = { NkTabBar(tabs, sel) { sel = it } },
    ) { pad ->
        Box(Modifier.padding(pad)) { tabs[sel].content(nav, me) }
    }
}

/** Нижняя панель вкладок: «плавающая» стеклянная, выбранная вкладка — со светящейся подложкой. */
@Composable
private fun NkTabBar(tabs: List<Tab>, sel: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)
            .shadow(if (NK.dark) 0.dp else 14.dp, shape, ambientColor = Color(0x261E3A8A), spotColor = Color(0x261E3A8A))
            .clip(shape)
            .background(if (NK.dark) Brush.verticalGradient(listOf(Color(0xF0182339), Color(0xF00E1628))) else Brush.verticalGradient(listOf(Color.White, Color.White)))
            .border(1.dp, if (NK.dark) Color.White.copy(alpha = 0.08f) else NK.Line, shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        tabs.forEachIndexed { i, t ->
            val on = i == sel
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(20.dp))
                    .then(if (on) Modifier.background(Brush.verticalGradient(listOf(NK.Glow.copy(alpha = if (NK.dark) 0.30f else 0.14f), NK.Cyan.copy(alpha = if (NK.dark) 0.12f else 0.06f)))) else Modifier)
                    .clickable { onSelect(i) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, t.title, tint = if (on) NK.Link else NK.Muted, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(2.dp))
                Text(t.title, fontSize = 11.sp, color = if (on) NK.Link else NK.Muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

@Composable
private fun UpdateBanner(json: JSONObject) {
    val app = json.optJSONObject("app") ?: return
    val code = app.optInt("version_code", 0)
    val url = app.optString("apk_url")
    if (code <= BuildConfig.VERSION_CODE || url.isBlank()) return
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(NK.AccentSoft).clickable { openUrl(ctx, url) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.SystemUpdate, null, tint = NK.Link)
        Spacer(Modifier.width(10.dp))
        Text("Доступна новая версия приложения — нажмите, чтобы скачать", color = NK.AccentText, fontSize = 13.sp)
    }
}
