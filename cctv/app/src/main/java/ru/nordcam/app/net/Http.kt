package ru.nordcam.app.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Клиент с авторизацией Basic/Digest — камеры используют обе. */
    fun withAuth(user: String, pass: String, readTimeoutSec: Long = 15): OkHttpClient =
        client.newBuilder()
            .authenticator(CamAuthenticator(user, pass))
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .build()

    /** Скачать снимок JPEG. */
    fun snapshot(url: String, user: String, pass: String): ByteArray? = try {
        val req = Request.Builder().url(url).header("User-Agent", "NordCam").build()
        withAuth(user, pass).newCall(req).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.bytes()?.takeIf { it.size > 100 }
        }
    } catch (e: Exception) {
        null
    }

    fun decode(bytes: ByteArray, maxSide: Int = 0): Bitmap? = try {
        if (maxSide <= 0) BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (o.outWidth / (s * 2) >= maxSide && o.outHeight / (s * 2) >= maxSide) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (e: Exception) {
        null
    }
}

/** Поддержка HTTP Digest (RFC 2617, MD5, qop=auth) и Basic. */
class CamAuthenticator(private val user: String, private val pass: String) : Authenticator {
    private var nc = 0

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 3) return null
        val headers = response.headers("WWW-Authenticate")
        val digest = headers.firstOrNull { it.trim().startsWith("Digest", ignoreCase = true) }
        if (digest != null) {
            val p = parseParams(digest.trim().substring(6))
            val realm = p["realm"] ?: ""
            val nonce = p["nonce"] ?: return null
            val opaque = p["opaque"]
            val qop = p["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
            val algorithm = p["algorithm"] ?: "MD5"
            val uri = response.request.url.encodedPath +
                (response.request.url.encodedQuery?.let { "?$it" } ?: "")
            val method = response.request.method
            val cnonce = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            nc++
            val ncs = "%08x".format(nc)
            var ha1 = md5("$user:$realm:$pass")
            if (algorithm.equals("MD5-sess", true)) ha1 = md5("$ha1:$nonce:$cnonce")
            val ha2 = md5("$method:$uri")
            val resp = if (qop != null) md5("$ha1:$nonce:$ncs:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
            val sb = StringBuilder("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\"")
            if (p.containsKey("algorithm")) sb.append(", algorithm=$algorithm")
            if (opaque != null) sb.append(", opaque=\"$opaque\"")
            if (qop != null) sb.append(", qop=$qop, nc=$ncs, cnonce=\"$cnonce\"")
            return response.request.newBuilder().header("Authorization", sb.toString()).build()
        }
        if (headers.any { it.trim().startsWith("Basic", ignoreCase = true) }) {
            return response.request.newBuilder().header("Authorization", Credentials.basic(user, pass)).build()
        }
        return null
    }

    private fun responseCount(r: Response): Int {
        var n = 1
        var p = r.priorResponse
        while (p != null) { n++; p = p.priorResponse }
        return n
    }

    private fun parseParams(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("""(\w+)=(?:"([^"]*)"|([^,\s]*))""").findAll(s).forEach {
            out[it.groupValues[1].lowercase()] = it.groupValues[2].ifEmpty { it.groupValues[3] }
        }
        return out
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
