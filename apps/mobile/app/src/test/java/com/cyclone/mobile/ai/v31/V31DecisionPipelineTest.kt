package com.cyclone.mobile.ai.v31

import com.cyclone.mobile.ai.vision.EvidenceSufficiency
import com.cyclone.mobile.ai.vision.StructuredEvidenceState
import com.cyclone.mobile.ai.vision.VisionEvidenceKind
import com.cyclone.mobile.ai.vision.VisionImageRef
import com.cyclone.mobile.ai.vision.VisionPurpose
import com.cyclone.mobile.ai.vision.VisionRequest
import com.cyclone.mobile.ai.vision.VisionResult
import com.cyclone.mobile.ai.vision.VisionResultStatus
import com.cyclone.mobile.ai.vision.VisionRouter
import com.cyclone.mobile.automation.capsule.RoutineActionProposal
import com.cyclone.mobile.automation.run.RoutinePolicyOutcome
import com.cyclone.mobile.brain.graphv2.InMemoryTemporalGraphStore
import com.cyclone.mobile.brain.memory.api.CycloneMemoryService
import com.cyclone.mobile.infrastructure.v3.CycloneV3Health
import com.cyclone.mobile.observability.context.ContextLedger
import com.cyclone.mobile.observability.context.ContextLedgerQuery
import com.cyclone.mobile.observability.context.InMemoryContextLedgerPersistence
import com.cyclone.mobile.platform.capability.CapabilityId
import com.cyclone.mobile.platform.event.DataClassification
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V31DecisionPipelineTest {
    @Test
    fun structuredEvidenceWinsBeforeAiOrVisionAndLedgerContainsNoRawGoalSecret() {
        val ledger = ContextLedger(InMemoryContextLedgerPersistence())
        var boundaryCalls = 0
        val dependencies = dependencies(ledger) {
            boundaryCalls += 1
            V31ActionBoundaryResult(
                RoutinePolicyOutcome.APPROVED,
                "policy:1",
                executionSucceeded = true,
                executionEvidenceId = "execution:1",
                verificationPassed = true,
                verificationEvidenceId = "verification:1",
            )
        }
        val planner = object : V31AiPlanner {
            override fun plan(input: V31DecisionInput): V31AiPlan = error("AI must not run")
            override fun planWithVision(input: V31DecisionInput, vision: VisionResult): V31AiPlan = error("Vision AI must not run")
        }
        val proposal = RoutineActionProposal(CapabilityId("phone.click"), "click")
        val result = V31DecisionPipeline(dependencies, planner).decide(
            input(
                goal = "password=super-secret",
                candidates = listOf(V31StructuredCandidate(V31ReasoningMode.GRAPH, proposal, listOf("graph:route"))),
            ),
        )

        assertTrue(result is V31DecisionResult.Proposed)
        assertEquals(V31ReasoningMode.GRAPH, (result as V31DecisionResult.Proposed).mode)
        assertEquals(1, boundaryCalls)
        val renderedLedger = ledger.query(ContextLedgerQuery()).joinToString("\n")
        assertFalse(renderedLedger.contains("super-secret"))
        assertFalse(renderedLedger.contains("password="))
    }

    @Test
    fun unavailableVisionDegradesCleanlyWithoutActionBoundary() {
        val ledger = ContextLedger(InMemoryContextLedgerPersistence())
        var boundaryCalls = 0
        val dependencies = dependencies(ledger) {
            boundaryCalls += 1
            error("Boundary must not be reached")
        }
        val planner = object : V31AiPlanner {
            override fun plan(input: V31DecisionInput) = V31AiPlan.NeedsVision("openai", "gpt", "request-1")
            override fun planWithVision(input: V31DecisionInput, vision: VisionResult): V31AiPlan = error("No provider is configured")
        }
        val result = V31DecisionPipeline(dependencies, planner).decide(
            input(goal = "Find the unlabeled control", candidates = emptyList(), vision = visionRequest()),
        )

        assertTrue(result is V31DecisionResult.NeedsHuman)
        assertEquals(VisionResultStatus.UNAVAILABLE, (result as V31DecisionResult.NeedsHuman).visionStatus)
        assertEquals(0, boundaryCalls)
    }

    private fun dependencies(
        ledger: ContextLedger,
        boundary: V31ActionProposalBoundary,
    ) = V31IntelligenceDependencies(
        memory = unusedMemoryService(),
        graph = InMemoryTemporalGraphStore(),
        contextLedger = ledger,
        visionRouter = VisionRouter(emptyList()),
        capabilityLookup = V31CapabilityLookup { true },
        actionProposalBoundary = boundary,
        runtimeHealth = V31RuntimeHealthSource { CycloneV3Health() },
    )

    private fun input(
        goal: String,
        candidates: List<V31StructuredCandidate>,
        vision: VisionRequest? = null,
    ) = V31DecisionInput(
        decisionId = "decision-1",
        timestampEpochMillis = 100,
        missionId = "mission-1",
        sessionId = "session-1",
        goal = goal,
        goalReference = "goal:1",
        appPackage = "com.android.settings",
        pageIdentity = "settings.home",
        structuredCandidates = candidates,
        visionRequest = vision,
    )

    private fun visionRequest() = VisionRequest(
        requestId = "vision-1",
        imageRef = VisionImageRef("0".repeat(64), 1080, 2400),
        purpose = VisionPurpose.CONTROL_DISCOVERY,
        requiredEvidence = setOf(VisionEvidenceKind.CONTROL_BOUNDS),
        privacyClassification = DataClassification.INTERNAL,
        latencyBudgetMillis = 1_000,
        structuredEvidence = StructuredEvidenceState(
            EvidenceSufficiency.INSUFFICIENT,
            EvidenceSufficiency.INSUFFICIENT,
            EvidenceSufficiency.INSUFFICIENT,
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun unusedMemoryService(): CycloneMemoryService = Proxy.newProxyInstance(
        CycloneMemoryService::class.java.classLoader,
        arrayOf(CycloneMemoryService::class.java),
    ) { _, method, _ -> error("Memory method ${method.name} is not part of this pipeline test") } as CycloneMemoryService
}
