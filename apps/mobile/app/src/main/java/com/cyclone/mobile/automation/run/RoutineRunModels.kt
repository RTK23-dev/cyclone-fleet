package com.cyclone.mobile.automation.run

import com.cyclone.mobile.automation.capsule.CapsuleSnapshot
import com.cyclone.mobile.automation.capsule.RecoveryPrimitive
import com.cyclone.mobile.automation.capsule.RoutineActionProposal
import com.cyclone.mobile.automation.capsule.RoutineStepId

private val RUN_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
private val EVIDENCE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,255}")
private val SHA_256 = Regex("[a-f0-9]{64}")

@JvmInline
value class RoutineRunId(val value: String) : Comparable<RoutineRunId> {
    init { require(RUN_ID_PATTERN.matches(value)) { "Invalid routine run id" } }
    override fun compareTo(other: RoutineRunId) = value.compareTo(other.value)
    override fun toString() = value
}

enum class RoutineRunStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    WAITING_FOR_USER,
    RECOVERING,
    COMPLETED,
    FAILED,
    STOPPED,
}

enum class RoutineStepStatus { PENDING, RUNNING, WAITING, SUCCEEDED, FAILED, SKIPPED }

data class RoutineObservationReference(
    val evidenceId: String,
    val observedAtEpochMillis: Long,
    val redacted: Boolean,
) {
    init {
        require(EVIDENCE_ID_PATTERN.matches(evidenceId))
        require(observedAtEpochMillis >= 0)
        require(redacted) { "Routine observation references must point to redacted evidence" }
    }
}

enum class RoutinePolicyOutcome { APPROVED, DENIED, ASK_USER }

data class RoutineActionRecord(
    val proposal: RoutineActionProposal,
    val policyOutcome: RoutinePolicyOutcome,
    val policyEvidenceId: String,
    val executionEvidenceId: String? = null,
    val verificationEvidenceId: String? = null,
    val recordedAtEpochMillis: Long,
) {
    init {
        require(EVIDENCE_ID_PATTERN.matches(policyEvidenceId))
        require(executionEvidenceId == null || EVIDENCE_ID_PATTERN.matches(executionEvidenceId))
        require(verificationEvidenceId == null || EVIDENCE_ID_PATTERN.matches(verificationEvidenceId))
        require(recordedAtEpochMillis >= 0)
        if (policyOutcome == RoutinePolicyOutcome.APPROVED) {
            require(executionEvidenceId != null) {
                "Approved actions must reference execution through the canonical typed action path"
            }
        } else {
            require(executionEvidenceId == null && verificationEvidenceId == null) {
                "Denied or pending policy decisions cannot claim execution"
            }
        }
    }
}

data class RoutineVerificationRecord(
    val verificationId: String,
    val passed: Boolean,
    val evidenceId: String,
    val verifiedAtEpochMillis: Long,
) {
    init {
        require(verificationId.isNotBlank())
        require(EVIDENCE_ID_PATTERN.matches(evidenceId))
        require(verifiedAtEpochMillis >= 0)
    }
}

enum class RecoveryAttemptOutcome { SUCCEEDED, FAILED, WAITING_FOR_USER }

data class RoutineRecoveryAttempt(
    val stepId: RoutineStepId,
    val ordinal: Int,
    val primitive: RecoveryPrimitive,
    val outcome: RecoveryAttemptOutcome,
    val evidenceId: String,
    val attemptedAtEpochMillis: Long,
) {
    init {
        require(ordinal >= 1)
        require(EVIDENCE_ID_PATTERN.matches(evidenceId))
        require(attemptedAtEpochMillis >= 0)
        require(
            primitive == RecoveryPrimitive.HUMAN_TAKEOVER || outcome != RecoveryAttemptOutcome.WAITING_FOR_USER,
        ) { "Only human takeover may wait for the user" }
    }
}

data class RoutineArtifactReference(
    val artifactId: String,
    val mediaType: String,
    val sha256: String,
    val createdAtEpochMillis: Long,
    val redacted: Boolean,
) {
    init {
        require(EVIDENCE_ID_PATTERN.matches(artifactId))
        require(mediaType.isNotBlank())
        require(SHA_256.matches(sha256))
        require(createdAtEpochMillis >= 0)
        require(redacted) { "Routine artifacts must be redacted before registration" }
    }
}

data class RoutineStepProgress(
    val stepId: RoutineStepId,
    val status: RoutineStepStatus = RoutineStepStatus.PENDING,
    val attempts: Int = 0,
    val startedAtEpochMillis: Long? = null,
    val endedAtEpochMillis: Long? = null,
    val observations: List<RoutineObservationReference> = emptyList(),
    val actions: List<RoutineActionRecord> = emptyList(),
    val verifications: List<RoutineVerificationRecord> = emptyList(),
    val recoveryAttempts: List<RoutineRecoveryAttempt> = emptyList(),
) {
    init {
        require(attempts >= 0)
        require(startedAtEpochMillis == null || startedAtEpochMillis >= 0)
        require(endedAtEpochMillis == null || endedAtEpochMillis >= 0)
    }
}

data class RoutineRunRecord(
    val runId: RoutineRunId,
    val capsuleSnapshot: CapsuleSnapshot,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val status: RoutineRunStatus = RoutineRunStatus.QUEUED,
    val steps: List<RoutineStepProgress>,
    val recoveryAttempts: List<RoutineRecoveryAttempt> = emptyList(),
    val artifacts: List<RoutineArtifactReference> = emptyList(),
    val completionEvidenceIds: List<String> = emptyList(),
    val failureCode: String? = null,
) {
    init {
        require(startedAtEpochMillis >= 0 && updatedAtEpochMillis >= startedAtEpochMillis)
        require(endedAtEpochMillis == null || endedAtEpochMillis >= startedAtEpochMillis)
        require(steps.map { it.stepId }.distinct().size == steps.size)
        require(completionEvidenceIds.all(EVIDENCE_ID_PATTERN::matches))
        require(completionEvidenceIds.distinct().size == completionEvidenceIds.size)
        require(failureCode == null || failureCode.matches(Regex("[A-Z][A-Z0-9_]{0,127}")))
        require(status != RoutineRunStatus.COMPLETED || completionEvidenceIds.isNotEmpty()) {
            "Completed runs require verifiable completion evidence"
        }
    }
}

sealed interface RoutineRunCommandResult {
    data class Applied(val run: RoutineRunRecord) : RoutineRunCommandResult
    data class Rejected(val reason: String) : RoutineRunCommandResult
    data class Missing(val runId: RoutineRunId) : RoutineRunCommandResult
}
