package ua.smsforwarder

import ua.smsforwarder.core.Message
import ua.smsforwarder.core.tr

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import ua.smsforwarder.core.I18n
import ua.smsforwarder.core.Event
import ua.smsforwarder.data.*
import ua.smsforwarder.delivery.*
import ua.smsforwarder.sources.CallScanner
import ua.smsforwarder.sources.MonitorService

class ForwarderApp : Application() {
    val monitorRunning = MutableStateFlow(false)
    val runtimeError = MutableStateFlow<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
        runtimeError.value = tr(Message.EVENT_PROCESSING_ERROR_CHECK_PERMISSIONS_AND_RESTART)
    })
    val settings by lazy { SettingsStore(this) }
    val db by lazy { Room.databaseBuilder(this, ForwarderDatabase::class.java, "forwarder.db").build() }
    val repo by lazy { Repository(db, settings, Secrets()) }
    val dispatcher by lazy { Dispatcher(repo, this) }
    val calls by lazy { CallScanner(this, repo) }
    override fun onCreate() {
        super.onCreate()
        DeliveryWork.install(this)
        scope.launch {
            settings.flow.collect { I18n.setPreference(it.language, resources.configuration.locales[0]?.language ?: "en") }
        }
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        scope.launch { I18n.setPreference(settings.current().language, newConfig.locales[0]?.language ?: "en") }
    }
    suspend fun accept(event: Event) { repo.accept(event); requestDelivery() }
    fun requestDelivery() {
        DeliveryWork.wake(this)
        if (monitorRunning.value) scope.launch { dispatcher.drain() }
    }
    fun startMonitor(): String? = try {
        ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java)); null
    } catch (_: Exception) { tr(Message.ANDROID_BLOCKED_BACKGROUND_STARTUP_OPEN_THE_APP) }
    fun stopMonitor() { stopService(Intent(this, MonitorService::class.java)) }
}
