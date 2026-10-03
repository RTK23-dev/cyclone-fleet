package com.cyclone.mobile.observability.context

import com.cyclone.mobile.observability.events.ActionResultTrace
import com.cyclone.mobile.observability.events.AiNecessityReason
import com.cyclone.mobile.observability.events.ContextDecisionEvent
import com.cyclone.mobile.observability.events.ContextSourceEvidence
import com.cyclone.mobile.observability.events.DecisionStage
import com.cyclone.mobile.observability.events.EvidenceRef
import com.cyclone.mobile.observability.events.LatencyTrace
import com.cyclone.mobile.observability.events.ModelTrace
import com.cyclone.mobile.observability.events.PayloadTruncation
import com.cyclone.mobile.observability.events.PolicyTrace
import com.cyclone.mobile.observability.events.ProposedActionTrace
import com.cyclone.mobile.observability.events.RecoveryTrace
import com.cyclone.mobile.observability.events.RedactedTextDigest
import com.cyclone.mobile.observability.events.SanitizedFailure
import com.cyclone.mobile.observability.events.VerificationTrace
import com.cyclone.mobile.observability.events.VerificationStatus
import com.cyclone.mobile.observability.events.VisionTrace
import com.cyclone.mobile.platform.event.EventEnvelope

data class ContextBudgetSummary(
    val totalBytes: Int,
    val totalEstimatedTokens: Int,
    val sourceCount: Int,
    val truncation: PayloadTruncation,
)

data class ContextDecisionDiagnostic(
    val decisionId: String,
    val missionId: String?,
    val sessionId: String?,
    val appPackage: String?,
    val pageRef: EvidenceRef?,
    val goal: RedactedTextDigest?,
    val routeRef: EvidenceRef?,
    val aiReason: AiNecessityReason?,
    val contextSources: List<ContextSourceEvidence>,
    val knowledgeRefs: List<EvidenceRef>,
    val budget: ContextBudgetSummary,
    val policy: PolicyTrace?,
    val vision: VisionTrace?,
    val model: ModelTrace?,
    val proposedAction: ProposedActionTrace?,
    val actionResult: ActionResultTrace?,
    val verification: VerificationTrace?,
    val latency: LatencyTrace,
    val recovery: RecoveryTrace?,
    val failure: SanitizedFailure?,
    val stagesSeen: List<DecisionStage>,
    val missingCoreStages: List<DecisionStage>,
    val firstEventEpochMillis: Long,
    val lastEventEpochMillis: Long,
    val eventCount: Int,
) {
    val complete: Boolean get() = missingCoreStages.isEmpty()

    /** Directly answers the five primary explainability questions without exposing prompt text. */
    val whyAi: AiNecessityReason? get() = aiReason
    val whyVision: VisionTrace? get() = vision
    val suppliedEvidence: List<ContextSourceEvidence> get() = contextSources.filter { it.included }
    val influentialKnowledge: List<EvidenceRef> get() = knowledgeRefs
    val wasVerified: Boolean get() = verification?.status == VerificationStatus.VERIFIED
}

internal object ContextDiagnosticBuilder {
    private val eventOrder = compareBy<EventEnvelope<ContextDecisionEvent>>(
        EventEnvelope<ContextDecisionEvent>::timestampEpochMillis,
        EventEnvelope<ContextDecisionEvent>::eventId,
    )
    private val coreStages = listOf(
        DecisionStage.STARTED,
        DecisionStage.CONTEXT_ASSEMBLED,
        DecisionStage.MODEL_INVOKED,
        DecisionStage.ACTION_PROPOSED,
        DecisionStage.ACTION_RESULT,
        DecisionStage.VERIFICATION_RESULT,
        DecisionStage.COMPLETED,
    )

    fun build(input: List<EventEnvelope<ContextDecisionEvent>>): ContextDecisionDiagnostic? {
        if (input.isEmpty()) return null
        val events = input.sortedWith(eventOrder)
        val payloads = events.map { it.payload }
        val seen = payloads.map { it.stage }.distinct()
        val latestSources = payloads.asReversed().firstOrNull { it.contextSources.isNotEmpty() }?.contextSources.orEmpty()
            .groupBy(ContextSourceEvidence::source)
            .mapValues { (_, values) -> values.last() }
            .values
            .sortedBy { it.source.name }
        val knowledge = payloads.flatMap { it.knowledgeRefs }.distinct().sortedBy(EvidenceRef::toString)
        val truncation = PayloadTruncation(
            droppedSources = payloads.sumOf { it.truncation.droppedSources },
            droppedEvidenceRefs = payloads.sumOf { it.truncation.droppedEvidenceRefs },
            droppedKnowledgeRefs = payloads.sumOf { it.truncation.droppedKnowledgeRefs },
        )
        val latestLatency = payloads.lastValue(ContextDecisionEvent::latency) ?: LatencyTrace()
        val finalStageSeen = DecisionStage.COMPLETED in seen || DecisionStage.FAILED in seen
        val missing = coreStages.filter { stage ->
            if (stage == DecisionStage.COMPLETED) !finalStageSeen else stage !in seen
        }
        return ContextDecisionDiagnostic(
            decisionId = payloads.first().decisionId,
            missionId = events.lastValue { it.missionId },
            sessionId = events.lastValue { it.sessionId },
            appPackage = payloads.lastValue(ContextDecisionEvent::appPackage),
            pageRef = payloads.lastValue(ContextDecisionEvent::pageRef),
            goal = payloads.lastValue(ContextDecisionEvent::goal),
            routeRef = payloads.lastValue(ContextDecisionEvent::routeRef),
            aiReason = payloads.lastValue(ContextDecisionEvent::aiReason),
            contextSources = latestSources,
            knowledgeRefs = knowledge,
            budget = ContextBudgetSummary(
                totalBytes = latestSources.sumOf { it.byteCount },
                totalEstimatedTokens = latestSources.sumOf { it.estimatedTokens },
                sourceCount = latestSources.size,
                truncation = truncation,
            ),
            policy = payloads.lastValue(ContextDecisionEvent::policy),
            vision = payloads.lastValue(ContextDecisionEvent::vision),
            model = payloads.lastValue(ContextDecisionEvent::model),
            proposedAction = payloads.lastValue(ContextDecisionEvent::proposedAction),
            actionResult = payloads.lastValue(ContextDecisionEvent::actionResult),
            verification = payloads.lastValue(ContextDecisionEvent::verification),
            latency = latestLatency,
            recovery = payloads.lastValue(ContextDecisionEvent::recovery),
            failure = payloads.lastValue(ContextDecisionEvent::failure),
            stagesSeen = seen.sortedBy(DecisionStage::ordinal),
            missingCoreStages = missing,
            firstEventEpochMillis = events.first().timestampEpochMillis,
            lastEventEpochMillis = events.last().timestampEpochMillis,
            eventCount = events.size,
        )
    }

    private fun <T, R : Any> List<T>.lastValue(selector: (T) -> R?): R? =
        asReversed().firstNotNullOfOrNull(selector)
}
