package ru.nordcam.app.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Маленький HTTP-прокси на 127.0.0.1 для видеоплеера. Нужен, потому что плеер не умеет:
 * доверять самоподписанному сертификату NAS и подставлять cookie/заголовки входа.
 * Плеер открывает http://127.0.0.1:порт/t/<метка>/..., прокси скачивает с сервера и отдаёт как есть
 * (с поддержкой перемотки через Range). Относительные ссылки внутри HLS тоже проходят через него.
 */
object LocalProxy {
    private class Route(val base: String, val origin: String, val client: OkHttpClient, val headers: Map<String, String>)

    private val routes = ConcurrentHashMap<String, Route>()
    @Volatile private var last: Route? = null
    @Volatile private var server: ServerSocket? = null

    @Synchronized
    private fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = ss
        thread(name = "nordcam-proxy", isDaemon = true) {
            while (!ss.isClosed) {
                val sock = try { ss.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) { serve(sock) }
            }
        }
        return ss.localPort
    }

    /** Регистрирует адрес и возвращает локальную ссылку для плеера. */
    fun register(url: String, client: OkHttpClient, headers: Map<String, String> = emptyMap()): String {
        val port = start()
        val u = java.net.URI(url)
        val origin = "${u.scheme}://${u.rawAuthority}"
        val dir = origin + (u.rawPath ?: "/").substringBeforeLast('/', "") + "/"
        val file = (u.rawPath ?: "").substringAfterLast('/') + (u.rawQuery?.let { "?$it" } ?: "")
        val token = UUID.randomUUID().toString().take(12)
        val r = Route(dir, origin, client, headers)
        routes[token] = r
        last = r
        if (routes.size > 200) routes.keys.take(100).forEach { routes.remove(it) }
        return "http://127.0.0.1:$port/t/$token/$file"
    }

    private fun serve(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 30_000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val line = reader.readLine() ?: return
                val path = line.split(' ').getOrNull(1) ?: return
                val reqHeaders = HashMap<String, String>()
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                    val i = h.indexOf(':')
                    if (i > 0) reqHeaders[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
                }
                val (route, target) = if (path.startsWith("/t/")) {
                    val token = path.removePrefix("/t/").substringBefore('/')
                    val r = routes[token] ?: return notFound(s)
                    r to (r.base + path.removePrefix("/t/$token/"))
                } else {
                    // Абсолютная ссылка внутри плейлиста HLS — отправляем на тот же сервер
                    val r = last ?: return notFound(s)
                    r to (r.origin + path)
                }
                val rb = Request.Builder().url(target)
                route.headers.forEach { (k, v) -> rb.header(k, v) }
                reqHeaders["range"]?.let { rb.header("Range", it) }
                route.client.newCall(rb.build()).execute().use { resp ->
                    val out = s.getOutputStream()
                    val sb = StringBuilder("HTTP/1.1 ${resp.code} ${resp.message.ifBlank { "OK" }}\r\n")
                    listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Content-Disposition").forEach { h ->
                        resp.header(h)?.let { sb.append("$h: $it\r\n") }
                    }
                    sb.append("Connection: close\r\n\r\n")
                    out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
                    resp.body?.byteStream()?.use { it.copyTo(out, 64 * 1024) }
                    out.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun notFound(s: Socket) {
        try {
            s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } catch (_: Exception) {
        }
    }
}
