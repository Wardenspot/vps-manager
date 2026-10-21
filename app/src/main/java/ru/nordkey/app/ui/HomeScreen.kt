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
import androidx.compose.ui.graphics.vector.ImageVector
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

    Box(Modifier.fillMaxSize().background(NK.Bg)) {
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
        containerColor = NK.Bg,
        topBar = { UpdateBanner(json) },
        bottomBar = {
            NavigationBar(containerColor = NK.Bar) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = sel == i, onClick = { sel = i },
                        icon = { Icon(t.icon, null) }, label = { Text(t.title, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NK.Link, selectedTextColor = NK.Link, indicatorColor = NK.AccentSoft,
                            unselectedIconColor = NK.Muted, unselectedTextColor = NK.Muted),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) { tabs[sel].content(nav, me) }
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
