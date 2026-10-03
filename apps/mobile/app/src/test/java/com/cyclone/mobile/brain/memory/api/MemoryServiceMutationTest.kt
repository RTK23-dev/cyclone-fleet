package com.cyclone.mobile.brain.memory.api

import com.cyclone.mobile.brain.memory.audit.InMemoryMemoryAuditJournal
import com.cyclone.mobile.brain.memory.audit.MemoryAuditDecision
import com.cyclone.mobile.brain.memory.audit.MemoryAuditQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryServiceMutationTest {
    private val actor = MemoryActor("learner.app", MemorySourceKind.APP_LEARNER)
    private val scope = MemoryScope(MemoryScopeKind.ROUTINE, "routine:settings")

    @Test
    fun unsupportedSchemaAndHardBudgetsFailBeforeProviderMutation() {
        val fixture = fixture(
            budgets = MemoryBudgetPolicy(
                maxRecordBytes = 20,
                maxRecordsPerScope = 1,
                supportedSchemaVersions = setOf(1),
            ),
        )
        val unsupported = fixture.service.proposeWrite(
            MemoryWriteProposalRequest("proposal:schema", draft(schemaVersion = 2)),
        )
        val oversized = fixture.service.proposeWrite(
            MemoryWriteProposalRequest(
                "proposal:large",
                draft(recordId = "memory:large", content = mapOf("note" to "this exceeds the hard byte budget")),
            ),
        )

        assertEquals(MemoryProposalStatus.INVALID, unsupported.status)
        assertEquals("UNSUPPORTED_SCHEMA", unsupported.reasonCode)
        assertEquals(MemoryProposalStatus.BUDGET_EXCEEDED, oversized.status)
        assertEquals("RAW_CONTENT_BUDGET", oversized.reasonCode)
        assertEquals(0, fixture.provider.mutationCalls)
    }

    @Test
    fun dedupAndCommitReplayAreDeterministic() {
        val fixture = fixture()
        val first = fixture.service.proposeWrite(MemoryWriteProposalRequest("proposal:first", draft()))
        assertEquals(MemoryProposalStatus.READY, first.status)
        assertEquals(MemoryMutationStatus.COMMITTED, fixture.service.commitApprovedWrite("proposal:first").status)
        assertEquals(1, fixture.provider.mutationCalls)

        val duplicate = fixture.service.proposeWrite(
            MemoryWriteProposalRequest("proposal:duplicate", draft(recordId = "memory:other")),
        )
        val replay = fixture.service.commitApprovedWrite("proposal:first")

        assertEquals(MemoryProposalStatus.DUPLICATE, duplicate.status)
        assertEquals("memory:one", duplicate.duplicateRecord!!.recordId)
        assertEquals(MemoryMutationStatus.REPLAYED, replay.status)
        assertEquals(1, fixture.provider.mutationCalls)
        assertEquals(
            MemoryAuditDecision.REPLAY_REJECTED,
            fixture.service.inspectAudit(MemoryAuditQuery(mutationId = "proposal:first")).first().decision,
        )
    }

    @Test
    fun replaceUsesOptimisticVersioningAndDoesNotRewriteUnchangedContent() {
        val fixture = fixture()
        create(fixture)
        val replaced = fixture.service.replace(
            MemoryReplaceRequest(
                "replace:one",
                expectedVersion = 1,
                draft(content = mapOf("note" to "updated safe memory")),
            ),
        )
        assertEquals(MemoryMutationStatus.COMMITTED, replaced.status)
        assertEquals(2, replaced.record!!.recordVersion)
        assertEquals(100L, replaced.record.createdAtEpochMillis)

        val stale = fixture.service.replace(
            MemoryReplaceRequest(
                "replace:stale",
                expectedVersion = 1,
                draft(content = mapOf("note" to "stale overwrite")),
            ),
        )
        val unchanged = fixture.service.replace(
            MemoryReplaceRequest(
                "replace:unchanged",
                expectedVersion = 2,
                draft(content = mapOf("note" to "updated safe memory")),
            ),
        )

        assertEquals(MemoryMutationStatus.STALE_VERSION, stale.status)
        assertEquals(MemoryMutationStatus.DUPLICATE, unchanged.status)
        assertEquals(2, fixture.provider.mutationCalls)
    }

    @Test
    fun archiveThenRemoveStayInsideServiceAndAdvanceVersion() {
        val fixture = fixture()
        create(fixture)
        val reference = MemoryRecordRef("memory:one", scope)
        val archived = fixture.service.archive(
            MemoryRecordMutationRequest("archive:one", reference, 1, actor),
        )
        assertEquals(MemoryMutationStatus.COMMITTED, archived.status)
        assertTrue(archived.record!!.archived)
        assertEquals(2, archived.record.recordVersion)
        assertTrue(fixture.service.query(MemoryQuery(scope)).isEmpty())
        assertEquals(1, fixture.service.query(MemoryQuery(scope, includeArchived = true)).size)

        val removed = fixture.service.remove(
            MemoryRecordMutationRequest("remove:one", reference, 2, actor),
        )
        assertEquals(MemoryMutationStatus.COMMITTED, removed.status)
        assertNull(fixture.provider.get(reference))
        assertEquals(3, fixture.provider.mutationCalls)
        assertEquals(
            2,
            fixture.service.inspectAudit(MemoryAuditQuery(mutationId = "remove:one")).single().recordVersion,
        )
    }

    @Test
    fun queryAndRecallAreScopeBoundedCappedAndDeterministicallyOrdered() {
        val fixture = fixture(budgets = MemoryBudgetPolicy(maxQueryResults = 2))
        listOf(
            "memory:c" to "display route",
            "memory:a" to "display shortcut",
            "memory:b" to "unrelated note",
        ).forEachIndexed { index, (id, text) ->
            val proposalId = "proposal:q$index"
            fixture.service.proposeWrite(MemoryWriteProposalRequest(proposalId, draft(recordId = id, content = mapOf("note" to text))))
            fixture.service.commitApprovedWrite(proposalId)
        }

        val queried = fixture.service.query(MemoryQuery(scope, limit = 99))
        val recalled = fixture.service.recall(MemoryRecallRequest(scope, setOf("display"), limit = 99))

        assertEquals(2, queried.size)
        assertEquals(listOf("memory:a", "memory:b"), queried.map { it.recordId })
        assertEquals(listOf("memory:a", "memory:c"), recalled.map { it.recordId })
        assertFalse(queried.any { it.scope != scope })
    }

    @Test
    fun currentVerifiedObservationOutranksRecentlyUpdatedStaleHistory() {
        val fixture = fixture()
        val stale = draft(recordId = "memory:stale", content = mapOf("note" to "display stale route")).copy(
            provenance = MemoryProvenance("app.learner", setOf("observation:old"), 10L),
            verificationState = MemoryVerificationState.STALE,
        )
        val current = draft(recordId = "memory:current", content = mapOf("note" to "display current route")).copy(
            provenance = MemoryProvenance("app.learner", setOf("observation:current"), 90L),
            verificationState = MemoryVerificationState.VERIFIED,
        )
        fixture.service.proposeWrite(MemoryWriteProposalRequest("proposal:stale", stale))
        fixture.service.commitApprovedWrite("proposal:stale")
        fixture.service.proposeWrite(MemoryWriteProposalRequest("proposal:current", current))
        fixture.service.commitApprovedWrite("proposal:current")

        assertEquals(
            listOf("memory:current", "memory:stale"),
            fixture.service.recall(MemoryRecallRequest(scope, setOf("display"))).map { it.recordId },
        )
    }

    @Test
    fun scopeBudgetIsAppliedAfterPolicyAndDedup() {
        var policyCalls = 0
        val fixture = fixture(
            budgets = MemoryBudgetPolicy(maxRecordsPerScope = 1),
            policy = MemoryWritePolicyGate {
                policyCalls += 1
                MemoryPolicyResult(MemoryPolicyDecision.ALLOW, "ALLOW")
            },
        )
        create(fixture)
        val second = fixture.service.proposeWrite(
            MemoryWriteProposalRequest(
                "proposal:second",
                draft(recordId = "memory:second", content = mapOf("note" to "different memory")),
            ),
        )

        assertEquals(MemoryProposalStatus.BUDGET_EXCEEDED, second.status)
        assertEquals("SCOPE_RECORD_BUDGET", second.reasonCode)
        assertEquals(3, policyCalls) // propose + commit recheck + second proposal
        assertEquals(1, fixture.provider.mutationCalls)
    }

    private fun create(fixture: Fixture) {
        assertEquals(
            MemoryProposalStatus.READY,
            fixture.service.proposeWrite(MemoryWriteProposalRequest("proposal:create", draft())).status,
        )
        assertEquals(MemoryMutationStatus.COMMITTED, fixture.service.commitApprovedWrite("proposal:create").status)
    }

    private fun fixture(
        budgets: MemoryBudgetPolicy = MemoryBudgetPolicy(),
        policy: MemoryWritePolicyGate = MemoryWritePolicyGate {
            MemoryPolicyResult(MemoryPolicyDecision.ALLOW, "ALLOW")
        },
    ): Fixture {
        val provider = TestMemoryProvider()
        val service = DefaultCycloneMemoryService(
            provider,
            policy,
            MemoryApprovalVerifier { _, _ -> false },
            InMemoryMemoryAuditJournal(),
            budgets = budgets,
            clock = MemoryClock { 100L },
        )
        return Fixture(provider, service)
    }

    private fun draft(
        recordId: String = "memory:one",
        schemaVersion: Int = 1,
        content: Map<String, String> = mapOf("note" to "safe memory"),
    ) = MemoryDraft(
        recordId,
        schemaVersion,
        actor,
        MemoryProvenance("app.learner", setOf("observation:1"), 90L),
        0.8,
        MemoryVerificationState.OBSERVED,
        scope,
        MemorySensitivity.INTERNAL,
        MemoryClass.STRUCTURAL_KNOWLEDGE,
        MemoryContent(content),
    )

    private data class Fixture(
        val provider: TestMemoryProvider,
        val service: DefaultCycloneMemoryService,
    )
}
