package com.cyclone.mobile.brain.memory.tiered

import com.cyclone.mobile.brain.memory.api.MemoryClass
import org.junit.Assert.assertEquals
import org.junit.Test

class TieredMemoryPolicyTest {
    @Test
    fun memoryClassSelectsExactlyOneDeterministicTier() {
        assertEquals(MemoryTier.MISSION_HOT, DeterministicTierSelector.select(MemoryClass.RUNTIME_HINT))
        assertEquals(MemoryTier.KNOWLEDGE_DOCUMENTS, DeterministicTierSelector.select(MemoryClass.DOCUMENT_REFERENCE))
        assertEquals(MemoryTier.STRUCTURAL_DURABLE, DeterministicTierSelector.select(MemoryClass.STRUCTURAL_KNOWLEDGE))
    }

    @Test
    fun tierBudgetsRejectInvalidRelationships() {
        try {
            TierBudget(maxRecordsPerScope = 1, maxContentBytesPerScope = 10, maxSingleRecordBytes = 11)
            throw AssertionError("Single-record budget must fit inside scope budget")
        } catch (expected: IllegalArgumentException) {
            assertEquals("A single record cannot exceed its tier's scope budget", expected.message)
        }
    }
}
