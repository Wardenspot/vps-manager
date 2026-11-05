package ru.nordcam.app.data

import ru.nordcam.app.net.Http
import ru.nordcam.app.net.Onvif
import ru.nordcam.app.net.OnvifProfile
import java.net.URI

/** Действия с камерой, общие для экранов и фоновой службы. */
object CamOps {
    fun onvif(c: Camera) = Onvif(c.host.trim(), c.onvifPort, c.user, c.password)

    /** Профили ONVIF, сгруппированные по видеовходу: на регистраторе — по одному на канал. */
    fun channels(profiles: List<OnvifProfile>): List<Pair<OnvifProfile, OnvifProfile?>> {
        val groups = LinkedHashMap<String, MutableList<OnvifProfile>>()
        profiles.forEach { p -> groups.getOrPut(p.sourceToken.ifBlank { p.token }) { mutableListOf() } += p }
        return groups.values.map { list ->
            val sorted = list.sortedByDescending { it.width * it.height }
            val main = sorted.first()
            val sub = sorted.drop(1).lastOrNull()
            main to sub
        }
    }

    /** Превращает адрес от камеры в шаблон, чтобы смена IP/порта в настройках применялась сразу. */
    fun toTemplate(url: String, rtsp: Boolean): String = try {
        val u = URI(url)
        val tail = (u.rawPath ?: "") + (u.rawQuery?.let { "?$it" } ?: "")
        if (rtsp) "rtsp://{host}:{rtsp}$tail" else "http://{host}:{http}$tail"
    } catch (e: Exception) {
        url
    }

    fun portOf(url: String, def: Int): Int = try {
        URI(url).port.takeIf { it > 0 } ?: def
    } catch (e: Exception) {
        def
    }

    /**
     * Профиль ONVIF для PTZ. Для камер, добавленных по шаблону, находится по номеру канала
     * и запоминается.
     */
    fun ptzProfile(store: Store, c: Camera): String {
        if (c.onvifProfile.isNotBlank()) return c.onvifProfile
        val chans = channels(onvif(c).profiles())
        val pick = chans.getOrNull(c.channel - 1) ?: chans.firstOrNull() ?: return ""
        store.upsert(c.copy(onvifProfile = pick.first.token, onvifProfileSub = pick.second?.token.orEmpty()))
        return pick.first.token
    }

    /** Снимок JPEG: по HTTP-адресу производителя, иначе через ONVIF (адрес запоминается). */
    fun snapshot(store: Store, c: Camera): ByteArray? {
        val url = Vendors.snapshotUrl(c)
        if (url.isNotBlank()) Http.snapshot(url, c.user, c.password)?.let { return it }
        if (c.snapshotUrl.isBlank()) {
            try {
                val o = onvif(c)
                val token = c.onvifProfile.ifBlank { ptzProfile(store, c) }
                if (token.isNotBlank()) {
                    val u = o.snapshotUri(token)
                    if (u.isNotBlank()) {
                        val cur = store.camera(c.id) ?: c
                        store.upsert(cur.copy(snapshotUrl = toTemplate(u, rtsp = false), httpPort = portOf(u, cur.httpPort)))
                        return Http.snapshot(Vendors.snapshotUrl(store.camera(c.id) ?: cur), c.user, c.password)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }
}
