package ua.smsforwarder.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import ua.smsforwarder.core.*
import ua.smsforwarder.delivery.WebhookClient
import java.time.Instant
import java.util.UUID

class Repository(val db: ForwarderDatabase, val settings: SettingsStore, val secrets: Secrets) {
    val dao = db.dao()
    val rules = dao.rules().map { rows -> rows.mapNotNull { runCatching { wireJson.decodeFromString<Rule>(it.json) }.getOrNull() } }
    suspend fun saveWebhook(id: String, name: String, url: String, headers: Map<String,String>, enabled: Boolean) {
        require(name.isNotBlank()) { tr(Message.ENTER_A_WEBHOOK_NAME) }
        WebhookClient.validate(url, headers)
        dao.saveWebhook(WebhookEntity(id, name.trim(), url, secrets.encrypt(wireJson.encodeToString(headers)), enabled))
    }
    suspend fun deleteWebhook(id: String) { db.withTransaction { dao.cancelWebhook(id); dao.deleteWebhook(id) } }
    suspend fun saveRule(rule: Rule) {
        require(RuleEngine.validate(rule) == null) { RuleEngine.validate(rule) ?: tr(Message.INVALID_RULE) }
        require(!rule.enabled || rule.webhookIds.all { id -> dao.allWebhooks().any { it.id == id } }) { tr(Message.THE_WEBHOOK_WAS_DELETED) }
        dao.saveRule(RuleEntity(rule.id, wireJson.encodeToString(rule)))
    }
    suspend fun accept(event: Event) {
        val s = settings.current()
        if (!s.enabled) return
        if (event.type == "sms.received" && !s.sms || event.type == "call.completed" && !s.calls || event.type == "notification.posted" && (!s.notifications || event.data.packageName !in s.packages)) return
        db.withTransaction {
            if (dao.insertEvent(EventEntity(event.id, event.sourceKey, System.currentTimeMillis())) == -1L) return@withTransaction
            val ids = dao.allRules().mapNotNull { runCatching { wireJson.decodeFromString<Rule>(it.json) }.getOrNull() }
                .filter { RuleEngine.matches(it, event) }.flatMap { it.webhookIds }.toSet()
            dao.allWebhooks().filter { it.enabled && it.id in ids }.forEach { enqueue(event, it, s) }
        }
    }
    suspend fun testWebhook(webhook: WebhookEntity) {
        check(webhook.enabled) { tr(Message.ENABLE_THE_WEBHOOK_FIRST) }
        val s = settings.current()
        check(s.enabled) { tr(Message.ENABLE_FORWARDING_FIRST) }
        val e = Event(type = "test", data = EventData(text = tr(Message.SMS_FORWARDER_TEST_MESSAGE)), sourceKey = "test:${UUID.randomUUID()}")
        db.withTransaction { dao.insertEvent(EventEntity(e.id,e.sourceKey,System.currentTimeMillis())); enqueue(e,webhook,s) }
    }
    private suspend fun enqueue(event: Event, hook: WebhookEntity, s: Settings) {
        val id = UUID.randomUUID().toString()
        val payload = wireJson.encodeToString(Envelope(eventId = event.id, deliveryId = id,
            deviceId = s.deviceId, deviceName = s.deviceName, occurredAt = Instant.ofEpochMilli(event.occurredAt).toString(), type = event.type, data = event.data))
        dao.insertDelivery(DeliveryEntity(id, event.id, hook.id, payload, hook.url, hook.encryptedHeaders, System.currentTimeMillis()))
    }
}
