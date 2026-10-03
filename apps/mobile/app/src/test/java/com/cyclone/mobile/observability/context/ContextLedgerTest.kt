package com.cyclone.mobile.observability.context

import com.cyclone.mobile.observability.events.ActionOutcome
import com.cyclone.mobile.observability.events.ActionResultTrace
import com.cyclone.mobile.observability.events.AiNecessityReason
import com.cyclone.mobile.observability.events.ContextDecisionEvent
import com.cyclone.mobile.observability.events.ContextEventRequest
import com.cyclone.mobile.observability.events.ContextPayloadBudget
import com.cyclone.mobile.observability.events.ContextPrivacy
import com.cyclone.mobile.observability.events.ContextSourceEvidence
import com.cyclone.mobile.observability.events.ContextSourceKind
import com.cyclone.mobile.observability.events.DecisionStage
import com.cyclone.mobile.observability.events.EvidenceRef
import com.cyclone.mobile.observability.events.LatencyTrace
import com.cyclone.mobile.observability.events.ModelTrace
import com.cyclone.mobile.observability.events.PolicyState
import com.cyclone.mobile.observability.events.PolicyTrace
import com.cyclone.mobile.observability.events.ProposedActionTrace
import com.cyclone.mobile.observability.events.RecoveryStatus
import com.cyclone.mobile.observability.events.RecoveryTrace
import com.cyclone.mobile.observability.events.VerificationStatus
import com.cyclone.mobile.observability.events.VerificationTrace
import com.cyclone.mobile.observability.events.VisionReason
import com.cyclone.mobile.observability.events.VisionTrace
import com.cyclone.mobile.platform.event.DataClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ContextLedgerTest {
    @Test
    fun `full causal chain is correlated and answers explainability questions`() {
        val ledger = ContextLedger(InMemoryContextLedgerPersistence())
        val decision = "decision-full"
        val sources = ContextSourceKind.values().mapIndexed { index, kind ->
            ContextSourceEvidence(
                source = kind,
                byteCount = 100 + index,
                estimatedTokens = 25 + index,
                evidenceRefs = listOf(ref("source", kind.name)),
                stale = kind == ContextSourceKind.BRAIN,
            )
        }
        append(ledger, decision, DecisionStage.STARTED, 10, aiReason = AiNecessityReason.NO_VERIFIED_ROUTE)
        append(
            ledger, decision, DecisionStage.CONTEXT_ASSEMBLED, 20,
            goal = ContextPrivacy.redactText("goal", "open the requested screen"),
            sources = sources, knowledge = listOf(ref("knowledge", "doc 4")),
        )
        append(
            ledger, decision, DecisionStage.POLICY_EVALUATED, 25,
            policy = PolicyTrace(PolicyState.ALLOW_ONCE, ref("policy", "grant 1"), "grant.single-use"),
        )
        append(
            ledger, decision, DecisionStage.MODEL_INVOKED, 30,
            model = ModelTrace("approved-provider", "reasoning-model", ref("request", "body")),
            vision = VisionTrace(true, VisionReason.STRUCTURED_EVIDENCE_INSUFFICIENT, "approved-vision", listOf(ref("image", "shot")), 1),
        )
        append(ledger, decision, DecisionStage.ACTION_PROPOSED, 40, action = ProposedActionTrace("phone.tap", ref("target", "button")))
        append(ledger, decision, DecisionStage.ACTION_RESULT, 50, result = ActionResultTrace(ActionOutcome.SUCCEEDED, ref("result", "after")))
        append(ledger, decision, DecisionStage.VERIFICATION_RESULT, 60, verification = VerificationTrace(VerificationStatus.VERIFIED, ref("verification", "page changed")))
        append(ledger, decision, DecisionStage.COMPLETED, 70, latency = LatencyTrace(5, 20, 10, 7, 42))

        val diagnostic = requireNotNull(ledger.diagnostic(decision))
        assertEquals(AiNecessityReason.NO_VERIFIED_ROUTE, diagnostic.whyAi)
        assertTrue(requireNotNull(diagnostic.whyVision).used)
        assertEquals(ContextSourceKind.values().toSet(), diagnostic.suppliedEvidence.map { it.source }.toSet())
        assertEquals(1, diagnostic.influentialKnowledge.size)
        assertEquals(PolicyState.ALLOW_ONCE, diagnostic.policy?.state)
        assertTrue(diagnostic.wasVerified)
        assertTrue(diagnostic.complete)
        assertEquals(8, diagnostic.eventCount)
        assertEquals(decision, ledger.query(ContextLedgerQuery(correlationId = decision)).first().correlationId)
    }

    @Test
    fun `query ordering and replay are deterministic`() {
        val ledger = ContextLedger(InMemoryContextLedgerPersistence())
        append(ledger, "decision-order", DecisionStage.STARTED, 100, eventId = "event-z")
        append(ledger, "decision-order", DecisionStage.CONTEXT_ASSEMBLED, 100, eventId = "event-a")
        val replay = append(ledger, "decision-order", DecisionStage.STARTED, 100, eventId = "event-z")

        assertTrue(replay.replayed)
        assertEquals(listOf("event-a", "event-z"), ledger.query().map { it.eventId })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `event id cannot be replayed with different evidence`() {
        val ledger = ContextLedger(InMemoryContextLedgerPersistence())
        append(ledger, "decision-replay", DecisionStage.STARTED, 1, eventId = "same-event")
        append(ledger, "decision-replay", DecisionStage.FAILED, 1, eventId = "same-event")
    }

    @Test
    fun `raw goal editable text target and exception never enter event or persistence`() {
        val persistence = InMemoryContextLedgerPersistence()
        val ledger = ContextLedger(persistence)
        val secret = "password=hunter2 otp=987654 bearer=sk-secret-key"
        append(
            ledger, "decision-secret", DecisionStage.FAILED, 1,
            goal = ContextPrivacy.redactText("goal", secret),
            action = ProposedActionTrace("phone.type", EvidenceRef.fromRaw("target", secret), setOf("text")),
            failure = ContextPrivacy.sanitizeFailure("provider.failure", secret),
            sources = listOf(
                ContextSourceEvidence(
                    ContextSourceKind.PAGE_AWARENESS,
                    byteCount = secret.length,
                    estimatedTokens = 12,
                    classification = DataClassification.RESTRICTED,
                    redactedValueCount = 3,
                ),
            ),
        )

        val stored = persistence.load().single()
        val rendered = stored.toString()
        assertFalse(rendered.contains(secret))
        assertFalse(rendered.contains("hunter2"))
        assertFalse(rendered.contains("987654"))
        val guessableDigest = MessageDigest.getInstance("SHA-256")
            .digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertFalse(rendered.contains(guessableDigest))
        assertEquals(EvidenceRef.omitted("goal"), stored.payload.goal?.reference)
        assertEquals(EvidenceRef.omitted("failure"), stored.payload.failure?.messageFingerprint)
        assertTrue(stored.redaction.containsSensitiveData)
        assertEquals(DataClassification.RESTRICTED, stored.redaction.classification)
        assertTrue("payload.goal" in stored.redaction.redactedFields)
        assertEquals(secret.length, stored.payload.failure?.messageCharacterCount)
    }

    @Test
    fun `source and reference budgets truncate deterministically`() {
        val persistence = InMemoryContextLedgerPersistence()
        val ledger = ContextLedger(
            persistence,
            payloadBudget = ContextPayloadBudget(maxSources = 2, maxEvidenceRefsPerSource = 1, maxKnowledgeRefs = 1),
        )
        val sources = listOf(ContextSourceKind.VISION_EVIDENCE, ContextSourceKind.GOAL, ContextSourceKind.APP_GRAPH).map { kind ->
            ContextSourceEvidence(kind, 10, 3, listOf(ref("evidence", "$kind-2"), ref("evidence", "$kind-1")))
        }
        append(
            ledger, "decision-budget", DecisionStage.CONTEXT_ASSEMBLED, 1,
            sources = sources,
            knowledge = listOf(ref("knowledge", "b"), ref("knowledge", "a")),
        )

        val payload = persistence.load().single().payload
        assertEquals(listOf(ContextSourceKind.APP_GRAPH, ContextSourceKind.GOAL), payload.contextSources.map { it.source })
        assertTrue(payload.contextSources.all { it.evidenceRefs.size == 1 })
        assertEquals(1, payload.knowledgeRefs.size)
        assertEquals(1, payload.truncation.droppedSources)
        assertEquals(2, payload.truncation.droppedEvidenceRefs)
        assertEquals(1, payload.truncation.droppedKnowledgeRefs)
    }

    @Test
    fun `partial decisions remain diagnosable and explicitly list missing stages`() {
        val ledger = ContextLedger(InMemoryContextLedgerPersistence())
        append(
            ledger, "decision-partial", DecisionStage.POLICY_EVALUATED, 1,
            policy = PolicyTrace(PolicyState.DENY, ref("policy", "denied"), "scope.mismatch"),
        )

        val diagnostic = requireNotNull(ledger.diagnostic("decision-partial"))
        assertFalse(diagnostic.complete)
        assertEquals(PolicyState.DENY, diagnostic.policy?.state)
        assertTrue(DecisionStage.STARTED in diagnostic.missingCoreStages)
        assertTrue(DecisionStage.VERIFICATION_RESULT in diagnostic.missingCoreStages)
        assertNull(diagnostic.model)
    }

    @Test
    fun `latency and recovery survive compact summary`() {
        val ledger = ContextLedger(InMemoryContextLedgerPersistence())
        append(ledger, "decision-recovery", DecisionStage.STARTED, 1, aiReason = AiNecessityReason.RECOVERY_NEEDED)
        append(
            ledger, "decision-recovery", DecisionStage.RECOVERY_RESULT, 2,
            latency = LatencyTrace(contextMillis = 4, modelMillis = 9, actionMillis = 3, verificationMillis = 2, totalMillis = 18),
            recovery = RecoveryTrace(RecoveryStatus.RECOVERED, "reobserve", ref("recovery", "ok")),
        )
        append(ledger, "decision-recovery", DecisionStage.FAILED, 3, failure = ContextPrivacy.sanitizeFailure("route.failed", "private UI text"))

        val compact = ledger.compactDiagnostics().single()
        assertEquals(18, compact.latency.totalMillis)
        assertEquals(RecoveryStatus.RECOVERED, compact.recovery?.status)
        assertNotNull(compact.failure?.messageFingerprint)
    }

    @Test
    fun `bounded persistence supports service restart without losing stable order`() {
        val persistence = InMemoryContextLedgerPersistence()
        val retention = ContextLedgerRetention(maxEvents = 3, maxDecisions = 2, maxEventsPerDecision = 2, maxEstimatedBytes = 4_096)
        var ledger = ContextLedger(persistence, retention)
        append(ledger, "decision-old", DecisionStage.STARTED, 1)
        append(ledger, "decision-middle", DecisionStage.STARTED, 2)
        append(ledger, "decision-new", DecisionStage.STARTED, 3)
        append(ledger, "decision-new", DecisionStage.COMPLETED, 4)

        assertNull(ledger.diagnostic("decision-old"))
        ledger = ContextLedger(persistence, retention)
        assertEquals(listOf("decision-middle", "decision-new", "decision-new"), ledger.query().map { it.payload.decisionId })
        assertEquals(2, ledger.diagnostic("decision-new")?.eventCount)
    }

    private fun ref(namespace: String, raw: String): EvidenceRef = EvidenceRef.fromRaw(namespace, raw)

    private fun append(
        ledger: ContextLedger,
        decisionId: String,
        stage: DecisionStage,
        timestamp: Long,
        eventId: String = "event-$timestamp-${stage.name.lowercase()}",
        goal: com.cyclone.mobile.observability.events.RedactedTextDigest? = null,
        sources: List<ContextSourceEvidence> = emptyList(),
        knowledge: List<EvidenceRef> = emptyList(),
        policy: PolicyTrace? = null,
        vision: VisionTrace? = null,
        model: ModelTrace? = null,
        aiReason: AiNecessityReason? = null,
        action: ProposedActionTrace? = null,
        result: ActionResultTrace? = null,
        verification: VerificationTrace? = null,
        latency: LatencyTrace? = null,
        recovery: RecoveryTrace? = null,
        failure: com.cyclone.mobile.observability.events.SanitizedFailure? = null,
    ) = ledger.append(
        ContextEventRequest(
            eventId = eventId,
            timestampEpochMillis = timestamp,
            missionId = "mission-test",
            sessionId = "session-test",
            payload = ContextDecisionEvent(
                decisionId = decisionId,
                stage = stage,
                appPackage = "com.example.app",
                pageRef = ref("page", "home"),
                routeRef = ref("route", "route"),
                goal = goal,
                contextSources = sources,
                knowledgeRefs = knowledge,
                policy = policy,
                vision = vision,
                model = model,
                aiReason = aiReason,
                proposedAction = action,
                actionResult = result,
                verification = verification,
                latency = latency,
                recovery = recovery,
                failure = failure,
            ),
        ),
    )
}
