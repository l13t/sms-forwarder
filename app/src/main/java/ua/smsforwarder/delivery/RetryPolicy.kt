package ua.smsforwarder.delivery

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min

object RetryPolicy {
    const val DAY = 86_400_000L
    fun delay(attempt: Int, retryAfter: Long? = null): Long {
        val exponential = min(30_000L * (1L shl (attempt - 1).coerceIn(0, 10)), 6 * 60 * 60_000L)
        return maxOf(exponential, retryAfter ?: 0)
    }
    fun retryAfter(value: String?, now: Long): Long? {
        if (value == null) return null
        value.toLongOrNull()?.let { return it.coerceIn(0L, 7L * 24 * 3600).times(1000L) }
        return runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceIn(0, 7 * DAY) }.getOrNull()
    }
}
