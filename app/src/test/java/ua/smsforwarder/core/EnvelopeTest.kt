package ua.smsforwarder.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.*
import org.junit.Test

class EnvelopeTest {
    @Test fun versionOneWireFormatIsStableAndPreservesUnavailableFields() {
        val value=Envelope(eventId="event-1",deliveryId="delivery-1",deviceId="device-1",deviceName="Phone",occurredAt="2026-10-19T12:00:00Z",type="sms.received",data=EventData(sender="Bank",text="Hello"))
        val encoded=wireJson.encodeToString(value)
        assertTrue(encoded.contains("\"schema_version\":1"))
        assertTrue(encoded.contains("\"event_id\":\"event-1\""))
        assertTrue(encoded.contains("\"sim_id\":null"))
        assertFalse(encoded.contains("sourceKey"))
        assertEquals(value,wireJson.decodeFromString<Envelope>(encoded))
    }
}
