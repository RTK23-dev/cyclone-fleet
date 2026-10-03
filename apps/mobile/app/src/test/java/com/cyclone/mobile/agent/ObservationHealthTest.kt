package com.cyclone.mobile.agent

import com.cyclone.mobile.agent.contract.*
import org.junit.Assert.*
import org.junit.Test

class ObservationHealthTest {
    @Test fun loadingRaceBudgetIsDistinctFromPermissionAndTimeoutFailures() {
        assertFalse(ObservationHealth(ObservationState.CAPTURE_CHANGED, "scope", 7, 2).terminal)
        assertTrue(ObservationHealth(ObservationState.CAPTURE_CHANGED, "scope", 7, 5).terminal)
    }

    @Test fun loadingRacesStayInObservedLoopAndThenComplete() = loadingRaceRun(3, true)

    @Test fun endlessLoadingEndsAsNonConvergenceWithoutCallingProvider() = loadingRaceRun(100, false)

    private fun loadingRaceRun(failures: Int, completes: Boolean) {
        var captures = 0
        var plans = 0
        val tools = object : CycloneAgentTools {
            override fun observationHealth() = ObservationHealth(ObservationState.CAPTURE_CHANGED, "scope", 7, captures)
            override fun observe(state: CycloneTaskState): CycloneObservation? {
                captures++
                return if (captures <= failures) null else CycloneObservation("fresh", "shopify", sessionId = "scope", displayId = 7)
            }
            override fun onRecovery(state: CycloneTaskState, kind: CycloneRecoveryKind, code: String) = error("No hidden recovery capture")
            override fun execute(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn) = error("No mutation")
            override fun verify(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn, toolResult: CycloneToolResult) = error("No mutation")
            override fun verifyCompletion(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn) = CycloneVerificationResult(true, true, true)
        }
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
                plans++
                return CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.DONE))
            }
        }
        val result = CycloneLocalAgent("open chrome and go to Shopify", model, tools).runUntilBoundary()
        assertEquals(if (completes) CycloneTaskClassification.COMPLETE else CycloneTaskClassification.NON_CONVERGENCE, result.state.finalClassification)
        assertEquals(if (completes) 4 else 5, captures)
        assertEquals(if (completes) 1 else 0, plans)
    }

    @Test fun permissionDisconnectAndScopeAreTerminalWhileTimeoutIsBounded() {
        listOf(AgentFailureClass.ACCESSIBILITY_UNAVAILABLE, AgentFailureClass.DEVICE_DISCONNECTED, AgentFailureClass.STALE_OBSERVATION).forEach {
            val value = ObservationHealth.failure(AgentFailure(it, AgentFailureLayer.OBSERVATION, false, "fixture"), "isolated", 7, 1, null, 0)
            assertTrue(value.terminal)
            assertEquals(7, value.displayId)
        }
        val timeout = AgentFailure(AgentFailureClass.TIMEOUT, AgentFailureLayer.OBSERVATION, true, "fixture")
        assertFalse(ObservationHealth.failure(timeout, "scope", 3, 1, null, 0).terminal)
        assertTrue(ObservationHealth.failure(timeout, "scope", 3, 2, null, 0).terminal)
        assertFalse(ObservationHealth(ObservationState.EMPTY_VALID, "scope", 3).terminal)
    }
    @Test fun deadBackendStopsWithoutAProviderCallOrDisplayFallback() {
        var captures = 0
        var providers = 0
        val tools = object : CycloneAgentTools {
            override fun observationHealth() = ObservationHealth(ObservationState.PERMISSION_REQUIRED, "isolated", 7, 1)
            override fun observe(state: CycloneTaskState): CycloneObservation? { captures++; return null }
            override fun execute(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn) = error("No action allowed")
            override fun verify(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn, toolResult: CycloneToolResult) = error("No verification allowed")
        }
        val model = object : CycloneAgentModel { override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult { providers++; error("No provider allowed") } }
        val result = CycloneLocalAgent("login", model, tools).runUntilBoundary()
        assertEquals(CycloneTaskClassification.HARD_BLOCKER, result.state.finalClassification)
        assertEquals(0, providers)
        assertEquals(1, captures)
    }
    @Test fun transientObservationRecoversWithinTheSameTask() {
        var captures = 0
        val tools = object : CycloneAgentTools {
            override fun observationHealth() = ObservationHealth(ObservationState.TIMEOUT, "isolated", 7, 1,
                cooldownUntilMs = System.nanoTime() / 1_000_000 + 25)
            override fun observe(state: CycloneTaskState): CycloneObservation? {
                captures++
                return if (captures == 1) null else CycloneObservation("fresh", "ready", sessionId = "isolated", displayId = 7)
            }
            override fun execute(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn) = error("No mutation")
            override fun verify(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn, toolResult: CycloneToolResult) = error("No mutation")
            override fun verifyCompletion(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn) = CycloneVerificationResult(true, true, captures == 2)
        }
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
                assertEquals(7, observation.displayId)
                return CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.DONE))
            }
        }
        val result = CycloneLocalAgent("inspect ready screen", model, tools, taskId = "same-task").runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Completed)
        assertEquals("same-task", result.state.taskId)
        assertEquals(2, captures)
    }
}
