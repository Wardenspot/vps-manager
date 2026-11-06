package ru.nordcam.app.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import ru.nordcam.app.data.HomeItem

/** Состояние устройства Home Assistant. */
data class HaEntity(val id: String, val state: String, val attrs: JSONObject) {
    val domain: String get() = id.substringBefore('.')
    val name: String get() = attrs.optString("friendly_name").ifBlank { id }
    val deviceClass: String get() = attrs.optString("device_class")
    val unit: String get() = attrs.optString("unit_of_measurement")
    val unavailable: Boolean get() = state == "unavailable" || state == "unknown"
}

/**
 * Клиент REST API Home Assistant: /api/states, /api/services, /api/template.
 * Подключение — адрес и долгосрочный токен (Профиль → Безопасность → Долгосрочные токены доступа).
 */
class HomeAssistant(baseUrl: String, private val token: String, trustAll: Boolean) {
    private val base = Nvr.baseUrl(baseUrl)
    private val http = Nvr.http(trustAll)

    private fun req(path: String): Request.Builder =
        Request.Builder().url("$base$path").header("Authorization", "Bearer $token").header("Content-Type", "application/json")

    private fun run(b: Request.Builder): String = http.newCall(b.build()).execute().use { r ->
        val t = r.body?.string().orEmpty()
        when {
            r.code == 401 -> throw NvrError("Home Assistant не принял токен — создайте новый долгосрочный токен")
            r.code == 404 -> throw NvrError("По адресу $base нет Home Assistant")
            !r.isSuccessful -> throw NvrError("Home Assistant: ошибка HTTP ${r.code} ${t.take(120)}")
        }
        t
    }

    fun check(): String {
        val o = JSONObject(run(req("/api/config")))
        return o.optString("location_name").ifBlank { "Home Assistant" } + " " + o.optString("version")
    }

    fun states(): List<HaEntity> {
        val arr = JSONArray(run(req("/api/states")))
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
            HaEntity(it.optString("entity_id"), it.optString("state"), it.optJSONObject("attributes") ?: JSONObject())
        }
    }

    /** Комнаты (зоны) устройств — через шаблон, т.к. в REST их нет. Если не вышло — пусто. */
    fun areas(): Map<String, String> = try {
        val tpl = "{% for s in states %}{{ s.entity_id }}|{{ area_name(s.entity_id) or '' }}\n{% endfor %}"
        val text = run(req("/api/template").post(JSONObject().put("template", tpl).toString().toRequestBody(JSON)))
        text.lines().mapNotNull { l -> l.split('|').takeIf { it.size == 2 && it[1].isNotBlank() }?.let { it[0] to it[1] } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    fun call(domain: String, service: String, data: JSONObject) {
        run(req("/api/services/$domain/$service").post(data.toString().toRequestBody(JSON)))
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Домены, для которых есть свои кнопки; остальное показывается как значение. */
        val supported = setOf(
            "climate", "water_heater", "cover", "lock", "switch", "light", "fan", "input_boolean", "siren",
            "button", "input_button", "script", "scene", "automation", "alarm_control_panel", "sensor",
            "binary_sensor", "number", "input_number", "select", "input_select", "valve", "humidifier",
        )
    }
}

/** Своя кнопка: запрос по ссылке (Shelly, Sonoff, ESP, контроллер ворот…). */
object HttpAction {
    fun run(item: HomeItem, off: Boolean = false): String? = try {
        val b = Request.Builder().url((if (off) item.urlOff else item.url).trim())
        if (item.method.equals("POST", true)) {
            val ct = if (item.body.trim().startsWith("{")) "application/json" else "application/x-www-form-urlencoded"
            b.post(item.body.toRequestBody(ct.toMediaType()))
        }
        val client = if (item.user.isNotBlank()) Http.withAuth(item.user, item.pass) else Http.client
        client.newCall(b.build()).execute().use { r -> if (r.isSuccessful) null else "Ответ ${r.code}" }
    } catch (e: Exception) {
        e.message ?: "Нет связи"
    }
}
