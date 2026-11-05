package ru.nordcam.app.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.videolan.libvlc.MediaPlayer
import ru.nordcam.app.data.Camera
import ru.nordcam.app.data.Store
import ru.nordcam.app.data.Vendors
import ru.nordcam.app.player.Vlc
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RecKind(val tag: String, val title: String) {
    MANUAL("ruchn", "Вручную"),
    MOTION("dvizh", "По движению"),
    CONTINUOUS("post", "Постоянная");

    companion object {
        fun ofFile(f: File) = entries.firstOrNull { f.name.contains("_${it.tag}") } ?: MANUAL
    }
}

/** Запись RTSP-потока в файл на телефоне — отдельным подключением, без влияния на просмотр. */
class Recorder(private val ctx: Context, private val store: Store) {
    private class Rec(val player: MediaPlayer, val file: File, val kind: RecKind)

    private val active = HashMap<String, Rec>()
    private val _recording = MutableStateFlow<Map<String, RecKind>>(emptyMap())
    val recording: StateFlow<Map<String, RecKind>> = _recording

    private val nameFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun isRecording(camId: String) = synchronized(active) { active.containsKey(camId) }

    /** Начать запись. Если уже идёт — вернёт текущий файл. */
    fun start(cam: Camera, kind: RecKind): File? = synchronized(active) {
        active[cam.id]?.let { return it.file }
        val url = Vendors.liveUrl(cam, sub = false)
        if (url.isBlank()) return null
        store.enforceStorageLimit()
        val file = File(store.cameraRecDir(cam.id), "${nameFmt.format(Date())}_${kind.tag}.mkv")
        return try {
            val p = MediaPlayer(Vlc.lib(ctx))
            val m = Vlc.recordMedia(ctx, url, cam, file)
            p.setMedia(m)
            m.release()
            p.play()
            active[cam.id] = Rec(p, file, kind)
            publish()
            file
        } catch (e: Exception) {
            null
        }
    }

    fun stop(camId: String): File? {
        val r = synchronized(active) { active.remove(camId).also { publish() } } ?: return null
        try {
            r.player.stop()
            r.player.release()
        } catch (_: Exception) {
        }
        // Пустые/битые файлы (камера не ответила) не храним
        if (r.file.exists() && r.file.length() < 16 * 1024) {
            r.file.delete()
            return null
        }
        return r.file.takeIf { it.exists() }
    }

    fun kindOf(camId: String): RecKind? = synchronized(active) { active[camId]?.kind }

    fun stopAll() {
        val ids = synchronized(active) { active.keys.toList() }
        ids.forEach { stop(it) }
    }

    private fun publish() {
        _recording.value = active.mapValues { it.value.kind }
    }
}
