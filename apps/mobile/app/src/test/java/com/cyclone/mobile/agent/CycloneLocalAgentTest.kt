package com.cyclone.mobile.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneLocalAgentTest {
    private class ScriptModel(private val plans: MutableList<CyclonePlanResult>) : CycloneAgentModel {
        var calls = 0
        override fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult {
            calls++
            return if (plans.isNotEmpty()) plans.removeAt(0) else CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.DONE))
        }
    }

    private class FakeTools(
        observations: List<CycloneObservation> = listOf(CycloneObservation("o1", "p1")),
    ) : CycloneAgentTools {
        private val observations = observations.toMutableList()
        var executeCalls = 0
        var verifyCalls = 0
        var boundary = CycloneTaskClassification.RECOVERABLE
        var completion = CycloneVerificationResult(false, false, false)
        var toolResults = mutableListOf<CycloneToolResult>()
        var verifications = mutableListOf<CycloneVerificationResult>()

        override fun observe(state: CycloneTaskState): CycloneObservation? =
            if (observations.size > 1) observations.removeAt(0) else observations.firstOrNull()

        override fun execute(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn): CycloneToolResult {
            executeCalls++
            return if (toolResults.isNotEmpty()) toolResults.removeAt(0) else CycloneToolResult(ok = true, actionSignature = turn.actionSignature)
        }

        override fun verify(
            state: CycloneTaskState,
            observation: CycloneObservation,
            turn: CycloneModelTurn,
            toolResult: CycloneToolResult,
        ): CycloneVerificationResult {
            verifyCalls++
            return if (verifications.isNotEmpty()) verifications.removeAt(0) else CycloneVerificationResult(true, true, false, evidenceIdentity = "e-$verifyCalls")
        }

        override fun classifyModelBoundary(
            state: CycloneTaskState,
            observation: CycloneObservation,
            turn: CycloneModelTurn,
        ): CycloneTaskClassification = boundary

        override fun verifyCompletion(
            state: CycloneTaskState,
            observation: CycloneObservation,
            turn: CycloneModelTurn,
        ): CycloneVerificationResult = completion
    }

    private fun act(signature: String) = CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.ACT, signature))
    private fun done() = CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.DONE))

    @Test fun backgroundAuthorityLossSuspendsAndResumesSameTask() {
        var paused = true
        val model = ScriptModel(mutableListOf(done()))
        val tools = FakeTools().apply { completion = CycloneVerificationResult(true, true, true, "verified") }
        val agent = CycloneLocalAgent("same goal", model, tools, taskId = "owned-task", externallyPaused = { paused })
        assertTrue(agent.runUntilBoundary() is CycloneAgentRunResult.Suspended)
        assertEquals(0, model.calls)
        assertEquals(0, tools.executeCalls)
        paused = false
        assertTrue(agent.resume())
        assertTrue(agent.runUntilBoundary() is CycloneAgentRunResult.Completed)
        assertEquals("owned-task", agent.snapshot().taskId)
    }

    @Test fun recoverableToolFailureDoesNotTerminateTask() {
        val model = ScriptModel(mutableListOf(act("a1"), act("a2"), done()))
        val tools = FakeTools(listOf(CycloneObservation("o1", "p1"), CycloneObservation("o2", "p1"), CycloneObservation("o3", "p2")))
        tools.toolResults += CycloneToolResult(ok = false, actionSignature = "a1")
        tools.toolResults += CycloneToolResult(ok = true, actionSignature = "a2", evidenceIdentity = "o3")
        tools.verifications += CycloneVerificationResult(true, true, false, "o3")
        tools.completion = CycloneVerificationResult(true, true, true, "o3")
        val result = CycloneLocalAgent("goal", model, tools).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Completed)
        assertTrue(model.calls >= 3)
    }

    @Test fun modelBlockedButClassifierRecoverableContinues() {
        val model = ScriptModel(mutableListOf(
            CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.BLOCKED, reason = "model says blocked")),
            act("recover"),
            done(),
        ))
        val tools = FakeTools(listOf(CycloneObservation("o1", "p1"), CycloneObservation("o2", "p1")))
        tools.boundary = CycloneTaskClassification.RECOVERABLE
        tools.verifications += CycloneVerificationResult(true, true, false, "o2")
        tools.completion = CycloneVerificationResult(true, true, true, "o2")
        val result = CycloneLocalAgent("goal", model, tools).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Completed)
        assertEquals(1, tools.executeCalls)
    }

    @Test fun providerRetryLaterStopsWithoutGroundingRecovery() {
        val model = ScriptModel(mutableListOf(
            CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.BLOCKED, reason = "RATE_LIMITED")),
            act("must-not-run"),
        ))
        val tools = FakeTools().apply { boundary = CycloneTaskClassification.PROVIDER_RETRY_LATER }

        val result = CycloneLocalAgent("goal", model, tools).runUntilBoundary()

        assertTrue(result is CycloneAgentRunResult.Stopped)
        assertEquals(CycloneTaskClassification.PROVIDER_RETRY_LATER, result.state.finalClassification)
        assertTrue(result.state.recoveryAttempts.isEmpty())
        assertEquals(1, model.calls)
        assertEquals(0, tools.executeCalls)
    }

    @Test fun malformedModelResponseGetsBoundedRecovery() {
        val model = ScriptModel(mutableListOf(
            CyclonePlanResult.Malformed("bad1"),
            CyclonePlanResult.Malformed("bad2"),
            act("fixed"),
            done(),
        ))
        val tools = FakeTools()
        tools.verifications += CycloneVerificationResult(true, true, false, "o2")
        tools.completion = CycloneVerificationResult(true, true, true, "o2")
        val result = CycloneLocalAgent(
            "goal", model, tools,
            CycloneConvergencePolicy(maxMalformedModelResponses = 3, maxConsecutiveRecoveryCyclesWithoutNewEvidence = 5),
        ).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Completed)
        assertEquals(2, result.state.recoveryAttempts[CycloneRecoveryKind.MALFORMED_MODEL])
    }

    @Test fun moreThanSixModelTurnsAllowedWithVerifiedProgress() {
        val plans = (1..8).map { act("step-$it") }.toMutableList<CyclonePlanResult>().apply { add(done()) }
        val observations = (1..10).map { CycloneObservation("o$it", "p$it") }
        val model = ScriptModel(plans)
        val tools = FakeTools(observations)
        repeat(8) { i ->
            tools.toolResults += CycloneToolResult(ok = true, actionSignature = "step-${i + 1}", evidenceIdentity = "o${i + 2}")
            tools.verifications += CycloneVerificationResult(true, true, false, "o${i + 2}")
        }
        tools.completion = CycloneVerificationResult(true, true, true, "o10")
        val result = CycloneLocalAgent("goal", model, tools).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Completed)
        assertEquals(9, result.state.modelTurns)
    }

    @Test fun recoveryBudgetCanTraverseFullProgressivePerceptionLadder() {
        val plans = MutableList<CyclonePlanResult>(6) {
            CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.BLOCKED, reason = "still ambiguous"))
        }.apply {
            add(act("recovered"))
            add(done())
        }
        val model = ScriptModel(plans)
        val tools = FakeTools()
        tools.boundary = CycloneTaskClassification.RECOVERABLE
        tools.toolResults += CycloneToolResult(ok = true, actionSignature = "recovered", evidenceIdentity = "changed")
        tools.verifications += CycloneVerificationResult(true, true, false, "changed")
        tools.completion = CycloneVerificationResult(true, true, true, "changed")

        val result = CycloneLocalAgent(
            "goal",
            model,
            tools,
            CycloneConvergencePolicy(maxConsecutiveRecoveryCyclesWithoutNewEvidence = 8),
        ).runUntilBoundary()

        assertTrue(result is CycloneAgentRunResult.Completed)
        assertTrue(model.calls >= 8)
    }

    @Test fun repeatedIdenticalNoProgressEventuallyStops() {
        val model = ScriptModel(MutableList(5) { act("same") })
        val tools = FakeTools()
        repeat(5) {
            tools.toolResults += CycloneToolResult(ok = true, actionSignature = "same")
            tools.verifications += CycloneVerificationResult(verified = false, progress = false)
        }
        val result = CycloneLocalAgent(
            "goal", model, tools,
            CycloneConvergencePolicy(maxRepeatedIdenticalActionWithoutProgress = 2, maxConsecutiveRecoveryCyclesWithoutNewEvidence = 10),
        ).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Stopped)
        assertEquals(CycloneTaskClassification.NON_CONVERGENCE, result.state.finalClassification)
    }

    @Test fun changingPageFingerprintsDoNotResetNoProgressRecoveryBudget() {
        val model = ScriptModel(mutableListOf(act("a1"), act("a2"), act("a3"), act("a4")))
        val tools = FakeTools(
            listOf(
                CycloneObservation("o1", "p1"),
                CycloneObservation("o2", "p2"),
                CycloneObservation("o3", "p3"),
                CycloneObservation("o4", "p4"),
            ),
        )
        repeat(4) { i -> tools.toolResults += CycloneToolResult(ok = false, actionSignature = "a${i + 1}") }

        val result = CycloneLocalAgent(
            "goal",
            model,
            tools,
            CycloneConvergencePolicy(
                maxConsecutiveRecoveryCyclesWithoutNewEvidence = 2,
                maxRepeatedIdenticalActionWithoutProgress = 10,
                maxMutationsWithoutVerifiedProgress = 20,
            ),
        ).runUntilBoundary()

        assertTrue(result is CycloneAgentRunResult.Stopped)
        assertEquals(CycloneTaskClassification.NON_CONVERGENCE, result.state.finalClassification)
        assertEquals(3, tools.executeCalls)
    }

    @Test fun differentActionsCannotCreateAnInfiniteNoProgressMutationLoop() {
        val model = ScriptModel((1..8).map { act("different-$it") }.toMutableList())
        val tools = FakeTools((1..9).map { CycloneObservation("o$it", "p$it") })
        repeat(8) { i ->
            tools.toolResults += CycloneToolResult(ok = true, actionSignature = "different-${i + 1}")
            tools.verifications += CycloneVerificationResult(verified = false, progress = false)
        }

        val result = CycloneLocalAgent(
            "goal",
            model,
            tools,
            CycloneConvergencePolicy(
                maxConsecutiveRecoveryCyclesWithoutNewEvidence = 50,
                maxRepeatedIdenticalActionWithoutProgress = 10,
                maxMutationsWithoutVerifiedProgress = 3,
            ),
        ).runUntilBoundary()

        assertTrue(result is CycloneAgentRunResult.Stopped)
        assertEquals(CycloneTaskClassification.NON_CONVERGENCE, result.state.finalClassification)
        assertEquals(3, tools.executeCalls)
    }

    @Test fun verifiedSemanticProgressResetsGlobalNoProgressBudget() {
        val model = ScriptModel(mutableListOf(act("a1"), act("a2"), act("a3"), act("a4"), done()))
        val tools = FakeTools((1..6).map { CycloneObservation("o$it", "p$it") })
        repeat(4) { i -> tools.toolResults += CycloneToolResult(ok = true, actionSignature = "a${i + 1}") }
        tools.verifications += CycloneVerificationResult(false, false)
        tools.verifications += CycloneVerificationResult(true, true, false, "progress-1")
        tools.verifications += CycloneVerificationResult(false, false)
        tools.verifications += CycloneVerificationResult(true, true, false, "progress-2")
        tools.completion = CycloneVerificationResult(true, true, true, "done")

        val result = CycloneLocalAgent(
            "goal",
            model,
            tools,
            CycloneConvergencePolicy(
                maxConsecutiveRecoveryCyclesWithoutNewEvidence = 10,
                maxRepeatedIdenticalActionWithoutProgress = 10,
                maxMutationsWithoutVerifiedProgress = 2,
            ),
        ).runUntilBoundary()

        assertTrue(result is CycloneAgentRunResult.Completed)
        assertEquals(4, tools.executeCalls)
    }

    @Test fun gateSuspendsRatherThanFails() {
        val model = ScriptModel(mutableListOf(CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.NEED_HUMAN))))
        val tools = FakeTools().apply { boundary = CycloneTaskClassification.HUMAN_OR_GATE }
        val result = CycloneLocalAgent("goal", model, tools).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Suspended)
        assertTrue(result.state.gateSuspended)
        assertEquals(CycloneTaskClassification.HUMAN_OR_GATE, result.state.finalClassification)
    }

    @Test fun resumedTaskRequiresFreshObservation() {
        val model = ScriptModel(mutableListOf(
            CyclonePlanResult.Valid(CycloneModelTurn(CycloneModelDirective.NEED_HUMAN)),
            done(),
        ))
        val observedRequireFresh = mutableListOf<Boolean>()
        val tools = object : CycloneAgentTools {
            override fun observe(state: CycloneTaskState): CycloneObservation {
                observedRequireFresh += state.requireFreshObservation
                return CycloneObservation("o${observedRequireFresh.size}", "p1")
            }
            override fun execute(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn) = CycloneToolResult(true)
            override fun verify(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn, toolResult: CycloneToolResult) = CycloneVerificationResult(true, true)
            override fun classifyModelBoundary(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn) = CycloneTaskClassification.HUMAN_OR_GATE
            override fun verifyCompletion(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn) = CycloneVerificationResult(true, true, true)
        }
        val agent = CycloneLocalAgent("goal", model, tools)
        assertTrue(agent.runUntilBoundary() is CycloneAgentRunResult.Suspended)
        assertTrue(agent.resume())
        assertTrue(agent.snapshot().requireFreshObservation)
        assertNull(agent.snapshot().latestObservationIdentity)
        assertNull(agent.snapshot().latestPageIdentity)
        assertNull(agent.snapshot().lastActionSignature)
        assertTrue(agent.runUntilBoundary() is CycloneAgentRunResult.Completed)
        assertEquals(listOf(true, true), observedRequireFresh)
    }

    @Test fun verifiedCompletionTerminates() {
        val model = ScriptModel(mutableListOf(done()))
        val tools = FakeTools().apply { completion = CycloneVerificationResult(true, true, true) }
        val result = CycloneLocalAgent("goal", model, tools).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Completed)
        assertEquals(CycloneTaskClassification.COMPLETE, result.state.finalClassification)
    }

    @Test fun userCancellationTerminatesImmediately() {
        val model = ScriptModel(mutableListOf(act("never")))
        val tools = FakeTools()
        val result = CycloneLocalAgent("goal", model, tools, externallyCancelled = { true }).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Cancelled)
        assertEquals(0, model.calls)
        assertEquals(0, tools.executeCalls)
    }

    @Test fun modelCannotOverrideAndroidPolicy() {
        val model = ScriptModel(mutableListOf(act("danger")))
        val tools = FakeTools().apply {
            toolResults += CycloneToolResult(
                ok = false,
                actionSignature = "danger",
                policyAllowed = false,
                gateRequired = true,
                message = "gate",
            )
        }
        val result = CycloneLocalAgent("goal", model, tools).runUntilBoundary()
        assertTrue(result is CycloneAgentRunResult.Suspended)
        assertEquals(CycloneTaskClassification.HUMAN_OR_GATE, result.state.finalClassification)
        assertEquals(0, tools.verifyCalls)
    }

    @Test fun traceIncludesTaskLevelEventsWithoutPayloads() {
        val events = mutableListOf<CycloneTraceEvent>()
        val model = ScriptModel(mutableListOf(act("safe-signature"), done()))
        val tools = FakeTools().apply {
            verifications += CycloneVerificationResult(true, true, false, "o2")
            completion = CycloneVerificationResult(true, true, true, "o2")
        }
        CycloneLocalAgent("goal", model, tools, trace = CycloneAgentTraceSink { events += it }).runUntilBoundary()
        assertTrue(CycloneTraceEventType.TASK_STARTED in events.map { it.type })
        assertTrue(CycloneTraceEventType.TOOL_RESULT in events.map { it.type })
        assertTrue(CycloneTraceEventType.COMPLETE in events.map { it.type })
        assertFalse(events.any { it.code?.contains("base64", ignoreCase = true) == true })
    }
}
