package com.cyclone.mobile.brain.memory.api

import com.cyclone.mobile.brain.memory.audit.MemoryAuditEntry
import com.cyclone.mobile.brain.memory.audit.MemoryAuditQuery

enum class MemoryProposalStatus {
    READY,
    APPROVAL_REQUIRED,
    DENIED,
    DUPLICATE,
    INVALID,
    BUDGET_EXCEEDED,
}

data class MemoryWriteProposalRequest(
    val proposalId: String,
    val draft: MemoryDraft,
)

data class MemoryWriteProposalResult(
    val proposalId: String,
    val status: MemoryProposalStatus,
    val reasonCode: String,
    val duplicateRecord: MemoryRecord? = null,
    val expiresAtEpochMillis: Long? = null,
)

enum class MemoryMutationStatus {
    COMMITTED,
    DENIED,
    DUPLICATE,
    NOT_FOUND,
    STALE_VERSION,
    INVALID,
    BUDGET_EXCEEDED,
    REPLAYED,
    PROVIDER_FAILED,
}

data class MemoryMutationResult(
    val mutationId: String,
    val status: MemoryMutationStatus,
    val reasonCode: String,
    val record: MemoryRecord? = null,
)

data class MemoryReplaceRequest(
    val mutationId: String,
    val expectedVersion: Int,
    val draft: MemoryDraft,
) {
    init {
        require(expectedVersion >= 1) { "Expected memory version must be positive" }
    }
}

data class MemoryRecordMutationRequest(
    val mutationId: String,
    val reference: MemoryRecordRef,
    val expectedVersion: Int,
    val actor: MemoryActor,
) {
    init {
        require(expectedVersion >= 1) { "Expected memory version must be positive" }
    }
}

interface CycloneMemoryService {
    fun query(query: MemoryQuery): List<MemoryRecord>
    fun recall(request: MemoryRecallRequest): List<MemoryRecord>
    fun proposeWrite(request: MemoryWriteProposalRequest): MemoryWriteProposalResult
    fun commitApprovedWrite(
        proposalId: String,
        approval: MemoryMutationApproval? = null,
    ): MemoryMutationResult

    fun replace(
        request: MemoryReplaceRequest,
        approval: MemoryMutationApproval? = null,
    ): MemoryMutationResult

    fun remove(
        request: MemoryRecordMutationRequest,
        approval: MemoryMutationApproval? = null,
    ): MemoryMutationResult

    fun archive(
        request: MemoryRecordMutationRequest,
        approval: MemoryMutationApproval? = null,
    ): MemoryMutationResult

    fun inspectAudit(query: MemoryAuditQuery): List<MemoryAuditEntry>
}
