package ru.nordcam.app.data

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Шаблоны адресов для популярных производителей. Подстановки:
 * {host} {rtsp} {http} {user} {pass} {ch} {ch2} (01, 02…) {chm1} (канал с нуля)
 * {start} {end} — время для архива, формат по умолчанию у производителя, либо {start:yyyyMMddHHmmss}.
 */
data class Vendor(
    val id: String,
    val title: String,
    val main: String,
    val sub: String = "",
    val snapshot: String = "",
    val playback: String = "",
    val timeFormat: String = "yyyyMMdd'T'HHmmss'Z'",
    val hint: String = "",
)

object Vendors {
    val HIKVISION = Vendor(
        id = "hikvision",
        title = "Hikvision / HiWatch / EZVIZ",
        main = "rtsp://{host}:{rtsp}/Streaming/Channels/{ch}01",
        sub = "rtsp://{host}:{rtsp}/Streaming/Channels/{ch}02",
        snapshot = "http://{host}:{http}/ISAPI/Streaming/channels/{ch}01/picture",
        playback = "rtsp://{host}:{rtsp}/Streaming/tracks/{ch}01?starttime={start}&endtime={end}",
        timeFormat = "yyyyMMdd'T'HHmmss'Z'",
        hint = "Для регистратора канал = номер камеры. Архив берётся с диска регистратора или SD-карты.",
    )
    val DAHUA = Vendor(
        id = "dahua",
        title = "Dahua / Imou / RVi / большинство китайских",
        main = "rtsp://{host}:{rtsp}/cam/realmonitor?channel={ch}&subtype=0",
        sub = "rtsp://{host}:{rtsp}/cam/realmonitor?channel={ch}&subtype=1",
        snapshot = "http://{host}:{http}/cgi-bin/snapshot.cgi?channel={ch}",
        playback = "rtsp://{host}:{rtsp}/cam/playback?channel={ch}&subtype=0&starttime={start}&endtime={end}",
        timeFormat = "yyyy_MM_dd_HH_mm_ss",
    )
    val UNIVIEW = Vendor(
        id = "uniview",
        title = "Uniview (UNV)",
        main = "rtsp://{host}:{rtsp}/unicast/c{ch}/s0/live",
        sub = "rtsp://{host}:{rtsp}/unicast/c{ch}/s1/live",
        snapshot = "http://{host}:{http}/images/snapshot.jpg",
    )
    val XMEYE = Vendor(
        id = "xmeye",
        title = "XMEye / Xiongmai / iCSee",
        main = "rtsp://{host}:{rtsp}/user={user}&password={pass}&channel={ch}&stream=0.sdp?",
        sub = "rtsp://{host}:{rtsp}/user={user}&password={pass}&channel={ch}&stream=1.sdp?",
        hint = "Если не работает — включите ONVIF в настройках камеры и добавьте через поиск.",
    )
    val REOLINK = Vendor(
        id = "reolink",
        title = "Reolink",
        main = "rtsp://{host}:{rtsp}/h264Preview_{ch2}_main",
        sub = "rtsp://{host}:{rtsp}/h264Preview_{ch2}_sub",
        snapshot = "http://{host}:{http}/cgi-bin/api.cgi?cmd=Snap&channel={chm1}&rs=nc&user={user}&password={pass}",
        hint = "Для камер H.265 замените h264Preview на h265Preview.",
    )
    val TAPO = Vendor(
        id = "tapo",
        title = "TP-Link Tapo / VIGI",
        main = "rtsp://{host}:{rtsp}/stream1",
        sub = "rtsp://{host}:{rtsp}/stream2",
        hint = "Логин и пароль — «учётная запись камеры», создаётся в приложении Tapo.",
    )
    val ONVIF = Vendor(
        id = "onvif",
        title = "Найдено по ONVIF",
        main = "",
        hint = "Адреса потоков получены от самой камеры.",
    )
    val GENERIC = Vendor(
        id = "generic",
        title = "Другая камера (свой RTSP-адрес)",
        main = "",
        hint = "Впишите адрес потока, например rtsp://192.168.1.10:554/stream. Логин/пароль — в полях ниже.",
    )

    val all = listOf(HIKVISION, DAHUA, UNIVIEW, XMEYE, REOLINK, TAPO, ONVIF, GENERIC)

    fun of(id: String) = all.firstOrNull { it.id == id } ?: GENERIC

    private fun enc(s: String) = Uri.encode(s)

    private fun fill(t: String, c: Camera, start: Date? = null, end: Date? = null, fmt: String = "") : String {
        if (t.isBlank()) return ""
        var r = t.replace("{host}", c.host.trim())
            .replace("{rtsp}", c.rtspPort.toString())
            .replace("{http}", c.httpPort.toString())
            .replace("{user}", enc(c.user))
            .replace("{pass}", enc(c.password))
            .replace("{ch2}", c.channel.toString().padStart(2, '0'))
            .replace("{chm1}", (c.channel - 1).coerceAtLeast(0).toString())
            .replace("{ch}", c.channel.toString())
        if (start != null) {
            val re = Regex("""\{(start|end)(?::([^}]+))?\}""")
            r = re.replace(r) { m ->
                val pattern = m.groupValues[2].ifBlank { fmt.ifBlank { "yyyyMMdd'T'HHmmss'Z'" } }
                val d = if (m.groupValues[1] == "start") start else (end ?: Date(start.time + 6 * 3600_000L))
                SimpleDateFormat(pattern, Locale.US).format(d)
            }
        }
        return r
    }

    fun liveUrl(c: Camera, sub: Boolean): String {
        val v = of(c.vendor)
        if (sub) {
            if (c.subUrl.isNotBlank()) return fill(c.subUrl, c)
            if (v.sub.isNotBlank() && c.mainUrl.isBlank()) return fill(v.sub, c)
        }
        if (c.mainUrl.isNotBlank()) return fill(c.mainUrl, c)
        return fill(v.main, c)
    }

    fun snapshotUrl(c: Camera): String =
        if (c.snapshotUrl.isNotBlank()) fill(c.snapshotUrl, c) else fill(of(c.vendor).snapshot, c)

    fun playbackTemplate(c: Camera): String = c.playbackUrl.ifBlank { of(c.vendor).playback }

    fun playbackUrl(c: Camera, start: Date, end: Date? = null): String {
        val v = of(c.vendor)
        return fill(playbackTemplate(c), c, start, end, v.timeFormat)
    }

    fun hasPlayback(c: Camera) = playbackTemplate(c).isNotBlank()
}
