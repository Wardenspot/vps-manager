package ru.nordkey.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class KeeneticException(msg: String) : Exception(msg)

/**
 * Роутер Keenetic в локальной сети (телефон подключён к его Wi-Fi): вход как в веб-интерфейсе и команды CLI через RCI.
 * Запросы идут именно через Wi-Fi (network), даже если у Wi-Fi нет интернета и телефон держит мобильную сеть.
 */
class Keenetic(val host: String, network: Network? = null, quick: Boolean = false) {
    private val jar = object : CookieJar {
        private val store = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            store.removeAll { c -> cookies.any { it.name == c.name } }; store.addAll(cookies)
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = store.toList()
    }
    private val http = OkHttpClient.Builder()
        .cookieJar(jar)
        .connectTimeout(if (quick) 2L else 6L, TimeUnit.SECONDS)
        .readTimeout(if (quick) 4L else 90L, TimeUnit.SECONDS)
        .apply { network?.let { socketFactory(it.socketFactory) } }
        .build()
    private val json = "application/json".toMediaType()

    /** Отвечает ли по этому адресу роутер Keenetic. */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url("http://$host/auth").build()).execute().use { it.code == 200 || it.header("X-NDM-Challenge") != null }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun login(user: String, pass: String) = withContext(Dispatchers.IO) {
        val first = try {
            http.newCall(Request.Builder().url("http://$host/auth").build()).execute()
        } catch (e: Exception) {
            throw KeeneticException("роутер не отвечает по адресу $host")
        }
        first.close()
        if (first.code == 200) return@withContext
        val realm = first.header("X-NDM-Realm") ?: throw KeeneticException("по адресу $host не роутер Keenetic")
        val challenge = first.header("X-NDM-Challenge") ?: throw KeeneticException("по адресу $host не роутер Keenetic")
        val body = JSONObject().put("login", user).put("password", authHash(user, pass, realm, challenge)).toString().toRequestBody(json)
        http.newCall(Request.Builder().url("http://$host/auth").post(body).build()).execute().use {
            if (it.code != 200) throw KeeneticException("неверный логин или пароль роутера")
        }
    }

    /** Команды CLI → сырой ответ RCI (как у батника). */
    suspend fun rci(vararg cmds: String): String = withContext(Dispatchers.IO) {
        val arr = JSONArray().apply { cmds.forEach { put(JSONObject().put("parse", it)) } }
        val req = Request.Builder().url("http://$host/rci/").post(arr.toString().toRequestBody(json)).build()
        try {
            http.newCall(req).execute().use { r ->
                if (r.code == 401) throw KeeneticException("роутер завершил сеанс — войдите заново")
                r.body?.string().orEmpty().replace("\\/", "/")
            }
        } catch (e: KeeneticException) {
            throw e
        } catch (e: Exception) {
            throw KeeneticException("связь с роутером прервалась: ${e.message}")
        }
    }

    companion object {
        fun hex(alg: String, s: String) = MessageDigest.getInstance(alg).digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Пароль для входа: SHA256(challenge + MD5(login:realm:password)) — как в веб-интерфейсе Keenetic. */
        fun authHash(user: String, pass: String, realm: String, challenge: String) = hex("SHA-256", challenge + hex("MD5", "$user:$realm:$pass"))

        /** Тексты ошибок из ответа RCI. */
        fun errors(resp: String): List<String> =
            Regex("\"status\"\\s*:\\s*\"error\"[^}]*\"message\"\\s*:\\s*\"([^\"]*)\"").findAll(resp).map { it.groupValues[1] }.toList()

        /** Wi-Fi телефона: подключён ли, сама сеть и адреса, где может быть роутер (шлюз, DHCP-сервер, x.y.z.1, привычные). */
        data class WifiInfo(val connected: Boolean, val network: Network?, val candidates: List<String>)

        @Suppress("DEPRECATION")
        fun wifi(ctx: Context): WifiInfo {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val out = LinkedHashSet<String>()
            var net: Network? = null
            var connected = false
            fun addFrom(lp: android.net.LinkProperties?) {
                if (lp == null) return
                lp.routes.filter { it.gateway is Inet4Address && !it.gateway!!.isAnyLocalAddress }
                    .sortedByDescending { it.isDefaultRoute }.forEach { r -> r.gateway?.hostAddress?.let { out += it } }
                if (android.os.Build.VERSION.SDK_INT >= 30) (lp.dhcpServerAddress as? Inet4Address)?.hostAddress?.let { out += it }
                lp.linkAddresses.mapNotNull { it.address as? Inet4Address }.filter { it.isSiteLocalAddress }.forEach { a ->
                    val b = a.address; out += "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}.1"
                }
            }
            try {
                cm?.allNetworks?.forEach { n ->
                    val caps = cm.getNetworkCapabilities(n) ?: return@forEach
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        connected = true; if (net == null) net = n; addFrom(cm.getLinkProperties(n))
                    }
                }
            } catch (_: Exception) {
            }
            // запасной путь — старый WifiManager: работает и там, где ConnectivityManager прячет сеть
            try {
                val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                if (wm != null && wm.isWifiEnabled) {
                    val gw = wm.dhcpInfo?.gateway ?: 0
                    if (gw != 0) {
                        connected = true
                        out += "${gw and 255}.${gw shr 8 and 255}.${gw shr 16 and 255}.${gw shr 24 and 255}"
                    }
                }
            } catch (_: Exception) {
            }
            out += listOf("192.168.1.1", "192.168.0.1", "my.keenetic.net")
            return WifiInfo(connected, net, out.toList())
        }

        /** Первый адрес, где отвечает Keenetic (по очереди, через Wi-Fi). */
        suspend fun find(hosts: List<String>, network: Network?): String? {
            for (h in hosts) if (Keenetic(h, network, quick = true).probe()) return h
            return null
        }
    }
}
