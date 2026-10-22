package ua.smsforwarder.sources

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.TelephonyManager
import kotlinx.coroutines.launch
import ua.smsforwarder.ForwarderApp
import ua.smsforwarder.core.Event
import ua.smsforwarder.core.EventData
import ua.smsforwarder.delivery.DeliveryWork
import java.security.MessageDigest

fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return
        val pending = goAsync()
        val app = context.applicationContext as ForwarderApp
        app.scope.launch {
            try {
                val first = messages.first()
                val text = messages.joinToString("") { it.messageBody.orEmpty() }
                val sim = intent.extras?.let { b ->
                    listOf("subscription", "android.telephony.extra.SUBSCRIPTION_INDEX", "slot", "phone").firstNotNullOfOrNull { key ->
                        if (b.containsKey(key)) b.getInt(key, -1).takeIf { it >= 0 }?.toString() else null
                    }
                }
                val sender = first.displayOriginatingAddress ?: first.originatingAddress
                app.accept(Event(type = "sms.received", occurredAt = first.timestampMillis,
                    data = EventData(sender = sender, text = text, simId = sim),
                    sourceKey = "sms:${fingerprint("$sender|${first.timestampMillis}|$sim|$text")}"))
            } finally { pending.finish() }
        }
    }
}
class PhoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val pending = goAsync()
        val app = context.applicationContext as ForwarderApp
        app.scope.launch {
            try { app.calls.scan(); DeliveryWork.wake(context) } finally { pending.finish() }
        }
    }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        val app = context.applicationContext as ForwarderApp
        app.scope.launch {
            try { if (app.settings.current().enabled) app.startMonitor(); DeliveryWork.wake(context) }
            finally { pending.finish() }
        }
    }
}
