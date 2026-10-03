package com.cyclone.mobile.automation.v31

import com.cyclone.mobile.ai.v31.V31ActionBoundaryRequest
import com.cyclone.mobile.ai.v31.V31ActionIntentSource
import com.cyclone.mobile.ai.v31.V31ActionProposalBoundary
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.capsule.CycloneRoutineCapsule
import com.cyclone.mobile.automation.capsule.LegacyAutomationCapsuleAdapter
import com.cyclone.mobile.automation.capsule.RecoveryPrimitive
import com.cyclone.mobile.automation.capsule.RoutineMigrationResult
import com.cyclone.mobile.automation.capsule.RoutineStepId
import com.cyclone.mobile.automation.run.RecoveryAttemptOutcome
import com.cyclone.mobile.automation.run.RoutineActionRecord
import com.cyclone.mobile.automation.run.RoutineObservationReference
import com.cyclone.mobile.automation.run.RoutinePolicyOutcome
import com.cyclone.mobile.automation.run.RoutineRecoveryAttempt
import com.cyclone.mobile.automation.run.RoutineRunCommandResult
import com.cyclone.mobile.automation.run.RoutineRunController
import com.cyclone.mobile.automation.run.RoutineRunId
import com.cyclone.mobile.automation.run.RoutineRunRecord
import com.cyclone.mobile.automation.run.RoutineVerificationRecord

sealed interface V31AutomationPreparation {
    data class Ready(val capsule: CycloneRoutineCapsule, val warnings: List<String>) : V31AutomationPreparation
    data class Blocked(val reasons: List<String>) : V31AutomationPreparation
}

data class V31StepExecutionResult(
    val run: RoutineRunRecord?,
    val boundaryCalled: Boolean,
    val recovery: RecoveryPrimitive?,
    val reasonCode: String,
)

/**
 * RoutineRunController remains a durable coordinator, not an executor. Every action leaves this
 * bridge through the single injected V3 proposal boundary.
 */
class V31AutomationRunBridge(
    private val runs: RoutineRunController,
    private val actionBoundary: V31ActionProposalBoundary,
) {
    fun prepareLegacy(source: AutomationDefinition, atEpochMillis: Long, author: String): V31AutomationPreparation =
        when (val migrated = LegacyAutomationCapsuleAdapter.migrate(source, atEpochMillis, author)) {
            is RoutineMigrationResult.Blocked -> V31AutomationPreparation.Blocked(migrated.reasons)
            is RoutineMigrationResult.Ready -> V31AutomationPreparation.Ready(migrated.capsule, migrated.warnings)
        }

    fun createAndStart(
        runId: RoutineRunId,
        capsule: CycloneRoutineCapsule,
        atEpochMillis: Long,
    ): RoutineRunCommandResult {
        val created = runs.create(runId, capsule, atEpochMillis)
        return if (created is RoutineRunCommandResult.Applied) runs.start(runId, atEpochMillis) else created
    }

    fun executeStep(
        runId: RoutineRunId,
        stepId: RoutineStepId,
        goalReference: String,
        observationEvidenceId: String,
        atEpochMillis: Long,
    ): V31StepExecutionResult {
        val run = runs.load(runId) ?: return V31StepExecutionResult(null, false, null, "RUN_MISSING")
        val step = run.capsuleSnapshot.capsule.graph.steps.singleOrNull { it.id == stepId }
            ?: return V31StepExecutionResult(run, false, null, "STEP_MISSING")
        val proposal = step.action ?: return V31StepExecutionResult(run, false, null, "STEP_HAS_NO_ACTION")

        if (runs.beginStep(runId, stepId, atEpochMillis) !is RoutineRunCommandResult.Applied) {
            return V31StepExecutionResult(runs.load(runId), false, null, "STEP_NOT_RUNNABLE")
        }
        runs.recordObservation(
            runId,
            stepId,
            RoutineObservationReference(observationEvidenceId, atEpochMillis, redacted = true),
        )

        val boundary = actionBoundary.submit(
            V31ActionBoundaryRequest(
                proposal = proposal,
                decisionId = "routine.${runId.value}.${stepId.value}",
                goalReference = goalReference,
                source = V31ActionIntentSource.ROUTINE,
            ),
        )
        val actionRecord = RoutineActionRecord(
            proposal = proposal,
            policyOutcome = boundary.policyOutcome,
            policyEvidenceId = boundary.policyEvidenceId,
            executionEvidenceId = boundary.executionEvidenceId,
            verificationEvidenceId = boundary.verificationEvidenceId,
            recordedAtEpochMillis = atEpochMillis,
        )
        val actionRecorded = runs.recordAction(runId, stepId, actionRecord)
        if (actionRecorded !is RoutineRunCommandResult.Applied) {
            return V31StepExecutionResult(runs.load(runId), true, nextRecovery(runId, stepId), "ACTION_RECORD_REJECTED")
        }

        if (boundary.verificationPassed != null && step.verificationIds.isNotEmpty()) {
            runs.recordVerification(
                runId,
                stepId,
                RoutineVerificationRecord(
                    verificationId = step.verificationIds.first(),
                    passed = boundary.verificationPassed,
                    evidenceId = requireNotNull(boundary.verificationEvidenceId),
                    verifiedAtEpochMillis = atEpochMillis,
                ),
            )
        }
        val succeeded = boundary.policyOutcome == RoutinePolicyOutcome.APPROVED &&
            boundary.executionSucceeded && boundary.verificationPassed == true
        runs.finishStep(runId, stepId, succeeded, atEpochMillis)
        return V31StepExecutionResult(
            run = runs.load(runId),
            boundaryCalled = true,
            recovery = if (succeeded) null else nextRecovery(runId, stepId),
            reasonCode = if (succeeded) "VERIFIED" else "RECOVERY_REQUIRED",
        )
    }

    fun nextRecovery(runId: RoutineRunId, stepId: RoutineStepId): RecoveryPrimitive? {
        val run = runs.load(runId) ?: return null
        val step = run.capsuleSnapshot.capsule.graph.steps.singleOrNull { it.id == stepId } ?: return null
        val attempted = run.recoveryAttempts.count { it.stepId == stepId }
        if (attempted >= step.recovery.maximumAttempts || step.recovery.sequence.isEmpty()) return null
        return step.recovery.sequence[attempted % step.recovery.sequence.size]
    }

    fun recordRecovery(
        runId: RoutineRunId,
        stepId: RoutineStepId,
        primitive: RecoveryPrimitive,
        succeeded: Boolean,
        evidenceId: String,
        atEpochMillis: Long,
    ): RoutineRunCommandResult {
        val run = runs.load(runId) ?: return RoutineRunCommandResult.Missing(runId)
        val ordinal = run.recoveryAttempts.count { it.stepId == stepId } + 1
        return runs.recordRecovery(
            runId,
            RoutineRecoveryAttempt(
                stepId = stepId,
                ordinal = ordinal,
                primitive = primitive,
                outcome = when {
                    primitive == RecoveryPrimitive.HUMAN_TAKEOVER && !succeeded -> RecoveryAttemptOutcome.WAITING_FOR_USER
                    succeeded -> RecoveryAttemptOutcome.SUCCEEDED
                    else -> RecoveryAttemptOutcome.FAILED
                },
                evidenceId = evidenceId,
                attemptedAtEpochMillis = atEpochMillis,
            ),
        )
    }
}
