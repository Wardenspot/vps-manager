package ru.nordkey.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import ru.nordkey.app.net.Api
import ru.nordkey.app.ui.*
import ru.nordkey.app.ui.staff.AccountScreen
import ru.nordkey.app.ui.staff.ClientsScreen

/** Снимки экранов на выдуманных данных (сервер не нужен): светлая и тёмная тема. */
class ScreensTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 4200), maxPercentDifference = 1.0, useDeviceResolution = true)

    private val staffPerms = listOf("clients.view", "routers.view", "routers.act", "routers.admin", "subs.view", "subs.edit", "msg", "ai",
                                    "newclient", "bat", "broadcast", "users.view", "audit.view", "server.view")

    private fun me(role: String, name: String, tg: Long, perms: List<String>) = JSONObject(
        """{"account": {"role": "$role", "role_name": "${if (role == "owner") "Владелец" else "Клиент"}", "name": "$name", "tg_id": $tg,
            "perms": ${perms.joinToString(",", "[", "]") { "\"$it\"" }}}, "routers": [], "app": {}}""")

    private val sub2 = """{"expires_at": "2026-12-20T10:00:00", "active": true, "days_left": 40, "trial": false, "devices": 2, "price_mult": 2.0}"""
    private val sub1 = """{"expires_at": "2027-06-01T19:54:00", "active": true, "days_left": 204, "trial": false, "devices": 1, "price_mult": 1.0}"""
    private val subG = """{"expires_at": "2027-06-02T12:50:00", "active": true, "days_left": 205, "trial": false, "devices": 1, "price_mult": 1.0}"""
    private val anast = """{"tg_id": 992762350, "level": "owner", "main": true, "name": "Анастасия Филенкова", "username": "AnastasiaflowersGarden"}"""
    private val roman = """{"tg_id": 699205706, "level": "member", "main": false, "name": "Роман", "username": "Rom_an111"}"""
    private val garden = """{"tg_id": 5133693803, "level": "member", "main": false, "name": "Garden", "username": "Gardenfl00"}"""

    private val clients = """{"clients": [
      {"ip": "10.66.66.6", "name": "AutoPlus33 — офис", "migrated": true, "online": true, "handshake_ago": 20, "rtt": 38.2, "owner_tg": 962197096, "owner_name": "Авто Плюс", "tg_name": "autoplus33", "subscription": $sub2, "family": 0},
      {"ip": "10.66.66.152", "name": "AutoPlus33 — склад", "migrated": true, "online": true, "handshake_ago": 40, "rtt": 41.0, "owner_tg": 962197096, "owner_name": "Авто Плюс", "subscription": $sub2, "family": 0},
      {"ip": "10.66.66.14", "name": "Анастасия — дом", "migrated": true, "online": true, "handshake_ago": 12, "rtt": 34.0, "owner_tg": 992762350, "owner_name": "Анастасия Филенкова", "subscription": $sub1, "family": 2},
      {"ip": "10.66.66.16", "name": "Garden — магазин", "migrated": true, "online": true, "handshake_ago": 30, "rtt": 47.5, "owner_tg": 5133693803, "owner_name": "Garden", "subscription": $subG, "family": 2},
      {"ip": "10.66.66.7", "name": "nutriMARIYA", "migrated": true, "online": false, "handshake_ago": 7200, "rtt": null, "owner_tg": 11, "subscription": $sub1, "family": 0},
      {"ip": "10.66.66.9", "name": "Казань — дача", "migrated": true, "online": true, "handshake_ago": 50, "rtt": 182.0, "owner_tg": 12, "subscription": {"expires_at": "2026-11-01T10:00:00", "active": false, "days_left": 0, "trial": false, "devices": 1, "price_mult": 1.0}, "family": 0},
      {"ip": "10.66.66.5", "name": "Андрей Пискарев", "migrated": false, "online": false, "handshake_ago": null, "rtt": null, "owner_tg": 13, "family": 0}
    ]}"""

    private fun router(level: String) = """{"ip": "10.66.66.14", "name": "Анастасия — дом", "migrated": true, "online": true, "handshake_ago": 12, "rtt": 34.0,
        "level": "$level", "subscription": ${if (level == "member") sub1 else sub2}, "game": {"available": false, "on": false}, "owner_tg": 992762350,
        "family": 2, "owner_name": "Анастасия Филенкова", "access": [$anast, $roman, $garden]}"""

    private val hist = JSONObject().put("points", org.json.JSONArray().apply {
        for (i in 0 until 96) put(JSONObject().put("rtt", if (i == 40) JSONObject.NULL else 30 + (i * 7 % 23) + (if (i in 60..64) 90 else 0)))
    })

    private val accAnast = """{"tg_id": 992762350, "name": "Анастасия Филенкова", "username": "AnastasiaflowersGarden", "subscription": $sub1,
        "routers": ["10.66.66.14", "10.66.66.16"], "app_role": null,
        "family": [{"ip": "10.66.66.14", "name": "Анастасия — дом", "level": "owner", "members": [$roman, $garden]},
                   {"ip": "10.66.66.16", "name": "Garden — магазин", "level": "member", "owner": {"tg_id": 5133693803, "name": "Garden", "username": "Gardenfl00"}}],
        "configs": [{"id": 14, "ip": "10.66.66.14", "protocol": "wg", "active": 1, "kind": "router", "created_at": "2026-05-01"}],
        "payments": [{"amount": 1700, "plan": "6m", "status": "paid", "created_at": "2026-06-01T19:54:00"}], "support": []}"""

    private val accAuto = """{"tg_id": 962197096, "name": "Авто Плюс", "username": "autoplus33", "subscription": $sub2,
        "routers": ["10.66.66.6", "10.66.66.152"], "family": [{"ip": "10.66.66.6", "name": "AutoPlus33 — офис", "level": "owner", "members": []},
                                                            {"ip": "10.66.66.152", "name": "AutoPlus33 — склад", "level": "owner", "members": []}],
        "configs": [{"id": 6, "ip": "10.66.66.6", "protocol": "wg", "active": 1, "kind": "router", "created_at": "2026-05-01"},
                    {"id": 60, "ip": "10.66.66.152", "protocol": "wg", "active": 1, "kind": "router", "created_at": "2026-11-05"}],
        "payments": [], "support": [{"ts": 1762700000, "sender": "client", "text": "на складе не открывается озон"}, {"ts": 1762700060, "sender": "ai", "text": "Проверил роутер «AutoPlus33 — склад» — Ozon идёт напрямую, всё открывается."}]}"""

    private val myRouters = """{"routers": [
      {"ip": "10.66.66.14", "name": "Анастасия — дом", "migrated": true, "online": true, "handshake_ago": 12, "rtt": 34.0, "level": "owner", "family": 2, "owner_name": "Анастасия Филенкова"},
      {"ip": "10.66.66.16", "name": "Garden — магазин", "migrated": true, "online": true, "handshake_ago": 30, "rtt": 47.5, "level": "member", "family": 2, "owner_name": "Garden"}]}"""

    private fun routes(path: String): String = when {
        path == "/v1/staff/clients" -> clients
        path.startsWith("/v1/routers/10.66.66.14/history") -> hist.toString()
        path == "/v1/routers/10.66.66.14" -> currentRouter
        path == "/v1/staff/accounts/992762350/info" -> accAnast
        path == "/v1/staff/accounts/962197096/info" -> accAuto
        path == "/v1/routers" -> myRouters
        path == "/v1/subscription" -> """{"subscription": $sub2, "pay_url": "https://t.me/x"}"""
        path == "/v1/me" -> me("client", "Анастасия", 992762350, emptyList()).put("routers", JSONObject(myRouters).getJSONArray("routers")).toString()
        else -> "{}"
    }

    private var currentRouter = router("staff")

    @Before fun setUp() { Api.fake = { _, path, _ -> JSONObject(routes(path.substringBefore('?').let { if (path.contains("history")) path else it })) } }
    @After fun tearDown() { Api.fake = null }

    private fun snap(name: String, content: @Composable () -> Unit) {
        for (dark in listOf(false, true)) {
            paparazzi.snapshot(name = name + if (dark) "_dark" else "_light") {
                NordKeyTheme(dark = dark) { Surface(Modifier.fillMaxSize(), color = NK.Bg, contentColor = NK.Text) { content() } }
            }
        }
    }

    private val staff get() = Me(me("owner", "Дмитрий", 1, staffPerms))
    private val client get() = Me(me("client", "Анастасия", 992762350, emptyList()))
    private fun nav() = Nav(mutableStateListOf())

    @Test fun clients() = snap("clients") { ClientsScreen(staff, nav()) }
    @Test fun routerStaff() { currentRouter = router("staff"); snap("router_staff") { RouterScreen("10.66.66.14", staff, nav()) } }
    @Test fun routerOwner() { currentRouter = router("owner"); snap("router_owner") { RouterScreen("10.66.66.14", client, nav()) } }
    @Test fun routerMember() { currentRouter = router("member").replace(""""access": [$anast, $roman, $garden]""", """"x": 1"""); snap("router_member") { RouterScreen("10.66.66.14", client, nav()) } }
    @Test fun accountFamily() = snap("account_family") { AccountScreen(992762350, staff, nav()) }
    @Test fun accountTwoRouters() = snap("account_two_routers") { AccountScreen(962197096, staff, nav()) }
    @Test fun myRouters() = snap("my_routers") { MyRoutersScreen(client, nav(), withBar = false) }
    @Test fun subscription() = snap("subscription") { SubscriptionScreen(nav(), withBar = false) }
    @Test fun more() = snap("more") { MoreScreen(client, nav(), {}, {}) }
}
