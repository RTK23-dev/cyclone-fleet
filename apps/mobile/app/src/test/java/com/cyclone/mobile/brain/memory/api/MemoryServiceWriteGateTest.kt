package com.cyclone.mobile.brain.memory.api

import com.cyclone.mobile.brain.memory.audit.InMemoryMemoryAuditJournal
import com.cyclone.mobile.brain.memory.audit.MemoryAuditDecision
import com.cyclone.mobile.brain.memory.audit.MemoryAuditQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryServiceWriteGateTest {
    private val actor = MemoryActor("agent.memory-producer", MemorySourceKind.AI_PROPOSAL)
    private val user = MemoryActor("user.local", MemorySourceKind.USER)
    private val scope = MemoryScope(MemoryScopeKind.APP, "com.example.app")

    @Test
    fun deniedWriteNeverMutatesProviderAndLeavesSafeAuditEvidence() {
        val fixture = fixture(policyDecision = MemoryPolicyDecision.DENY)
        val result = fixture.service.proposeWrite(
            MemoryWriteProposalRequest(
                "proposal:denied",
                draft(
                    content = mapOf(
                        "summary" to "safe operational note",
                        "password" to "do-not-persist-this-password",
                    ),
                ),
            ),
        )

        assertEquals(MemoryProposalStatus.DENIED, result.status)
        assertEquals(0, fixture.provider.mutationCalls)
        assertNull(fixture.provider.get(MemoryRecordRef("memory:one", scope)))
        val audit = fixture.service.inspectAudit(MemoryAuditQuery(mutationId = "proposal:denied")).single()
        assertEquals(MemoryAuditDecision.DENIED, audit.decision)
        assertEquals(1, audit.redactedFieldCount)
        val rendered = audit.toString()
        assertFalse(rendered.contains("do-not-persist-this-password"))
        assertFalse(rendered.contains("safe operational note"))
        assertFalse(rendered.contains(actor.actorId))
        assertTrue(audit.actorReference.startsWith("sha256:"))
    }

    @Test
    fun requiredApprovalIsVerifiedBeforeRedactedRecordCanCommit() {
        val fixture = fixture(policyDecision = MemoryPolicyDecision.REQUIRE_APPROVAL)
        val proposed = fixture.service.proposeWrite(
            MemoryWriteProposalRequest(
                "proposal:approval",
                draft(
                    sensitivity = MemorySensitivity.SENSITIVE,
                    content = mapOf(
                        "route" to "settings > display",
                        "oneTimeCode" to "123456",
                        "typedText" to "private typed value",
                    ),
                ),
            ),
        )
        assertEquals(MemoryProposalStatus.APPROVAL_REQUIRED, proposed.status)
        assertEquals(0, fixture.provider.mutationCalls)

        val withoutApproval = fixture.service.commitApprovedWrite("proposal:approval")
        assertEquals(MemoryMutationStatus.DENIED, withoutApproval.status)
        assertEquals(0, fixture.provider.mutationCalls)

        val committed = fixture.service.commitApprovedWrite(
            "proposal:approval",
            MemoryMutationApproval("proposal:approval", user, 100L, 200L),
        )
        assertEquals(MemoryMutationStatus.COMMITTED, committed.status)
        assertEquals(1, fixture.provider.mutationCalls)
        assertEquals(mapOf("route" to "settings > display"), committed.record!!.content.fields)
        assertFalse(committed.record.content.toString().contains("123456"))
        assertFalse(committed.record.content.toString().contains("private typed value"))
    }

    @Test
    fun approvalRequiredAtProposalCannotBeDowngradedAtCommit() {
        var policyCalls = 0
        val fixture = fixture(policy = MemoryWritePolicyGate {
            policyCalls += 1
            val decision = if (policyCalls == 1) {
                MemoryPolicyDecision.REQUIRE_APPROVAL
            } else {
                MemoryPolicyDecision.ALLOW
            }
            MemoryPolicyResult(decision, "TEST_${decision.name}")
        })
        assertEquals(
            MemoryProposalStatus.APPROVAL_REQUIRED,
            fixture.service.proposeWrite(MemoryWriteProposalRequest("proposal:sticky", draft())).status,
        )

        val result = fixture.service.commitApprovedWrite("proposal:sticky")

        assertEquals(MemoryMutationStatus.DENIED, result.status)
        assertEquals("APPROVAL_REQUIRED", result.reasonCode)
        assertEquals(0, fixture.provider.mutationCalls)
    }

    @Test
    fun restrictedOrOnlySensitiveContentIsRejectedBeforePolicyAndProvider() {
        var policyCalls = 0
        val fixture = fixture(policy = MemoryWritePolicyGate {
            policyCalls += 1
            MemoryPolicyResult(MemoryPolicyDecision.ALLOW, "ALLOW")
        })
        val restricted = fixture.service.proposeWrite(
            MemoryWriteProposalRequest(
                "proposal:restricted",
                draft(sensitivity = MemorySensitivity.RESTRICTED),
            ),
        )
        val onlySecret = fixture.service.proposeWrite(
            MemoryWriteProposalRequest(
                "proposal:only-secret",
                draft(recordId = "memory:secret", content = mapOf("api_token" to "sk-private-value")),
            ),
        )

        assertEquals(MemoryProposalStatus.DENIED, restricted.status)
        assertEquals("RESTRICTED_CONTENT", restricted.reasonCode)
        assertEquals(MemoryProposalStatus.DENIED, onlySecret.status)
        assertEquals("NO_SAFE_CONTENT", onlySecret.reasonCode)
        assertEquals(0, policyCalls)
        assertEquals(0, fixture.provider.mutationCalls)
    }

    @Test
    fun policyGateReceivesOnlyMetadataNotRawContent() {
        var captured: MemoryPolicyRequest? = null
        val fixture = fixture(policy = MemoryWritePolicyGate {
            captured = it
            MemoryPolicyResult(MemoryPolicyDecision.DENY, "TEST_DENY")
        })
        fixture.service.proposeWrite(
            MemoryWriteProposalRequest(
                "proposal:metadata",
                draft(content = mapOf("note" to "private narrative", "token" to "secret-token-value")),
            ),
        )

        val rendered = captured.toString()
        assertFalse(rendered.contains("private narrative"))
        assertFalse(rendered.contains("secret-token-value"))
        assertEquals(1, captured!!.redactedFieldCount)
    }

    @Test
    fun providerContractHasOneExplicitMutationBoundary() {
        val mutationMethods = MemoryStoreProvider::class.java.declaredMethods.filter {
            it.name == "apply"
        }
        assertEquals(1, mutationMethods.size)
        assertEquals(AuthorizedMemoryMutation::class.java, mutationMethods.single().parameterTypes.single())
        assertFalse(MemoryStoreProvider::class.java.declaredMethods.any { it.name in setOf("insert", "replace", "remove", "archive") })
    }

    private fun fixture(
        policyDecision: MemoryPolicyDecision = MemoryPolicyDecision.ALLOW,
        policy: MemoryWritePolicyGate = MemoryWritePolicyGate {
            MemoryPolicyResult(policyDecision, "TEST_${policyDecision.name}")
        },
        budgets: MemoryBudgetPolicy = MemoryBudgetPolicy(),
    ): Fixture {
        val provider = TestMemoryProvider()
        val audit = InMemoryMemoryAuditJournal()
        val service = DefaultCycloneMemoryService(
            provider,
            policy,
            MemoryApprovalVerifier { approval, _ -> approval.approvedBy.sourceKind == MemorySourceKind.USER },
            audit,
            budgets = budgets,
            clock = MemoryClock { 100L },
        )
        return Fixture(provider, service)
    }

    private fun draft(
        recordId: String = "memory:one",
        schemaVersion: Int = 1,
        sensitivity: MemorySensitivity = MemorySensitivity.INTERNAL,
        content: Map<String, String> = mapOf("note" to "safe memory"),
    ) = MemoryDraft(
        recordId,
        schemaVersion,
        actor,
        MemoryProvenance("ai.trace", setOf("event:1"), 90L),
        0.7,
        MemoryVerificationState.OBSERVED,
        scope,
        sensitivity,
        MemoryClass.RUNTIME_HINT,
        MemoryContent(content),
    )

    private data class Fixture(
        val provider: TestMemoryProvider,
        val service: DefaultCycloneMemoryService,
    )
}
