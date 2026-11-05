package ru.nordcam.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Всё хранится только на телефоне. Камеры с паролями и настройки — в зашифрованном хранилище
 * (ключ в Android Keystore), журнал событий — обычным файлом (в нём нет паролей).
 */
class Store(private val ctx: Context) {
    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            "nordcam_secure",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            ctx,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // На некоторых прошивках Keystore сбоит — работаем, но без шифрования
        ctx.getSharedPreferences("nordcam_plain", Context.MODE_PRIVATE)
    }

    private val _cameras = MutableStateFlow(loadCameras())
    val cameras: StateFlow<List<Camera>> = _cameras

    private val _settings = MutableStateFlow(Settings.from(prefs.getString("settings", null)?.let { JSONObject(it) }))
    val settings: StateFlow<Settings> = _settings

    private val _servers = MutableStateFlow(
        try { JSONArray(prefs.getString("servers", "[]")).mapObjects { ArchiveServer.from(it) } } catch (e: Exception) { emptyList() },
    )
    val servers: StateFlow<List<ArchiveServer>> = _servers

    fun server(id: String) = _servers.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsertServer(s: ArchiveServer) {
        val list = _servers.value.filterNot { it.id == s.id } + s
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("servers", arr.toString()).apply()
        _servers.value = list
    }

    @Synchronized
    fun deleteServer(id: String) {
        val list = _servers.value.filterNot { it.id == id }
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("servers", arr.toString()).apply()
        _servers.value = list
        // Отвязываем камеры от удалённого сервера
        val cams = _cameras.value
        if (cams.any { it.serverId == id }) saveCameras(cams.map { if (it.serverId == id) it.copy(serverId = "", serverCam = "", serverCamName = "") else it })
    }

    // ---- ИБП ----
    private val _ups = MutableStateFlow(
        try { JSONArray(prefs.getString("ups", "[]")).mapObjects { ru.nordcam.app.net.UpsDevice.from(it) } } catch (e: Exception) { emptyList() },
    )
    val ups: StateFlow<List<ru.nordcam.app.net.UpsDevice>> = _ups

    /** Последнее известное состояние каждого ИБП — для полосы внизу экрана. */
    val upsStatus = MutableStateFlow<Map<String, ru.nordcam.app.net.UpsStatus>>(emptyMap())

    @Synchronized
    fun saveUps(list: List<ru.nordcam.app.net.UpsDevice>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("ups", arr.toString()).apply()
        _ups.value = list
    }

    private val eventsFile = File(ctx.filesDir, "events.json")
    private val _events = MutableStateFlow(loadEvents())
    val events: StateFlow<List<Event>> = _events

    private fun loadCameras(): List<Camera> = try {
        JSONArray(prefs.getString("cameras", "[]")).mapObjects { Camera.from(it) }.sortedBy { it.order }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveCameras(list: List<Camera>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("cameras", arr.toString()).apply()
        _cameras.value = list.sortedBy { it.order }
    }

    fun camera(id: String) = _cameras.value.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(c: Camera) {
        val list = _cameras.value.toMutableList()
        val i = list.indexOfFirst { it.id == c.id }
        if (i >= 0) list[i] = c else list += c.copy(order = (list.maxOfOrNull { it.order } ?: 0) + 1)
        saveCameras(list)
    }

    @Synchronized
    fun upsertAll(cs: List<Camera>) {
        val list = _cameras.value.toMutableList()
        var next = (list.maxOfOrNull { it.order } ?: 0) + 1
        cs.forEach { c ->
            val i = list.indexOfFirst { it.id == c.id }
            if (i >= 0) list[i] = c else list += c.copy(order = next++)
        }
        saveCameras(list)
    }

    @Synchronized
    fun delete(id: String) = saveCameras(_cameras.value.filterNot { it.id == id })

    @Synchronized
    fun move(id: String, delta: Int) {
        val list = _cameras.value.sortedBy { it.order }.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        val a = list[i]; list[i] = list[j]; list[j] = a
        saveCameras(list.mapIndexed { idx, c -> c.copy(order = idx) })
    }

    val groups: List<String> get() = _cameras.value.map { it.group }.filter { it.isNotBlank() }.distinct().sorted()

    @Synchronized
    fun updateSettings(f: (Settings) -> Settings) {
        val s = f(_settings.value)
        prefs.edit().putString("settings", s.toJson().toString()).apply()
        _settings.value = s
    }

    // ---- журнал событий ----

    private fun loadEvents(): List<Event> = try {
        if (eventsFile.exists()) JSONArray(eventsFile.readText()).mapObjects { Event.from(it) } else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveEvents(list: List<Event>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        try {
            eventsFile.writeText(arr.toString())
        } catch (_: Exception) {
        }
        _events.value = list
    }

    @Synchronized
    fun addEvent(e: Event) {
        val list = (listOf(e) + _events.value)
        val keep = list.take(1000)
        list.drop(1000).forEach { old -> if (old.snapshot.isNotBlank()) File(old.snapshot).delete() }
        saveEvents(keep)
    }

    @Synchronized
    fun updateEvent(id: String, f: (Event) -> Event) = saveEvents(_events.value.map { if (it.id == id) f(it) else it })

    @Synchronized
    fun markAllSeen() = saveEvents(_events.value.map { it.copy(seen = true) })

    @Synchronized
    fun clearEvents() {
        _events.value.forEach { if (it.snapshot.isNotBlank()) File(it.snapshot).delete() }
        saveEvents(emptyList())
    }

    // ---- папки ----

    val recordingsDir: File
        get() = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir, "records").apply { mkdirs() }

    val snapshotsDir: File
        get() = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir, "snapshots").apply { mkdirs() }

    val eventsDir: File
        get() = File(ctx.filesDir, "event_shots").apply { mkdirs() }

    fun cameraRecDir(cameraId: String) = File(recordingsDir, cameraId).apply { mkdirs() }

    /** Удаляет самые старые записи, пока всё не уложится в лимит. */
    fun enforceStorageLimit() {
        val limit = _settings.value.storageMb.toLong() * 1024 * 1024
        val files = recordingsDir.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toMutableList()
        var total = files.sumOf { it.length() }
        while (total > limit && files.isNotEmpty()) {
            val f = files.removeAt(0)
            total -= f.length()
            f.delete()
        }
    }
}
