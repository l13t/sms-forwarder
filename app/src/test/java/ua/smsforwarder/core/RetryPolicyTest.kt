package ua.smsforwarder.core

import org.junit.Assert.*
import org.junit.Test
import ua.smsforwarder.delivery.RetryPolicy
import java.time.Instant

class RetryPolicyTest {
    @Test fun backoffGrowsAndCapsAtSixHours() {
        assertEquals(30_000L,RetryPolicy.delay(1))
        assertEquals(60_000L,RetryPolicy.delay(2))
        assertEquals(21_600_000L,RetryPolicy.delay(100))
    }
    @Test fun serverDelayCanExtendBackoff() {
        assertEquals(120_000L,RetryPolicy.delay(1,120_000))
        assertEquals(30_000L,RetryPolicy.delay(1,1000))
    }
    @Test fun retryAfterParsesSecondsDatesAndInvalidValues() {
        val now=Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
        assertEquals(120_000L,RetryPolicy.retryAfter("120",now))
        assertEquals(60_000L,RetryPolicy.retryAfter("Mon, 19 Oct 2026 12:01:00 GMT",now))
        assertEquals(0L,RetryPolicy.retryAfter("Mon, 19 Oct 2026 11:00:00 GMT",now))
        assertNull(RetryPolicy.retryAfter("invalid",now))
        assertNull(RetryPolicy.retryAfter(null,now))
    }
}
