package ua.smsforwarder.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

val wireJson = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }

@Serializable
data class EventData(
    val sender: String? = null, val number: String? = null, val text: String? = null,
    @SerialName("sim_id") val simId: String? = null,
    @SerialName("call_type") val callType: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Long? = null,
    @SerialName("package_name") val packageName: String? = null,
    @SerialName("app_name") val appName: String? = null,
    val title: String? = null,
    @SerialName("notification_key") val notificationKey: String? = null
)
@Serializable
data class Event(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val occurredAt: Long = System.currentTimeMillis(),
    val data: EventData,
    val sourceKey: String
)
@Serializable
data class Envelope(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("event_id") val eventId: String,
    @SerialName("delivery_id") val deliveryId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("occurred_at") val occurredAt: String,
    val type: String,
    val data: EventData
)

@Serializable
enum class Field { SENDER, SIM, PACKAGE, TEXT, CALL_TYPE }
@Serializable
enum class Operator { EQUALS, CONTAINS, REGEX }
@Serializable
data class Condition(val field: Field = Field.TEXT, val operator: Operator = Operator.CONTAINS, val value: String = "")
@Serializable
data class ConditionGroup(val any: Boolean = false, val conditions: List<Condition> = emptyList(), val groups: List<ConditionGroup> = emptyList())
@Serializable
data class Schedule(val days: Set<Int> = (1..7).toSet(), val startMinute: Int = 0, val endMinute: Int = 0)
@Serializable
data class Rule(
    val id: String = UUID.randomUUID().toString(), val name: String = "",
    val enabled: Boolean = true,
    val types: Set<String> = setOf("sms.received"),
    val webhookIds: Set<String> = emptySet(),
    val conditions: ConditionGroup = ConditionGroup(), val schedule: Schedule? = null
)

object RuleEngine {
    fun validate(rule: Rule): String? {
        if (rule.name.isBlank()) return tr(Message.ENTER_A_RULE_NAME)
        if (rule.types.isEmpty() || rule.types.any { it !in setOf("sms.received", "call.completed", "notification.posted") }) return tr(Message.CHOOSE_EVENT_TYPES)
        if (rule.webhookIds.isEmpty()) return tr(Message.CHOOSE_A_WEBHOOK)
        rule.schedule?.let {
            if (it.days.isEmpty() || it.days.any { d -> d !in 1..7 } || it.startMinute !in 0..1439 || it.endMinute !in 0..1439) return tr(Message.INVALID_SCHEDULE)
        }
        fun check(group: ConditionGroup, depth: Int): String? {
            if (depth > 8) return tr(Message.MAXIMUM_8_NESTING_LEVELS)
            for (c in group.conditions) {
                if (c.value.isBlank()) return tr(Message.A_CONDITION_CANNOT_BE_EMPTY)
                if (c.value.length > 512) return tr(Message.CONDITION_IS_TOO_LONG_MAXIMUM_512_CHARACTERS)
                if (c.operator == Operator.REGEX && runCatching { Regex(c.value) }.isFailure) return tr(Message.INVALID_REGULAR_EXPRESSION , c.value)
            }
            return group.groups.firstNotNullOfOrNull { check(it, depth + 1) }
        }
        return check(rule.conditions, 0)
    }
    fun matches(rule: Rule, event: Event, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (!rule.enabled || event.type !in rule.types) return false
        if (rule.schedule?.let { !inSchedule(it, event.occurredAt, zone) } == true) return false
        return matchGroup(rule.conditions, event.data)
    }
    private fun matchGroup(group: ConditionGroup, data: EventData): Boolean {
        val values = group.conditions.map { c ->
            val actual = when(c.field) {
                Field.SENDER -> data.sender ?: data.number
                Field.SIM -> data.simId
                Field.PACKAGE -> data.packageName
                Field.TEXT -> listOfNotNull(data.title, data.text).joinToString("\n")
                Field.CALL_TYPE -> data.callType
            }
            actual != null && when(c.operator) {
                Operator.EQUALS -> actual.equals(c.value, ignoreCase = true)
                Operator.CONTAINS -> actual.contains(c.value, ignoreCase = true)
                Operator.REGEX -> runCatching { Regex(c.value).containsMatchIn(actual) }.getOrDefault(false)
            }
        } + group.groups.map { matchGroup(it, data) }
        return if (group.any) values.any { it } else values.all { it }
    }
    fun inSchedule(s: Schedule, at: Long, zone: ZoneId): Boolean {
        val local = Instant.ofEpochMilli(at).atZone(zone)
        val minute = local.hour * 60 + local.minute
        if (s.startMinute == s.endMinute) return local.dayOfWeek.value in s.days
        if (s.startMinute < s.endMinute) return local.dayOfWeek.value in s.days && minute >= s.startMinute && minute < s.endMinute
        val anchor = if (minute < s.endMinute) local.minusDays(1) else local
        return anchor.dayOfWeek.value in s.days && (minute >= s.startMinute || minute < s.endMinute)
    }
}
