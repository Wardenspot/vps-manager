package ru.nordkey.app.ui.setup

import kotlinx.coroutines.delay
import org.json.JSONObject
import ru.nordkey.app.net.Api
import ru.nordkey.app.net.Keenetic
import ru.nordkey.app.net.KeeneticException
import ru.nordkey.app.net.Server
import java.security.SecureRandom

/** Шаги мастера — как их видит пользователь. */
enum class Step(val title: String) {
    Login("Вход в роутер"),
    Ssh("Сервер SSH на роутере"),
    Tunnel("Туннель NordKey"),
    Server("Регистрация на сервере"),
    Access("Доступ для сервера"),
    Finish("Настройка сайтов и проверка"),
}

enum class StepStatus { Wait, Run, Done, Fail }

class SetupFailed(msg: String) : Exception(msg)

/** Итог: адрес роутера в VPN и персональная ссылка клиента на бота (у нового клиента). */
data class SetupResult(val ip: String, val link: String?, val newClient: Boolean, val note: String?)

/**
 * Подключение роутера к NordKey — то же, что делает батник (setup.ps1), только из приложения:
 * роутер настраивается через его веб-API по Wi-Fi, а сервер получает заявку через nk-api (/v1/staff/onboard).
 */
class RouterSetup(
    private val k: Keenetic,
    private val clientName: String,
    private val event: (Step, StepStatus, String?) -> Unit,
    private val serverMsg: (String) -> Unit,
) {
    companion object {
        const val SRV_KEY = "jwn8oUjJ2IUJv9a0i/JVGXPgYZMIjvlYLjiHenuzh3w="
        const val ASC = "4 40 70 107 53 1068678846 1558046116 2145091299 1103781949"
        val KNOWN = listOf("143.246.193.43", "143.246.193.103")
        private const val ABC = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        fun password(n: Int = 24): String { val r = SecureRandom(); return (1..n).map { ABC[r.nextInt(ABC.length)] }.joinToString("") }

        /** Туннель NordKey в конфиге роутера: имя интерфейса, его адрес в VPN и кусок конфига. */
        fun findTunnel(cfg: String): Triple<String, String, String>? {
            for (m in Regex("interface (Wireguard\\d+)").findAll(cfg)) {
                var end = cfg.indexOf("interface ", m.range.first + 10)
                if (end < 0) end = cfg.length
                val seg = cfg.substring(m.range.first, end)
                if (seg.contains(SRV_KEY)) return Triple(m.groupValues[1], Regex("ip address (10\\.66\\.66\\.\\d+)").find(seg)?.groupValues?.get(1) ?: "", seg)
            }
            return null
        }

        fun freeWireguard(cfg: String): String {
            val used = Regex("interface Wireguard(\\d+)").findAll(cfg).map { it.groupValues[1].toInt() }.toSet()
            var n = 0
            while (n in used) n++
            return "Wireguard$n"
        }
    }

    private val ngPass = password()
    private val endpoint = "${Server.HOST.takeIf { it in KNOWN } ?: KNOWN[0]}:35180"

    private suspend fun ok(vararg cmds: String, what: String) {
        val e = Keenetic.errors(k.rci(*cmds))
        if (e.isNotEmpty()) throw SetupFailed("$what: ${e.joinToString("; ")}")
    }

    /** Ждём от сервера нужное состояние заявки; показываем его сообщения. */
    private suspend fun waitServer(run: String, want: String, seconds: Int, allowMove: Boolean = false): JSONObject {
        var last = ""
        val until = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < until) {
            val s = try { Api.get("/v1/staff/onboard/status?run=$run") } catch (e: Exception) { null }
            if (s != null && s.optString("run") == run) {
                val msg = s.optString("msg")
                if (msg.isNotBlank() && msg != last) { serverMsg(msg); last = msg }
                val st = s.optString("state")
                if (st == want || st == "err" || (allowMove && st == "move")) return s
            }
            delay(3000)
        }
        throw SetupFailed("сервер не ответил вовремя — итог придёт администратору в Telegram")
    }

    suspend fun run(login: String, pass: String): SetupResult {
        // 1. вход
        event(Step.Login, StepStatus.Run, null)
        try { k.login(login, pass) } catch (e: KeeneticException) { event(Step.Login, StepStatus.Fail, e.message); throw SetupFailed(e.message ?: "не вошёл") }
        val ver = k.rci("show version")
        val model = Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").find(ver)?.groupValues?.get(1)
        event(Step.Login, StepStatus.Done, model)

        // 2. SSH — без него сервер не сможет управлять роутером, проверяем до любых изменений
        event(Step.Ssh, StepStatus.Run, null)
        if (!Regex("\\bssh\\b").containsMatchIn(ver)) {
            val m = "на роутере нет компонента «Сервер SSH». Откройте настройки роутера → Общие настройки → Изменить набор компонентов → " +
                    "отметьте «Сервер SSH» и установите (роутер перезагрузится), затем запустите мастер снова"
            event(Step.Ssh, StepStatus.Fail, m); throw SetupFailed(m)
        }
        ok("service ssh", "system configuration save", what = "включение SSH")
        event(Step.Ssh, StepStatus.Done, "включён")

        // 3. туннель
        event(Step.Tunnel, StepStatus.Run, null)
        val cfg = k.rci("show running-config")
        val found = findTunnel(cfg)
        var wif: String
        var ip: String
        var seg = found?.third ?: ""
        val newClient = found == null
        val run: String
        if (found != null) {
            wif = found.first; ip = found.second
            if (ip.isBlank()) { event(Step.Tunnel, StepStatus.Fail, "туннель есть ($wif), но без адреса"); throw SetupFailed("туннель NordKey есть ($wif), но без адреса — напишите администратору") }
            event(Step.Tunnel, StepStatus.Done, "уже есть: $wif, $ip")
            event(Step.Server, StepStatus.Run, null)
            val mykey = Regex("\"public-key\"\\s*:\\s*\"([A-Za-z0-9+/]{43}=)\"").findAll(k.rci("show interface $wif")).map { it.groupValues[1] }.firstOrNull { it != SRV_KEY }
            run = Api.post("/v1/staff/onboard", "step" to "reg", "ip" to ip, "key" to (mykey ?: ""), "pass" to ngPass).getString("run")
            val s = waitServer(run, "ready", 60)
            if (s.optString("state") != "ready") { event(Step.Server, StepStatus.Fail, s.optString("msg")); throw SetupFailed(s.optString("msg")) }
            event(Step.Server, StepStatus.Done, "сервер знает этот роутер")
        } else {
            val idn = k.rci("show identification")
            val serial = Regex("\"serial\"\\s*:\\s*\"([A-Za-z0-9]+)\"").find(idn)?.groupValues?.get(1)
            val mac = Regex("\"mac\"\\s*:\\s*\"([0-9A-Fa-f:]{17})\"").find(idn)?.groupValues?.get(1)
            if (serial == null) { event(Step.Tunnel, StepStatus.Fail, "нет серийного номера"); throw SetupFailed("не удалось прочитать серийный номер роутера") }
            wif = freeWireguard(cfg)
            ok("interface $wif", what = "создание туннеля")
            delay(1000)
            val mykey = Regex("\"public-key\"\\s*:\\s*\"([A-Za-z0-9+/]{43}=)\"").find(k.rci("show interface $wif"))?.groupValues?.get(1)
            if (mykey == null) { k.rci("no interface $wif"); event(Step.Tunnel, StepStatus.Fail, "нет ключа"); throw SetupFailed("роутер не создал ключ туннеля — роутер вернул как было") }
            event(Step.Tunnel, StepStatus.Done, "создан $wif")
            event(Step.Server, StepStatus.Run, null)
            val r = try {
                Api.post("/v1/staff/onboard", "step" to "new", "key" to mykey, "pass" to ngPass, "serial" to serial, "mac" to mac?.lowercase(), "name" to clientName)
            } catch (e: Exception) { k.rci("no interface $wif"); event(Step.Server, StepStatus.Fail, e.message); throw SetupFailed("${e.message} — роутер вернул как было") }
            run = r.getString("run")
            val s = waitServer(run, "ready", 60)
            if (s.optString("state") != "ready") { k.rci("no interface $wif"); event(Step.Server, StepStatus.Fail, s.optString("msg")); throw SetupFailed(s.optString("msg") + " — роутер вернул как было") }
            ip = s.getString("ip")
            ok("interface $wif description NordKey", "interface $wif security-level public", "interface $wif ip address $ip 255.255.255.255",
               "interface $wif ip mtu 1420", "interface $wif ip tcp adjust-mss pmtu", "interface $wif wireguard asc $ASC",
               "interface $wif wireguard peer $SRV_KEY", "interface $wif wireguard peer $SRV_KEY endpoint $endpoint",
               "interface $wif wireguard peer $SRV_KEY keepalive-interval 25", "interface $wif wireguard peer $SRV_KEY allow-ips 0.0.0.0 0.0.0.0",
               "interface $wif up", "system configuration save", what = "настройка туннеля")
            k.rci("interface $wif wireguard peer $SRV_KEY connect", "system configuration save")
            event(Step.Server, StepStatus.Done, "клиент создан, адрес $ip")
        }

        // 4. доступ для сервера: SSH-пользователь netguard, фильтр, маршрут к серверу, адрес сервера
        event(Step.Access, StepStatus.Run, null)
        val acl = "_WEBADMIN_$wif"
        val c = mutableListOf<String>()
        if (!cfg.contains("access-list $acl")) c += listOf("access-list $acl permit icmp 10.66.66.1 255.255.255.255 0.0.0.0 0.0.0.0",
                                                           "access-list $acl permit tcp 10.66.66.1 255.255.255.255 0.0.0.0 0.0.0.0 port eq 22")
        else {
            if (!cfg.contains("permit icmp 10.66.66.1 255.255.255.255")) c += "access-list $acl permit icmp 10.66.66.1 255.255.255.255 0.0.0.0 0.0.0.0"
            if (!cfg.contains("permit tcp 10.66.66.1 255.255.255.255 0.0.0.0 0.0.0.0 port eq 22")) c += "access-list $acl permit tcp 10.66.66.1 255.255.255.255 0.0.0.0 0.0.0.0 port eq 22"
        }
        c += listOf("interface $wif ip access-group $acl in", "user netguard", "user netguard password plain $ngPass", "user netguard tag cli", "system configuration save")
        ok(*c.toTypedArray(), what = "доступ для сервера")
        if (!cfg.contains("ip route 10.66.66.1 $wif")) k.rci("ip route 10.66.66.1 255.255.255.255 $wif auto", "system configuration save")
        if (!newClient && !(KNOWN.any { seg.contains("endpoint $it:35180") } && seg.contains("wireguard asc $ASC")))
            ok("interface $wif wireguard asc $ASC", "interface $wif wireguard peer $SRV_KEY endpoint $endpoint", "system configuration save", what = "переключение туннеля")
        event(Step.Access, StepStatus.Done, null)

        // 5. сервер сам заходит по SSH и донастраивает
        event(Step.Finish, StepStatus.Run, null)
        Api.post("/v1/staff/onboard", "step" to "done", "ip" to ip, "run" to run)
        var s = waitServer(run, "ok", if (newClient) 1500 else 600, allowMove = true)
        if (s.optString("state") == "move") {
            ip = s.getString("ip")
            ok("interface $wif ip address $ip 255.255.255.255", "system configuration save", what = "возврат прежнего адреса")
            serverMsg("роутер уже был клиентом NordKey — вернул ему прежний адрес $ip")
            Api.post("/v1/staff/onboard", "step" to "done", "ip" to ip, "run" to run)
            s = waitServer(run, "ok", 1500)
        }
        val link = s.optString("link").ifBlank { null }
        if (s.optString("state") != "ok") {
            event(Step.Finish, StepStatus.Fail, s.optString("msg").take(300))
            if (link != null) return SetupResult(ip, link, newClient, s.optString("msg"))
            throw SetupFailed(s.optString("msg").ifBlank { "сервер сообщил об ошибке" })
        }
        event(Step.Finish, StepStatus.Done, null)
        return SetupResult(ip, link, newClient, null)
    }
}
