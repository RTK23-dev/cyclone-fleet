package com.cyclone.mobile.brain.v31

import com.cyclone.mobile.brain.memory.api.MemoryClass
import com.cyclone.mobile.brain.memory.api.MemoryVerificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V31MemoryBridgeTest {
    @Test
    fun currentObservationOutranksStaleMemory() {
        val current = V31KnowledgeItem("current", "Apps is visible", V31KnowledgeOrigin.CURRENT_OBSERVATION, 0.7, false, 10)
        val staleMemory = V31KnowledgeItem("memory", "Old route", V31KnowledgeOrigin.MEMORY_SERVICE, 0.99, true, 100)
        val legacy = V31KnowledgeItem("legacy", "Older route", V31KnowledgeOrigin.LEGACY_BRAIN, 1.0, false, 200)

        val ranked = V31KnowledgeRanking.merge(listOf(current), listOf(staleMemory), listOf(legacy), 10)

        assertEquals("current", ranked.first().id)
        assertTrue(ranked.indexOf(staleMemory) > 0)
    }

    @Test
    fun sensitiveMemoryFieldsAreRemovedAndMockVerificationIsDowngraded() {
        val prepared = V31MemorySanitizer.prepare(
            V31StructuredMemoryWrite(
                producer = V31MemoryProducer.TEACH,
                appPackage = "com.example.app",
                evidenceId = "mock-evidence",
                observedAtEpochMillis = 10,
                confidence = 0.9,
                verificationState = MemoryVerificationState.VERIFIED,
                verifiedRuntimeEvidence = false,
                memoryClass = MemoryClass.STRUCTURAL_KNOWLEDGE,
                fields = mapOf("route" to "Settings to Apps", "password" to "do-not-store"),
            ),
        )

        assertTrue(prepared is V31PreparedMemory.Ready)
        val draft = (prepared as V31PreparedMemory.Ready).proposal.draft
        assertEquals(MemoryVerificationState.OBSERVED, draft.verificationState)
        assertFalse(draft.content.fields.containsKey("password"))
        assertEquals("Settings to Apps", draft.content.fields["route"])
    }

    @Test
    fun sensitiveOnlyMemoryIsRejected() {
        val prepared = V31MemorySanitizer.prepare(
            V31StructuredMemoryWrite(
                producer = V31MemoryProducer.AI,
                appPackage = "com.example.app",
                evidenceId = "event-1",
                observedAtEpochMillis = 10,
                confidence = 0.5,
                verificationState = MemoryVerificationState.UNVERIFIED,
                verifiedRuntimeEvidence = false,
                memoryClass = MemoryClass.RUNTIME_HINT,
                fields = mapOf("otp" to "123456"),
            ),
        )
        assertTrue(prepared is V31PreparedMemory.Rejected)
    }
}
