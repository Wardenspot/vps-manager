package ru.nordcam.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.nordcam.app.CamApp
import ru.nordcam.app.MainActivity
import ru.nordcam.app.R
import ru.nordcam.app.app
import ru.nordcam.app.data.CamOps
import ru.nordcam.app.data.Camera
import ru.nordcam.app.data.Event
import ru.nordcam.app.data.MotionMode
import ru.nordcam.app.data.RecordMode
import ru.nordcam.app.net.Http
import ru.nordcam.app.net.Mail
import ru.nordcam.app.net.Telegram
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Фоновое наблюдение: события движения (ONVIF или сравнение снимков), уведомления,
 * запись роликов по движению и постоянная запись по отрезкам.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<String, Pair<Camera, Job>>()
    private val lastNotify = HashMap<String, Long>()
    private val lastMotion = HashMap<String, Long>()
    private var wake: PowerManager.WakeLock? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.store.updateSettings { it.copy(monitoring = false) }
            stopEverything()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(0),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NordCam:monitor").apply { setReferenceCounted(false); acquire() }
        }
        if (watcher == null) {
            watcher = scope.launch {
                while (isActive) {
                    reconcile()
                    delay(3000)
                }
            }
        }
        return START_STICKY
    }

    private fun watched(c: Camera) = !c.perms.expired &&
        (c.motion != MotionMode.OFF || (c.record == RecordMode.CONTINUOUS && c.perms.record))

    /** Синхронизирует задачи с текущим списком камер и настройками. */
    private fun reconcile() {
        val store = app.store
        if (!store.settings.value.monitoring) {
            stopEverything()
            return
        }
        val cams = store.cameras.value.filter(::watched).associateBy { it.id }
        synchronized(jobs) {
            jobs.keys.toList().forEach { id ->
                val (old, job) = jobs[id]!!
                val now = cams[id]
                if (now == null || now != old || !job.isActive) {
                    job.cancel()
                    jobs.remove(id)
                    if (app.recorder.kindOf(id) != RecKind.MANUAL) app.recorder.stop(id)
                }
            }
            cams.values.forEach { c ->
                if (!jobs.containsKey(c.id)) jobs[c.id] = c to scope.launch { runCamera(c) }
            }
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(cams.size))
    }

    private suspend fun runCamera(c: Camera) = coroutineScope {
        if (c.record == RecordMode.CONTINUOUS && c.perms.record) launch { continuous(c) }
        when (c.motion) {
            MotionMode.ONVIF -> onvifLoop(c)
            MotionMode.SNAPSHOT -> snapshotLoop(c)
            MotionMode.OFF -> {}
        }
    }

    private suspend fun alive() = currentCoroutineContext().isActive

    // ------------------------------------------------------------------ события ONVIF

    private suspend fun onvifLoop(c: Camera) {
        var backoff = 5_000L
        while (alive()) {
            val o = CamOps.onvif(c)
            try {
                o.subscribe()
                backoff = 5_000L
                var lastRenew = System.currentTimeMillis()
                while (alive()) {
                    val evs = o.pull(10)
                    evs.firstOrNull { it.active }?.let { alarm(c, it.kind ?: "Движение") }
                    if (System.currentTimeMillis() - lastRenew > 240_000) {
                        o.renew(); lastRenew = System.currentTimeMillis()
                    }
                    motionRecordingTick(c)
                }
                o.unsubscribe()
                return
            } catch (e: Exception) {
                motionRecordingTick(c)
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
    }

    // ------------------------------------------------------------------ сравнение снимков

    private suspend fun snapshotLoop(c: Camera) {
        var prev: IntArray? = null
        var hits = 0
        while (alive()) {
            val jpeg = try { CamOps.snapshot(app.store, c) } catch (e: Exception) { null }
            if (jpeg != null) {
                val cur = Http.decode(jpeg, 160)?.let { gray(it) }
                val p = prev
                if (cur != null && p != null && p.size == cur.size) {
                    val changed = cur.indices.count { abs(cur[it] - p[it]) > 28 }.toFloat() / cur.size
                    val need = 0.20f - (c.sensitivity.coerceIn(1, 100) - 1) / 99f * 0.195f
                    // Резкая смена всей картинки — это включение ИК-подсветки или свет, не движение
                    hits = if (changed >= need && changed < 0.75f) hits + 1 else 0
                    if (hits >= 2) {
                        hits = 0
                        alarm(c, "Движение", jpeg)
                    }
                }
                if (cur != null) prev = cur
            }
            motionRecordingTick(c)
            delay(1500)
        }
    }

    private fun gray(b: Bitmap): IntArray {
        val w = 64
        val h = 48
        val s = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        s.getPixels(px, 0, w, 0, 0, w, h)
        if (s !== b) s.recycle()
        b.recycle()
        return IntArray(px.size) { i ->
            val p = px[i]
            ((p shr 16 and 0xFF) * 30 + (p shr 8 and 0xFF) * 59 + (p and 0xFF) * 11) / 100
        }
    }

    // ------------------------------------------------------------------ запись

    private suspend fun continuous(c: Camera) {
        val seg = app.store.settings.value.segmentMinutes.coerceIn(1, 60) * 60_000L
        while (alive()) {
            val f = app.recorder.start(c, RecKind.CONTINUOUS)
            val started = System.currentTimeMillis()
            while (alive() && System.currentTimeMillis() - started < seg) delay(5_000)
            if (app.recorder.kindOf(c.id) == RecKind.CONTINUOUS) app.recorder.stop(c.id)
            if (f == null || !f.exists()) delay(30_000)
        }
    }

    /** Ролик по движению длится, пока есть движение, плюс заданное время после. */
    private fun motionRecordingTick(c: Camera) {
        if (app.recorder.kindOf(c.id) != RecKind.MOTION) return
        val clip = app.store.settings.value.clipSeconds.coerceIn(5, 600) * 1000L
        val last = synchronized(lastMotion) { lastMotion[c.id] ?: 0L }
        if (System.currentTimeMillis() - last > clip) app.recorder.stop(c.id)
    }

    // ------------------------------------------------------------------ тревога

    private fun alarm(c: Camera, kind: String, jpegIn: ByteArray? = null) {
        val now = System.currentTimeMillis()
        synchronized(lastMotion) { lastMotion[c.id] = now }
        var clip: File? = null
        if (c.record == RecordMode.MOTION && c.perms.record && !app.recorder.isRecording(c.id)) {
            clip = app.recorder.start(c, RecKind.MOTION)
        } else if (app.recorder.isRecording(c.id)) {
            clip = null
        }
        val cooldown = app.store.settings.value.cooldownSec * 1000L
        val last = synchronized(lastNotify) { lastNotify[c.id] ?: 0L }
        if (now - last < cooldown) return
        synchronized(lastNotify) { lastNotify[c.id] = now }

        val jpeg = jpegIn ?: try { CamOps.snapshot(app.store, c) } catch (e: Exception) { null }
        var shotPath = ""
        if (jpeg != null) {
            val f = File(app.store.eventsDir, "${c.id}_$now.jpg")
            try { f.writeBytes(jpeg); shotPath = f.absolutePath } catch (_: Exception) {}
        }
        val ev = Event(cameraId = c.id, cameraName = c.title, time = now, kind = kind, snapshot = shotPath, clip = clip?.absolutePath.orEmpty())
        app.store.addEvent(ev)
        if (c.notify) notifyAlarm(c, ev, jpeg)
        val s = app.store.settings.value
        val t = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ru")).format(Date(now))
        val where = c.title + if (c.group.isNotBlank()) " (${c.group})" else ""
        // Отправка идёт отдельно, чтобы не задерживать приём следующих событий с камеры
        if (s.tgToken.isNotBlank() && s.tgChat.isNotBlank()) {
            scope.launch { Telegram.send(s.tgToken, s.tgChat, "🚨 $kind — $where\n$t", jpeg) }
        }
        if (s.mailReady) {
            scope.launch {
                Mail.send(
                    s.mailHost, s.mailPort, s.mailUser, s.mailPass, s.mailTo,
                    "NordCam: $kind — $where",
                    "$kind\nКамера: $where\nВремя: $t\n\n" + if (jpeg != null) "Снимок во вложении." else "Снимок получить не удалось.",
                    jpeg,
                )
            }
        }
    }

    private fun notifyAlarm(c: Camera, ev: Event, jpeg: ByteArray?) {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_EVENT, ev.id)
            putExtra(MainActivity.EXTRA_CAMERA, c.id)
        }
        val pi = PendingIntent.getActivity(this, ev.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val t = SimpleDateFormat("HH:mm:ss", Locale("ru")).format(Date(ev.time))
        val b = NotificationCompat.Builder(this, CamApp.CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${ev.kind}: ${c.title}")
            .setContentText("${if (c.group.isNotBlank()) c.group + " · " else ""}$t")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        val bmp = jpeg?.let { Http.decode(it, 720) }
        if (bmp != null) b.setLargeIcon(bmp).setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as Bitmap?))
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try { nm.notify(c.id.hashCode(), b.build()) } catch (_: SecurityException) {}
    }

    private fun buildNotification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MonitorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CamApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Наблюдение включено")
            .setContentText(if (count == 0) "Нет камер с детекцией или записью" else "Камер под наблюдением: $count")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Выключить", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopEverything() {
        synchronized(jobs) {
            jobs.values.forEach { it.second.cancel() }
            jobs.clear()
        }
        app.recorder.stopAll()
        watcher?.cancel(); watcher = null
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        synchronized(jobs) { jobs.values.forEach { it.second.cancel() }; jobs.clear() }
        scope.cancel()
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 42
        const val ACTION_STOP = "ru.nordcam.STOP"

        fun sync(ctx: Context) {
            val s = ctx.app.store.settings.value
            if (s.monitoring) {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MonitorService::class.java))
            } else {
                ctx.stopService(Intent(ctx, MonitorService::class.java))
            }
        }
    }
}
