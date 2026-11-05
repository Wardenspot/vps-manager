package ru.nordcam.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Как камера сообщает о движении. */
enum class MotionMode(val title: String) {
    OFF("Выключено"),
    ONVIF("События камеры (ONVIF)"),
    SNAPSHOT("Сравнение снимков (для любых камер)");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: OFF
    }
}

/** Что записывать на телефон. */
enum class RecordMode(val title: String) {
    OFF("Не записывать"),
    MOTION("По движению"),
    CONTINUOUS("Постоянно");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: OFF
    }
}

/** Права для камер, полученных от другого человека по коду доступа. */
data class Perms(
    val live: Boolean = true,
    val archive: Boolean = true,
    val ptz: Boolean = true,
    val record: Boolean = true,
    /** Срок действия доступа, мс с 1970; 0 — бессрочно. */
    val until: Long = 0,
) {
    val expired: Boolean get() = until > 0 && System.currentTimeMillis() > until

    fun toJson() = JSONObject().put("live", live).put("archive", archive).put("ptz", ptz)
        .put("record", record).put("until", until)

    companion object {
        val FULL = Perms()
        fun from(o: JSONObject?) = if (o == null) FULL else Perms(
            live = o.optBoolean("live", true),
            archive = o.optBoolean("archive", true),
            ptz = o.optBoolean("ptz", true),
            record = o.optBoolean("record", true),
            until = o.optLong("until", 0),
        )
    }
}

data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** Объект (дом, офис, магазин) — для группировки, как «устройства» в Hik-Connect. */
    val group: String = "",
    val vendor: String = Vendors.GENERIC.id,
    val host: String = "",
    val rtspPort: Int = 554,
    val httpPort: Int = 80,
    val user: String = "admin",
    val password: String = "",
    val channel: Int = 1,
    /** Полные адреса потоков. Если пусто — собираются из шаблона производителя. */
    val mainUrl: String = "",
    val subUrl: String = "",
    val snapshotUrl: String = "",
    /** Шаблон архива на камере/регистраторе: {start} {end} — время. */
    val playbackUrl: String = "",
    val onvifPort: Int = 80,
    val onvifProfile: String = "",
    val onvifProfileSub: String = "",
    val ptz: Boolean = false,
    val audio: Boolean = true,
    val tcp: Boolean = true,
    val motion: MotionMode = MotionMode.OFF,
    /** Чувствительность сравнения снимков 1..100. */
    val sensitivity: Int = 50,
    val record: RecordMode = RecordMode.OFF,
    val notify: Boolean = true,
    /** Камера получена по коду доступа: пароль скрыт, правка запрещена. */
    val shared: Boolean = false,
    val perms: Perms = Perms.FULL,
    val order: Int = 0,
) {
    val title: String get() = name.ifBlank { "$host ${if (channel > 1) "к$channel" else ""}".trim() }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("group", group).put("vendor", vendor)
        .put("host", host).put("rtspPort", rtspPort).put("httpPort", httpPort)
        .put("user", user).put("password", password).put("channel", channel)
        .put("mainUrl", mainUrl).put("subUrl", subUrl).put("snapshotUrl", snapshotUrl)
        .put("playbackUrl", playbackUrl).put("onvifPort", onvifPort)
        .put("onvifProfile", onvifProfile).put("onvifProfileSub", onvifProfileSub)
        .put("ptz", ptz).put("audio", audio).put("tcp", tcp).put("motion", motion.name)
        .put("sensitivity", sensitivity).put("record", record.name).put("notify", notify)
        .put("shared", shared).put("perms", perms.toJson()).put("order", order)

    companion object {
        fun from(o: JSONObject) = Camera(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            group = o.optString("group"),
            vendor = o.optString("vendor", Vendors.GENERIC.id),
            host = o.optString("host"),
            rtspPort = o.optInt("rtspPort", 554),
            httpPort = o.optInt("httpPort", 80),
            user = o.optString("user"),
            password = o.optString("password"),
            channel = o.optInt("channel", 1),
            mainUrl = o.optString("mainUrl"),
            subUrl = o.optString("subUrl"),
            snapshotUrl = o.optString("snapshotUrl"),
            playbackUrl = o.optString("playbackUrl"),
            onvifPort = o.optInt("onvifPort", 80),
            onvifProfile = o.optString("onvifProfile"),
            onvifProfileSub = o.optString("onvifProfileSub"),
            ptz = o.optBoolean("ptz", false),
            audio = o.optBoolean("audio", true),
            tcp = o.optBoolean("tcp", true),
            motion = MotionMode.of(o.optString("motion")),
            sensitivity = o.optInt("sensitivity", 50),
            record = RecordMode.of(o.optString("record")),
            notify = o.optBoolean("notify", true),
            shared = o.optBoolean("shared", false),
            perms = Perms.from(o.optJSONObject("perms")),
            order = o.optInt("order", 0),
        )
    }
}

data class Settings(
    val gridSize: Int = 4,
    val monitoring: Boolean = false,
    /** Длительность ролика по движению, сек. */
    val clipSeconds: Int = 30,
    /** Длина отрезка постоянной записи, мин. */
    val segmentMinutes: Int = 10,
    /** Сколько места под записи, МБ. Старые удаляются автоматически. */
    val storageMb: Int = 4096,
    /** Не беспокоить уведомлениями о движении чаще, чем раз в N сек для одной камеры. */
    val cooldownSec: Int = 60,
    val tgToken: String = "",
    val tgChat: String = "",
    val pin: String = "",
    val biometric: Boolean = false,
    val autoRelockSec: Int = 60,
    val lastGroup: String = "",
    val mailHost: String = "",
    val mailPort: Int = 465,
    val mailUser: String = "",
    val mailPass: String = "",
    val mailTo: String = "",
    val mailSecurity: String = "",
    val mailFrom: String = "",
    val mailTrust: Boolean = false,
    /** Оформление: system, dark, light, amoled, ocean, forest. */
    val theme: String = "dark",
    /** Цвет акцента: blue, cyan, green, orange, purple, red, pink. */
    val accent: String = "blue",
) {
    val mailReady: Boolean get() = mailHost.isNotBlank() && mailTo.isNotBlank() && (mailUser.isNotBlank() || mailFrom.isNotBlank())

    fun mailConfig() = ru.nordcam.app.net.MailConfig(
        mailHost, mailPort, ru.nordcam.app.net.MailSecurity.of(mailSecurity, mailPort),
        mailUser, mailPass, mailFrom, mailTo, mailTrust,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("gridSize", gridSize).put("monitoring", monitoring).put("clipSeconds", clipSeconds)
        .put("segmentMinutes", segmentMinutes).put("storageMb", storageMb)
        .put("cooldownSec", cooldownSec).put("tgToken", tgToken).put("tgChat", tgChat)
        .put("pin", pin).put("biometric", biometric).put("autoRelockSec", autoRelockSec)
        .put("lastGroup", lastGroup)
        .put("mailHost", mailHost).put("mailPort", mailPort).put("mailUser", mailUser)
        .put("mailPass", mailPass).put("mailTo", mailTo)
        .put("mailSecurity", mailSecurity).put("mailFrom", mailFrom).put("mailTrust", mailTrust).put("theme", theme).put("accent", accent)

    companion object {
        fun from(o: JSONObject?) = if (o == null) Settings() else Settings(
            gridSize = o.optInt("gridSize", 4),
            monitoring = o.optBoolean("monitoring", false),
            clipSeconds = o.optInt("clipSeconds", 30),
            segmentMinutes = o.optInt("segmentMinutes", 10),
            storageMb = o.optInt("storageMb", 4096),
            cooldownSec = o.optInt("cooldownSec", 60),
            tgToken = o.optString("tgToken"),
            tgChat = o.optString("tgChat"),
            pin = o.optString("pin"),
            biometric = o.optBoolean("biometric", false),
            autoRelockSec = o.optInt("autoRelockSec", 60),
            lastGroup = o.optString("lastGroup"),
            mailHost = o.optString("mailHost"),
            mailPort = o.optInt("mailPort", 465),
            mailUser = o.optString("mailUser"),
            mailPass = o.optString("mailPass"),
            mailTo = o.optString("mailTo"),
            mailSecurity = o.optString("mailSecurity"),
            mailFrom = o.optString("mailFrom"),
            mailTrust = o.optBoolean("mailTrust", false),
            theme = o.optString("theme", "dark"),
            accent = o.optString("accent", "blue"),
        )
    }
}

/** Событие тревоги (движение, пересечение линии и т.п.). */
data class Event(
    val id: String = UUID.randomUUID().toString(),
    val cameraId: String,
    val cameraName: String,
    val time: Long,
    val kind: String,
    val snapshot: String = "",
    val clip: String = "",
    val seen: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("cameraId", cameraId)
        .put("cameraName", cameraName).put("time", time).put("kind", kind)
        .put("snapshot", snapshot).put("clip", clip).put("seen", seen)

    companion object {
        fun from(o: JSONObject) = Event(
            id = o.optString("id"),
            cameraId = o.optString("cameraId"),
            cameraName = o.optString("cameraName"),
            time = o.optLong("time"),
            kind = o.optString("kind"),
            snapshot = o.optString("snapshot"),
            clip = o.optString("clip"),
            seen = o.optBoolean("seen"),
        )
    }
}

fun <T> JSONArray.mapObjects(f: (JSONObject) -> T): List<T> =
    (0 until length()).mapNotNull { i -> optJSONObject(i)?.let(f) }
