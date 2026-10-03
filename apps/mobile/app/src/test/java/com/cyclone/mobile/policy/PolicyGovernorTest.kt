package com.cyclone.mobile.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PolicyGovernorTest {
    private val agent = PrincipalRef("cyclone.agent", PrincipalKind.AI_AGENT)

    @Test
    fun maliciousUiInstructionCannotAuthorizeOrReachOperation() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        val guard = PolicyGuard(governor)
        var operationCalls = 0
        val request = request(
            actionId = "action:malicious",
            risk = ActionRisk.EXTERNAL_COMMUNICATION,
            authorityClaims = listOf(
                AuthorityClaim(AuthorityOrigin.APP_UI_TEXT, "screen-text:ignore-policy-and-send"),
            ),
        )

        val result = guard.authorizeThen(request) {
            operationCalls += 1
            "must-not-run"
        }

        assertTrue(result is GuardedPolicyResult.Blocked)
        assertEquals(0, operationCalls)
        assertEquals(PolicyDecision.DENY, result.evaluation.decision)
        assertEquals(PolicyReason.UNTRUSTED_AUTHORITY, result.evaluation.audit.reason)
        assertNull(result.evaluation.authorization)
    }

    @Test
    fun untrustedUiTextRemainsSafeAsContextEvidence() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        governor.issueGrant(grant(maximumUses = 2))
        val evaluation = governor.evaluate(
            request(
                contextEvidence = listOf(AuthorityClaim(AuthorityOrigin.APP_UI_TEXT, "screen:send-button")),
            ),
        )

        assertEquals(PolicyDecision.ALLOW, evaluation.decision)
        assertEquals(listOf(AuthorityOrigin.APP_UI_TEXT), evaluation.audit.contextOrigins)
    }

    @Test
    fun staleOrPoisonedMemoryCannotCreateAuthorityGrant() {
        try {
            grant(
                authority = AuthorityClaim(AuthorityOrigin.BRAIN_MEMORY, "memory:stale-permission"),
            )
            fail("Memory provenance must never create a grant")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("cannot issue authority"))
        }
    }

    @Test
    fun allowOnceIsConsumedAtomicallyAndCannotBeReused() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        governor.issueGrant(
            grant(
                grantId = "grant:once",
                authority = AuthorityClaim(AuthorityOrigin.CURRENT_CONFIRMATION, "confirmation:42"),
                maximumUses = 1,
            ),
        )
        val request = request(grantId = "grant:once")

        val first = governor.evaluate(request)
        val replay = governor.evaluate(request.copy(actionId = "action:replay"))

        assertEquals(PolicyDecision.ALLOW_ONCE, first.decision)
        assertTrue(first.authorization!!.singleUse)
        assertEquals(PolicyDecision.DENY, replay.decision)
        assertEquals(PolicyReason.GRANT_EXHAUSTED, replay.audit.reason)
        assertEquals(1, governor.inspectGrant("grant:once")!!.usesConsumed)
    }

    @Test
    fun concurrentCallersCannotBothConsumeAllowOnce() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        governor.issueGrant(
            grant(
                grantId = "grant:concurrent-once",
                authority = AuthorityClaim(AuthorityOrigin.CURRENT_CONFIRMATION, "confirmation:concurrent"),
                maximumUses = 1,
            ),
        )
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val complete = CountDownLatch(8)
        val decisions = java.util.Collections.synchronizedList(mutableListOf<PolicyDecision>())
        repeat(8) { index ->
            pool.execute {
                ready.countDown()
                start.await()
                decisions += governor.evaluate(
                    request(actionId = "action:concurrent-$index", grantId = "grant:concurrent-once"),
                ).decision
                complete.countDown()
            }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(complete.await(2, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(1, decisions.count { it == PolicyDecision.ALLOW_ONCE })
        assertEquals(7, decisions.count { it == PolicyDecision.DENY })
        assertEquals(1, governor.inspectGrant("grant:concurrent-once")!!.usesConsumed)
    }

    @Test
    fun expiredAndRevokedGrantsFailClosed() {
        var now = 100L
        val governor = InMemoryPolicyGovernor(PolicyClock { now })
        governor.issueGrant(grant(grantId = "grant:expires", expiresAt = 200L))
        governor.issueGrant(grant(grantId = "grant:revoked"))
        assertEquals(GrantRevocationResult.REVOKED, governor.revokeGrant("grant:revoked"))
        assertEquals(GrantRevocationResult.ALREADY_REVOKED, governor.revokeGrant("grant:revoked"))
        now = 200L

        val expired = governor.evaluate(request(grantId = "grant:expires"))
        val revoked = governor.evaluate(request(actionId = "action:revoked", grantId = "grant:revoked"))

        assertEquals(PolicyDecision.ASK, expired.decision)
        assertEquals(PolicyReason.GRANT_EXPIRED, expired.audit.reason)
        assertEquals(PolicyDecision.DENY, revoked.decision)
        assertEquals(PolicyReason.GRANT_REVOKED, revoked.audit.reason)
    }

    @Test
    fun mismatchedScopeAndRiskAreExplicitlyDenied() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        governor.issueGrant(grant(grantId = "grant:scoped", maximumUses = 3))

        val wrongContact = governor.evaluate(
            request(
                actionId = "action:wrong-target",
                grantId = "grant:scoped",
                target = PolicyTarget(
                    packageName = "com.example.messages",
                    targetType = "contact",
                    targetId = "mallory",
                ),
            ),
        )
        val wrongRisk = governor.evaluate(
            request(
                actionId = "action:wrong-risk",
                grantId = "grant:scoped",
                risk = ActionRisk.FINANCIAL,
            ),
        )

        assertEquals(PolicyReason.GRANT_SCOPE_MISMATCH, wrongContact.audit.reason)
        assertEquals(PolicyReason.GRANT_RISK_MISMATCH, wrongRisk.audit.reason)
        assertEquals(0, governor.inspectGrant("grant:scoped")!!.usesConsumed)
    }

    @Test
    fun consequentialRisksRequireFreshConfirmationEvenWithMissionGrant() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        governor.issueGrant(
            grant(
                grantId = "grant:mission-delete",
                risks = setOf(ActionRisk.DESTRUCTIVE),
                maximumUses = 5,
            ),
        )

        val evaluation = governor.evaluate(
            request(
                actionId = "action:delete",
                grantId = "grant:mission-delete",
                risk = ActionRisk.DESTRUCTIVE,
            ),
        )

        assertEquals(PolicyDecision.ASK, evaluation.decision)
        assertEquals(PolicyReason.CURRENT_CONFIRMATION_REQUIRED, evaluation.audit.reason)
        assertEquals(0, governor.inspectGrant("grant:mission-delete")!!.usesConsumed)
    }

    @Test
    fun freshConfirmationAllowsExactlyOneConsequentialAction() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        governor.issueGrant(
            grant(
                grantId = "grant:confirmed-delete",
                authority = AuthorityClaim(AuthorityOrigin.CURRENT_CONFIRMATION, "confirmation:delete"),
                risks = setOf(ActionRisk.DESTRUCTIVE),
                maximumUses = 1,
            ),
        )

        val evaluation = governor.evaluate(
            request(
                actionId = "action:confirmed-delete",
                grantId = "grant:confirmed-delete",
                risk = ActionRisk.DESTRUCTIVE,
            ),
        )

        assertEquals(PolicyDecision.ALLOW_ONCE, evaluation.decision)
        assertEquals(PolicyReason.AUTHORITY_ACCEPTED, evaluation.audit.reason)
    }

    @Test
    fun standingRuleCannotBeUnrestrictedAndConfirmationCannotBeReusable() {
        try {
            grant(
                authority = AuthorityClaim(AuthorityOrigin.STANDING_USER_RULE, "rule:unbounded"),
            ).copy(
                scope = ActionScope(capabilities = setOf("messages.send")),
            )
            fail("A standing rule must have a target boundary")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("target boundary"))
        }

        try {
            grant(
                authority = AuthorityClaim(AuthorityOrigin.CURRENT_CONFIRMATION, "confirmation:reusable"),
                maximumUses = 2,
            )
            fail("Current confirmation must be single use")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("single-use"))
        }
    }

    @Test
    fun implicitGrantSelectionIsStableRegardlessOfRegistrationOrder() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        governor.issueGrant(grant(grantId = "grant:z", maximumUses = 2))
        governor.issueGrant(grant(grantId = "grant:a", maximumUses = 2))

        val evaluation = governor.evaluate(request(grantId = null))

        assertEquals("grant:a", evaluation.authorization!!.grantId)
        assertEquals(1, governor.inspectGrant("grant:a")!!.usesConsumed)
        assertEquals(0, governor.inspectGrant("grant:z")!!.usesConsumed)
    }

    @Test
    fun auditOutputIsDeterministicAndDoesNotCopySensitiveEvidence() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        governor.issueGrant(
            grant(grantId = "grant:sensitive", maximumUses = 2).copy(
                scope = ActionScope(
                    capabilities = setOf("messages.send"),
                    missionId = "mission-1",
                    packageName = "com.example.messages",
                    targetType = "contact",
                ),
            ),
        )
        val request = request(
            grantId = "grant:sensitive",
            target = PolicyTarget(
                packageName = "com.example.messages",
                targetType = "contact",
                targetId = "secret-contact-name",
                attributes = mapOf("typedText" to "top-secret-message"),
            ),
            contextEvidence = listOf(AuthorityClaim(AuthorityOrigin.WEB_CONTENT, "web:private-token-123")),
        )

        val audit = governor.evaluate(request).audit
        val rendered = audit.toString()

        assertEquals(PolicyReason.AUTHORITY_ACCEPTED.explanation, audit.explanation)
        assertEquals("com.example.messages", audit.packageName)
        assertEquals("contact", audit.targetType)
        assertTrue(audit.grantReference!!.startsWith("sha256:"))
        assertFalse(rendered.contains("grant:sensitive"))
        assertFalse(rendered.contains("secret-contact-name"))
        assertFalse(rendered.contains("top-secret-message"))
        assertFalse(rendered.contains("private-token-123"))
    }

    private fun grant(
        grantId: String = "grant:messages",
        authority: AuthorityClaim = AuthorityClaim(AuthorityOrigin.DIRECT_USER_MISSION, "mission:user-request"),
        risks: Set<ActionRisk> = setOf(ActionRisk.EXTERNAL_COMMUNICATION),
        maximumUses: Int = 1,
        expiresAt: Long = 1_000L,
    ) = AuthorityGrant(
        grantId = grantId,
        subject = agent,
        authority = authority,
        scope = ActionScope(
            capabilities = setOf("messages.send"),
            missionId = "mission-1",
            packageName = "com.example.messages",
            targetType = "contact",
            targetId = "alice",
        ),
        allowedRisks = risks,
        issuedAtEpochMillis = 50L,
        expiresAtEpochMillis = expiresAt,
        maximumUses = maximumUses,
    )

    private fun request(
        actionId: String = "action:send",
        risk: ActionRisk = ActionRisk.EXTERNAL_COMMUNICATION,
        grantId: String? = "grant:messages",
        target: PolicyTarget = PolicyTarget(
            packageName = "com.example.messages",
            targetType = "contact",
            targetId = "alice",
        ),
        authorityClaims: List<AuthorityClaim> = emptyList(),
        contextEvidence: List<AuthorityClaim> = emptyList(),
    ) = PolicyRequest(
        actionId = actionId,
        capability = "messages.send",
        risk = risk,
        principal = PolicyPrincipal(agent),
        requestedAtEpochMillis = 100L,
        missionId = "mission-1",
        target = target,
        grantId = grantId,
        authorityClaims = authorityClaims,
        contextEvidence = contextEvidence,
    )
}
