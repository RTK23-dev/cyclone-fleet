package com.cyclone.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneExecutionBoundaryTest {
    @Test fun staleLaunchThenProgressThenStaleBackStillReachesCookieAction() {
        var plans = 0
        var actions = 0
        val trace = mutableListOf<CycloneTraceEvent>()
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
                plans++
                return CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.ACT, "step-$plans"))
            }
        }
        val tools = object : CycloneAgentTools {
            override fun observe(state: CycloneTaskState) = CycloneObservation("page-$actions", "page-$actions")
            override fun execute(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn): CycloneToolResult {
                actions++
                val stale = actions == 1 || actions == 4
                return CycloneToolResult(ok = !stale, staleTarget = stale, message = if (stale) "target.stale" else null)
            }
            override fun verify(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn, toolResult: CycloneToolResult) =
                CycloneVerificationResult(verified = true, progress = true, complete = actions == 5)
        }
        val result = CycloneLocalAgent("open reddit and reject cookies", model, tools,
            trace = CycloneAgentTraceSink { trace += it }).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Completed)
        assertEquals(5, actions)
        assertEquals(5, plans)
        assertEquals(2, trace.count { it.type == CycloneTraceEventType.TOOL_RESULT && it.safeMessage == "target.stale" })
    }

    private class Tools : CycloneAgentTools {
        var executions = 0
        var recoveries = 0
        override fun onRecovery(state: CycloneTaskState, kind: CycloneRecoveryKind, code: String) { recoveries++ }
        override fun observe(state: CycloneTaskState) = CycloneObservation("same", "same")
        override fun execute(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn): CycloneToolResult {
            executions++
            return CycloneToolResult(ok = false)
        }
        override fun verify(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn, toolResult: CycloneToolResult) = CycloneVerificationResult(false, false)
    }

    @Test fun earlyToolRejectionReachesRecoveryBeforeAnotherPlan() {
        val tools = Tools()
        var plans = 0
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
                assertEquals(plans, tools.recoveries)
                plans++
                return CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.ACT, "click"))
            }
        }
        CycloneLocalAgent("reject cookies", model, tools).runUntilBoundary()
        assertEquals(2, tools.executions)
        assertEquals(2, tools.recoveries)
    }

    @Test fun cancellationDuringPlanningCannotExecuteReturnedAction() {
        val tools = Tools()
        var cancelled = false
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
                cancelled = true
                return CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.ACT, "click"))
            }
        }
        val result = CycloneLocalAgent("test", model, tools, externallyCancelled = { cancelled }).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Cancelled)
        assertEquals(0, tools.executions)
    }

    @Test fun deadlineExpiredDuringPlanningCannotExecuteReturnedAction() {
        val tools = Tools()
        var clock = 0L
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
                clock = 100L
                return CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.ACT, "click"))
            }
        }
        val result = CycloneLocalAgent("test", model, tools,
            convergence = CycloneConvergencePolicy(taskTimeoutMs = 100), now = { clock }).runUntilBoundary()
        assertEquals(CycloneTaskClassification.NON_CONVERGENCE, result.state.finalClassification)
        assertEquals(0, tools.executions)
    }

    @Test fun failedActionBetweenDoneClaimsDoesNotResetCompletionBudget() {
        val tools = Tools()
        var calls = 0
        val model = object : CycloneAgentModel {
            override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
                calls++
                return CyclonePlanResult.Valid(if (calls % 2 == 1)
                    CycloneModelTurn(CycloneModelDirective.DONE)
                else CycloneModelTurn(CycloneModelDirective.ACT, "retry"))
            }
        }
        val result = CycloneLocalAgent("test", model, tools).runUntilBoundary()
        assertEquals(CycloneTaskClassification.NON_CONVERGENCE, result.state.finalClassification)
        assertEquals(3, calls)
        assertEquals(1, tools.executions)
    }
}
