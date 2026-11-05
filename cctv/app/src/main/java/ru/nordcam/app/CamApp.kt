package ru.nordcam.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import ru.nordcam.app.data.Store
import ru.nordcam.app.service.Recorder

class CamApp : Application() {
    lateinit var store: Store
        private set
    lateinit var recorder: Recorder
        private set

    /** Когда приложение ушло в фон — для автоблокировки PIN-кодом. */
    @Volatile
    var backgroundSince: Long = 0L

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        recorder = Recorder(this, store)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Наблюдение в фоне", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Постоянное уведомление, пока идёт наблюдение и запись"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Тревоги", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Движение и другие события с камер"
                enableVibration(true)
            },
        )
    }

    companion object {
        const val CH_SERVICE = "service"
        const val CH_ALARM = "alarm"
    }
}

val Context.app: CamApp get() = applicationContext as CamApp
