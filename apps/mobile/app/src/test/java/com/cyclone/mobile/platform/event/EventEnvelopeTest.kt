package com.cyclone.mobile.platform.event

import com.cyclone.mobile.platform.module.ModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventEnvelopeTest {
    @Test
    fun envelopeCarriesCorrelationAndExplicitRedactionMetadata() {
        val envelope = EventEnvelope(
            eventId = "evt:001",
            eventType = "mission.observed",
            schemaVersion = 1,
            timestampEpochMillis = 123L,
            missionId = "mission-1",
            sessionId = "session-1",
            moduleId = ModuleId("page.awareness"),
            correlationId = "action-1",
            payload = mapOf("typedValue" to "[REDACTED]"),
            redaction = RedactionMetadata(
                classification = DataClassification.SENSITIVE,
                redactedFields = setOf("typedValue"),
                containsSensitiveData = true,
            ),
        )

        assertEquals("mission.observed", envelope.eventType)
        assertEquals("[REDACTED]", envelope.payload["typedValue"])
        assertTrue(envelope.redaction.containsSensitiveData)
    }

    @Test(expected = IllegalArgumentException::class)
    fun envelopeRejectsUnnamespacedEventType() {
        EventEnvelope(
            eventId = "evt:002",
            eventType = "observed",
            schemaVersion = 1,
            timestampEpochMillis = 123L,
            moduleId = ModuleId("page.awareness"),
            payload = Unit,
        )
    }
}
