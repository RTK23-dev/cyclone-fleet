package com.cyclone.mobile.ai

import com.cyclone.mobile.agent.contract.*
import com.cyclone.mobile.agent.recovery.ProgressClassification
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProgressAcceptanceTest {
    @Test
    fun readOnlyFreshObservationCanEstablishProgressWithoutMutationVerification() {
        val envelope = envelope(
            tool = "phone.wait_for",
            verification = AgentSemanticVerification(
                AgentVerificationStatus.NOT_REQUIRED,
                passed = false,
                semanticSuccessClaimed = false,
                basis = "READ_ONLY_TOOL",
            ),
            after = card("after"),
        )

        assertTrue(AgentProgressAcceptance.observationOnlyAccepted(envelope))
        assertTrue(AgentProgressAcceptance.acceptedForExecution(envelope))
        assertTrue(AgentProgressAcceptance.madeProgress(envelope, ProgressClassification.VERIFIED_PROGRESS))
    }

    @Test
    fun mutationCannotUseReadOnlyProgressEscapeHatch() {
        val envelope = envelope(
            tool = "phone.click",
            verification = AgentSemanticVerification(
                AgentVerificationStatus.OBSERVED,
                passed = false,
                semanticSuccessClaimed = false,
                basis = "NO_SEMANTIC_PROGRESS",
            ),
            after = card("after"),
        )

        assertFalse(AgentProgressAcceptance.observationOnlyAccepted(envelope))
        assertFalse(AgentProgressAcceptance.acceptedForExecution(envelope))
        assertFalse(AgentProgressAcceptance.madeProgress(envelope, ProgressClassification.VERIFIED_PROGRESS))
    }

    @Test
    fun readOnlyWithoutFreshAfterStateDoesNotCountAsAcceptedProgress() {
        val envelope = envelope(
            tool = "phone.wait_for",
            verification = AgentSemanticVerification(
                AgentVerificationStatus.NOT_REQUIRED,
                passed = false,
                semanticSuccessClaimed = false,
                basis = "READ_ONLY_TOOL",
            ),
            after = null,
        )

        assertFalse(AgentProgressAcceptance.observationOnlyAccepted(envelope))
        assertFalse(AgentProgressAcceptance.madeProgress(envelope, ProgressClassification.VERIFIED_PROGRESS))
    }

    private fun envelope(
        tool: String,
        verification: AgentSemanticVerification,
        after: AgentPageCard?,
    ) = AgentActionEnvelope(
        tool = tool,
        goal = "open facebook",
        androidExecutionOk = true,
        executorReportedOk = true,
        verification = verification,
        before = card("before"),
        after = after,
        pageChanged = true,
        delta = AgentStateDelta(true, true, true, emptyList(), false, "changed"),
        errorClass = AgentFailureClass.NONE,
        failureLayer = AgentFailureLayer.NONE,
        retryable = false,
        semanticSuccessClaimed = verification.semanticSuccessClaimed,
        beforeObservationId = "before",
        afterObservationId = after?.observationId,
        observationGeneration = after?.generation,
        learning = AgentLearningResult(false, "test"),
        executorInvoked = true,
    )

    private fun card(id: String) = AgentPageCard(
        observationId = id,
        generation = if (id == "before") 1 else 2,
        actionable = true,
        capturedAtMs = if (id == "before") 1 else 2,
        packageName = if (id == "before") "com.android.settings" else "com.android.chrome",
        activity = null,
        pageKey = id,
        structuralKey = id,
        contentKey = id,
        accessibilityFingerprint = id,
        pageSummary = JSONObject(),
        pageText = JSONObject(),
        pageEvidence = JSONObject(),
        controls = emptyList(),
        nextHopHints = JSONArray(),
    )
}
