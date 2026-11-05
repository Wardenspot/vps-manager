package ru.nordcam.app.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.nordcam.app.data.ArchiveServer
import ru.nordcam.app.data.ServerType
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class NvrError(msg: String) : Exception(msg)

/** Камера на сервере записи. [live] — адрес живого потока через сервер, если сервер его даёт. */
data class RemoteCam(val id: String, val name: String, val ip: String, val live: String)

/** Отрезок записи. Время — мс с 1970. [kind]: cont — постоянная, motion — движение, object — объект. */
data class Segment(val start: Long, val end: Long, val kind: String = "cont", val ref: String = "", val extra: String = "")

/** Событие на сервере (человек, машина…). */
data class RemoteEvent(val id: String, val start: Long, val end: Long, val label: String, val thumb: String = "")

/** Что играть: адрес для плеера и какое время соответствует началу ролика. */
data class PlayItem(val url: String, val base: Long, val segment: Segment?, val offset: Long)

/** Общий интерфейс для систем записи — добавить новую систему = реализовать эти методы. */
interface Nvr {
    fun cameras(): List<RemoteCam>
    fun segments(cam: String, from: Long, to: Long): List<Segment>
    fun events(cam: String, from: Long, to: Long): List<RemoteEvent> = emptyList()
    fun play(cam: String, segments: List<Segment>, at: Long): PlayItem
    fun thumb(e: RemoteEvent): ByteArray? = null

    companion object {
        private val cache = ConcurrentHashMap<String, Pair<ArchiveServer, Nvr>>()

        /** Клиент для сервера; сессия переиспользуется, пока настройки сервера не меняются. */
        fun of(s: ArchiveServer): Nvr {
            cache[s.id]?.let { (old, c) -> if (old == s) return c }
            val c: Nvr = when (s.type) {
                ServerType.SYNOLOGY -> SynologyNvr(s)
                ServerType.FRIGATE -> FrigateNvr(s)
            }
            cache[s.id] = s to c
            return c
        }

        fun http(trustAll: Boolean): OkHttpClient {
            val b = Http.client.newBuilder().readTimeout(30, TimeUnit.SECONDS)
            if (trustAll) {
                val tm = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                val ctx = SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
                b.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }
            }
            return b.build()
        }

        fun baseUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (u.isNotBlank() && !u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            return u
        }
    }
}

// ============================================================== Synology Surveillance Station

/**
 * Synology Surveillance Station Web API: вход (SYNO.API.Auth), камеры (SYNO.SurveillanceStation.Camera),
 * записи (SYNO.SurveillanceStation.Recording List / Download). Пути и версии API берутся у самого NAS
 * через SYNO.API.Info, поэтому работает и на DSM 6, и на DSM 7.
 */
class SynologyNvr(private val s: ArchiveServer) : Nvr {
    private val http = Nvr.http(s.trustAll)
    private val base = Nvr.baseUrl(s.url)
    @Volatile private var apis: Map<String, Pair<String, Int>>? = null
    @Volatile private var sid: String? = null

    private fun url(path: String, params: Map<String, String>): HttpUrl {
        val b = "$base/webapi/$path".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    private fun get(path: String, params: Map<String, String>): JSONObject {
        val req = Request.Builder().url(url(path, params)).build()
        return http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw NvrError("NAS ответил ошибкой HTTP ${r.code}")
            try { JSONObject(t) } catch (e: Exception) { throw NvrError("Это не Synology: по адресу $base не отвечает DSM") }
        }
    }

    private fun info(): Map<String, Pair<String, Int>> {
        apis?.let { return it }
        val o = get("query.cgi", mapOf("api" to "SYNO.API.Info", "method" to "Query", "version" to "1", "query" to "ALL"))
        val d = o.optJSONObject("data") ?: throw NvrError("NAS не сообщил список API")
        val m = HashMap<String, Pair<String, Int>>()
        d.keys().forEach { k -> d.optJSONObject(k)?.let { m[k] = it.optString("path") to it.optInt("maxVersion", 1) } }
        if (m.keys.none { it.startsWith("SYNO.SurveillanceStation") }) {
            throw NvrError("На NAS не установлен или не запущен Surveillance Station")
        }
        apis = m
        return m
    }

    private fun errText(code: Int, auth: Boolean): String = when {
        auth && code == 400 -> "Неверный логин или пароль DSM"
        auth && code == 401 -> "Учётная запись отключена или это гость"
        auth && code == 402 -> "У пользователя нет доступа к Surveillance Station"
        auth && (code == 403 || code == 404 || code == 406) -> "Включена двухэтапная проверка — заведите для приложения отдельного пользователя без неё"
        auth && code == 407 -> "IP заблокирован автоблокировкой DSM — разблокируйте в Панели управления"
        code == 105 -> "У пользователя нет прав на это действие в Surveillance Station"
        code == 102 || code == 103 || code == 104 -> "Эта версия Surveillance Station не поддерживает запрос (код $code)"
        else -> "Surveillance Station вернул ошибку $code"
    }

    private fun login() {
        val (path, max) = info()["SYNO.API.Auth"] ?: ("auth.cgi" to 3)
        val o = get(
            path,
            mapOf(
                "api" to "SYNO.API.Auth", "method" to "login", "version" to minOf(max, 6).toString(),
                "account" to s.user, "passwd" to s.password, "session" to "SurveillanceStation", "format" to "sid",
            ),
        )
        if (!o.optBoolean("success")) throw NvrError(errText(o.optJSONObject("error")?.optInt("code") ?: 0, true))
        sid = o.optJSONObject("data")?.optString("sid")?.takeIf { it.isNotBlank() } ?: throw NvrError("NAS не выдал сессию")
    }

    private fun params(api: String, method: String, maxV: Int, extra: Map<String, String>): Pair<String, Map<String, String>> {
        if (sid == null) login()
        val (path, max) = info()[api] ?: throw NvrError("На NAS нет $api — обновите Surveillance Station")
        return path to (mapOf("api" to api, "method" to method, "version" to minOf(max, maxV).toString(), "_sid" to sid!!) + extra)
    }

    /** Вызов API. Возвращает весь ответ (у разных методов data бывает объектом или массивом). */
    @Synchronized
    private fun call(api: String, method: String, maxV: Int, extra: Map<String, String> = emptyMap()): JSONObject {
        repeat(2) { attempt ->
            val (path, p) = params(api, method, maxV, extra)
            val o = get(path, p)
            if (o.optBoolean("success")) return o
            val code = o.optJSONObject("error")?.optInt("code") ?: 0
            if (attempt == 0 && code in listOf(105, 106, 107, 119)) { sid = null; return@repeat }
            throw NvrError(errText(code, false))
        }
        throw NvrError("Не удалось войти в Surveillance Station")
    }

    override fun cameras(): List<RemoteCam> {
        val o = call("SYNO.SurveillanceStation.Camera", "List", 9)
        val arr = o.optJSONObject("data")?.optJSONArray("cameras") ?: JSONArray()
        val cams = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = c.optString("id").ifBlank { c.optString("camId") }
            val name = c.optString("newName").ifBlank { c.optString("name") }.ifBlank { "Камера $id" }
            RemoteCam(id, name, c.optString("ip").ifBlank { c.optString("host") }, "")
        }
        if (cams.isEmpty()) return cams
        // Живые потоки через NAS (RTSP прокси Surveillance Station)
        val live = HashMap<String, String>()
        try {
            val lo = call("SYNO.SurveillanceStation.Camera", "GetLiveViewPath", 9, mapOf("idList" to cams.joinToString(",") { it.id }))
            val la = lo.optJSONArray("data") ?: lo.optJSONObject("data")?.optJSONArray("pathInfos") ?: JSONArray()
            for (i in 0 until la.length()) {
                val p = la.optJSONObject(i) ?: continue
                val rtsp = p.optString("rtspPath")
                if (rtsp.isNotBlank()) live[p.optString("id")] = rtsp
            }
        } catch (_: Exception) {
        }
        return cams.map { it.copy(live = live[it.id].orEmpty()) }
    }

    override fun segments(cam: String, from: Long, to: Long): List<Segment> {
        val out = ArrayList<Segment>()
        var offset = 0
        while (offset < 5000) {
            val o = call(
                "SYNO.SurveillanceStation.Recording", "List", 6,
                mapOf(
                    "cameraIds" to cam, "fromTime" to (from / 1000).toString(), "toTime" to (to / 1000).toString(),
                    "offset" to offset.toString(), "limit" to "500",
                ),
            )
            val d = o.optJSONObject("data") ?: break
            val arr = d.optJSONArray("recordings") ?: d.optJSONArray("events") ?: d.optJSONArray("data") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val st = r.optLong("startTime", r.optLong("start_time")) * 1000
                var en = r.optLong("stopTime", r.optLong("endTime", r.optLong("stop_time"))) * 1000
                if (st <= 0) continue
                if (en <= st) en = minOf(System.currentTimeMillis(), st + 30 * 60_000L) // запись ещё идёт
                val reason = r.optInt("reason", r.optInt("eventType", 0))
                val kind = if (reason == 0 || reason == 1) "cont" else "motion"
                val camId = r.optString("cameraId", r.optString("camera_id"))
                if (camId.isNotBlank() && camId != cam) continue
                out += Segment(st, en, kind, r.optString("id"), r.optString("mountId", "0"))
            }
            if (arr.length() < 500) break
            offset += 500
        }
        return out.sortedBy { it.start }
    }

    override fun play(cam: String, segments: List<Segment>, at: Long): PlayItem {
        val seg = segments.firstOrNull { at >= it.start && at < it.end }
            ?: segments.firstOrNull { it.start >= at }
            ?: throw NvrError("После этого времени записей нет")
        val offset = (at - seg.start).coerceAtLeast(0)
        val extra = linkedMapOf(
            "id" to seg.ref,
            "mountId" to seg.extra.ifBlank { "0" },
            "offsetTimeMs" to offset.toString(),
            "playTimeMs" to (seg.end - seg.start - offset).coerceAtLeast(1000).toString(),
        )
        val (path, p) = synchronized(this) { params("SYNO.SurveillanceStation.Recording", "Download", 6, extra) }
        val proxied = LocalProxy.register(url(path, p).toString(), http)
        return PlayItem(proxied, seg.start + offset, seg, offset)
    }
}

// ============================================================== Frigate

/**
 * Frigate NVR: камеры из /api/config, отрезки из /api/<cam>/recordings, события (человек, машина…)
 * из /api/events, воспроизведение — HLS /vod/<cam>/start/<t>/end/<t>/index.m3u8.
 */
class FrigateNvr(private val s: ArchiveServer) : Nvr {
    private val http = Nvr.http(s.trustAll)
    private val base = Nvr.baseUrl(s.url)
    @Volatile private var cookie: String = ""

    private fun login() {
        if (s.user.isBlank() || cookie.isNotBlank()) return
        val body = JSONObject().put("user", s.user).put("password", s.password).toString()
            .toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url("$base/api/login").post(body).build()).execute().use { r ->
            if (r.code == 401 || r.code == 400) throw NvrError("Frigate: неверный логин или пароль")
            if (!r.isSuccessful) throw NvrError("Frigate: ошибка входа HTTP ${r.code}")
            cookie = r.headers("Set-Cookie").firstOrNull { it.startsWith("frigate_token=") }?.substringBefore(';').orEmpty()
        }
    }

    private fun headers(): Map<String, String> = if (cookie.isNotBlank()) mapOf("Cookie" to cookie) else emptyMap()

    private fun getText(path: String): String {
        login()
        val b = Request.Builder().url("$base$path")
        headers().forEach { (k, v) -> b.header(k, v) }
        return http.newCall(b.build()).execute().use { r ->
            if (r.code == 401) { cookie = ""; throw NvrError("Frigate просит вход — укажите логин и пароль") }
            if (!r.isSuccessful) throw NvrError("Frigate ответил HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    override fun cameras(): List<RemoteCam> {
        val cfg = try { JSONObject(getText("/api/config")) } catch (e: NvrError) { throw e } catch (e: Exception) {
            throw NvrError("Это не Frigate: по адресу $base нет /api/config")
        }
        val cams = cfg.optJSONObject("cameras") ?: JSONObject()
        val host = try { base.toHttpUrl().host } catch (e: Exception) { "" }
        return cams.keys().asSequence().map { name ->
            // Живой поток через встроенный в Frigate go2rtc (порт 8554)
            RemoteCam(name, name.replace('_', ' '), "", if (host.isNotBlank()) "rtsp://$host:8554/$name" else "")
        }.toList()
    }

    override fun segments(cam: String, from: Long, to: Long): List<Segment> {
        val arr = JSONArray(getText("/api/${enc(cam)}/recordings?after=${from / 1000}&before=${to / 1000}"))
        val raw = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            val obj = it.optInt("objects", 0)
            val mot = it.optInt("motion", 0)
            Segment(
                (it.optDouble("start_time") * 1000).toLong(), (it.optDouble("end_time") * 1000).toLong(),
                if (obj > 0) "object" else if (mot > 0) "motion" else "cont",
            )
        }.filter { it.end > it.start }.sortedBy { it.start }
        // Frigate хранит отрезки по ~10 сек — склеиваем соседние одного типа
        val merged = ArrayList<Segment>()
        raw.forEach { sgm ->
            val last = merged.lastOrNull()
            if (last != null && sgm.start - last.end < 5_000 && last.kind == sgm.kind) merged[merged.lastIndex] = last.copy(end = maxOf(last.end, sgm.end))
            else merged += sgm
        }
        return merged
    }

    override fun events(cam: String, from: Long, to: Long): List<RemoteEvent> {
        val arr = JSONArray(getText("/api/events?cameras=${enc(cam)}&after=${from / 1000}&before=${to / 1000}&limit=300"))
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            val st = (it.optDouble("start_time") * 1000).toLong()
            val en = if (it.isNull("end_time")) st + 10_000 else (it.optDouble("end_time") * 1000).toLong()
            val sub = it.optJSONArray("sub_label")?.optString(0) ?: it.optString("sub_label")
            RemoteEvent(it.optString("id"), st, en, label(it.optString("label"), sub), "/api/events/${it.optString("id")}/thumbnail.jpg")
        }.sortedByDescending { it.start }
    }

    override fun thumb(e: RemoteEvent): ByteArray? = try {
        login()
        val b = Request.Builder().url(base + e.thumb)
        headers().forEach { (k, v) -> b.header(k, v) }
        http.newCall(b.build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    } catch (ex: Exception) {
        null
    }

    override fun play(cam: String, segments: List<Segment>, at: Long): PlayItem {
        login()
        val start = segments.firstOrNull { at >= it.start && at < it.end }?.let { at }
            ?: segments.firstOrNull { it.start >= at }?.start
            ?: throw NvrError("После этого времени записей нет")
        val end = maxOf(segments.lastOrNull()?.end ?: 0L, start + 60_000).coerceAtMost(start + 6 * 3600_000L)
        val url = "$base/vod/${enc(cam)}/start/${start / 1000}/end/${end / 1000}/index.m3u8"
        return PlayItem(LocalProxy.register(url, http, headers()), start, null, 0)
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun label(l: String, sub: String): String {
        val ru = when (l) {
            "person" -> "Человек"; "car" -> "Машина"; "truck" -> "Грузовик"; "bus" -> "Автобус"
            "motorcycle" -> "Мотоцикл"; "bicycle" -> "Велосипед"; "dog" -> "Собака"; "cat" -> "Кошка"
            "bird" -> "Птица"; "license_plate" -> "Номер"; "face" -> "Лицо"; "package" -> "Посылка"
            else -> l.ifBlank { "Событие" }
        }
        return if (sub.isNotBlank() && sub != "null") "$ru: $sub" else ru
    }
}
