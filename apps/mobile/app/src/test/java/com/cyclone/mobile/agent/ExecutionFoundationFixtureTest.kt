package com.cyclone.mobile.agent

import com.cyclone.mobile.ai.CookieInterruptionPolicy
import com.cyclone.mobile.agent.contract.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Suite v1, seed 0. Offline scripted provider/device; oracle is separate from model DONE. */
class ExecutionFoundationFixtureTest {
    private class Fixture : CycloneAgentTools {
        var consent = true
        var dispatches = 0
        var stale = false
        override fun observe(state: CycloneTaskState) = CycloneObservation(if (consent) "consent" else "login", "browser")
        override fun execute(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn): CycloneToolResult {
            if (stale) return CycloneToolResult(false, staleTarget = true)
            dispatches++
            consent = false
            return CycloneToolResult(true)
        }
        override fun verify(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn, toolResult: CycloneToolResult) =
            CycloneVerificationResult(!consent, !consent, evidenceIdentity = "login")
        override fun verifyCompletion(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn) =
            CycloneVerificationResult(!consent, !consent, !consent)
        override fun classifyModelBoundary(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn) =
            if (turn.directive == CycloneModelDirective.NEED_HUMAN) CycloneTaskClassification.HUMAN_OR_GATE else CycloneTaskClassification.HARD_BLOCKER
    }
    private fun act() = CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.ACT, "reject"))
    private fun page() = AgentPageCard("obs", 1, true, 0, "com.android.chrome", null, "consent", "structure", "content", "fp",
        JSONObject(), JSONObject().put("text", "We use cookies"), JSONObject(),
        listOf(AgentElementCandidate("semantic:obs:reject", "obs", "Reject optional cookies", "reject cookies", "button", "semantic", 1.0,
            JSONObject().put("enabled", true).put("clickable", true))), JSONArray())

    @Test fun consentThenLoginKeepsGoalAndUsesNoProviderBeforeDismissal() {
        // The real collection policy must exclude Cyclone chrome before policy/planning.
        assertFalse(com.cyclone.mobile.TaskSurfaceWindows.includeSibling(
            com.cyclone.mobile.ui.overlay.OverlayChromeObservation.ACCESSIBILITY_OVERLAY_WINDOW_TYPE,
            "com.cyclone.mobile", "com.android.chrome"))
        val fixture = Fixture()
        val policy = CookieInterruptionPolicy()
        var providerCalls = 0
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
                assertEquals("continue login", state.goal)
                if (fixture.consent && policy.next(page(), state.goal) != null) {
                    assertEquals(0, providerCalls)
                    return act()
                }
                providerCalls++
                return CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.NEED_HUMAN, reason = "Authentication remains human-owned"))
            }
        }
        val result = CycloneLocalAgent("continue login", model, fixture).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Suspended)
        assertEquals("continue login", result.state.goal)
        assertFalse(fixture.consent)
        assertEquals(1, fixture.dispatches)
    }
    @Test fun delayedResponseAfterStopCannotDispatch() {
        val fixture = Fixture()
        var stopped = false
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult { stopped = true; return act() }
        }
        val spans = mutableListOf<ExecutionSpan>()
        val result = CycloneLocalAgent("login", model, fixture, externallyCancelled = { stopped },
            trace = CycloneAgentTraceSink { it.span?.let(spans::add) }).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Cancelled)
        assertEquals(0, fixture.dispatches)
        assertTrue(spans.any { it.result == "cancelled" })
    }
    @Test fun delayedResponsePastTaskDeadlineCannotDispatch() {
        val fixture = Fixture()
        var clock = 0L
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult { clock = 101; return act() }
        }
        val result = CycloneLocalAgent("login", model, fixture, convergence = CycloneConvergencePolicy(taskTimeoutMs = 100), now = { clock }).runUntilBoundary()
        assertEquals(CycloneTaskClassification.NON_CONVERGENCE, result.state.finalClassification)
        assertEquals(0, fixture.dispatches)
    }
    @Test fun permanent403StopsAfterOneProviderAttempt() {
        var calls = 0
        val fixture = Fixture()
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
                calls++; return CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.BLOCKED, reason = "provider.access_denied"))
            }
        }
        assertEquals(CycloneTaskClassification.HARD_BLOCKER, CycloneLocalAgent("login", model, fixture).runUntilBoundary().state.finalClassification)
        assertEquals(1, calls)
        assertEquals(0, fixture.dispatches)
    }
    @Test fun staleTargetDoesNotDispatchAndRecoveryIsBounded() {
        val fixture = Fixture().apply { stale = true }
        val model = object : CycloneAgentModel { override fun plan(state: CycloneTaskState, observation: CycloneObservation) = act() }
        assertEquals(CycloneTaskClassification.NON_CONVERGENCE, CycloneLocalAgent("login", model, fixture).runUntilBoundary().state.finalClassification)
        assertEquals(0, fixture.dispatches)
    }
    @Test fun spansAccountForInjectedDelayAndCloseExceptionsAsIncomplete() {
        var clock = 10L
        val spans = mutableListOf<ExecutionSpan>()
        val timing = ExecutionTiming({ clock }, spans::add)
        runCatching { timing.measure(1, ExecutionPhase.PROVIDER_WAIT) { clock += 75; error("synthetic") } }
        assertEquals(75L, spans.single().durationMs)
        assertEquals("incomplete", spans.single().result)
    }
    @Test fun reproduceLegacyDoubleCaptureRaceBeforeProjectionFix() {
        var generation = 0
        fun capture() = ++generation
        val legacy = capture()
        val executable = capture()
        assertNotEquals("Controlled banner arrival reproduces mixed generations", legacy, executable)
    }

    @Test fun phaseDeadlineRejectsLatePlanBeforeDispatch() {
        val fixture = Fixture()
        var monotonic = 0L
        val spans = mutableListOf<ExecutionSpan>()
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
                monotonic += 35_001
                return act()
            }
        }
        val result = CycloneLocalAgent("login", model, fixture, monotonicNow = { monotonic },
            trace = CycloneAgentTraceSink { it.span?.let(spans::add) }).runUntilBoundary()
        assertEquals(CycloneTaskClassification.NON_CONVERGENCE, result.state.finalClassification)
        assertEquals(0, fixture.dispatches)
        assertTrue(spans.any { it.phase == ExecutionPhase.PLAN_OR_RECALL && it.result == "deadline" && it.durationMs == 35_001L })
    }

    @Test fun stopWinsOverPhaseDeadlineAndSpanIdsAreUniqueAcrossTimers() {
        var clock = 0L
        val spans = mutableListOf<ExecutionSpan>()
        ExecutionTiming({ clock }, spans::add).bounded(1, ExecutionPhase.LOCAL_POLICY, { true }) { clock = 201 }
        ExecutionTiming({ clock }, spans::add).measure(1, ExecutionPhase.PROMPT) { }
        assertEquals("cancelled", spans.first().result)
        assertEquals(2, spans.map { it.spanId }.distinct().size)
    }
}
