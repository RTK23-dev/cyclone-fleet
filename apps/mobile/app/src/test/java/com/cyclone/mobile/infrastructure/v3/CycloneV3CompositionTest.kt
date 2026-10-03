package com.cyclone.mobile.infrastructure.v3

import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.brain.memory.api.DefaultCycloneMemoryService
import com.cyclone.mobile.brain.memory.api.MemoryActor
import com.cyclone.mobile.brain.memory.api.MemoryApprovalVerifier
import com.cyclone.mobile.brain.memory.api.MemoryClass
import com.cyclone.mobile.brain.memory.api.MemoryClock
import com.cyclone.mobile.brain.memory.api.MemoryContent
import com.cyclone.mobile.brain.memory.api.MemoryDraft
import com.cyclone.mobile.brain.memory.api.MemoryPolicyDecision
import com.cyclone.mobile.brain.memory.api.MemoryPolicyResult
import com.cyclone.mobile.brain.memory.api.MemoryProvenance
import com.cyclone.mobile.brain.memory.api.MemoryProposalStatus
import com.cyclone.mobile.brain.memory.api.MemoryScope
import com.cyclone.mobile.brain.memory.api.MemoryScopeKind
import com.cyclone.mobile.brain.memory.api.MemorySensitivity
import com.cyclone.mobile.brain.memory.api.MemorySourceKind
import com.cyclone.mobile.brain.memory.api.MemoryVerificationState
import com.cyclone.mobile.brain.memory.api.MemoryWritePolicyGate
import com.cyclone.mobile.brain.memory.api.MemoryWriteProposalRequest
import com.cyclone.mobile.brain.memory.api.TestMemoryProvider
import com.cyclone.mobile.brain.memory.audit.InMemoryMemoryAuditJournal
import com.cyclone.mobile.observability.context.ContextLedger
import com.cyclone.mobile.observability.context.InMemoryContextLedgerPersistence
import com.cyclone.mobile.observability.events.ActionOutcome
import com.cyclone.mobile.observability.events.ActionResultTrace
import com.cyclone.mobile.observability.events.ContextDecisionEvent
import com.cyclone.mobile.observability.events.ContextEventRequest
import com.cyclone.mobile.observability.events.DecisionStage
import com.cyclone.mobile.observability.events.EvidenceRef
import com.cyclone.mobile.observability.events.ProposedActionTrace
import com.cyclone.mobile.observability.events.VerificationStatus
import com.cyclone.mobile.observability.events.VerificationTrace
import com.cyclone.mobile.policy.ActionRisk
import com.cyclone.mobile.policy.ActionScope
import com.cyclone.mobile.policy.AuthorityClaim
import com.cyclone.mobile.policy.AuthorityGrant
import com.cyclone.mobile.policy.AuthorityOrigin
import com.cyclone.mobile.policy.InMemoryPolicyGovernor
import com.cyclone.mobile.policy.PolicyClock
import com.cyclone.mobile.policy.PolicyPrincipal
import com.cyclone.mobile.policy.PolicyRequest
import com.cyclone.mobile.policy.PolicyTarget
import com.cyclone.mobile.policy.PrincipalKind
import com.cyclone.mobile.policy.PrincipalRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneV3CompositionTest {
    @Test
    fun goalPolicyKnowledgePageAiRoutineProposalVerificationLedgerMemoryHasNoExecutorBypass() {
        val governor = governorWithGrant()
        val ledger = ContextLedger(InMemoryContextLedgerPersistence())
        val memory = memory()
        var proposals = 0
        var executorCalls = 0
        val composition = CycloneV3ActionComposition(
            governor,
            CanonicalPhoneExecutorProposalSink {
                proposals += 1
                // This sink records a handoff only. The canonical executor is deliberately not invoked.
                "handoff:1"
            },
            ledger,
            memory,
        )
        val evidence = DecisionEvidence(
            goalId = "goal:open-settings",
            knowledgeReferences = listOf("graph:route:settings", "memory:verified:settings"),
            pageObservationId = "observation:7",
            selectorObservationId = "observation:7",
            decisionSource = DecisionSource.ROUTINE,
            routineId = "routine:settings",
        )

        val proposed = composition.propose(
            request(),
            PhoneToolRequest("action:open", "phone.click", JSONObject().put("selector", JSONObject().put("text", "Settings"))),
            evidence,
        )
        val event = verifiedEvent()
        val completion = composition.recordVerifiedCompletion(event, memoryProposal(event))

        assertEquals(ActionCompositionDecision.Proposed("handoff:1"), proposed)
        assertEquals(1, proposals)
        assertEquals(0, executorCalls)
        assertEquals(VerifiedCompletionDecision.Recorded(MemoryProposalStatus.READY), completion)
        assertEquals(1, ledger.query().size)
    }

    @Test
    fun mismatchedPhoneActionCannotConsumeGrantOrReachSink() {
        val governor = governorWithGrant(maximumUses = 1)
        var proposals = 0
        val composition = CycloneV3ActionComposition(
            governor,
            CanonicalPhoneExecutorProposalSink { proposals += 1; "handoff" },
            ContextLedger(InMemoryContextLedgerPersistence()),
            null,
        )
        val evidence = DecisionEvidence("goal:1", emptyList(), "observation:1", "observation:1", DecisionSource.AI)
        val clickPolicy = request()

        assertEquals(
            ActionCompositionDecision.Blocked("CAPABILITY_MISMATCH"),
            composition.propose(clickPolicy, PhoneToolRequest("action:open", "phone.type"), evidence),
        )
        assertEquals(
            ActionCompositionDecision.Blocked("CAPABILITY_MISMATCH"),
            composition.propose(clickPolicy, PhoneToolRequest("action:open", "phone.home"), evidence),
        )
        assertEquals(
            ActionCompositionDecision.Blocked("ACTION_ID_MISMATCH"),
            composition.propose(clickPolicy, PhoneToolRequest("action:other", "phone.click"), evidence),
        )

        // The one-use grant remains available because all mismatches fail before policy evaluation.
        assertEquals(
            ActionCompositionDecision.Proposed("handoff"),
            composition.propose(clickPolicy, clickRequest(), evidence),
        )
        assertEquals(1, proposals)
    }

    @Test
    fun policyTargetComesOnlyFromTrustedResolverAndNeverFromForgedActionParams() {
        val allowedPackage = "com.cyclone.settings"
        val governor = governorWithGrant(maximumUses = 1, packageName = allowedPackage)
        var proposals = 0
        var trustedTarget: PolicyTarget? = null
        val composition = CycloneV3ActionComposition(
            governor,
            CanonicalPhoneExecutorProposalSink { proposals += 1; "handoff" },
            ContextLedger(InMemoryContextLedgerPersistence()),
            null,
            TrustedPolicyTargetResolver { _, _ -> trustedTarget },
        )
        val evidence = DecisionEvidence("goal:1", emptyList(), "observation:1", "observation:1", DecisionSource.AI)
        val policy = request().copy(
            target = PolicyTarget(packageName = allowedPackage),
        )
        val forgedAllowedPackage = PhoneToolRequest(
            "action:open",
            "phone.click",
            JSONObject().put("package", allowedPackage).put("elementId", "forged:settings"),
        )

        // Caller-controlled params cannot stand in for trusted target resolution.
        assertEquals(
            ActionCompositionDecision.Blocked("TARGET_SCOPE_MISMATCH"),
            composition.propose(policy, forgedAllowedPackage, evidence),
        )
        trustedTarget = PolicyTarget(packageName = "com.attacker.other")
        assertEquals(
            ActionCompositionDecision.Blocked("TARGET_SCOPE_MISMATCH"),
            composition.propose(policy, forgedAllowedPackage, evidence),
        )

        // Both failed attempts occurred before grant consumption. Trusted state is sufficient even
        // when the action proposal deliberately omits package identity.
        trustedTarget = PolicyTarget(packageName = allowedPackage, targetType = "resolved.selector")
        assertEquals(ActionCompositionDecision.Proposed("handoff"), composition.propose(policy, clickRequest(), evidence))
        assertEquals(1, proposals)
    }

    @Test
    fun onlySuccessfulCorrelatedVerificationCanProposeVerifiedMemory() {
        val ledger = ContextLedger(InMemoryContextLedgerPersistence())
        val composition = CycloneV3ActionComposition(
            governorWithGrant(),
            CanonicalPhoneExecutorProposalSink { "unused" },
            ledger,
            memory(),
        )
        val notExecuted = verifiedEvent("event:not-executed", ActionOutcome.NOT_EXECUTED)
        val failed = verifiedEvent("event:failed", ActionOutcome.FAILED)
        val wrongStage = verifiedEvent("event:wrong-stage").copy(
            payload = verifiedEvent("event:wrong-stage").payload.copy(stage = DecisionStage.ACTION_RESULT),
        )
        val missingWitness = verifiedEvent("event:missing-witness").let {
            it.copy(payload = it.payload.copy(actionResult = ActionResultTrace(ActionOutcome.SUCCEEDED)))
        }
        val success = verifiedEvent("event:success")

        assertEquals(
            VerifiedCompletionDecision.Blocked("ACTION_NOT_SUCCEEDED"),
            composition.recordVerifiedCompletion(notExecuted, memoryProposal(notExecuted)),
        )
        assertEquals(
            VerifiedCompletionDecision.Blocked("ACTION_NOT_SUCCEEDED"),
            composition.recordVerifiedCompletion(failed, memoryProposal(failed)),
        )
        assertEquals(
            VerifiedCompletionDecision.Blocked("VERIFICATION_STAGE_REQUIRED"),
            composition.recordVerifiedCompletion(wrongStage, memoryProposal(wrongStage)),
        )
        assertEquals(
            VerifiedCompletionDecision.Blocked("ACTION_WITNESS_REQUIRED"),
            composition.recordVerifiedCompletion(missingWitness, memoryProposal(missingWitness, verifiedEvent("event:missing-witness"))),
        )
        assertEquals(
            VerifiedCompletionDecision.Blocked("MEMORY_PROVENANCE_MISMATCH"),
            composition.recordVerifiedCompletion(success, memoryProposal(success, verifiedEvent("event:other"))),
        )
        assertEquals(0, ledger.query().size)

        assertEquals(
            VerifiedCompletionDecision.Recorded(MemoryProposalStatus.READY),
            composition.recordVerifiedCompletion(success, memoryProposal(success)),
        )
        assertEquals(1, ledger.query().size)
    }

    @Test
    fun staleSelectorAndPolicyDenialNeverReachCanonicalExecutorProposalSeam() {
        var proposals = 0
        val denied = CycloneV3ActionComposition(
            InMemoryPolicyGovernor(PolicyClock { 100 }),
            CanonicalPhoneExecutorProposalSink { proposals += 1; "unexpected" },
            ContextLedger(InMemoryContextLedgerPersistence()),
            null,
        )
        val stale = DecisionEvidence("goal:1", emptyList(), "observation:2", "observation:1", DecisionSource.AI)
        val current = stale.copy(selectorObservationId = "observation:2")

        assertEquals(ActionCompositionDecision.Blocked("STALE_SELECTOR"), denied.propose(request(), PhoneToolRequest("action:open", "phone.click"), stale))
        assertTrue(denied.propose(request(), PhoneToolRequest("action:open", "phone.click"), current) is ActionCompositionDecision.Blocked)
        assertEquals(0, proposals)
    }

    @Test
    fun failuresDegradeIntoExplicitBoundedDirectives() {
        assertEquals(
            DegradationDirective.values().toList(),
            CycloneV3Health(false, false, false, false, false).degradationPlan(),
        )
    }

    private fun governorWithGrant(
        maximumUses: Int = 2,
        packageName: String? = null,
    ) = InMemoryPolicyGovernor(PolicyClock { 100 }).also {
        it.issueGrant(
            AuthorityGrant(
                grantId = "grant:open",
                subject = principal,
                authority = AuthorityClaim(AuthorityOrigin.DIRECT_USER_MISSION, "mission:user"),
                scope = ActionScope(
                    setOf("phone.click"),
                    missionId = "mission:settings",
                    packageName = packageName,
                ),
                allowedRisks = setOf(ActionRisk.ROUTINE),
                issuedAtEpochMillis = 50,
                expiresAtEpochMillis = 1_000,
                maximumUses = maximumUses,
            ),
        )
    }

    private fun request() = PolicyRequest(
        actionId = "action:open",
        capability = "phone.click",
        risk = ActionRisk.ROUTINE,
        principal = PolicyPrincipal(principal),
        requestedAtEpochMillis = 100,
        missionId = "mission:settings",
        grantId = "grant:open",
    )

    private fun clickRequest() = PhoneToolRequest(
        "action:open",
        "phone.click",
        JSONObject().put("selector", JSONObject().put("text", "Settings")),
    )

    private fun verifiedEvent(
        eventId: String = "event:verified",
        outcome: ActionOutcome = ActionOutcome.SUCCEEDED,
    ): ContextEventRequest {
        val actionWitness = EvidenceRef.fromRaw("action_result", "$eventId:action")
        val verificationWitness = EvidenceRef.fromRaw("verification", "$eventId:verification")
        return ContextEventRequest(
        eventId = eventId,
        timestampEpochMillis = 110,
        missionId = "mission:settings",
        payload = ContextDecisionEvent(
            decisionId = "decision:settings",
            stage = DecisionStage.VERIFICATION_RESULT,
            proposedAction = ProposedActionTrace("phone.click"),
            actionResult = ActionResultTrace(outcome, actionWitness),
            verification = VerificationTrace(VerificationStatus.VERIFIED, verificationWitness),
        ),
    )
    }

    private fun memoryProposal(event: ContextEventRequest, evidenceEvent: ContextEventRequest = event) = MemoryWriteProposalRequest(
        "proposal:${event.eventId}",
        MemoryDraft(
            "memory:${event.eventId}",
            1,
            MemoryActor("cyclone.runtime", MemorySourceKind.CYCLONE_RUNTIME),
            MemoryProvenance(
                "context.ledger",
                setOf(
                    evidenceEvent.eventId,
                    evidenceEvent.payload.decisionId,
                    evidenceEvent.payload.proposedAction!!.actionCode,
                    evidenceEvent.payload.actionResult!!.resultRef.toString(),
                    evidenceEvent.payload.verification!!.resultRef.toString(),
                ),
                evidenceEvent.timestampEpochMillis,
            ),
            0.9,
            MemoryVerificationState.VERIFIED,
            MemoryScope(MemoryScopeKind.ROUTINE, "routine:settings"),
            MemorySensitivity.INTERNAL,
            MemoryClass.STRUCTURAL_KNOWLEDGE,
            MemoryContent(mapOf("route" to "settings")),
        ),
    )

    private fun memory() = DefaultCycloneMemoryService(
        TestMemoryProvider(),
        MemoryWritePolicyGate { MemoryPolicyResult(MemoryPolicyDecision.ALLOW, "ALLOW") },
        MemoryApprovalVerifier { _, _ -> false },
        InMemoryMemoryAuditJournal(),
        clock = MemoryClock { 120 },
    )

    private companion object {
        val principal = PrincipalRef("cyclone.agent", PrincipalKind.AI_AGENT)
    }
}
