package ua.smsforwarder.sources

import ua.smsforwarder.core.Message
import ua.smsforwarder.core.tr

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import ua.smsforwarder.core.I18n
import ua.smsforwarder.ForwarderApp
import ua.smsforwarder.MainActivity
import ua.smsforwarder.R

class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app get() = application as ForwarderApp
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { scope.launch { runCatching { app.calls.scan() }.onFailure { app.runtimeError.value = tr(Message.COULD_NOT_READ_THE_CALL_LOG_CHECK) }; runCatching { app.dispatcher.drain() }.onFailure { app.runtimeError.value = tr(Message.QUEUE_ERROR_RESTART_FORWARDING) } } }
    }
    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, notification)
        app.monitorRunning.value = true
        app.runtimeError.value = null
        runCatching { contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer) }
        scope.launch {
            app.settings.flow.collect {
                I18n.setPreference(it.language, resources.configuration.locales[0]?.language ?: "en")
                runCatching { requireNotNull(getSystemService(NotificationManager::class.java)).notify(1, buildNotification()) }
            }
        }
        scope.launch {
            while (isActive) {
                if (!app.settings.current().enabled) { stopSelf(); break }
                runCatching { app.calls.scan() }.onFailure { app.runtimeError.value = tr(Message.COULD_NOT_READ_THE_CALL_LOG_CHECK) }
                runCatching { app.dispatcher.drain() }.onFailure { app.runtimeError.value = tr(Message.QUEUE_ERROR_RESTART_FORWARDING) }
                delay(10_000)
            }
        }
    }
    private fun buildNotification(): Notification {
        val manager = requireNotNull(getSystemService(NotificationManager::class.java))
        manager.createNotificationChannel(NotificationChannel("monitor", tr(Message.BACKGROUND_FORWARDING), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "monitor").setSmallIcon(R.drawable.ic_forward)
            .setContentTitle(tr(Message.SMS_FORWARDER_IS_RUNNING)).setContentText(tr(Message.FORWARDING_SELECTED_EVENTS_IS_ENABLED))
            .setContentIntent(open).setOngoing(true).setSilent(true).build()
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        scope.launch {
            I18n.setPreference(app.settings.current().language, newConfig.locales[0]?.language ?: "en")
            runCatching { requireNotNull(getSystemService(NotificationManager::class.java)).notify(1, buildNotification()) }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { app.monitorRunning.value = false; contentResolver.unregisterContentObserver(observer); scope.cancel(); super.onDestroy() }
}
