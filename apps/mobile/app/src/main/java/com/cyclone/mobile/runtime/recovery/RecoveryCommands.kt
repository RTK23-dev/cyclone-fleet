package com.cyclone.mobile.runtime.recovery

import com.cyclone.mobile.platform.module.ModuleId

sealed interface RecoveryCommand {
    val commandId: String
    val issuedAtEpochMillis: Long
    val preservesUserData: Boolean

    data class PromoteCandidate(
        override val commandId: String,
        override val issuedAtEpochMillis: Long,
        val updateId: String,
        val candidateRuntime: RuntimeIdentity,
        override val preservesUserData: Boolean = true,
    ) : RecoveryCommand

    data class RollbackRuntime(
        override val commandId: String,
        override val issuedAtEpochMillis: Long,
        val failedRuntime: RuntimeIdentity,
        val targetKnownGood: RuntimeIdentity,
        val reason: RecoveryFailureReason,
        override val preservesUserData: Boolean = true,
    ) : RecoveryCommand

    data class QuarantineOptionalModule(
        override val commandId: String,
        override val issuedAtEpochMillis: Long,
        val moduleId: ModuleId,
        val reason: RecoveryFailureReason,
        override val preservesUserData: Boolean = true,
    ) : RecoveryCommand

    data class EnterSafeMode(
        override val commandId: String,
        override val issuedAtEpochMillis: Long,
        val plan: SafeModePlan,
        val reason: RecoveryFailureReason,
        override val preservesUserData: Boolean = true,
    ) : RecoveryCommand
}

internal fun RecoveryCommand.validate() {
    requireRecoveryId(commandId, "Recovery command id")
    require(issuedAtEpochMillis >= 0)
    require(preservesUserData) { "Recovery commands must preserve user data" }
    when (this) {
        is RecoveryCommand.PromoteCandidate -> requireRecoveryId(updateId, "Update id")
        is RecoveryCommand.RollbackRuntime -> require(failedRuntime != targetKnownGood)
        is RecoveryCommand.QuarantineOptionalModule -> Unit
        is RecoveryCommand.EnterSafeMode -> require(plan.preserveUserData && !plan.allowsAutomaticDataErase)
    }
}

enum class RecoveryCommandOutcome { SUCCEEDED, FAILED }

data class RecoveryCommandResult(
    val commandId: String,
    val outcome: RecoveryCommandOutcome,
    val completedAtEpochMillis: Long,
) {
    init {
        requireRecoveryId(commandId, "Recovery command id")
        require(completedAtEpochMillis >= 0)
    }
}

enum class RecoveryJournalEvent {
    KNOWN_GOOD_INITIALIZED,
    ACTIVATION_ACCEPTED,
    BOOT_ATTEMPT_RECORDED,
    CRASH_RECORDED,
    HEALTH_OBSERVED,
    COMMAND_ISSUED,
    COMMAND_SUCCEEDED,
    COMMAND_FAILED,
    CANDIDATE_PROMOTED,
    RUNTIME_ROLLED_BACK,
    MODULE_QUARANTINED,
    SAFE_MODE_ENTERED,
    DUPLICATE_OBSERVATION_IGNORED,
}

data class RecoveryJournalEntry(
    val sequence: Long,
    val event: RecoveryJournalEvent,
    val occurredAtEpochMillis: Long,
    val updateId: String? = null,
    val runtimeId: String? = null,
    val moduleId: ModuleId? = null,
    val commandId: String? = null,
    val reason: RecoveryFailureReason? = null,
) {
    init {
        require(sequence >= 1)
        require(occurredAtEpochMillis >= 0)
        updateId?.let { requireRecoveryId(it, "Update id") }
        runtimeId?.let { require(Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*").matches(it)) }
        commandId?.let { requireRecoveryId(it, "Recovery command id") }
    }
}

data class RecoveryPersistentState(
    val schemaVersion: Int = 1,
    val lastKnownGood: RecoverySnapshot? = null,
    val activeRuntime: RuntimeIdentity? = null,
    val candidate: CandidateRecoveryState? = null,
    val pendingCommand: RecoveryCommand? = null,
    val quarantinedModules: Set<ModuleId> = emptySet(),
    val safeModePlan: SafeModePlan? = null,
    val consecutiveActiveCrashes: Int = 0,
    val lastCrashAttribution: CrashAttribution? = null,
    val journal: List<RecoveryJournalEntry> = emptyList(),
    val nextSequence: Long = 1,
) {
    init {
        require(schemaVersion == 1)
        require(consecutiveActiveCrashes >= 0)
        require(nextSequence >= 1)
        require(journal.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
        require(journal.lastOrNull()?.sequence?.let { nextSequence > it } != false)
        pendingCommand?.validate()
        require(safeModePlan == null || activeRuntime != null)
    }

    fun normalized(): RecoveryPersistentState = copy(
        lastKnownGood = lastKnownGood?.normalized(),
        activeRuntime = activeRuntime?.copy(),
        candidate = candidate?.copy(
            previousKnownGood = candidate.previousKnownGood.normalized(),
            candidate = candidate.candidate.normalized(),
        ),
        pendingCommand = pendingCommand?.normalized(),
        quarantinedModules = quarantinedModules.toSortedSet(),
        safeModePlan = safeModePlan?.normalized(),
        lastCrashAttribution = lastCrashAttribution?.normalized(),
        journal = journal.sortedBy { it.sequence }.map { it.copy() },
    )
}

internal fun RecoveryCommand.normalized(): RecoveryCommand = when (this) {
    is RecoveryCommand.PromoteCandidate -> copy(candidateRuntime = candidateRuntime.copy())
    is RecoveryCommand.RollbackRuntime -> copy(
        failedRuntime = failedRuntime.copy(),
        targetKnownGood = targetKnownGood.copy(),
    )
    is RecoveryCommand.QuarantineOptionalModule -> copy()
    is RecoveryCommand.EnterSafeMode -> copy(plan = plan.normalized())
}

sealed interface RecoveryDecision {
    data class CommandRequired(val command: RecoveryCommand) : RecoveryDecision
    data class StateChanged(val state: RecoveryPersistentState) : RecoveryDecision
    data class NoChange(val reason: String) : RecoveryDecision
    data class Rejected(val reason: RecoveryRejection) : RecoveryDecision
}
