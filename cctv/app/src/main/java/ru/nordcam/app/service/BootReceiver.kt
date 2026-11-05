package ru.nordcam.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.nordcam.app.app

/** После перезагрузки телефона или обновления приложения — вернуть наблюдение, если оно было включено. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (context.app.store.settings.value.monitoring) {
            try { MonitorService.sync(context) } catch (_: Exception) {}
        }
    }
}
