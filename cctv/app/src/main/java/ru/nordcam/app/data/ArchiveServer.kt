package ru.nordcam.app.data

import org.json.JSONObject
import java.util.UUID

/** Системы записи, с которых приложение умеет брать архив. */
enum class ServerType(val title: String, val hint: String) {
    SYNOLOGY(
        "Synology Surveillance Station",
        "Адрес NAS: https://192.168.1.10:5001 или http://192.168.1.10:5000 (через интернет — домен QuickConnect/DDNS или VPN). " +
            "Лучше завести отдельного пользователя DSM без двухэтапной проверки и дать ему доступ к Surveillance Station.",
    ),
    FRIGATE(
        "Frigate NVR",
        "Адрес Frigate: http://192.168.1.20:5000 (без входа) или https://192.168.1.20:8971 (с логином и паролем).",
    );

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: SYNOLOGY
    }
}

/** Сервер записи: Synology, Frigate и т.п. Камеры NordCam привязываются к его камерам для архива. */
data class ArchiveServer(
    val id: String = UUID.randomUUID().toString(),
    val type: ServerType = ServerType.SYNOLOGY,
    val name: String = "",
    val url: String = "",
    val user: String = "",
    val password: String = "",
    /** Самоподписанный сертификат (обычно у NAS на порту 5001). */
    val trustAll: Boolean = true,
) {
    val title: String get() = name.ifBlank { type.title }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type.name).put("name", name)
        .put("url", url).put("user", user).put("password", password).put("trustAll", trustAll)

    companion object {
        fun from(o: JSONObject) = ArchiveServer(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            type = ServerType.of(o.optString("type")),
            name = o.optString("name"),
            url = o.optString("url"),
            user = o.optString("user"),
            password = o.optString("password"),
            trustAll = o.optBoolean("trustAll", true),
        )
    }
}
