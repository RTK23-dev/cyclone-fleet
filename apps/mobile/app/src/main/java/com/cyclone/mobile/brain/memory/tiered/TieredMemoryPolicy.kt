package com.cyclone.mobile.brain.memory.tiered

import com.cyclone.mobile.brain.memory.api.MemoryClass
import com.cyclone.mobile.brain.memory.api.MemoryRecord

enum class MemoryTier {
    MISSION_HOT,
    KNOWLEDGE_DOCUMENTS,
    STRUCTURAL_DURABLE,
}

object DeterministicTierSelector {
    fun select(memoryClass: MemoryClass): MemoryTier = when (memoryClass) {
        MemoryClass.RUNTIME_HINT -> MemoryTier.MISSION_HOT
        MemoryClass.DOCUMENT_REFERENCE -> MemoryTier.KNOWLEDGE_DOCUMENTS
        MemoryClass.STRUCTURAL_KNOWLEDGE -> MemoryTier.STRUCTURAL_DURABLE
    }

    fun select(record: MemoryRecord): MemoryTier = select(record.memoryClass)
}

data class TierBudget(
    val maxRecordsPerScope: Int,
    val maxContentBytesPerScope: Int,
    val maxSingleRecordBytes: Int,
) {
    init {
        require(maxRecordsPerScope >= 1) { "Tier record budget must be positive" }
        require(maxContentBytesPerScope >= 1) { "Tier byte budget must be positive" }
        require(maxSingleRecordBytes >= 1) { "Tier record byte budget must be positive" }
        require(maxSingleRecordBytes <= maxContentBytesPerScope) {
            "A single record cannot exceed its tier's scope budget"
        }
    }
}

data class TieredMemoryBudgets(
    val missionHot: TierBudget = TierBudget(
        maxRecordsPerScope = 12,
        maxContentBytesPerScope = 8 * 1024,
        maxSingleRecordBytes = 2 * 1024,
    ),
    val knowledgeDocuments: TierBudget = TierBudget(
        maxRecordsPerScope = 200,
        maxContentBytesPerScope = 4 * 1024 * 1024,
        maxSingleRecordBytes = 32 * 1024,
    ),
    val structuralDurable: TierBudget = TierBudget(
        maxRecordsPerScope = 2_000,
        maxContentBytesPerScope = 8 * 1024 * 1024,
        maxSingleRecordBytes = 16 * 1024,
    ),
) {
    fun forTier(tier: MemoryTier): TierBudget = when (tier) {
        MemoryTier.MISSION_HOT -> missionHot
        MemoryTier.KNOWLEDGE_DOCUMENTS -> knowledgeDocuments
        MemoryTier.STRUCTURAL_DURABLE -> structuralDurable
    }
}

data class TieredFreshnessPolicy(
    val runtimeHintMaxAgeMillis: Long = 15 * 60 * 1_000L,
    val documentMaxAgeMillis: Long = 90L * 24 * 60 * 60 * 1_000L,
    val structuralMaxAgeMillis: Long = 30L * 24 * 60 * 60 * 1_000L,
) {
    init {
        require(runtimeHintMaxAgeMillis >= 1) { "Runtime hint freshness must be positive" }
        require(documentMaxAgeMillis >= 1) { "Document freshness must be positive" }
        require(structuralMaxAgeMillis >= 1) { "Structural freshness must be positive" }
    }

    fun maxAge(memoryClass: MemoryClass): Long = when (memoryClass) {
        MemoryClass.RUNTIME_HINT -> runtimeHintMaxAgeMillis
        MemoryClass.DOCUMENT_REFERENCE -> documentMaxAgeMillis
        MemoryClass.STRUCTURAL_KNOWLEDGE -> structuralMaxAgeMillis
    }
}
