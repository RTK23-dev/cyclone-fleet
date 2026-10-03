package com.cyclone.mobile.brain.memory.audit

import com.cyclone.mobile.brain.memory.api.MemoryMutationKind
import com.cyclone.mobile.brain.memory.api.MemoryScope
import com.cyclone.mobile.brain.memory.api.MemorySourceKind

enum class MemoryAuditDecision {
    PROPOSED,
    APPROVAL_REQUIRED,
    DENIED,
    COMMITTED,
    DUPLICATE,
    REPLAY_REJECTED,
    FAILED,
}

/** Privacy-safe mutation evidence. Memory content and provenance values are never represented. */
data class MemoryAuditEntry(
    val sequence: Long,
    val timestampEpochMillis: Long,
    val mutationId: String,
    val mutationKind: MemoryMutationKind,
    val actorReference: String,
    val sourceKind: MemorySourceKind,
    val decision: MemoryAuditDecision,
    val reasonCode: String,
    val destinationProviderId: String,
    val scope: MemoryScope,
    val recordId: String,
    val recordVersion: Int?,
    val redactedFieldCount: Int,
) {
    init {
        require(sequence >= 1) { "Audit sequence must be positive" }
        require(timestampEpochMillis >= 0) { "Audit timestamp must be non-negative" }
        require(reasonCode.matches(Regex("[A-Z][A-Z0-9_]*"))) { "Audit reason must be a stable code" }
        require(redactedFieldCount >= 0) { "Redacted field count must be non-negative" }
    }
}

data class MemoryAuditQuery(
    val recordId: String? = null,
    val mutationId: String? = null,
    val decisions: Set<MemoryAuditDecision> = MemoryAuditDecision.entries.toSet(),
    val limit: Int = 100,
) {
    init {
        require(decisions.isNotEmpty()) { "Audit query needs at least one decision" }
        require(limit >= 1) { "Audit query limit must be positive" }
    }
}

interface MemoryAuditJournal {
    fun append(entry: MemoryAuditEntry)
    fun inspect(query: MemoryAuditQuery): List<MemoryAuditEntry>
}

/** Deterministic local fixture/journal; integration may replace it with a durable provider. */
class InMemoryMemoryAuditJournal : MemoryAuditJournal {
    private val entries = mutableListOf<MemoryAuditEntry>()

    @Synchronized
    override fun append(entry: MemoryAuditEntry) {
        require(entry.sequence == entries.size.toLong() + 1L) { "Audit sequence must be contiguous" }
        entries += entry
    }

    @Synchronized
    override fun inspect(query: MemoryAuditQuery): List<MemoryAuditEntry> = entries.asSequence()
        .filter { query.recordId == null || it.recordId == query.recordId }
        .filter { query.mutationId == null || it.mutationId == query.mutationId }
        .filter { it.decision in query.decisions }
        .sortedByDescending { it.sequence }
        .take(query.limit)
        .toList()
}
