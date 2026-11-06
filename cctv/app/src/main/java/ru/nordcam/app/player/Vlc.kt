package ru.nordcam.app.player

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import ru.nordcam.app.data.Camera
import java.io.File

object Vlc {
    @Volatile
    private var lib: LibVLC? = null

    fun lib(ctx: Context): LibVLC = lib ?: synchronized(this) {
        lib ?: LibVLC(
            ctx.applicationContext,
            arrayListOf("--no-sub-autodetect-file", "--no-stats", "--no-osd", "--no-snapshot-preview"),
        ).also { lib = it }
    }

    /** Добавить логин/пароль: для RTSP — опциями, для HTTP — в адрес. */
    private fun withCreds(url: String, cam: Camera): String {
        if (cam.user.isBlank() || !url.startsWith("http", true) || url.contains("@")) return url
        val u = Uri.parse(url)
        return u.buildUpon()
            .encodedAuthority("${Uri.encode(cam.user)}:${Uri.encode(cam.password)}@${u.encodedAuthority}")
            .build().toString()
    }

    private fun applyCommon(m: Media, url: String, cam: Camera) {
        if (url.startsWith("rtsp", true)) {
            if (cam.tcp) m.addOption(":rtsp-tcp")
            if (cam.user.isNotBlank() && !url.contains("@")) {
                m.addOption(":rtsp-user=${cam.user}")
                m.addOption(":rtsp-pwd=${cam.password}")
            }
            m.addOption(":rtsp-frame-buffer-size=2000000")
        }
    }

    fun liveMedia(ctx: Context, url: String, cam: Camera, lowLatency: Boolean, audio: Boolean): Media {
        val full = withCreds(url, cam)
        val m = Media(lib(ctx), Uri.parse(full))
        m.setHWDecoderEnabled(true, false)
        m.addOption(":network-caching=${if (lowLatency) 400 else 1200}")
        m.addOption(":live-caching=${if (lowLatency) 400 else 1200}")
        m.addOption(":clock-jitter=0")
        m.addOption(":clock-synchro=0")
        if (!audio) m.addOption(":no-audio")
        applyCommon(m, full, cam)
        return m
    }

    fun fileMedia(ctx: Context, file: File): Media {
        val m = Media(lib(ctx), Uri.fromFile(file))
        m.setHWDecoderEnabled(true, false)
        return m
    }

    /** Запись потока в файл без перекодирования (Matroska — переживает обрывы и любые кодеки). */
    fun recordMedia(ctx: Context, url: String, cam: Camera, dst: File): Media {
        val full = withCreds(url, cam)
        val m = Media(lib(ctx), Uri.parse(full))
        m.addOption(":network-caching=1500")
        val path = dst.absolutePath.replace("\\", "/")
        m.addOption(":sout=#std{access=file,mux=mkv,dst='$path'}")
        m.addOption(":sout-all")
        m.addOption(":sout-keep")
        m.addOption(":no-sout-display")
        applyCommon(m, full, cam)
        return m
    }

    fun findTexture(v: View): TextureView? {
        if (v is TextureView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTexture(v.getChildAt(i))?.let { return it }
        return null
    }
}

enum class PlayState { CONNECTING, PLAYING, ERROR, ENDED }

/** Обёртка над плеером libVLC с состоянием для интерфейса. */
class CamPlayer(private val ctx: Context) {
    val player = MediaPlayer(Vlc.lib(ctx))
    private val _state = MutableStateFlow(PlayState.CONNECTING)
    val state: StateFlow<PlayState> = _state
    private val _time = MutableStateFlow(0L)
    val time: StateFlow<Long> = _time
    private val _length = MutableStateFlow(0L)
    val length: StateFlow<Long> = _length
    private var layout: VLCVideoLayout? = null
    @Volatile var released = false; private set

    /**
     * Остановка и смена потока в libVLC блокируют поток, пока камера не ответит (до десятков секунд,
     * если камера недоступна). Поэтому всё тяжёлое делается в отдельном потоке — экран не зависает.
     */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "nordcam-player").apply { isDaemon = true } }

    private fun bg(f: () -> Unit) {
        try { worker.execute { try { f() } catch (_: Throwable) {} } } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    init {
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Opening, MediaPlayer.Event.Buffering ->
                    if (_state.value != PlayState.PLAYING) _state.value = PlayState.CONNECTING
                MediaPlayer.Event.Vout -> if (e.voutCount > 0) _state.value = PlayState.PLAYING
                MediaPlayer.Event.Playing -> if (_state.value == PlayState.ERROR) _state.value = PlayState.CONNECTING
                MediaPlayer.Event.EncounteredError -> _state.value = PlayState.ERROR
                MediaPlayer.Event.EndReached -> _state.value = PlayState.ENDED
                MediaPlayer.Event.TimeChanged -> _time.value = e.timeChanged
                MediaPlayer.Event.LengthChanged -> _length.value = e.lengthChanged
                else -> {}
            }
        }
    }

    fun attach(l: VLCVideoLayout) {
        if (released) return
        if (layout === l) return
        if (layout != null) player.detachViews()
        layout = l
        player.attachViews(l, null, false, true)
        player.setVideoScale(MediaPlayer.ScaleType.SURFACE_BEST_FIT)
    }

    fun setFill(fill: Boolean) {
        if (!released) player.setVideoScale(if (fill) MediaPlayer.ScaleType.SURFACE_FILL else MediaPlayer.ScaleType.SURFACE_BEST_FIT)
    }

    fun play(media: Media) {
        if (released) { media.release(); return }
        _state.value = PlayState.CONNECTING
        bg {
            if (released) { media.release(); return@bg }
            player.setMedia(media)
            media.release()
            player.play()
        }
    }

    fun stop() {
        bg { if (!released) player.stop() }
    }

    fun setVolume(v: Int) {
        if (!released) player.setVolume(v)
    }

    fun snapshot(): Bitmap? = layout?.let { Vlc.findTexture(it) }?.takeIf { it.isAvailable }?.bitmap

    fun release() {
        if (released) return
        released = true
        try {
            player.setEventListener(null)
            player.detachViews()
        } catch (_: Exception) {
        }
        layout = null
        bg {
            player.stop()
            player.release()
        }
        worker.shutdown()
    }
}
