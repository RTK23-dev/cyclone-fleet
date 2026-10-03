package com.cyclone.mobile.runtime.recovery

import com.cyclone.mobile.platform.module.ModuleId

/**
 * Pure recovery authority over durable state. External adapters execute returned commands; this
 * manager never edits slots, controls modules, starts launchers, erases data, or invokes the phone.
 */
class RecoveryManager(
    private val store: RecoveryStateStore,
    private val criteria: RecoveryHealthCriteria = RecoveryHealthCriteria(),
) : RecoveryActivationHandoffSink {
    fun state(): RecoveryPersistentState = store.load().normalized()

    fun initializeKnownGood(snapshot: RecoverySnapshot, atEpochMillis: Long): RecoveryDecision {
        require(atEpochMillis >= snapshot.capturedAtEpochMillis)
        val current = state()
        current.lastKnownGood?.let { existing ->
            return if (existing.normalized() == snapshot.normalized()) {
                RecoveryDecision.NoChange("known-good-already-initialized")
            } else {
                RecoveryDecision.Rejected(RecoveryRejection.REQUEST_CONFLICT)
            }
        }
        val next = append(
            current.copy(lastKnownGood = snapshot.normalized(), activeRuntime = snapshot.runtime.copy()),
            RecoveryJournalEvent.KNOWN_GOOD_INITIALIZED,
            atEpochMillis,
            updateId = snapshot.lastUpdateId,
            runtimeId = snapshot.runtime.runtimeId,
        )
        return saveChanged(next)
    }

    override fun requestActivation(request: RecoveryActivationHandoff): RecoveryActivationDecision {
        val current = state()
        if (current.pendingCommand != null) return RecoveryActivationDecision.Rejected(RecoveryRejection.COMMAND_PENDING)
        current.candidate?.let { candidate ->
            return if (
                candidate.requestId == request.requestId &&
                candidate.updateId == request.updateId &&
                candidate.previousKnownGood == request.activeKnownGood.normalized() &&
                candidate.candidate == request.candidate.normalized()
            ) RecoveryActivationDecision.Accepted
            else RecoveryActivationDecision.Rejected(RecoveryRejection.REQUEST_CONFLICT)
        }
        val knownGood = current.lastKnownGood
            ?: return RecoveryActivationDecision.Rejected(RecoveryRejection.NO_KNOWN_GOOD_STATE)
        if (current.activeRuntime != knownGood.runtime || knownGood != request.activeKnownGood.normalized()) {
            return RecoveryActivationDecision.Rejected(RecoveryRejection.ACTIVE_RUNTIME_MISMATCH)
        }
        val candidateModules = request.candidate.modules.associateBy { it.moduleId }
        val omitsEssential = knownGood.modules
            .filter { it.enabled && it.essential }
            .any { required -> candidateModules[required.moduleId]?.let { it.enabled && it.essential } != true }
        if (omitsEssential) {
            return RecoveryActivationDecision.Rejected(RecoveryRejection.CANDIDATE_OMITS_ESSENTIAL_MODULE)
        }
        if (request.requestedAtEpochMillis < latestTimestamp(current)) {
            return RecoveryActivationDecision.Rejected(RecoveryRejection.STALE_TIMESTAMP)
        }
        val candidate = CandidateRecoveryState(
            requestId = request.requestId,
            updateId = request.updateId,
            previousKnownGood = request.activeKnownGood.normalized(),
            candidate = request.candidate.normalized(),
            requestedAtEpochMillis = request.requestedAtEpochMillis,
        )
        val next = append(
            current.copy(candidate = candidate, activeRuntime = candidate.candidate.runtime),
            RecoveryJournalEvent.ACTIVATION_ACCEPTED,
            request.requestedAtEpochMillis,
            updateId = request.updateId,
            runtimeId = request.candidate.runtime.runtimeId,
        )
        store.save(next.normalized())
        return RecoveryActivationDecision.Accepted
    }

    fun recordCandidateBootAttempt(runtime: RuntimeIdentity, atEpochMillis: Long): RecoveryDecision {
        val current = state()
        if (current.pendingCommand != null) return RecoveryDecision.Rejected(RecoveryRejection.COMMAND_PENDING)
        val candidate = current.candidate ?: return RecoveryDecision.Rejected(RecoveryRejection.NO_ACTIVE_CANDIDATE)
        if (candidate.candidate.runtime != runtime) return RecoveryDecision.Rejected(RecoveryRejection.OBSERVATION_MISMATCH)
        if (atEpochMillis < latestTimestamp(current)) return RecoveryDecision.Rejected(RecoveryRejection.STALE_TIMESTAMP)
        val updatedCandidate = candidate.copy(bootAttempts = candidate.bootAttempts + 1)
        var next = append(
            current.copy(candidate = updatedCandidate),
            RecoveryJournalEvent.BOOT_ATTEMPT_RECORDED,
            atEpochMillis,
            updateId = candidate.updateId,
            runtimeId = runtime.runtimeId,
        )
        if (updatedCandidate.bootAttempts >= criteria.maximumCandidateBootAttempts) {
            next = next.copy(lastCrashAttribution = attribution(updatedCandidate, RecoveryFailureReason.CANDIDATE_BOOT_LIMIT, atEpochMillis))
            return issueRollback(next, RecoveryFailureReason.CANDIDATE_BOOT_LIMIT, atEpochMillis)
        }
        return saveChanged(next)
    }

    fun observeCandidateHealth(observation: RecoveryHealthObservation): RecoveryDecision {
        val current = state()
        if (current.pendingCommand != null) return RecoveryDecision.Rejected(RecoveryRejection.COMMAND_PENDING)
        val candidate = current.candidate ?: return RecoveryDecision.Rejected(RecoveryRejection.NO_ACTIVE_CANDIDATE)
        if (observation.updateId != candidate.updateId || observation.runtime != candidate.candidate.runtime) {
            return RecoveryDecision.Rejected(RecoveryRejection.OBSERVATION_MISMATCH)
        }
        if (candidate.lastObservationId == observation.observationId) {
            return RecoveryDecision.NoChange("duplicate-observation")
        }
        if (
            observation.observedAtEpochMillis < latestTimestamp(current) ||
            candidate.lastObservationAtEpochMillis?.let { observation.observedAtEpochMillis <= it } == true
        ) return RecoveryDecision.Rejected(RecoveryRejection.STALE_TIMESTAMP)

        val normalized = observation.normalized()
        val observedCandidate = candidate.copy(
            lastObservationId = normalized.observationId,
            lastObservationAtEpochMillis = normalized.observedAtEpochMillis,
        )
        var next = append(
            current.copy(candidate = observedCandidate),
            RecoveryJournalEvent.HEALTH_OBSERVED,
            normalized.observedAtEpochMillis,
            updateId = candidate.updateId,
            runtimeId = normalized.runtime.runtimeId,
        )

        if (!normalized.schemasReadable) {
            next = next.copy(lastCrashAttribution = attribution(observedCandidate, RecoveryFailureReason.SCHEMA_UNREADABLE, normalized.observedAtEpochMillis))
            return issueRollback(next, RecoveryFailureReason.SCHEMA_UNREADABLE, normalized.observedAtEpochMillis)
        }
        if (normalized.trustedCore.keys != SafeModePlan.REQUIRED_TRUSTED_CORE) {
            next = next.copy(lastCrashAttribution = attribution(observedCandidate, RecoveryFailureReason.TRUSTED_CORE_UNHEALTHY, normalized.observedAtEpochMillis))
            return issueRollback(next, RecoveryFailureReason.TRUSTED_CORE_UNHEALTHY, normalized.observedAtEpochMillis)
        }
        if (normalized.trustedCore.values.any { it == ObservedHealth.FAILED || it == ObservedHealth.UNAVAILABLE }) {
            next = next.copy(lastCrashAttribution = attribution(observedCandidate, RecoveryFailureReason.TRUSTED_CORE_UNHEALTHY, normalized.observedAtEpochMillis))
            return issueRollback(next, RecoveryFailureReason.TRUSTED_CORE_UNHEALTHY, normalized.observedAtEpochMillis)
        }
        val declaredModules = candidate.candidate.modules.filter { it.enabled }.associateBy { it.moduleId }
        if (normalized.modules.any { it.moduleId !in declaredModules }) {
            return RecoveryDecision.Rejected(RecoveryRejection.OBSERVATION_MISMATCH)
        }
        val healthByModule = normalized.modules.associateBy { it.moduleId }
        val requiredBroken = declaredModules.values
            .filter { it.essential }
            .firstOrNull { healthByModule[it.moduleId]?.health != ObservedHealth.HEALTHY }
        if (requiredBroken != null) {
            next = next.copy(lastCrashAttribution = attribution(observedCandidate, RecoveryFailureReason.REQUIRED_MODULE_UNHEALTHY, normalized.observedAtEpochMillis))
            return issueRollback(next, RecoveryFailureReason.REQUIRED_MODULE_UNHEALTHY, normalized.observedAtEpochMillis)
        }
        val optionalBroken = declaredModules.values
            .filter { !it.essential && it.moduleId !in current.quarantinedModules }
            .filter {
                healthByModule[it.moduleId]?.health in setOf(null, ObservedHealth.FAILED, ObservedHealth.UNAVAILABLE)
            }
            .minByOrNull { it.moduleId }
        if (optionalBroken != null) {
            return issueQuarantine(next, optionalBroken.moduleId, normalized.observedAtEpochMillis)
        }

        val fullyHealthy = normalized.runtimeStable &&
            normalized.trustedCore.values.all { it == ObservedHealth.HEALTHY } &&
            declaredModules.values.all {
                it.moduleId in current.quarantinedModules || healthByModule[it.moduleId]?.health == ObservedHealth.HEALTHY
            }
        val progressed = if (fullyHealthy) {
            observedCandidate.copy(
                consecutiveHealthyObservations = observedCandidate.consecutiveHealthyObservations + 1,
                firstHealthyAtEpochMillis = observedCandidate.firstHealthyAtEpochMillis ?: normalized.observedAtEpochMillis,
            )
        } else {
            observedCandidate.copy(consecutiveHealthyObservations = 0, firstHealthyAtEpochMillis = null)
        }
        next = next.copy(candidate = progressed)
        val duration = progressed.firstHealthyAtEpochMillis?.let { normalized.observedAtEpochMillis - it } ?: 0
        if (
            fullyHealthy &&
            progressed.consecutiveHealthyObservations >= criteria.requiredConsecutiveHealthyObservations &&
            duration >= criteria.minimumHealthyDurationMillis
        ) return issuePromotion(next, normalized.observedAtEpochMillis)
        return saveChanged(next)
    }

    fun recordCrash(
        runtime: RuntimeIdentity,
        reason: RecoveryFailureReason,
        atEpochMillis: Long,
    ): RecoveryDecision {
        val current = state()
        if (current.pendingCommand != null) return RecoveryDecision.Rejected(RecoveryRejection.COMMAND_PENDING)
        if (runtime != current.activeRuntime) return RecoveryDecision.Rejected(RecoveryRejection.OBSERVATION_MISMATCH)
        if (atEpochMillis < latestTimestamp(current)) return RecoveryDecision.Rejected(RecoveryRejection.STALE_TIMESTAMP)
        val candidate = current.candidate
        if (candidate != null && candidate.candidate.runtime == runtime) {
            val crashed = candidate.copy(bootAttempts = candidate.bootAttempts + 1)
            val next = append(
                current.copy(
                    candidate = crashed,
                    consecutiveActiveCrashes = current.consecutiveActiveCrashes + 1,
                    lastCrashAttribution = attribution(crashed, reason, atEpochMillis),
                ),
                RecoveryJournalEvent.CRASH_RECORDED,
                atEpochMillis,
                updateId = crashed.updateId,
                runtimeId = runtime.runtimeId,
                reason = reason,
            )
            return issueRollback(next, reason, atEpochMillis)
        }
        val knownGood = current.lastKnownGood ?: return RecoveryDecision.Rejected(RecoveryRejection.NO_KNOWN_GOOD_STATE)
        val attempts = current.consecutiveActiveCrashes + 1
        val attribution = CrashAttribution(
            previousActiveRuntime = runtime,
            moduleSet = knownGood.modules,
            schemas = knownGood.schemas,
            lastUpdateId = knownGood.lastUpdateId,
            bootAttempts = attempts,
            safeFailureReason = reason,
            recordedAtEpochMillis = atEpochMillis,
        ).normalized()
        val next = append(
            current.copy(consecutiveActiveCrashes = attempts, lastCrashAttribution = attribution),
            RecoveryJournalEvent.CRASH_RECORDED,
            atEpochMillis,
            updateId = knownGood.lastUpdateId,
            runtimeId = runtime.runtimeId,
            reason = reason,
        )
        return if (attempts >= criteria.crashLoopThreshold) {
            issueSafeMode(next, RecoveryFailureReason.CRASH_LOOP, atEpochMillis)
        } else saveChanged(next)
    }

    fun recordStableActiveHealth(runtime: RuntimeIdentity, atEpochMillis: Long): RecoveryDecision {
        val current = state()
        if (runtime != current.activeRuntime || current.candidate != null) {
            return RecoveryDecision.Rejected(RecoveryRejection.OBSERVATION_MISMATCH)
        }
        if (atEpochMillis < latestTimestamp(current)) return RecoveryDecision.Rejected(RecoveryRejection.STALE_TIMESTAMP)
        if (current.consecutiveActiveCrashes == 0) return RecoveryDecision.NoChange("active-runtime-already-stable")
        return saveChanged(current.copy(consecutiveActiveCrashes = 0))
    }

    fun reportOptionalModuleFailure(moduleId: ModuleId, atEpochMillis: Long): RecoveryDecision {
        val current = state()
        if (current.pendingCommand != null) return RecoveryDecision.Rejected(RecoveryRejection.COMMAND_PENDING)
        val snapshot = current.candidate?.candidate ?: current.lastKnownGood
            ?: return RecoveryDecision.Rejected(RecoveryRejection.NO_KNOWN_GOOD_STATE)
        val module = snapshot.modules.singleOrNull { it.moduleId == moduleId && it.enabled }
            ?: return RecoveryDecision.Rejected(RecoveryRejection.OBSERVATION_MISMATCH)
        if (module.essential) return RecoveryDecision.Rejected(RecoveryRejection.OBSERVATION_MISMATCH)
        if (moduleId in current.quarantinedModules) return RecoveryDecision.NoChange("module-already-quarantined")
        if (atEpochMillis < latestTimestamp(current)) return RecoveryDecision.Rejected(RecoveryRejection.STALE_TIMESTAMP)
        return issueQuarantine(current, moduleId, atEpochMillis)
    }

    fun recordCommandResult(result: RecoveryCommandResult): RecoveryDecision {
        val current = state()
        val command = current.pendingCommand ?: return RecoveryDecision.Rejected(RecoveryRejection.RESULT_MISMATCH)
        if (command.commandId != result.commandId) return RecoveryDecision.Rejected(RecoveryRejection.RESULT_MISMATCH)
        if (result.completedAtEpochMillis < command.issuedAtEpochMillis) {
            return RecoveryDecision.Rejected(RecoveryRejection.STALE_TIMESTAMP)
        }
        var next = append(
            current.copy(pendingCommand = null),
            if (result.outcome == RecoveryCommandOutcome.SUCCEEDED) RecoveryJournalEvent.COMMAND_SUCCEEDED else RecoveryJournalEvent.COMMAND_FAILED,
            result.completedAtEpochMillis,
            commandId = command.commandId,
        )
        if (result.outcome == RecoveryCommandOutcome.FAILED) {
            return when (command) {
                is RecoveryCommand.PromoteCandidate -> issueRollback(next, RecoveryFailureReason.PROMOTION_FAILED, result.completedAtEpochMillis)
                is RecoveryCommand.QuarantineOptionalModule -> if (next.candidate != null) {
                    issueRollback(next, RecoveryFailureReason.QUARANTINE_FAILED, result.completedAtEpochMillis)
                } else {
                    issueSafeMode(next, RecoveryFailureReason.QUARANTINE_FAILED, result.completedAtEpochMillis)
                }
                is RecoveryCommand.RollbackRuntime -> issueSafeMode(next, RecoveryFailureReason.ROLLBACK_FAILED, result.completedAtEpochMillis)
                is RecoveryCommand.EnterSafeMode -> saveChanged(next)
            }
        }
        return when (command) {
            is RecoveryCommand.PromoteCandidate -> {
                val candidate = next.candidate ?: return RecoveryDecision.Rejected(RecoveryRejection.NO_ACTIVE_CANDIDATE)
                next = append(
                    next.copy(
                        lastKnownGood = candidate.candidate,
                        activeRuntime = candidate.candidate.runtime,
                        candidate = null,
                        consecutiveActiveCrashes = 0,
                        safeModePlan = null,
                    ),
                    RecoveryJournalEvent.CANDIDATE_PROMOTED,
                    result.completedAtEpochMillis,
                    updateId = candidate.updateId,
                    runtimeId = candidate.candidate.runtime.runtimeId,
                    commandId = command.commandId,
                )
                saveChanged(next)
            }
            is RecoveryCommand.RollbackRuntime -> {
                val knownGood = next.lastKnownGood ?: return RecoveryDecision.Rejected(RecoveryRejection.NO_KNOWN_GOOD_STATE)
                next = append(
                    next.copy(
                        activeRuntime = knownGood.runtime,
                        candidate = null,
                        consecutiveActiveCrashes = 0,
                    ),
                    RecoveryJournalEvent.RUNTIME_ROLLED_BACK,
                    result.completedAtEpochMillis,
                    updateId = knownGood.lastUpdateId,
                    runtimeId = knownGood.runtime.runtimeId,
                    commandId = command.commandId,
                )
                saveChanged(next)
            }
            is RecoveryCommand.QuarantineOptionalModule -> {
                next = append(
                    next.copy(
                        quarantinedModules = next.quarantinedModules + command.moduleId,
                        candidate = next.candidate?.copy(
                            consecutiveHealthyObservations = 0,
                            firstHealthyAtEpochMillis = null,
                        ),
                    ),
                    RecoveryJournalEvent.MODULE_QUARANTINED,
                    result.completedAtEpochMillis,
                    moduleId = command.moduleId,
                    commandId = command.commandId,
                )
                saveChanged(next)
            }
            is RecoveryCommand.EnterSafeMode -> {
                next = append(
                    next.copy(
                        candidate = null,
                        safeModePlan = command.plan,
                        quarantinedModules = next.quarantinedModules + command.plan.disabledOptionalModules,
                        consecutiveActiveCrashes = 0,
                    ),
                    RecoveryJournalEvent.SAFE_MODE_ENTERED,
                    result.completedAtEpochMillis,
                    runtimeId = next.activeRuntime?.runtimeId,
                    commandId = command.commandId,
                    reason = command.reason,
                )
                saveChanged(next)
            }
        }
    }

    private fun issuePromotion(state: RecoveryPersistentState, at: Long): RecoveryDecision {
        val candidate = requireNotNull(state.candidate)
        val command = RecoveryCommand.PromoteCandidate(
            commandId(state, "promote"),
            at,
            candidate.updateId,
            candidate.candidate.runtime,
        )
        return issue(state, command, candidate.updateId, candidate.candidate.runtime.runtimeId, null, null)
    }

    private fun issueRollback(state: RecoveryPersistentState, reason: RecoveryFailureReason, at: Long): RecoveryDecision {
        val knownGood = state.lastKnownGood ?: return RecoveryDecision.Rejected(RecoveryRejection.NO_KNOWN_GOOD_STATE)
        val failed = state.activeRuntime ?: state.candidate?.candidate?.runtime
            ?: return RecoveryDecision.Rejected(RecoveryRejection.NO_ACTIVE_CANDIDATE)
        val command = RecoveryCommand.RollbackRuntime(
            commandId(state, "rollback"),
            at,
            failed,
            knownGood.runtime,
            reason,
        )
        return issue(state, command, state.candidate?.updateId, failed.runtimeId, null, reason)
    }

    private fun issueQuarantine(state: RecoveryPersistentState, moduleId: ModuleId, at: Long): RecoveryDecision {
        val command = RecoveryCommand.QuarantineOptionalModule(
            commandId(state, "quarantine"),
            at,
            moduleId,
            RecoveryFailureReason.OPTIONAL_MODULE_UNHEALTHY,
        )
        return issue(state, command, state.candidate?.updateId, state.activeRuntime?.runtimeId, moduleId, command.reason)
    }

    private fun issueSafeMode(state: RecoveryPersistentState, reason: RecoveryFailureReason, at: Long): RecoveryDecision {
        val snapshot = state.lastKnownGood ?: return RecoveryDecision.Rejected(RecoveryRejection.NO_KNOWN_GOOD_STATE)
        val allModules = (snapshot.modules + state.candidate?.candidate?.modules.orEmpty())
            .associateBy { it.moduleId }
            .values
            .toList()
        val command = RecoveryCommand.EnterSafeMode(
            commandId(state, "safe-mode"),
            at,
            SafeModePlan.forSnapshot(snapshot.copy(modules = allModules)),
            reason,
        )
        return issue(state, command, state.candidate?.updateId, state.activeRuntime?.runtimeId, null, reason)
    }

    private fun issue(
        state: RecoveryPersistentState,
        command: RecoveryCommand,
        updateId: String?,
        runtimeId: String?,
        moduleId: ModuleId?,
        reason: RecoveryFailureReason?,
    ): RecoveryDecision {
        command.validate()
        check(state.pendingCommand == null)
        val next = append(
            state.copy(pendingCommand = command),
            RecoveryJournalEvent.COMMAND_ISSUED,
            command.issuedAtEpochMillis,
            updateId,
            runtimeId,
            moduleId,
            command.commandId,
            reason,
        )
        store.save(next.normalized())
        return RecoveryDecision.CommandRequired(command)
    }

    private fun attribution(candidate: CandidateRecoveryState, reason: RecoveryFailureReason, at: Long) = CrashAttribution(
        previousActiveRuntime = candidate.candidate.runtime,
        moduleSet = candidate.candidate.modules,
        schemas = candidate.candidate.schemas,
        lastUpdateId = candidate.updateId,
        bootAttempts = candidate.bootAttempts.coerceAtLeast(1),
        safeFailureReason = reason,
        recordedAtEpochMillis = at,
    ).normalized()

    private fun commandId(state: RecoveryPersistentState, kind: String) = "recovery-${state.nextSequence}-$kind"

    private fun append(
        state: RecoveryPersistentState,
        event: RecoveryJournalEvent,
        at: Long,
        updateId: String? = null,
        runtimeId: String? = null,
        moduleId: ModuleId? = null,
        commandId: String? = null,
        reason: RecoveryFailureReason? = null,
    ): RecoveryPersistentState {
        val entry = RecoveryJournalEntry(
            state.nextSequence,
            event,
            at,
            updateId,
            runtimeId,
            moduleId,
            commandId,
            reason,
        )
        return state.copy(
            journal = (state.journal + entry).takeLast(MAXIMUM_JOURNAL_ENTRIES),
            nextSequence = state.nextSequence + 1,
        )
    }

    private fun latestTimestamp(state: RecoveryPersistentState): Long = state.journal.lastOrNull()?.occurredAtEpochMillis ?: 0

    private fun saveChanged(state: RecoveryPersistentState): RecoveryDecision.StateChanged {
        val normalized = state.normalized()
        store.save(normalized)
        return RecoveryDecision.StateChanged(normalized)
    }

    companion object {
        const val MAXIMUM_JOURNAL_ENTRIES = 200
    }
}
