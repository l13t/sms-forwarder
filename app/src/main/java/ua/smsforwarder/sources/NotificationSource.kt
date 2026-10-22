package ua.smsforwarder.sources

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ua.smsforwarder.ForwarderApp
import ua.smsforwarder.core.Event
import ua.smsforwarder.core.EventData
import ua.smsforwarder.data.CursorEntity

class NotificationSource : NotificationListenerService() {
    private val mutex = Mutex()
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val app = application as ForwarderApp
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        app.scope.launch {
            mutex.withLock {
                val s = app.settings.current()
                if (!s.enabled || !s.notifications || sbn.packageName !in s.packages) return@withLock
                val hash = fingerprint("$title|$text")
                val key = "notification:${sbn.key}"
                if (app.repo.dao.cursor(key) == hash) return@withLock
                val name = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                app.accept(Event(type = "notification.posted", occurredAt = sbn.postTime,
                    data = EventData(packageName = sbn.packageName, appName = name, title = title, text = text, notificationKey = sbn.key),
                    sourceKey = "$key:${sbn.postTime}:$hash"))
                app.repo.dao.cursor(CursorEntity(key, hash))
            }
        }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val app = application as ForwarderApp
        app.scope.launch { mutex.withLock { app.repo.dao.deleteCursor("notification:${sbn.key}") } }
    }
}
