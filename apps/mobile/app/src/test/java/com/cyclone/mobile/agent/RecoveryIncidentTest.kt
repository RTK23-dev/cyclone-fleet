package com.cyclone.mobile.agent

import com.cyclone.mobile.agent.recovery.*
import org.junit.Assert.*
import org.junit.Test

class RecoveryIncidentTest {
    private fun incident() = RecoveryIncident(taskId = "task", sessionId = "scope", displayId = 3,
        openingGeneration = 7, category = "STALE_TARGET", intendedEffect = IncidentEffect.CONSENT_REMOVED)
    @Test fun unrelatedAcceptedActionAndDifferentScopeCannotCloseIncident() {
        val value = incident().attempt("dispatch.accepted")
        assertEquals("OPEN", value.verified(IncidentEffect.USER_GOAL_VERIFIED, "scope", 3).resolution)
        assertEquals("OPEN", value.verified(IncidentEffect.CONSENT_REMOVED, "scope", 0).resolution)
        assertEquals("VERIFIED", value.verified(IncidentEffect.CONSENT_REMOVED, "scope", 3).resolution)
    }
    @Test fun persistenceRetainsScopeIntentStrategiesAndResolutionWithoutTypedSecrets() {
        val value = incident().attempt("target.stale").attempt("password=fixture-secret")
        val json = value.toJson()
        assertFalse(json.toString().contains("fixture-secret"))
        val restored = RecoveryIncident.fromJson(json)!!
        assertEquals(value.incidentId, restored.incidentId)
        assertEquals(3, restored.displayId)
        assertTrue("target.stale" in restored.strategies)
        assertEquals("VERIFIED", RecoveryIncident.fromJson(restored.verified(IncidentEffect.CONSENT_REMOVED, "scope", 3).toJson())!!.resolution)
    }
    @Test fun taskRecoveryKeepsOneIncidentAcrossAcceptedButUnverifiedActions() {
        val checkpoints = mutableListOf<CycloneTaskState>()
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation) = CyclonePlanResult.Valid(
                CycloneModelTurn(CycloneModelDirective.ACT, "different-${state.modelTurns}", intendedEffect = IncidentEffect.CONSENT_REMOVED))
        }
        val tools = object : CycloneAgentTools {
            override fun observe(state: CycloneTaskState) = CycloneObservation("churn-${state.modelTurns}", "consent")
            override fun execute(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn) = CycloneToolResult(true)
            override fun verify(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn, toolResult: CycloneToolResult) = CycloneVerificationResult(false, false)
        }
        val agent = CycloneLocalAgent("login", model, tools, checkpoints = object : CycloneTaskCheckpointStore {
            override fun save(state: CycloneTaskState) { checkpoints += state }
        })
        agent.runUntilBoundary()
        assertEquals(1, checkpoints.mapNotNull { it.incident?.incidentId }.distinct().size)
        assertTrue(checkpoints.filter { it.currentStage != CycloneAgentStage.TERMINAL }.mapNotNull { it.incident }.all { it.resolution == "OPEN" })
        assertEquals("login", agent.snapshot().goal)
    }
}
