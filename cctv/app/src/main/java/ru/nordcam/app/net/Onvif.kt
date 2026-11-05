package ru.nordcam.app.net

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class OnvifException(msg: String) : Exception(msg)

data class OnvifInfo(val manufacturer: String, val model: String, val firmware: String, val serial: String)

data class OnvifProfile(
    val token: String,
    val name: String,
    val width: Int,
    val height: Int,
    val sourceToken: String,
    val encoding: String,
    val hasPtz: Boolean,
)

data class OnvifPreset(val token: String, val name: String)

data class OnvifEvent(val topic: String, val active: Boolean, val kind: String?)

data class Discovered(val host: String, val port: Int, val xaddr: String, val name: String, val hardware: String)

/**
 * Клиент ONVIF (Profile S/T): информация об устройстве, профили и адреса потоков, снимки,
 * управление PTZ и события движения через PullPoint. Все методы блокирующие — вызывать из IO.
 */
class Onvif(val host: String, val port: Int, private val user: String, private val pass: String) {
    private val http = Http.withAuth(user, pass, 25)
    private val deviceUrl = "http://$host:$port/onvif/device_service"
    private var timeOffsetMs = 0L
    private var synced = false
    var mediaUrl: String = ""; private set
    var ptzUrl: String = ""; private set
    var eventsUrl: String = ""; private set

    // ------------------------------------------------------------------ низкий уровень

    private fun created(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(System.currentTimeMillis() + timeOffsetMs))
    }

    private fun security(): String {
        if (user.isBlank()) return ""
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = created()
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(nonce); sha.update(created.toByteArray()); sha.update(pass.toByteArray())
        val digest = Base64.encodeToString(sha.digest(), Base64.NO_WRAP)
        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        return """<Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken><Username>${esc(user)}</Username><Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password><Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceB64</Nonce><Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created></UsernameToken></Security>"""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun call(url: String, body: String, auth: Boolean = true, extraHeader: String = "", action: String = ""): XNode {
        val env = """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:a="http://www.w3.org/2005/08/addressing" xmlns:tt="http://www.onvif.org/ver10/schema"><s:Header>${if (auth) security() else ""}$extraHeader</s:Header><s:Body>$body</s:Body></s:Envelope>"""
        val ct = if (action.isBlank()) "application/soap+xml; charset=utf-8" else "application/soap+xml; charset=utf-8; action=\"$action\""
        val req = Request.Builder().url(url).post(env.toRequestBody(ct.toMediaType())).build()
        val text = http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful && !t.contains("Fault")) {
                throw OnvifException(if (r.code == 401) "Неверный логин или пароль" else "Ошибка HTTP ${r.code}")
            }
            t
        }
        val x = try { XNode.parse(text) } catch (e: Exception) { throw OnvifException("Камера вернула не ONVIF-ответ") }
        val fault = x.find("Fault")
        if (fault != null) {
            val reason = fault.find("Text")?.text?.trim().orEmpty()
            val sub = fault.find("Subcode")?.allText()?.trim().orEmpty()
            if (sub.contains("NotAuthorized", true) || reason.contains("auth", true) || sub.contains("FailedAuthentication", true))
                throw OnvifException("Неверный логин или пароль (или не совпадает время на камере)")
            throw OnvifException(reason.ifBlank { "Ошибка ONVIF" })
        }
        return x
    }

    /** Камеры часто сообщают адреса со своим внутренним IP — подменяем на тот, по которому подключаемся. */
    private fun fixAddr(u: String): String = try {
        if (u.isBlank()) u else {
            val uri = URI(u.trim())
            URI("http", null, host, port, uri.path, uri.query, null).toString()
        }
    } catch (e: Exception) {
        u
    }

    private fun fixRtsp(u: String): String = try {
        val uri = URI(u.trim())
        val p = if (uri.port > 0) uri.port else 554
        URI(uri.scheme ?: "rtsp", null, host, p, uri.path, uri.query, null).toString()
    } catch (e: Exception) {
        u
    }

    // ------------------------------------------------------------------ устройство

    /** Сверка часов: WS-Security отклоняется, если время камеры и телефона сильно расходится. */
    fun syncTime() {
        if (synced) return
        try {
            val x = call(deviceUrl, """<GetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"/>""", auth = false)
            val utc = x.find("UTCDateTime")
            if (utc != null) {
                val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                c.set(
                    utc.textOf("Year").toInt(), utc.textOf("Month").toInt() - 1, utc.textOf("Day").toInt(),
                    utc.textOf("Hour").toInt(), utc.textOf("Minute").toInt(), utc.textOf("Second").toInt(),
                )
                timeOffsetMs = c.timeInMillis - System.currentTimeMillis()
            }
        } catch (_: Exception) {
        }
        synced = true
    }

    fun info(): OnvifInfo {
        syncTime()
        val x = call(deviceUrl, """<GetDeviceInformation xmlns="http://www.onvif.org/ver10/device/wsdl"/>""")
        return OnvifInfo(x.textOf("Manufacturer"), x.textOf("Model"), x.textOf("FirmwareVersion"), x.textOf("SerialNumber"))
    }

    fun capabilities() {
        if (mediaUrl.isNotBlank()) return
        syncTime()
        val x = call(deviceUrl, """<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>All</Category></GetCapabilities>""")
        val caps = x.find("Capabilities") ?: throw OnvifException("Камера не сообщила свои возможности")
        mediaUrl = fixAddr(caps.child("Media")?.textOf("XAddr").orEmpty())
        ptzUrl = fixAddr(caps.child("PTZ")?.textOf("XAddr").orEmpty())
        eventsUrl = fixAddr(caps.child("Events")?.textOf("XAddr").orEmpty())
        if (mediaUrl.isBlank()) mediaUrl = "http://$host:$port/onvif/media_service"
    }

    // ------------------------------------------------------------------ медиа

    fun profiles(): List<OnvifProfile> {
        capabilities()
        val x = call(mediaUrl, """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>""")
        return x.findAll("Profiles").map { p ->
            val enc = p.find("VideoEncoderConfiguration")
            val res = enc?.find("Resolution")
            OnvifProfile(
                token = p.attrs["token"].orEmpty(),
                name = p.child("Name")?.text?.trim().orEmpty(),
                width = res?.textOf("Width")?.toIntOrNull() ?: 0,
                height = res?.textOf("Height")?.toIntOrNull() ?: 0,
                sourceToken = p.find("VideoSourceConfiguration")?.textOf("SourceToken").orEmpty(),
                encoding = enc?.textOf("Encoding").orEmpty(),
                hasPtz = p.find("PTZConfiguration") != null,
            )
        }.filter { it.token.isNotBlank() }
    }

    fun streamUri(token: String): String {
        capabilities()
        val x = call(
            mediaUrl,
            """<GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl"><StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></StreamSetup><ProfileToken>${esc(token)}</ProfileToken></GetStreamUri>""",
        )
        val u = x.textOf("Uri")
        if (u.isBlank()) throw OnvifException("Камера не дала адрес потока")
        return fixRtsp(u)
    }

    fun snapshotUri(token: String): String = try {
        capabilities()
        val x = call(mediaUrl, """<GetSnapshotUri xmlns="http://www.onvif.org/ver10/media/wsdl"><ProfileToken>${esc(token)}</ProfileToken></GetSnapshotUri>""")
        x.textOf("Uri").let { if (it.isBlank()) "" else fixAddrKeepPort(it) }
    } catch (e: Exception) {
        ""
    }

    /** Для снимков сохраняем путь, но хост меняем на наш (порт HTTP оставляем как у камеры, если он указан). */
    private fun fixAddrKeepPort(u: String): String = try {
        val uri = URI(u.trim())
        val p = if (uri.port > 0) uri.port else port
        URI(uri.scheme ?: "http", null, host, p, uri.path, uri.query, null).toString()
    } catch (e: Exception) {
        u
    }

    // ------------------------------------------------------------------ PTZ

    private fun ptzEndpoint(): String {
        capabilities()
        return ptzUrl.ifBlank { throw OnvifException("Камера не поддерживает поворот (PTZ)") }
    }

    fun ptzMove(profile: String, x: Float, y: Float, zoom: Float) {
        val body = """<ContinuousMove xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>${esc(profile)}</ProfileToken><Velocity><tt:PanTilt x="$x" y="$y"/><tt:Zoom x="$zoom"/></Velocity></ContinuousMove>"""
        call(ptzEndpoint(), body)
    }

    fun ptzStop(profile: String) {
        call(ptzEndpoint(), """<Stop xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>${esc(profile)}</ProfileToken><PanTilt>true</PanTilt><Zoom>true</Zoom></Stop>""")
    }

    fun presets(profile: String): List<OnvifPreset> {
        val x = call(ptzEndpoint(), """<GetPresets xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>${esc(profile)}</ProfileToken></GetPresets>""")
        return x.findAll("Preset").map { OnvifPreset(it.attrs["token"].orEmpty(), it.textOf("Name")) }.filter { it.token.isNotBlank() }
    }

    fun gotoPreset(profile: String, preset: String) {
        call(ptzEndpoint(), """<GotoPreset xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>${esc(profile)}</ProfileToken><PresetToken>${esc(preset)}</PresetToken></GotoPreset>""")
    }

    fun setPreset(profile: String, name: String) {
        call(ptzEndpoint(), """<SetPreset xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>${esc(profile)}</ProfileToken><PresetName>${esc(name)}</PresetName></SetPreset>""")
    }

    // ------------------------------------------------------------------ события

    private var subAddr = ""
    private var subRefParams = ""

    fun subscribe() {
        capabilities()
        val ev = eventsUrl.ifBlank { throw OnvifException("Камера не поддерживает события ONVIF") }
        val env = call(ev, """<CreatePullPointSubscription xmlns="http://www.onvif.org/ver10/events/wsdl"><InitialTerminationTime>PT600S</InitialTerminationTime></CreatePullPointSubscription>""")
        val ref = env.find("SubscriptionReference") ?: throw OnvifException("Не удалось подписаться на события")
        subAddr = fixAddr(ref.textOf("Address"))
        subRefParams = ""
        if (subAddr.isBlank()) throw OnvifException("Не удалось подписаться на события")
    }

    /** Ждёт события до [timeoutSec] секунд. Возвращает только «тревожные» события. */
    fun pull(timeoutSec: Int = 10): List<OnvifEvent> {
        if (subAddr.isBlank()) subscribe()
        val hdr = """<a:Action>http://www.onvif.org/ver10/events/wsdl/PullPointSubscription/PullMessagesRequest</a:Action><a:To>${esc(subAddr)}</a:To>$subRefParams"""
        val x = call(
            subAddr,
            """<PullMessages xmlns="http://www.onvif.org/ver10/events/wsdl"><Timeout>PT${timeoutSec}S</Timeout><MessageLimit>32</MessageLimit></PullMessages>""",
            extraHeader = hdr,
            action = "http://www.onvif.org/ver10/events/wsdl/PullPointSubscription/PullMessagesRequest",
        )
        return x.findAll("NotificationMessage").map { m ->
            val topic = m.find("Topic")?.allText()?.trim().orEmpty()
            val data = m.find("Data")
            val items = data?.findAll("SimpleItem").orEmpty()
            val active = items.any {
                val v = it.attrs["Value"].orEmpty().lowercase()
                v == "true" || v == "1" || v == "active"
            }
            OnvifEvent(topic, active, classify(topic))
        }.filter { it.kind != null }
    }

    fun renew() {
        if (subAddr.isBlank()) return
        try {
            call(
                subAddr,
                """<Renew xmlns="http://docs.oasis-open.org/wsn/b-2"><TerminationTime>PT600S</TerminationTime></Renew>""",
                extraHeader = """<a:Action>http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/RenewRequest</a:Action><a:To>${esc(subAddr)}</a:To>""",
            )
        } catch (_: Exception) {
        }
    }

    fun unsubscribe() {
        if (subAddr.isBlank()) return
        try {
            call(
                subAddr,
                """<Unsubscribe xmlns="http://docs.oasis-open.org/wsn/b-2"/>""",
                extraHeader = """<a:Action>http://docs.oasis-open.org/wsn/bw-2/SubscriptionManager/UnsubscribeRequest</a:Action><a:To>${esc(subAddr)}</a:To>""",
            )
        } catch (_: Exception) {
        }
        subAddr = ""
    }

    companion object {
        fun classify(topic: String): String? {
            val t = topic.lowercase()
            return when {
                t.contains("linecross") || t.contains("linedetector") || t.contains("crossline") -> "Пересечение линии"
                t.contains("fielddetector") || t.contains("intrusion") || t.contains("region") -> "Вторжение в зону"
                t.contains("human") || t.contains("person") || t.contains("people") || t.contains("face") -> "Человек"
                t.contains("vehicle") || t.contains("car") -> "Транспорт"
                t.contains("tamper") -> "Закрытие объектива"
                t.contains("motion") -> "Движение"
                t.contains("alarm") && !t.contains("storage") && !t.contains("network") -> "Тревога"
                else -> null
            }
        }

        /** Поиск камер ONVIF в локальной сети (WS-Discovery). */
        fun discover(timeoutMs: Int = 3500): List<Discovered> {
            val found = LinkedHashMap<String, Discovered>()
            val probes = listOf("dn:NetworkVideoTransmitter", "tds:Device")
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) {
                    probes.forEach { type ->
                        val msg = """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl" xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>$type</d:Types></d:Probe></e:Body></e:Envelope>""".toByteArray()
                        try { sock.send(DatagramPacket(msg, msg.size, group, 3702)) } catch (_: Exception) {}
                    }
                }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(65535)
                while (System.currentTimeMillis() < end) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        break
                    }
                    val text = String(pkt.data, 0, pkt.length)
                    try {
                        val x = XNode.parse(text)
                        val xaddrs = x.textOf("XAddrs").split(' ', '\n', '\t').filter { it.startsWith("http") }
                        val scopes = x.textOf("Scopes").split(' ', '\n', '\t')
                        val src = pkt.address.hostAddress ?: continue
                        val xaddr = xaddrs.firstOrNull { it.contains(src) } ?: xaddrs.firstOrNull() ?: continue
                        val u = URI(xaddr)
                        fun scope(k: String) = scopes.firstOrNull { it.contains("/$k/") }
                            ?.substringAfter("/$k/")?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                        val port = if (u.port > 0) u.port else 80
                        found["$src:$port"] = Discovered(src, port, xaddr, scope("name"), scope("hardware"))
                    } catch (_: Exception) {
                    }
                }
            }
            return found.values.toList()
        }
    }
}
