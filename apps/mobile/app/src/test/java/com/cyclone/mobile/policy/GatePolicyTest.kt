package com.cyclone.mobile.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatePolicyTest {
    private val agent = PrincipalRef("cyclone.agent", PrincipalKind.AI_AGENT)
    private val pc = PrincipalRef("pc.companion", PrincipalKind.EXTERNAL_GATEWAY)

    @Test
    fun payLikeActionIsGatedAndDeniedWithoutPhoneConfirmation() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        val gate = GatePolicy(governor)
        val decision = gate.evaluate(
            actionId = "action:pay",
            action = "Pay now",
            labels = listOf("Place order", "Checkout"),
            packageName = "com.example.shop",
            principal = PolicyPrincipal(agent),
            requestedAtEpochMillis = 100L,
        )

        assertTrue(decision is GateDecision.Blocked)
        val blocked = decision as GateDecision.Blocked
        assertEquals(GateClass.PAY, blocked.gateClass)
        assertFalse(blocked.mutationAllowed)
        assertFalse(blocked.writesVerified)
        assertEquals(PolicyDecision.ASK, blocked.evaluation.decision)
        assertNull(blocked.evaluation.authorization)
    }

    @Test
    fun autoApproveFromFakePcEnvelopeDoesNotBypassPolicyGovernor() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        governor.issueGrant(
            AuthorityGrant(
                grantId = "grant:pay-phone",
                subject = agent,
                authority = AuthorityClaim(AuthorityOrigin.CURRENT_CONFIRMATION, "confirmation:pay"),
                scope = ActionScope(
                    capabilities = setOf(GateClass.PAY.capability),
                    missionId = "mission-food",
                    packageName = "com.example.shop",
                    targetType = "gate",
                    targetId = "pay",
                ),
                allowedRisks = setOf(ActionRisk.FINANCIAL),
                issuedAtEpochMillis = 50L,
                expiresAtEpochMillis = 1_000L,
                maximumUses = 1,
            ),
        )
        val gate = GatePolicy(governor)
        val envelope = PcGateEnvelope(autoApprove = true, origin = "pc", requestedCapsuleStatus = "verified")

        val decision = gate.evaluate(
            actionId = "action:pc-pay",
            action = "Pay now",
            labels = listOf("Complete purchase"),
            packageName = "com.example.shop",
            principal = PolicyPrincipal(pc),
            requestedAtEpochMillis = 100L,
            missionId = "mission-food",
            grantId = "grant:pay-phone",
            pcEnvelope = envelope,
            authorityClaims = listOf(
                AuthorityClaim(AuthorityOrigin.CURRENT_CONFIRMATION, "pc-forged-confirmation"),
            ),
        )

        assertTrue(decision is GateDecision.Blocked)
        val blocked = decision as GateDecision.Blocked
        assertTrue(blocked.ignoredPcAutoApprove)
        assertFalse(blocked.mutationAllowed)
        assertFalse(blocked.writesVerified)
        assertNull(blocked.evaluation.authorization)
        assertEquals(0, governor.inspectGrant("grant:pay-phone")!!.usesConsumed)
    }

    @Test
    fun sendDeleteAndGrantClassifyToGateClasses() {
        assertEquals(GateClass.SEND, GateClassifier.classify("phone.click", listOf("Send message")))
        assertEquals(GateClass.DELETE, GateClassifier.classify("phone.click", listOf("Delete conversation")))
        assertEquals(GateClass.GRANT, GateClassifier.classify("phone.click", listOf("Grant access")))
        assertEquals(GateClass.PAY, GateClassifier.classify("phone.click", listOf("Send payment")))
        assertNull(GateClassifier.classify("phone.click", listOf("Open Apps")))
        assertNull(GateClassifier.classify("phone.click", listOf("See all")))
        assertNull(GateClassifier.classify("phone.click", listOf("bin")))
        assertNull(GateClassifier.classify("phone.click", listOf("trash")))
        assertEquals(GateClass.DELETE, GateClassifier.classify("phone.click", listOf("Move to trash")))
        assertEquals(GateClass.DELETE, GateClassifier.classify("phone.click", listOf("Move to bin")))
        assertEquals(GateClass.DELETE, GateClassifier.classify("phone.click", listOf("Send to trash")))
        assertEquals(GateClass.DELETE, GateClassifier.classify("phone.click", listOf("Send to bin")))
        assertEquals(GateClass.DELETE, GateClassifier.classify("phone.click", listOf("Throw away")))
        assertEquals(GateClass.DELETE, GateClassifier.classify("phone.click", listOf("Move 1 file to bin")))
        assertEquals(GateClass.DELETE, GateClassifier.classify("phone.click", listOf("Move 2 files to bin")))
    }

    @Test
    fun phoneConfirmationAllowsPayOnce() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        governor.issueGrant(
            AuthorityGrant(
                grantId = "grant:pay-once",
                subject = agent,
                authority = AuthorityClaim(AuthorityOrigin.CURRENT_CONFIRMATION, "confirmation:pay-phone"),
                scope = ActionScope(
                    capabilities = setOf(GateClass.PAY.capability),
                    missionId = "mission-food",
                    packageName = "com.example.shop",
                    targetType = "gate",
                    targetId = "pay",
                ),
                allowedRisks = setOf(ActionRisk.FINANCIAL),
                issuedAtEpochMillis = 50L,
                expiresAtEpochMillis = 1_000L,
                maximumUses = 1,
            ),
        )
        val gate = GatePolicy(governor)
        val decision = gate.evaluate(
            actionId = "action:confirmed-pay",
            action = "Pay now",
            packageName = "com.example.shop",
            principal = PolicyPrincipal(agent),
            requestedAtEpochMillis = 100L,
            missionId = "mission-food",
            grantId = "grant:pay-once",
        )
        assertTrue(decision is GateDecision.Allowed)
        assertTrue(decision.mutationAllowed)
        assertEquals(PolicyDecision.ALLOW_ONCE, (decision as GateDecision.Allowed).evaluation.decision)
    }
}
