package com.cyclone.mobile.brain.memory.api

enum class MemoryMutationKind {
    CREATE,
    REPLACE,
    REMOVE,
    ARCHIVE,
}

enum class MemoryPolicyDecision {
    ALLOW,
    REQUIRE_APPROVAL,
    DENY,
}

data class MemoryPolicyRequest(
    val mutationId: String,
    val mutationKind: MemoryMutationKind,
    val actor: MemoryActor,
    val scope: MemoryScope,
    val memoryClass: MemoryClass,
    val sensitivity: MemorySensitivity,
    val recordId: String,
    val schemaVersion: Int,
    val safeContentBytes: Int,
    val redactedFieldCount: Int,
)

data class MemoryPolicyResult(
    val decision: MemoryPolicyDecision,
    val reasonCode: String,
) {
    init {
        require(reasonCode.matches(Regex("[A-Z][A-Z0-9_]*"))) { "Memory policy reason must be a stable code" }
    }
}

fun interface MemoryWritePolicyGate {
    /** Receives metadata only, never raw memory content. */
    fun evaluate(request: MemoryPolicyRequest): MemoryPolicyResult
}

data class MemoryMutationApproval(
    val mutationId: String,
    val approvedBy: MemoryActor,
    val approvedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(mutationId.isNotBlank()) { "Approval mutation id must not be blank" }
        require(approvedAtEpochMillis >= 0 && expiresAtEpochMillis > approvedAtEpochMillis) {
            "Memory approval lifetime is invalid"
        }
    }
}

fun interface MemoryApprovalVerifier {
    fun isValid(approval: MemoryMutationApproval, policyRequest: MemoryPolicyRequest): Boolean
}
