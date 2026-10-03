package com.cyclone.mobile.ai

import com.cyclone.mobile.agent.contract.AgentActionEnvelope
import com.cyclone.mobile.agent.contract.AgentVerificationStatus
import com.cyclone.mobile.agent.recovery.ProgressClassification

/**
 * Keeps task-progress accounting separate from mutation verification.
 *
 * Mutations still require semantic verification. Read-only tools may establish progress only when
 * Android accepted the operation, a fresh after-observation exists, semantic verification explicitly
 * says it was not required because the tool is read-only, and the progress classifier found
 * goal-relevant progress in the fresh before/after evidence.
 */
internal object AgentProgressAcceptance {
    fun observationOnlyAccepted(envelope: AgentActionEnvelope): Boolean =
        envelope.androidExecutionOk &&
            envelope.after != null &&
            envelope.verification.status == AgentVerificationStatus.NOT_REQUIRED &&
            envelope.verification.basis == "READ_ONLY_TOOL"

    fun madeProgress(
        envelope: AgentActionEnvelope,
        classification: ProgressClassification,
    ): Boolean =
        classification == ProgressClassification.VERIFIED_PROGRESS &&
            (envelope.verification.passed || observationOnlyAccepted(envelope))

    fun acceptedForExecution(envelope: AgentActionEnvelope): Boolean =
        envelope.verification.passed || observationOnlyAccepted(envelope)
}
