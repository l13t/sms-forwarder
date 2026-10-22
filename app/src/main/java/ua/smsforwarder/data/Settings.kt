package ua.smsforwarder.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.store by preferencesDataStore("settings")
data class Settings(val enabled: Boolean = false, val sms: Boolean = false, val calls: Boolean = false, val notifications: Boolean = false, val packages: Set<String> = emptySet(), val deviceId: String = "", val deviceName: String = Build.MODEL, val language: String = "system")
class SettingsStore(private val context: Context) {
    private val enabled = booleanPreferencesKey("enabled")
    private val sms = booleanPreferencesKey("sms")
    private val calls = booleanPreferencesKey("calls")
    private val notifications = booleanPreferencesKey("notifications")
    private val packages = stringSetPreferencesKey("packages")
    private val deviceId = stringPreferencesKey("device_id")
    private val name = stringPreferencesKey("device_name")
    private val language = stringPreferencesKey("language")
    val flow = context.store.data.map { p -> Settings(p[enabled] ?: false, p[sms] ?: false, p[calls] ?: false, p[notifications] ?: false, p[packages] ?: emptySet(), p[deviceId] ?: "", p[name] ?: Build.MODEL, p[language] ?: "system") }
    suspend fun current(): Settings { ensureId(); return flow.first() }
    private suspend fun ensureId() { context.store.edit { if (it[deviceId] == null) it[deviceId] = UUID.randomUUID().toString() } }
    suspend fun update(change: (Settings) -> Settings) {
        context.store.edit { p ->
            val old = Settings(p[enabled] ?: false, p[sms] ?: false, p[calls] ?: false, p[notifications] ?: false, p[packages] ?: emptySet(), p[deviceId] ?: UUID.randomUUID().toString(), p[name] ?: Build.MODEL, p[language] ?: "system")
            val s = change(old)
            require(s.language in setOf("system", "en", "uk")) { "Unsupported language" }
            p[enabled] = s.enabled; p[sms] = s.sms; p[calls] = s.calls; p[notifications] = s.notifications
            p[packages] = s.packages; p[deviceId] = s.deviceId; p[name] = s.deviceName; p[language] = s.language
        }
    }
}
