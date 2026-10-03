package com.cyclone.mobile.automation.run

import com.cyclone.mobile.automation.capsule.CapsuleSnapshot
import com.cyclone.mobile.automation.capsule.CycloneRoutineCapsule
import com.cyclone.mobile.automation.capsule.RecoveryPrimitive
import com.cyclone.mobile.automation.capsule.RoutineStepId

/**
 * Durable run-state coordinator only. It never invokes a phone tool, policy engine, Accessibility,
 * AI provider, shell, script, or network operation.
 */
class RoutineRunController(private val store: RoutineRunStore) {
    fun create(
        runId: RoutineRunId,
        capsule: CycloneRoutineCapsule,
        startedAtEpochMillis: Long,
    ): RoutineRunCommandResult {
        require(startedAtEpochMillis >= 0)
        if (store.load(runId) != null) return RoutineRunCommandResult.Rejected("Run id already exists")
        val snapshot = CapsuleSnapshot.capture(capsule)
        val run = RoutineRunRecord(
            runId = runId,
            capsuleSnapshot = snapshot,
            startedAtEpochMillis = startedAtEpochMillis,
            updatedAtEpochMillis = startedAtEpochMillis,
            steps = snapshot.capsule.graph.steps.sortedBy { it.id }.map { RoutineStepProgress(it.id) },
        )
        store.save(run)
        return RoutineRunCommandResult.Applied(run)
    }

    fun start(runId: RoutineRunId, atEpochMillis: Long) = transition(runId, RoutineRunStatus.RUNNING, atEpochMillis)
    fun pause(runId: RoutineRunId, atEpochMillis: Long) = transition(runId, RoutineRunStatus.PAUSED, atEpochMillis)
    fun resume(runId: RoutineRunId, atEpochMillis: Long) = transition(runId, RoutineRunStatus.RUNNING, atEpochMillis)
    fun waitForUser(runId: RoutineRunId, atEpochMillis: Long) =
        transition(runId, RoutineRunStatus.WAITING_FOR_USER, atEpochMillis)
    fun stop(runId: RoutineRunId, atEpochMillis: Long) = transition(runId, RoutineRunStatus.STOPPED, atEpochMillis)

    fun fail(runId: RoutineRunId, failureCode: String, atEpochMillis: Long): RoutineRunCommandResult =
        mutate(runId, atEpochMillis) { run ->
            if (!canTransition(run.status, RoutineRunStatus.FAILED)) return@mutate rejected(run.status, RoutineRunStatus.FAILED)
            run.copy(
                status = RoutineRunStatus.FAILED,
                updatedAtEpochMillis = atEpochMillis,
                endedAtEpochMillis = atEpochMillis,
                failureCode = failureCode,
            )
        }

    fun complete(
        runId: RoutineRunId,
        completionEvidenceIds: List<String>,
        atEpochMillis: Long,
    ): RoutineRunCommandResult = mutate(runId, atEpochMillis) { run ->
        if (!canTransition(run.status, RoutineRunStatus.COMPLETED)) {
            return@mutate rejected(run.status, RoutineRunStatus.COMPLETED)
        }
        if (completionEvidenceIds.isEmpty()) return@mutate RoutineRunCommandResult.Rejected("Completion evidence is required")
        run.copy(
            status = RoutineRunStatus.COMPLETED,
            updatedAtEpochMillis = atEpochMillis,
            endedAtEpochMillis = atEpochMillis,
            completionEvidenceIds = completionEvidenceIds.distinct().sorted(),
        )
    }

    fun beginStep(
        runId: RoutineRunId,
        stepId: RoutineStepId,
        atEpochMillis: Long,
    ): RoutineRunCommandResult = mutate(runId, atEpochMillis) { run ->
        if (run.status != RoutineRunStatus.RUNNING) return@mutate RoutineRunCommandResult.Rejected("Run is not active")
        updateStep(run, stepId, atEpochMillis) { step ->
            if (step.status !in setOf(RoutineStepStatus.PENDING, RoutineStepStatus.WAITING)) {
                return@updateStep RoutineRunCommandResult.Rejected("Step cannot start from ${step.status}")
            }
            step.copy(
                status = RoutineStepStatus.RUNNING,
                attempts = step.attempts + 1,
                startedAtEpochMillis = step.startedAtEpochMillis ?: atEpochMillis,
            )
        }
    }

    fun finishStep(
        runId: RoutineRunId,
        stepId: RoutineStepId,
        succeeded: Boolean,
        atEpochMillis: Long,
    ): RoutineRunCommandResult = mutate(runId, atEpochMillis) { run ->
        updateStep(run, stepId, atEpochMillis) { step ->
            if (step.status != RoutineStepStatus.RUNNING) {
                return@updateStep RoutineRunCommandResult.Rejected("Step is not running")
            }
            step.copy(
                status = if (succeeded) RoutineStepStatus.SUCCEEDED else RoutineStepStatus.FAILED,
                endedAtEpochMillis = atEpochMillis,
            )
        }
    }

    fun recordObservation(
        runId: RoutineRunId,
        stepId: RoutineStepId,
        observation: RoutineObservationReference,
    ): RoutineRunCommandResult = mutate(runId, observation.observedAtEpochMillis) { run ->
        updateStep(run, stepId, observation.observedAtEpochMillis) { step ->
            step.copy(observations = step.observations + observation)
        }
    }

    fun recordAction(
        runId: RoutineRunId,
        stepId: RoutineStepId,
        action: RoutineActionRecord,
    ): RoutineRunCommandResult = mutate(runId, action.recordedAtEpochMillis) { run ->
        if (run.status != RoutineRunStatus.RUNNING) {
            return@mutate RoutineRunCommandResult.Rejected("Actions can be recorded only for a running run")
        }
        val declared = run.capsuleSnapshot.capsule.graph.steps.singleOrNull { it.id == stepId }?.action
            ?: return@mutate RoutineRunCommandResult.Rejected("Step does not declare an action proposal")
        if (declared != action.proposal) {
            return@mutate RoutineRunCommandResult.Rejected("Action differs from the frozen capsule snapshot")
        }
        updateStep(run, stepId, action.recordedAtEpochMillis) { step ->
            if (step.status != RoutineStepStatus.RUNNING) {
                return@updateStep RoutineRunCommandResult.Rejected("Actions can be recorded only for a running step")
            }
            step.copy(actions = step.actions + action)
        }
    }

    fun recordVerification(
        runId: RoutineRunId,
        stepId: RoutineStepId,
        verification: RoutineVerificationRecord,
    ): RoutineRunCommandResult = mutate(runId, verification.verifiedAtEpochMillis) { run ->
        val capsuleStep = run.capsuleSnapshot.capsule.graph.steps.singleOrNull { it.id == stepId }
            ?: return@mutate RoutineRunCommandResult.Rejected("Unknown step")
        if (verification.verificationId !in capsuleStep.verificationIds) {
            return@mutate RoutineRunCommandResult.Rejected("Verification is not declared by the frozen step")
        }
        updateStep(run, stepId, verification.verifiedAtEpochMillis) { step ->
            step.copy(verifications = step.verifications + verification)
        }
    }

    fun recordRecovery(
        runId: RoutineRunId,
        attempt: RoutineRecoveryAttempt,
    ): RoutineRunCommandResult = mutate(runId, attempt.attemptedAtEpochMillis) { run ->
        if (run.status !in setOf(RoutineRunStatus.RUNNING, RoutineRunStatus.RECOVERING)) {
            return@mutate RoutineRunCommandResult.Rejected("Recovery requires an active or recovering run")
        }
        val capsuleStep = run.capsuleSnapshot.capsule.graph.steps.singleOrNull { it.id == attempt.stepId }
            ?: return@mutate RoutineRunCommandResult.Rejected("Unknown recovery step")
        val plan = capsuleStep.recovery
        val expectedOrdinal = run.recoveryAttempts.count { it.stepId == attempt.stepId } + 1
        if (attempt.ordinal != expectedOrdinal) return@mutate RoutineRunCommandResult.Rejected("Recovery ordinal is not next")
        if (attempt.ordinal > plan.maximumAttempts) return@mutate RoutineRunCommandResult.Rejected("Recovery bound exhausted")
        val expectedPrimitive = plan.sequence[(attempt.ordinal - 1) % plan.sequence.size]
        if (attempt.primitive != expectedPrimitive) {
            return@mutate RoutineRunCommandResult.Rejected("Recovery primitive differs from the deterministic plan")
        }
        val nextStatus = if (
            attempt.primitive == RecoveryPrimitive.HUMAN_TAKEOVER ||
            attempt.outcome == RecoveryAttemptOutcome.WAITING_FOR_USER
        ) RoutineRunStatus.WAITING_FOR_USER else RoutineRunStatus.RECOVERING
        val stepResult = updateStep(run, attempt.stepId, attempt.attemptedAtEpochMillis) { step ->
            step.copy(
                status = if (nextStatus == RoutineRunStatus.WAITING_FOR_USER) RoutineStepStatus.WAITING else step.status,
                recoveryAttempts = step.recoveryAttempts + attempt,
            )
        }
        if (stepResult !is RoutineRunRecord) return@mutate stepResult
        stepResult.copy(
            status = nextStatus,
            recoveryAttempts = stepResult.recoveryAttempts + attempt,
            updatedAtEpochMillis = attempt.attemptedAtEpochMillis,
        )
    }

    fun addArtifact(runId: RoutineRunId, artifact: RoutineArtifactReference): RoutineRunCommandResult =
        mutate(runId, artifact.createdAtEpochMillis) { run ->
            if (run.artifacts.any { it.artifactId == artifact.artifactId }) {
                return@mutate RoutineRunCommandResult.Rejected("Artifact id already exists")
            }
            run.copy(artifacts = (run.artifacts + artifact).sortedBy { it.artifactId })
        }

    fun load(runId: RoutineRunId): RoutineRunRecord? = store.load(runId)

    private fun transition(
        runId: RoutineRunId,
        target: RoutineRunStatus,
        atEpochMillis: Long,
    ): RoutineRunCommandResult = mutate(runId, atEpochMillis) { run ->
        if (!canTransition(run.status, target)) return@mutate rejected(run.status, target)
        run.copy(
            status = target,
            updatedAtEpochMillis = atEpochMillis,
            endedAtEpochMillis = if (target in TERMINAL) atEpochMillis else null,
        )
    }

    private fun mutate(
        runId: RoutineRunId,
        atEpochMillis: Long,
        block: (RoutineRunRecord) -> Any,
    ): RoutineRunCommandResult {
        val run = store.load(runId) ?: return RoutineRunCommandResult.Missing(runId)
        if (atEpochMillis < run.updatedAtEpochMillis) return RoutineRunCommandResult.Rejected("Timestamp is older than run state")
        return when (val result = block(run)) {
            is RoutineRunRecord -> {
                store.save(result.copy(updatedAtEpochMillis = atEpochMillis))
                RoutineRunCommandResult.Applied(result.copy(updatedAtEpochMillis = atEpochMillis))
            }
            is RoutineRunCommandResult.Rejected -> result
            else -> error("Invalid routine mutation result")
        }
    }

    private fun updateStep(
        run: RoutineRunRecord,
        stepId: RoutineStepId,
        atEpochMillis: Long,
        block: (RoutineStepProgress) -> Any,
    ): Any {
        val step = run.steps.singleOrNull { it.stepId == stepId }
            ?: return RoutineRunCommandResult.Rejected("Unknown step")
        return when (val updated = block(step)) {
            is RoutineStepProgress -> run.copy(
                steps = run.steps.map { if (it.stepId == stepId) updated else it },
                updatedAtEpochMillis = atEpochMillis,
            )
            is RoutineRunCommandResult.Rejected -> updated
            else -> error("Invalid step mutation result")
        }
    }

    private fun rejected(from: RoutineRunStatus, to: RoutineRunStatus) =
        RoutineRunCommandResult.Rejected("Invalid run transition $from -> $to")

    private fun canTransition(from: RoutineRunStatus, to: RoutineRunStatus): Boolean = to in ALLOWED.getValue(from)

    companion object {
        private val TERMINAL = setOf(RoutineRunStatus.COMPLETED, RoutineRunStatus.FAILED, RoutineRunStatus.STOPPED)
        private val ALLOWED = mapOf(
            RoutineRunStatus.QUEUED to setOf(RoutineRunStatus.RUNNING, RoutineRunStatus.STOPPED),
            RoutineRunStatus.RUNNING to setOf(
                RoutineRunStatus.PAUSED,
                RoutineRunStatus.WAITING_FOR_USER,
                RoutineRunStatus.RECOVERING,
                RoutineRunStatus.COMPLETED,
                RoutineRunStatus.FAILED,
                RoutineRunStatus.STOPPED,
            ),
            RoutineRunStatus.PAUSED to setOf(RoutineRunStatus.RUNNING, RoutineRunStatus.STOPPED),
            RoutineRunStatus.WAITING_FOR_USER to setOf(
                RoutineRunStatus.RUNNING,
                RoutineRunStatus.FAILED,
                RoutineRunStatus.STOPPED,
            ),
            RoutineRunStatus.RECOVERING to setOf(
                RoutineRunStatus.RUNNING,
                RoutineRunStatus.WAITING_FOR_USER,
                RoutineRunStatus.FAILED,
                RoutineRunStatus.STOPPED,
            ),
            RoutineRunStatus.COMPLETED to emptySet(),
            RoutineRunStatus.FAILED to emptySet(),
            RoutineRunStatus.STOPPED to emptySet(),
        )
    }
}
