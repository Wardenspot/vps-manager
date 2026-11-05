package ru.nordcam.app.data

import org.json.JSONObject
import java.util.UUID

/**
 * Элемент вкладки «Дом»: устройство Home Assistant (kind = "ha") или своя HTTP-кнопка (kind = "http").
 * Можно привязать к камере — тогда кнопка видна на экране этой камеры (например, «Открыть ворота»).
 */
data class HomeItem(
    val id: String = UUID.randomUUID().toString(),
    val kind: String = "ha",
    /** Home Assistant: entity_id, например climate.kotel или cover.vorota. */
    val entityId: String = "",
    /** Своё название; пусто — как в Home Assistant. */
    val name: String = "",
    /** Комната/группа; пусто — из Home Assistant или «Без комнаты». */
    val room: String = "",
    /** Показывать на экране этой камеры. */
    val cameraId: String = "",
    /** Присылать тревогу, когда датчик срабатывает. */
    val alert: Boolean = false,
    /** Спрашивать подтверждение перед действием (ворота, замок). */
    val confirm: Boolean = false,
    // ---- своя HTTP-кнопка ----
    val url: String = "",
    val method: String = "GET",
    val body: String = "",
    val user: String = "",
    val pass: String = "",
    val order: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind).put("entityId", entityId).put("name", name)
        .put("room", room).put("cameraId", cameraId).put("alert", alert).put("confirm", confirm)
        .put("url", url).put("method", method).put("body", body).put("user", user).put("pass", pass).put("order", order)

    companion object {
        fun from(o: JSONObject) = HomeItem(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            kind = o.optString("kind", "ha"),
            entityId = o.optString("entityId"),
            name = o.optString("name"),
            room = o.optString("room"),
            cameraId = o.optString("cameraId"),
            alert = o.optBoolean("alert", false),
            confirm = o.optBoolean("confirm", false),
            url = o.optString("url"),
            method = o.optString("method", "GET"),
            body = o.optString("body"),
            user = o.optString("user"),
            pass = o.optString("pass"),
            order = o.optInt("order", 0),
        )
    }
}
