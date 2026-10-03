package com.cyclone.mobile.brain.memory.tiered

import com.cyclone.mobile.brain.memory.api.MemoryRecord
import com.cyclone.mobile.brain.memory.api.MemorySourceKind
import com.cyclone.mobile.brain.memory.api.MemoryVerificationState

class TieredMemoryRanker(
    private val freshness: TieredFreshnessPolicy,
) {
    fun surfaceStaleness(record: MemoryRecord, nowEpochMillis: Long): MemoryRecord {
        if (record.verificationState == MemoryVerificationState.STALE) return record
        val age = (nowEpochMillis - record.provenance.observedAtEpochMillis).coerceAtLeast(0)
        return if (age > freshness.maxAge(record.memoryClass)) {
            record.copy(verificationState = MemoryVerificationState.STALE)
        } else {
            record
        }
    }

    /**
     * Fresh user/current-observation evidence sorts before historical memory. Explicitly stale
     * knowledge always sorts last, regardless of its old confidence score.
     */
    fun recallOrder(nowEpochMillis: Long): Comparator<MemoryRecord> =
        compareByDescending<MemoryRecord> { precedence(surfaceStaleness(it, nowEpochMillis)) }
            .thenByDescending { it.provenance.observedAtEpochMillis }
            .thenByDescending { it.updatedAtEpochMillis }
            .thenByDescending { it.confidence }
            .thenBy { it.recordId }

    fun evictionOrder(nowEpochMillis: Long): Comparator<MemoryRecord> = recallOrder(nowEpochMillis)

    private fun precedence(record: MemoryRecord): Int = when {
        record.verificationState == MemoryVerificationState.STALE -> 0
        record.source.sourceKind == MemorySourceKind.USER -> 5
        record.memoryClass == com.cyclone.mobile.brain.memory.api.MemoryClass.RUNTIME_HINT &&
            record.verificationState in setOf(
                MemoryVerificationState.OBSERVED,
                MemoryVerificationState.VERIFIED,
            ) -> 4

        record.verificationState == MemoryVerificationState.VERIFIED -> 3
        record.verificationState == MemoryVerificationState.OBSERVED -> 2
        else -> 1
    }
}
