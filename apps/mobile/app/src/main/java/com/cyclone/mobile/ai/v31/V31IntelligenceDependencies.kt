package com.cyclone.mobile.ai.v31

import com.cyclone.mobile.ai.vision.VisionRouter
import com.cyclone.mobile.automation.capsule.RoutineActionProposal
import com.cyclone.mobile.automation.run.RoutinePolicyOutcome
import com.cyclone.mobile.brain.graphv2.TemporalGraphStore
import com.cyclone.mobile.brain.memory.api.CycloneMemoryService
import com.cyclone.mobile.infrastructure.v3.CycloneV3Health
import com.cyclone.mobile.observability.context.ContextLedger

/**
 * Agent-2-owned integration seam. It deliberately contains references only; it does not create
 * another policy governor, memory service, module supervisor, or phone executor.
 */
data class V31IntelligenceDependencies(
    val memory: CycloneMemoryService,
    val graph: TemporalGraphStore,
    val contextLedger: ContextLedger,
    val visionRouter: VisionRouter,
    val capabilityLookup: V31CapabilityLookup,
    val actionProposalBoundary: V31ActionProposalBoundary,
    val runtimeHealth: V31RuntimeHealthSource,
)

fun interface V31CapabilityLookup {
    fun isAvailable(capabilityId: String): Boolean
}

fun interface V31RuntimeHealthSource {
    fun current(): CycloneV3Health
}

enum class V31ActionIntentSource { AI, ROUTINE, TEACH }

data class V31ActionBoundaryRequest(
    val proposal: RoutineActionProposal,
    val decisionId: String,
    val goalReference: String,
    val source: V31ActionIntentSource,
) {
    init {
        require(decisionId.isNotBlank())
        require(goalReference.isNotBlank())
    }
}

/**
 * Evidence returned by the canonical V3 proposal/policy/execution boundary. Agent 2 never executes
 * the phone action itself. An approved proposal always carries execution evidence, including a
 * failed canonical execution attempt.
 */
data class V31ActionBoundaryResult(
    val policyOutcome: RoutinePolicyOutcome,
    val policyEvidenceId: String,
    val executionSucceeded: Boolean = false,
    val executionEvidenceId: String? = null,
    val verificationPassed: Boolean? = null,
    val verificationEvidenceId: String? = null,
) {
    init {
        require(policyEvidenceId.isNotBlank())
        require(!executionSucceeded || policyOutcome == RoutinePolicyOutcome.APPROVED)
        require(policyOutcome != RoutinePolicyOutcome.APPROVED || !executionEvidenceId.isNullOrBlank())
        require(policyOutcome == RoutinePolicyOutcome.APPROVED || executionEvidenceId == null)
        require(policyOutcome == RoutinePolicyOutcome.APPROVED || verificationEvidenceId == null)
        require(verificationPassed == null || !verificationEvidenceId.isNullOrBlank())
    }
}

fun interface V31ActionProposalBoundary {
    fun submit(request: V31ActionBoundaryRequest): V31ActionBoundaryResult
}
