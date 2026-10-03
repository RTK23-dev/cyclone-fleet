package com.cyclone.mobile.automation.v31

import com.cyclone.mobile.ai.v31.V31ActionBoundaryResult
import com.cyclone.mobile.ai.v31.V31ActionProposalBoundary
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.FailureAction
import com.cyclone.mobile.automation.RecoveryPolicy
import com.cyclone.mobile.automation.Selector
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerType
import com.cyclone.mobile.automation.VariableDefinition
import com.cyclone.mobile.automation.capsule.CapsuleSnapshot
import com.cyclone.mobile.automation.capsule.RecoveryPrimitive
import com.cyclone.mobile.automation.run.InMemoryRoutineRunStore
import com.cyclone.mobile.automation.run.RoutinePolicyOutcome
import com.cyclone.mobile.automation.run.RoutineRunController
import com.cyclone.mobile.automation.run.RoutineRunId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V31AutomationRunBridgeTest {
    @Test
    fun legacyAutomationConvertsWithoutLosingRepresentableInputsOrSteps() {
        val bridge = V31AutomationRunBridge(RoutineRunController(InMemoryRoutineRunStore()), boundary())
        val prepared = bridge.prepareLegacy(legacyAutomation(), 10, "user")
        assertTrue(prepared is V31AutomationPreparation.Ready)
        val capsule = (prepared as V31AutomationPreparation.Ready).capsule
        assertEquals(1, capsule.inputs.size)
        assertEquals("query", capsule.inputs.single().name)
        assertEquals(1, capsule.graph.steps.size)
        assertEquals("phone.click", capsule.requiredCapabilities.single().value)
    }

    @Test
    fun runSnapshotIsImmutableAndFailureChoosesExplicitRecoveryThroughBoundary() {
        val controller = RoutineRunController(InMemoryRoutineRunStore())
        var boundaryCalls = 0
        val bridge = V31AutomationRunBridge(
            controller,
            V31ActionProposalBoundary {
                boundaryCalls += 1
                V31ActionBoundaryResult(
                    policyOutcome = RoutinePolicyOutcome.APPROVED,
                    policyEvidenceId = "policy:1",
                    executionSucceeded = false,
                    executionEvidenceId = "execution:failed",
                    verificationPassed = false,
                    verificationEvidenceId = "verification:failed",
                )
            },
        )
        val capsule = (bridge.prepareLegacy(legacyAutomation(), 10, "user") as V31AutomationPreparation.Ready).capsule
        val runId = RoutineRunId("run-1")
        bridge.createAndStart(runId, capsule, 20)
        val originalHash = controller.load(runId)!!.capsuleSnapshot.sha256
        val edited = capsule.copy(intent = "Edited after run started")
        assertNotEquals(originalHash, CapsuleSnapshot.capture(edited).sha256)
        assertEquals(originalHash, controller.load(runId)!!.capsuleSnapshot.sha256)

        val step = capsule.graph.steps.single()
        val result = bridge.executeStep(runId, step.id, "goal:apps", "observation:1", 21)

        assertEquals(1, boundaryCalls)
        assertTrue(result.boundaryCalled)
        assertEquals(RecoveryPrimitive.REOBSERVE, result.recovery)
    }

    private fun boundary() = V31ActionProposalBoundary {
        V31ActionBoundaryResult(
            policyOutcome = RoutinePolicyOutcome.APPROVED,
            policyEvidenceId = "policy:ok",
            executionSucceeded = true,
            executionEvidenceId = "execution:ok",
            verificationPassed = true,
            verificationEvidenceId = "verification:ok",
        )
    }

    private fun legacyAutomation() = AutomationDefinition(
        id = "settings-apps",
        name = "Open Apps",
        description = "Open the Apps page in Android settings",
        trigger = TriggerDefinition(TriggerType.MANUAL),
        variables = listOf(VariableDefinition("query", defaultValue = "Apps")),
        steps = listOf(
            StepDefinition(
                name = "Open Apps",
                type = StepType.PHONE_TOOL,
                parameters = mapOf("tool" to "phone.click"),
                selector = Selector(text = "Apps", requireClickable = true),
                recovery = RecoveryPolicy(maxRetries = 2, onFailure = FailureAction.REQUEST_AI_HELP),
            ),
        ),
    )
}
