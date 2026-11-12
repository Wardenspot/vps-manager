package ru.nordkey.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Сервер NordKey. Сертификат самоподписанный — проверяем его по отпечатку SHA-256 (а не по цепочке).
 * Адресов у сервера несколько: если провайдер заблокировал один, приложение само пробует следующий
 * и запоминает тот, что ответил. 10.66.66.1 — адрес сервера внутри VPN (работает, когда VPN включён).
 */
object Server {
    val HOSTS = listOf("143.246.193.43", "143.246.193.103", "10.66.66.1")
    @Volatile var current = 0
    val HOST get() = HOSTS[current]
    const val CERT_SHA256 = "FF73B7792A7168B4ED276B5BF9389B5BCCA02B01F5D757F58D986E0330C9A278"
}

class ApiException(val code: Int, message: String) : Exception(message)

private object PinnedTrust : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certs not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("нет сертификата")
        val fp = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02X".format(it) }
        if (fp != Server.CERT_SHA256) throw CertificateException("чужой сертификат сервера")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

object Api {
    @Volatile var token: String? = null
    var onUnauthorized: (() -> Unit)? = null
    /** Только для снимков экранов в тестах: ответы без сервера. В приложении всегда null. */
    @Volatile var fake: ((method: String, path: String, body: JSONObject?) -> JSONObject)? = null

    private val client: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(PinnedTrust), null) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, PinnedTrust)
            // Сертификат выписан на IP — сверяем именно его; подлинность проверяет отпечаток выше.
            .hostnameVerifier { host, _ -> host in Server.HOSTS }
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(200, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun request(method: String, path: String, body: JSONObject?, host: String = Server.HOST): Request {
        val b = Request.Builder().url("https://$host$path")
        token?.let { b.header("Authorization", "Bearer $it") }
        if (method == "POST") b.post((body ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        return b.build()
    }

    suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        fake?.let { f -> return@withContext f(method, path, body) }
        val resp = try {
            execute(method, path, body)
        } catch (e: javax.net.ssl.SSLException) {
            throw ApiException(0, "Не удалось безопасно подключиться к серверу NordKey")
        } catch (e: java.io.IOException) {
            throw ApiException(0, "Нет связи с сервером. Проверьте интернет.")
        }
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (r.code == 401 && token != null) onUnauthorized?.invoke()
            if (!r.isSuccessful) throw ApiException(r.code, json.optString("error").ifBlank { "Ошибка сервера (${r.code})" })
            json
        }
    }

    suspend fun bytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val resp = try { execute("GET", path, null) } catch (e: java.io.IOException) {
            throw ApiException(0, "Нет связи с сервером")
        }
        resp.use { r ->
            if (!r.isSuccessful) {
                val msg = try { JSONObject(r.body?.string().orEmpty()).optString("error") } catch (e: Exception) { "" }
                throw ApiException(r.code, msg.ifBlank { "Ошибка сервера (${r.code})" })
            }
            r.body?.bytes() ?: ByteArray(0)
        }
    }

    /**
     * Отправляет запрос, начиная с адреса, который ответил последним. Если до адреса не достучаться,
     * пробует следующий. POST повторяется только при ошибке подключения (до отправки запроса),
     * чтобы одно действие не выполнилось дважды.
     */
    private fun execute(method: String, path: String, body: JSONObject?): okhttp3.Response {
        val start = Server.current
        var last: java.io.IOException? = null
        for (i in Server.HOSTS.indices) {
            val idx = (start + i) % Server.HOSTS.size
            try {
                val r = client.newCall(request(method, path, body, Server.HOSTS[idx])).execute()
                Server.current = idx
                return r
            } catch (e: java.io.IOException) {
                last = e
                if (method != "GET" && !beforeSend(e)) throw e
            }
        }
        throw last ?: java.io.IOException("нет адресов сервера")
    }

    /** Ошибка случилась до того, как запрос ушёл на сервер, — повторять безопасно. */
    private fun beforeSend(e: java.io.IOException): Boolean =
        e is java.net.ConnectException || e is java.net.NoRouteToHostException ||
            e is java.net.UnknownHostException || e is javax.net.ssl.SSLHandshakeException ||
            (e is java.net.SocketTimeoutException && (e.message ?: "").contains("connect", ignoreCase = true))

    suspend fun get(path: String) = call("GET", path)
    suspend fun post(path: String, vararg pairs: Pair<String, Any?>) = call("POST", path, obj(*pairs))
}

fun obj(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

fun JSONObject.dbl(key: String): Double? = if (isNull(key) || !has(key)) null else optDouble(key).takeIf { !it.isNaN() }

fun JSONObject.intOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
