package com.cyclone.mobile.automation.run

import com.cyclone.mobile.automation.capsule.CapsuleSnapshot
import com.cyclone.mobile.automation.capsule.RecoveryPrimitive
import com.cyclone.mobile.automation.capsule.RoutineActionProposal
import com.cyclone.mobile.automation.capsule.RoutineArgument
import com.cyclone.mobile.automation.capsule.RoutineCapsuleTest
import com.cyclone.mobile.automation.capsule.RoutineStepId
import com.cyclone.mobile.platform.capability.CapabilityId
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RoutineRunControllerTest {
    @Test
    fun lifecycleRejectsInvalidTransitionsAndRequiresCompletionEvidence() {
        val controller = RoutineRunController(InMemoryRoutineRunStore())
        val id = RoutineRunId("run-lifecycle")
        assertTrue(controller.create(id, RoutineCapsuleTest.fixture(), 10) is RoutineRunCommandResult.Applied)
        assertTrue(controller.pause(id, 11) is RoutineRunCommandResult.Rejected)
        assertTrue(controller.start(id, 12) is RoutineRunCommandResult.Applied)
        assertTrue(controller.complete(id, emptyList(), 13) is RoutineRunCommandResult.Rejected)
        assertTrue(controller.complete(id, listOf("evidence:complete"), 14) is RoutineRunCommandResult.Applied)
        assertTrue(controller.resume(id, 15) is RoutineRunCommandResult.Rejected)
        assertEquals(RoutineRunStatus.COMPLETED, controller.load(id)!!.status)
    }

    @Test
    fun recoverySequenceAndBoundAreDeterministic() {
        val controller = RoutineRunController(InMemoryRoutineRunStore())
        val id = RoutineRunId("run-recovery")
        controller.create(id, RoutineCapsuleTest.fixture(), 1)
        controller.start(id, 2)

        assertTrue(controller.recordRecovery(id, recovery(1, RecoveryPrimitive.RETRY_SELECTOR, 3)) is RoutineRunCommandResult.Rejected)
        assertTrue(controller.recordRecovery(id, recovery(1, RecoveryPrimitive.REOBSERVE, 4)) is RoutineRunCommandResult.Applied)
        assertTrue(controller.recordRecovery(id, recovery(2, RecoveryPrimitive.RETRY_SELECTOR, 5)) is RoutineRunCommandResult.Applied)
        assertTrue(controller.recordRecovery(id, recovery(3, RecoveryPrimitive.REOBSERVE, 6)) is RoutineRunCommandResult.Rejected)
        assertEquals(2, controller.load(id)!!.recoveryAttempts.size)
    }

    @Test
    fun actionRecordsCannotBypassFrozenProposalOrPolicyEvidence() {
        val controller = RoutineRunController(InMemoryRoutineRunStore())
        val id = RoutineRunId("run-policy")
        val capsule = RoutineCapsuleTest.fixture()
        controller.create(id, capsule, 1)
        controller.start(id, 2)
        controller.beginStep(id, RoutineStepId("click"), 2)
        val proposal = capsule.graph.steps.first { it.id == RoutineStepId("click") }.action!!
        val approved = RoutineActionRecord(
            proposal,
            RoutinePolicyOutcome.APPROVED,
            policyEvidenceId = "policy:approved",
            executionEvidenceId = "execution:typed-path",
            recordedAtEpochMillis = 3,
        )
        assertTrue(controller.recordAction(id, RoutineStepId("click"), approved) is RoutineRunCommandResult.Applied)

        val altered = approved.copy(
            proposal = RoutineActionProposal(
                CapabilityId("phone.click"),
                "click",
                mapOf("query" to RoutineArgument.NonSensitiveLiteral("altered")),
            ),
            recordedAtEpochMillis = 4,
        )
        assertTrue(controller.recordAction(id, RoutineStepId("click"), altered) is RoutineRunCommandResult.Rejected)
        expectFailure {
            RoutineActionRecord(proposal, RoutinePolicyOutcome.APPROVED, "policy:approved", recordedAtEpochMillis = 5)
        }
        expectFailure {
            RoutineActionRecord(
                proposal,
                RoutinePolicyOutcome.DENIED,
                "policy:denied",
                executionEvidenceId = "execution:forbidden",
                recordedAtEpochMillis = 5,
            )
        }
    }

    @Test
    fun fileStoreMakesInterruptedRunReadableAfterRestart() {
        val directory = Files.createTempDirectory("cyclone-routine-test").toFile()
        try {
            val id = RoutineRunId("run-restart")
            val first = RoutineRunController(FileRoutineRunStore(directory))
            first.create(id, RoutineCapsuleTest.fixture(), 10)
            first.start(id, 11)
            first.pause(id, 12)

            val restored = RoutineRunController(FileRoutineRunStore(directory)).load(id)!!
            assertEquals(RoutineRunStatus.PAUSED, restored.status)
            assertEquals(CapsuleSnapshot.capture(RoutineCapsuleTest.fixture()).sha256, restored.capsuleSnapshot.sha256)
            assertEquals(restored, RoutineRunCodec.decode(RoutineRunCodec.encode(restored)))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun boundedStoreRetainsNewestRunsWithStableTieBreak() {
        val store = InMemoryRoutineRunStore(maximumRuns = 2)
        val capsule = CapsuleSnapshot.capture(RoutineCapsuleTest.fixture())
        store.save(record("run-a", capsule, 1))
        store.save(record("run-b", capsule, 2))
        store.save(record("run-c", capsule, 3))

        assertNull(store.load(RoutineRunId("run-a")))
        assertEquals(listOf("run-c", "run-b"), store.list().map { it.runId.value })
    }

    private fun recovery(ordinal: Int, primitive: RecoveryPrimitive, at: Long) = RoutineRecoveryAttempt(
        stepId = RoutineStepId("click"),
        ordinal = ordinal,
        primitive = primitive,
        outcome = RecoveryAttemptOutcome.FAILED,
        evidenceId = "recovery:$ordinal",
        attemptedAtEpochMillis = at,
    )

    private fun record(id: String, capsule: CapsuleSnapshot, at: Long) = RoutineRunRecord(
        runId = RoutineRunId(id),
        capsuleSnapshot = capsule,
        startedAtEpochMillis = at,
        updatedAtEpochMillis = at,
        steps = capsule.capsule.graph.steps.map { RoutineStepProgress(it.id) },
    )

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected validation failure")
        } catch (_: IllegalArgumentException) {
            // Expected: invalid records are rejected before they can reach persistence.
        }
    }
}
