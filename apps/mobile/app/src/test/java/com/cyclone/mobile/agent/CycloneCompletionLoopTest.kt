package com.cyclone.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneCompletionLoopTest {
    @Test
    fun rejectedDoneReobservesLocallyBeforeAnotherModelTurn() {
        var modelCalls = 0
        var observeCalls = 0
        val model = object : CycloneAgentModel {
            override fun plan(
                state: CycloneTaskState,
                observation: CycloneObservation,
            ): CyclonePlanResult {
                modelCalls++
                return CyclonePlanResult.Valid(
                    CycloneModelTurn(CycloneModelDirective.DONE, payload = "done"),
                )
            }
        }
        val tools = object : CycloneAgentTools {
            override fun observe(state: CycloneTaskState): CycloneObservation {
                observeCalls++
                return CycloneObservation("obs-$observeCalls", "page-$observeCalls")
            }

            override fun execute(
                state: CycloneTaskState,
                observation: CycloneObservation,
                turn: CycloneModelTurn,
            ) = error("execute should not be called")

            override fun verify(
                state: CycloneTaskState,
                observation: CycloneObservation,
                turn: CycloneModelTurn,
                toolResult: CycloneToolResult,
            ) = error("verify should not be called")

            override fun verifyCompletion(
                state: CycloneTaskState,
                observation: CycloneObservation,
                turn: CycloneModelTurn,
            ): CycloneVerificationResult = if (observation.identity == "obs-2") {
                CycloneVerificationResult(true, true, complete = true, message = "Done after fresh observation")
            } else {
                CycloneVerificationResult(false, false)
            }
        }

        val result = CycloneLocalAgent("open ad.nl", model, tools).runUntilBoundary()

        assertTrue(result is CycloneAgentRunResult.Completed)
        assertEquals(1, modelCalls)
        assertEquals(2, observeCalls)
    }

    @Test
    fun repeatedUnverifiedDoneStopsAfterTwoClaimsInsteadOfBurningProviderTurns() {
        var modelCalls = 0
        var observeCalls = 0
        val model = object : CycloneAgentModel {
            override fun plan(
                state: CycloneTaskState,
                observation: CycloneObservation,
            ): CyclonePlanResult {
                modelCalls++
                return CyclonePlanResult.Valid(
                    CycloneModelTurn(CycloneModelDirective.DONE, payload = "done"),
                )
            }
        }
        val tools = object : CycloneAgentTools {
            override fun observe(state: CycloneTaskState): CycloneObservation {
                observeCalls++
                return CycloneObservation("obs-$observeCalls", "page")
            }

            override fun execute(
                state: CycloneTaskState,
                observation: CycloneObservation,
                turn: CycloneModelTurn,
            ) = error("execute should not be called")

            override fun verify(
                state: CycloneTaskState,
                observation: CycloneObservation,
                turn: CycloneModelTurn,
                toolResult: CycloneToolResult,
            ) = error("verify should not be called")

            override fun verifyCompletion(
                state: CycloneTaskState,
                observation: CycloneObservation,
                turn: CycloneModelTurn,
            ) = CycloneVerificationResult(false, false)
        }

        val result = CycloneLocalAgent(
            "ambiguous task",
            model,
            tools,
            convergence = CycloneConvergencePolicy(maxRepeatedUnverifiedDone = 2),
        ).runUntilBoundary()

        assertTrue(result is CycloneAgentRunResult.Stopped)
        assertEquals(2, modelCalls)
        assertTrue(observeCalls <= 4)
        assertEquals(CycloneTaskClassification.NON_CONVERGENCE, result.state.finalClassification)
    }
}
