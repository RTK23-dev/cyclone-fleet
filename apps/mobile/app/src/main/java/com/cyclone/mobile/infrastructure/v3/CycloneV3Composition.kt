package com.cyclone.mobile.infrastructure.v3

import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.brain.memory.api.CycloneMemoryService
import com.cyclone.mobile.brain.memory.api.MemoryProposalStatus
import com.cyclone.mobile.brain.memory.api.MemoryVerificationState
import com.cyclone.mobile.brain.memory.api.MemoryWriteProposalRequest
import com.cyclone.mobile.observability.context.ContextLedger
import com.cyclone.mobile.observability.events.ContextEventRequest
import com.cyclone.mobile.observability.events.VerificationStatus
import com.cyclone.mobile.policy.GuardedPolicyResult
import com.cyclone.mobile.policy.PolicyAuthorization
import com.cyclone.mobile.policy.PolicyGovernor
import com.cyclone.mobile.policy.PolicyGuard
import com.cyclone.mobile.policy.PolicyRequest
import com.cyclone.mobile.policy.PolicyTarget

enum class DecisionSource { AI, ROUTINE }

data class DecisionEvidence(
    val goalId: String,
    val knowledgeReferences: List<String>,
    val pageObservationId: String,
    val selectorObservationId: String,
    val decisionSource: DecisionSource,
    val routineId: String? = null,
) {
    init {
        require(goalId.isNotBlank() && pageObservationId.isNotBlank() && selectorObservationId.isNotBlank())
        require(knowledgeReferences.none { it.isBlank() })
        require(decisionSource != DecisionSource.ROUTINE || !routineId.isNullOrBlank())
    }
}

data class AuthorizedPhoneActionProposal(
    val request: PhoneToolRequest,
    val authorization: PolicyAuthorization,
    val evidence: DecisionEvidence,
)

/**
 * Proposal-only boundary to the existing canonical PhoneToolExecutor adapter. Implementations may
 * enqueue the approved proposal, but this V3 composition layer contains no second action engine.
 */
fun interface CanonicalPhoneExecutorProposalSink {
    fun propose(action: AuthorizedPhoneActionProposal): String
}

/**
 * Resolves a normalized target from trusted page/selector state. Phone action parameters are
 * intentionally absent from this interface because they are a proposal, never target authority.
 */
fun interface TrustedPolicyTargetResolver {
    fun resolve(actionId: String, evidence: DecisionEvidence): PolicyTarget?
}

/**
 * Optional production seam for freshness checks that need more than observation-id equality.
 * Implementations must be read-only and fail closed; they cannot grant authority or execute.
 */
fun interface TrustedDecisionEvidenceValidator {
    fun invalidReason(actionId: String, evidence: DecisionEvidence): String?
}

sealed interface ActionCompositionDecision {
    data class Proposed(val handoffId: String) : ActionCompositionDecision
    data class Blocked(val reasonCode: String) : ActionCompositionDecision
}

sealed interface VerifiedCompletionDecision {
    data class Recorded(val memoryStatus: MemoryProposalStatus) : VerifiedCompletionDecision
    data class Blocked(val reasonCode: String) : VerifiedCompletionDecision
}

/** Small contract-composition seam: policy precedes a proposal and verified evidence precedes memory. */
class CycloneV3ActionComposition(
    policyGovernor: PolicyGovernor,
    private val executorProposalSink: CanonicalPhoneExecutorProposalSink,
    private val ledger: ContextLedger,
    private val memory: CycloneMemoryService?,
    private val targetResolver: TrustedPolicyTargetResolver = TrustedPolicyTargetResolver { _, _ -> null },
    private val evidenceValidator: TrustedDecisionEvidenceValidator = TrustedDecisionEvidenceValidator { _, _ -> null },
) {
    private val policy = PolicyGuard(policyGovernor)

    fun propose(
        policyRequest: PolicyRequest,
        phoneRequest: PhoneToolRequest,
        evidence: DecisionEvidence,
    ): ActionCompositionDecision {
        if (evidence.selectorObservationId != evidence.pageObservationId) {
            return ActionCompositionDecision.Blocked("STALE_SELECTOR")
        }
        evidenceValidator.invalidReason(policyRequest.actionId, evidence)?.let {
            return ActionCompositionDecision.Blocked(it)
        }
        actionBindingMismatch(policyRequest, phoneRequest, evidence)?.let {
            return ActionCompositionDecision.Blocked(it)
        }
        return when (val guarded = policy.authorizeThen(policyRequest) { authorization ->
            executorProposalSink.propose(AuthorizedPhoneActionProposal(phoneRequest, authorization, evidence))
        }) {
            is GuardedPolicyResult.Blocked -> ActionCompositionDecision.Blocked("POLICY_${guarded.evaluation.audit.reason.name}")
            is GuardedPolicyResult.Executed -> ActionCompositionDecision.Proposed(guarded.value)
        }
    }

    fun recordVerifiedCompletion(
        event: ContextEventRequest,
        memoryProposal: MemoryWriteProposalRequest,
    ): VerifiedCompletionDecision {
        val payload = event.payload
        if (payload.stage != com.cyclone.mobile.observability.events.DecisionStage.VERIFICATION_RESULT) {
            return VerifiedCompletionDecision.Blocked("VERIFICATION_STAGE_REQUIRED")
        }
        val actionResult = payload.actionResult
            ?: return VerifiedCompletionDecision.Blocked("ACTION_RESULT_REQUIRED")
        if (actionResult.outcome != com.cyclone.mobile.observability.events.ActionOutcome.SUCCEEDED) {
            return VerifiedCompletionDecision.Blocked("ACTION_NOT_SUCCEEDED")
        }
        val actionWitness = actionResult.resultRef
            ?.takeIf(::isSpecificEvidence)
            ?: return VerifiedCompletionDecision.Blocked("ACTION_WITNESS_REQUIRED")
        val verification = payload.verification
        if (verification?.status != VerificationStatus.VERIFIED) {
            return VerifiedCompletionDecision.Blocked("VERIFICATION_REQUIRED")
        }
        val verificationWitness = verification.resultRef
            ?.takeIf(::isSpecificEvidence)
            ?: return VerifiedCompletionDecision.Blocked("VERIFICATION_WITNESS_REQUIRED")
        val action = payload.proposedAction
            ?: return VerifiedCompletionDecision.Blocked("ACTION_CORRELATION_REQUIRED")
        val draft = memoryProposal.draft
        val requiredEvidence = setOf(
            event.eventId,
            payload.decisionId,
            action.actionCode,
            actionWitness.toString(),
            verificationWitness.toString(),
        )
        val provenance = draft.provenance
        if (
            draft.verificationState != MemoryVerificationState.VERIFIED ||
            provenance.sourceSystem != "context.ledger" ||
            provenance.observedAtEpochMillis != event.timestampEpochMillis ||
            !provenance.evidenceReferences.containsAll(requiredEvidence)
        ) {
            return VerifiedCompletionDecision.Blocked("MEMORY_PROVENANCE_MISMATCH")
        }
        val envelope = ledger.append(event).envelope
        if (envelope.eventId != event.eventId || envelope.correlationId != payload.decisionId) {
            return VerifiedCompletionDecision.Blocked("EVENT_CORRELATION_MISMATCH")
        }
        val service = memory ?: return VerifiedCompletionDecision.Blocked("MEMORY_UNAVAILABLE")
        return VerifiedCompletionDecision.Recorded(service.proposeWrite(memoryProposal).status)
    }

    /** Validate the exact action before PolicyGovernor can consume a one-shot grant. */
    private fun actionBindingMismatch(
        policyRequest: PolicyRequest,
        phoneRequest: PhoneToolRequest,
        evidence: DecisionEvidence,
    ): String? {
        if (phoneRequest.commandId != policyRequest.actionId) return "ACTION_ID_MISMATCH"
        if (phoneRequest.tool != policyRequest.capability) return "CAPABILITY_MISMATCH"
        val requestedTarget = policyRequest.target ?: return null
        val resolvedTarget = targetResolver.resolve(policyRequest.actionId, evidence)
            ?: return "TARGET_SCOPE_MISMATCH"
        return if (requestedTarget.contains(resolvedTarget)) null else "TARGET_SCOPE_MISMATCH"
    }

    /** The requested policy target may be broader than, but never conflict with, resolved state. */
    private fun PolicyTarget.contains(resolved: PolicyTarget): Boolean =
        (packageName == null || packageName == resolved.packageName) &&
            (targetType == null || targetType == resolved.targetType) &&
            (targetId == null || targetId == resolved.targetId) &&
            attributes.all { (key, value) -> resolved.attributes[key] == value }

    private fun isSpecificEvidence(reference: com.cyclone.mobile.observability.events.EvidenceRef): Boolean =
        reference.sha256.any { it != '0' }
}

data class CycloneV3Health(
    val visionAvailable: Boolean = true,
    val memoryAvailable: Boolean = true,
    val optionalModulesHealthy: Boolean = true,
    val runtimeHealthy: Boolean = true,
    val gatewayConnected: Boolean = true,
)

enum class DegradationDirective {
    USE_STRUCTURED_PAGE_EVIDENCE,
    CONTINUE_WITHOUT_MEMORY_RECALL,
    QUARANTINE_OPTIONAL_MODULE,
    REQUEST_RECOVERY_ROLLBACK,
    PAUSE_FOR_GATEWAY_RECONNECT,
}

fun CycloneV3Health.degradationPlan(): List<DegradationDirective> = buildList {
    if (!visionAvailable) add(DegradationDirective.USE_STRUCTURED_PAGE_EVIDENCE)
    if (!memoryAvailable) add(DegradationDirective.CONTINUE_WITHOUT_MEMORY_RECALL)
    if (!optionalModulesHealthy) add(DegradationDirective.QUARANTINE_OPTIONAL_MODULE)
    if (!runtimeHealthy) add(DegradationDirective.REQUEST_RECOVERY_ROLLBACK)
    if (!gatewayConnected) add(DegradationDirective.PAUSE_FOR_GATEWAY_RECONNECT)
}
