package com.cyclone.mobile.ai.v31

import com.cyclone.mobile.ai.vision.VisionRequest
import com.cyclone.mobile.ai.vision.VisionResult
import com.cyclone.mobile.ai.vision.VisionResultStatus
import com.cyclone.mobile.automation.capsule.RoutineActionProposal
import com.cyclone.mobile.automation.run.RoutinePolicyOutcome
import com.cyclone.mobile.observability.events.ActionOutcome
import com.cyclone.mobile.observability.events.ActionResultTrace
import com.cyclone.mobile.observability.events.AiNecessityReason
import com.cyclone.mobile.observability.events.ContextDecisionEvent
import com.cyclone.mobile.observability.events.ContextEventRequest
import com.cyclone.mobile.observability.events.ContextPrivacy
import com.cyclone.mobile.observability.events.ContextSourceEvidence
import com.cyclone.mobile.observability.events.ContextSourceKind
import com.cyclone.mobile.observability.events.DecisionStage
import com.cyclone.mobile.observability.events.EvidenceRef
import com.cyclone.mobile.observability.events.ModelTrace
import com.cyclone.mobile.observability.events.ProposedActionTrace
import com.cyclone.mobile.observability.events.VerificationStatus
import com.cyclone.mobile.observability.events.VerificationTrace
import com.cyclone.mobile.observability.events.VisionReason
import com.cyclone.mobile.observability.events.VisionTrace

enum class V31ReasoningMode {
    KNOWN_ROUTE,
    GRAPH,
    MEMORY,
    SEMANTIC,
    AI,
    VISION_FALLBACK,
    HUMAN_TAKEOVER,
}

data class V31StructuredCandidate(
    val mode: V31ReasoningMode,
    val proposal: RoutineActionProposal,
    val evidenceIds: List<String>,
    val stale: Boolean = false,
    val compatibleWithCurrentObservation: Boolean = true,
) {
    init {
        require(mode in setOf(V31ReasoningMode.KNOWN_ROUTE, V31ReasoningMode.GRAPH, V31ReasoningMode.MEMORY, V31ReasoningMode.SEMANTIC))
        require(evidenceIds.none(String::isBlank))
    }
}

data class V31DecisionInput(
    val decisionId: String,
    val timestampEpochMillis: Long,
    val missionId: String?,
    val sessionId: String?,
    val goal: String,
    val goalReference: String,
    val appPackage: String,
    val pageIdentity: String,
    val structuredCandidates: List<V31StructuredCandidate>,
    val visionRequest: VisionRequest? = null,
) {
    init {
        require(decisionId.length in 1..72 && decisionId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]*")))
        require(timestampEpochMillis >= 0 && goalReference.isNotBlank())
        require(appPackage.isNotBlank() && pageIdentity.isNotBlank())
    }
}

sealed interface V31AiPlan {
    data class Proposal(
        val proposal: RoutineActionProposal,
        val providerCode: String,
        val modelCode: String,
        val requestId: String,
    ) : V31AiPlan

    data class NeedsVision(
        val providerCode: String,
        val modelCode: String,
        val requestId: String,
    ) : V31AiPlan

    data class HumanTakeover(val reasonCode: String) : V31AiPlan
}

interface V31AiPlanner {
    fun plan(input: V31DecisionInput): V31AiPlan
    fun planWithVision(input: V31DecisionInput, vision: VisionResult): V31AiPlan
}

sealed interface V31DecisionResult {
    data class Proposed(
        val mode: V31ReasoningMode,
        val proposal: RoutineActionProposal,
        val boundary: V31ActionBoundaryResult,
    ) : V31DecisionResult

    data class NeedsHuman(
        val reasonCode: String,
        val visionStatus: VisionResultStatus? = null,
    ) : V31DecisionResult
}

/**
 * Deterministic-first AI pipeline. Vision is reachable only after structured routes fail and the AI
 * explicitly requests more visual evidence. The ledger stores references and redacted metadata,
 * never hidden chain-of-thought or action parameter values.
 */
class V31DecisionPipeline(
    private val dependencies: V31IntelligenceDependencies,
    private val aiPlanner: V31AiPlanner,
) {
    fun decide(input: V31DecisionInput): V31DecisionResult {
        append(input, DecisionStage.STARTED)
        val candidate = input.structuredCandidates
            .filter { !it.stale && it.compatibleWithCurrentObservation }
            .minByOrNull { structuredRank(it.mode) }
        if (candidate != null) {
            append(
                input,
                DecisionStage.CONTEXT_ASSEMBLED,
                knowledgeRefs = candidate.evidenceIds,
                source = sourceFor(candidate.mode),
            )
            return submit(input, candidate.mode, candidate.proposal, candidate.evidenceIds)
        }

        val initial = aiPlanner.plan(input)
        appendModel(input, initial)
        return when (initial) {
            is V31AiPlan.HumanTakeover -> V31DecisionResult.NeedsHuman(initial.reasonCode)
            is V31AiPlan.Proposal -> submit(input, V31ReasoningMode.AI, initial.proposal, emptyList())
            is V31AiPlan.NeedsVision -> resolveVision(input)
        }
    }

    private fun resolveVision(input: V31DecisionInput): V31DecisionResult {
        val request = input.visionRequest ?: return V31DecisionResult.NeedsHuman("VISION_INPUT_UNAVAILABLE")
        val vision = dependencies.visionRouter.route(request)
        appendVision(input, vision)
        if (vision.status != VisionResultStatus.SUCCESS) {
            return V31DecisionResult.NeedsHuman("VISION_${vision.status.name}", vision.status)
        }
        val refined = aiPlanner.planWithVision(input, vision)
        appendModel(input, refined)
        return when (refined) {
            is V31AiPlan.Proposal -> submit(input, V31ReasoningMode.VISION_FALLBACK, refined.proposal, vision.evidence.map { it.valueRef })
            is V31AiPlan.HumanTakeover -> V31DecisionResult.NeedsHuman(refined.reasonCode, vision.status)
            is V31AiPlan.NeedsVision -> V31DecisionResult.NeedsHuman("VISION_REPLAN_STILL_AMBIGUOUS", vision.status)
        }
    }

    private fun submit(
        input: V31DecisionInput,
        mode: V31ReasoningMode,
        proposal: RoutineActionProposal,
        evidenceIds: List<String>,
    ): V31DecisionResult {
        if (!dependencies.capabilityLookup.isAvailable(proposal.capabilityId.value)) {
            return V31DecisionResult.NeedsHuman("CAPABILITY_UNAVAILABLE")
        }
        append(
            input,
            DecisionStage.ACTION_PROPOSED,
            knowledgeRefs = evidenceIds,
            proposedAction = proposal,
            aiReason = if (mode in setOf(V31ReasoningMode.AI, V31ReasoningMode.VISION_FALLBACK)) AiNecessityReason.NO_VERIFIED_ROUTE else null,
        )
        val boundary = dependencies.actionProposalBoundary.submit(
            V31ActionBoundaryRequest(proposal, input.decisionId, input.goalReference, V31ActionIntentSource.AI),
        )
        appendActionResult(input, boundary)
        return V31DecisionResult.Proposed(mode, proposal, boundary)
    }

    private fun appendModel(input: V31DecisionInput, plan: V31AiPlan) {
        val model = when (plan) {
            is V31AiPlan.Proposal -> Triple(plan.providerCode, plan.modelCode, plan.requestId)
            is V31AiPlan.NeedsVision -> Triple(plan.providerCode, plan.modelCode, plan.requestId)
            is V31AiPlan.HumanTakeover -> null
        }
        append(
            input,
            DecisionStage.MODEL_INVOKED,
            model = model?.let { ModelTrace(it.first, it.second, EvidenceRef.fromRaw("model-request", it.third)) },
            aiReason = AiNecessityReason.NO_VERIFIED_ROUTE,
        )
    }

    private fun appendVision(input: V31DecisionInput, result: VisionResult) {
        append(
            input,
            DecisionStage.VISION_EVALUATED,
            vision = VisionTrace(
                used = result.attempts.any { it.invoked },
                reason = VisionReason.STRUCTURED_EVIDENCE_INSUFFICIENT,
                providerCode = result.providerId,
                evidenceRefs = result.evidence.map { EvidenceRef.fromDigest("vision", it.valueRef) },
                attemptCount = result.attempts.size,
            ),
        )
    }

    private fun appendActionResult(input: V31DecisionInput, boundary: V31ActionBoundaryResult) {
        val outcome = when {
            boundary.policyOutcome == RoutinePolicyOutcome.DENIED -> ActionOutcome.DENIED
            boundary.policyOutcome == RoutinePolicyOutcome.ASK_USER -> ActionOutcome.NOT_EXECUTED
            boundary.executionSucceeded -> ActionOutcome.SUCCEEDED
            else -> ActionOutcome.FAILED
        }
        append(
            input,
            DecisionStage.ACTION_RESULT,
            actionResult = ActionResultTrace(
                outcome,
                boundary.executionEvidenceId?.let { EvidenceRef.fromRaw("execution", it) },
            ),
            verification = VerificationTrace(
                status = when (boundary.verificationPassed) {
                    true -> VerificationStatus.VERIFIED
                    false -> VerificationStatus.FAILED
                    null -> VerificationStatus.NOT_REQUESTED
                },
                resultRef = boundary.verificationEvidenceId?.let { EvidenceRef.fromRaw("verification", it) },
            ),
        )
    }

    private fun append(
        input: V31DecisionInput,
        stage: DecisionStage,
        knowledgeRefs: List<String> = emptyList(),
        source: ContextSourceKind? = null,
        model: ModelTrace? = null,
        vision: VisionTrace? = null,
        proposedAction: RoutineActionProposal? = null,
        actionResult: ActionResultTrace? = null,
        verification: VerificationTrace? = null,
        aiReason: AiNecessityReason? = null,
    ) {
        val payload = ContextDecisionEvent(
            decisionId = input.decisionId,
            stage = stage,
            appPackage = input.appPackage,
            pageRef = EvidenceRef.fromRaw("page", input.pageIdentity),
            goal = ContextPrivacy.redactText("goal", input.goal),
            contextSources = source?.let {
                listOf(ContextSourceEvidence(it, 0, 0, knowledgeRefs.map { id -> EvidenceRef.fromRaw("context", id) }))
            }.orEmpty(),
            knowledgeRefs = knowledgeRefs.map { EvidenceRef.fromRaw("knowledge", it) },
            model = model,
            vision = vision,
            aiReason = aiReason,
            proposedAction = proposedAction?.let {
                ProposedActionTrace(
                    actionCode = it.capabilityId.value,
                    parameterNames = it.arguments.keys.map(::safeParameterName).toSet(),
                )
            },
            actionResult = actionResult,
            verification = verification,
        )
        dependencies.contextLedger.append(
            ContextEventRequest(
                eventId = "${input.decisionId}.${stage.name.lowercase()}",
                timestampEpochMillis = input.timestampEpochMillis,
                missionId = input.missionId,
                sessionId = input.sessionId,
                payload = payload,
            ),
        )
    }

    private fun safeParameterName(value: String): String = value
        .replace(Regex("([a-z0-9])([A-Z])"), "$1-$2")
        .lowercase()
        .replace(Regex("[^a-z0-9_.-]"), "-")
        .trim('-')
        .ifBlank { "parameter" }
        .take(96)

    private fun structuredRank(mode: V31ReasoningMode): Int = when (mode) {
        V31ReasoningMode.KNOWN_ROUTE -> 0
        V31ReasoningMode.GRAPH -> 1
        V31ReasoningMode.MEMORY -> 2
        V31ReasoningMode.SEMANTIC -> 3
        else -> 4
    }

    private fun sourceFor(mode: V31ReasoningMode): ContextSourceKind = when (mode) {
        V31ReasoningMode.KNOWN_ROUTE -> ContextSourceKind.ROUTINE
        V31ReasoningMode.GRAPH -> ContextSourceKind.APP_GRAPH
        V31ReasoningMode.MEMORY -> ContextSourceKind.BRAIN
        V31ReasoningMode.SEMANTIC -> ContextSourceKind.PAGE_AWARENESS
        else -> ContextSourceKind.PREVIOUS_RESULT
    }
}
