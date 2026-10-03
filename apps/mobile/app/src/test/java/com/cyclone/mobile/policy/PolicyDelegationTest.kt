package com.cyclone.mobile.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyDelegationTest {
    private val orchestrator = PrincipalRef("cyclone.orchestrator", PrincipalKind.AI_AGENT)
    private val worker = PrincipalRef("cyclone.worker", PrincipalKind.DELEGATED_AGENT)

    @Test
    fun narrowContiguousDelegationCanUseParentAuthority() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        val parentScope = parentScope()
        val childScope = parentScope.copy(targetId = "alice", attributes = mapOf("channel" to "sms"))
        governor.issueGrant(grant(parentScope))

        val evaluation = governor.evaluate(
            request(
                DelegationChain(listOf(DelegationLink(orchestrator, worker, childScope))),
            ),
        )

        assertEquals(PolicyDecision.ALLOW, evaluation.decision)
        assertEquals(1, evaluation.audit.delegationDepth)
    }

    @Test
    fun delegatedAgentCannotWidenParentScope() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        val parentScope = parentScope()
        val widened = parentScope.copy(packageName = null, targetId = null)
        governor.issueGrant(grant(parentScope))

        val evaluation = governor.evaluate(
            request(
                DelegationChain(listOf(DelegationLink(orchestrator, worker, widened))),
            ),
        )

        assertEquals(PolicyDecision.DENY, evaluation.decision)
        assertEquals(PolicyReason.DELEGATION_INVALID, evaluation.audit.reason)
        assertEquals(0, governor.inspectGrant("grant:delegate")!!.usesConsumed)
    }

    @Test
    fun brokenOrCyclicDelegationIsDenied() {
        val governor = InMemoryPolicyGovernor(PolicyClock { 100L })
        val parentScope = parentScope()
        val intermediate = PrincipalRef("cyclone.middle", PrincipalKind.DELEGATED_AGENT)
        governor.issueGrant(grant(parentScope))

        val broken = governor.evaluate(
            request(
                DelegationChain(listOf(DelegationLink(intermediate, worker, parentScope))),
            ),
        )
        val cyclic = governor.evaluate(
            request(
                DelegationChain(
                    listOf(
                        DelegationLink(orchestrator, intermediate, parentScope),
                        DelegationLink(intermediate, orchestrator, parentScope),
                    ),
                ),
            ),
        )

        assertEquals(PolicyReason.DELEGATION_INVALID, broken.audit.reason)
        assertEquals(PolicyReason.DELEGATION_INVALID, cyclic.audit.reason)
    }

    private fun parentScope() = ActionScope(
        capabilities = setOf("messages.send"),
        missionId = "mission-1",
        packageName = "com.example.messages",
        targetType = "contact",
        targetId = "alice",
    )

    private fun grant(scope: ActionScope) = AuthorityGrant(
        grantId = "grant:delegate",
        subject = orchestrator,
        authority = AuthorityClaim(AuthorityOrigin.DIRECT_USER_MISSION, "mission:user-request"),
        scope = scope,
        allowedRisks = setOf(ActionRisk.EXTERNAL_COMMUNICATION),
        issuedAtEpochMillis = 50L,
        expiresAtEpochMillis = 1_000L,
        maximumUses = 3,
    )

    private fun request(chain: DelegationChain) = PolicyRequest(
        actionId = "action:delegated-send",
        capability = "messages.send",
        risk = ActionRisk.EXTERNAL_COMMUNICATION,
        principal = PolicyPrincipal(worker, chain),
        requestedAtEpochMillis = 100L,
        missionId = "mission-1",
        target = PolicyTarget(
            packageName = "com.example.messages",
            targetType = "contact",
            targetId = "alice",
            attributes = mapOf("channel" to "sms"),
        ),
        grantId = "grant:delegate",
    )
}
