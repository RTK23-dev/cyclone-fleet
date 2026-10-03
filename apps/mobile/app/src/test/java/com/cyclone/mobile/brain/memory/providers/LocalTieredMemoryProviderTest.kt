package com.cyclone.mobile.brain.memory.providers

import com.cyclone.mobile.brain.memory.api.DefaultCycloneMemoryService
import com.cyclone.mobile.brain.memory.api.MemoryActor
import com.cyclone.mobile.brain.memory.api.MemoryApprovalVerifier
import com.cyclone.mobile.brain.memory.api.MemoryBudgetPolicy
import com.cyclone.mobile.brain.memory.api.MemoryClass
import com.cyclone.mobile.brain.memory.api.MemoryClock
import com.cyclone.mobile.brain.memory.api.MemoryContent
import com.cyclone.mobile.brain.memory.api.MemoryDraft
import com.cyclone.mobile.brain.memory.api.MemoryMutationStatus
import com.cyclone.mobile.brain.memory.api.MemoryPolicyDecision
import com.cyclone.mobile.brain.memory.api.MemoryPolicyResult
import com.cyclone.mobile.brain.memory.api.MemoryProvenance
import com.cyclone.mobile.brain.memory.api.MemoryProposalStatus
import com.cyclone.mobile.brain.memory.api.MemoryQuery
import com.cyclone.mobile.brain.memory.api.MemoryRecallRequest
import com.cyclone.mobile.brain.memory.api.MemoryRecordRef
import com.cyclone.mobile.brain.memory.api.MemoryReplaceRequest
import com.cyclone.mobile.brain.memory.api.MemoryScope
import com.cyclone.mobile.brain.memory.api.MemoryScopeKind
import com.cyclone.mobile.brain.memory.api.MemorySensitivity
import com.cyclone.mobile.brain.memory.api.MemorySourceKind
import com.cyclone.mobile.brain.memory.api.MemoryVerificationState
import com.cyclone.mobile.brain.memory.api.MemoryWritePolicyGate
import com.cyclone.mobile.brain.memory.api.MemoryWriteProposalRequest
import com.cyclone.mobile.brain.memory.audit.InMemoryMemoryAuditJournal
import com.cyclone.mobile.brain.memory.tiered.AppGraphMemoryReference
import com.cyclone.mobile.brain.memory.tiered.MemoryTier
import com.cyclone.mobile.brain.memory.tiered.TierBudget
import com.cyclone.mobile.brain.memory.tiered.TieredFreshnessPolicy
import com.cyclone.mobile.brain.memory.tiered.TieredMemoryBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class LocalTieredMemoryProviderTest {
    private val sessionScope = MemoryScope(MemoryScopeKind.SESSION, "mission:one")

    @Test
    fun normalRecallInjectsHotMemoryOnlyAndExplicitRecallRetrievesOtherTiers() {
        val fixture = fixture()
        create(fixture, "hot", draft("memory:hot", MemoryClass.RUNTIME_HINT, "display current page"))
        create(fixture, "doc", draft("memory:doc", MemoryClass.DOCUMENT_REFERENCE, "display teaching report"))
        create(fixture, "struct", draft("memory:struct", MemoryClass.STRUCTURAL_KNOWLEDGE, "display selector"))

        val normal = fixture.service.recall(MemoryRecallRequest(sessionScope, setOf("display"), limit = 10))
        val documents = fixture.service.recall(
            MemoryRecallRequest(
                sessionScope,
                setOf("display"),
                memoryClasses = setOf(MemoryClass.DOCUMENT_REFERENCE),
                limit = 10,
            ),
        )
        val structural = fixture.service.recall(
            MemoryRecallRequest(
                sessionScope,
                setOf("display"),
                memoryClasses = setOf(MemoryClass.STRUCTURAL_KNOWLEDGE),
                limit = 10,
            ),
        )

        assertEquals(listOf("memory:hot"), normal.map { it.recordId })
        assertEquals(listOf("memory:doc"), documents.map { it.recordId })
        assertEquals(listOf("memory:struct"), structural.map { it.recordId })
        val mirror = String(Files.readAllBytes(fixture.root.resolve("knowledge-documents.md")), Charsets.UTF_8)
        assertTrue(mirror.contains("display teaching report"))
    }

    @Test
    fun missionHotMemoryEvictsLowestPriorityWithinHardCountBudget() {
        val budgets = TieredMemoryBudgets(
            missionHot = TierBudget(2, 1_024, 512),
        )
        val fixture = fixture(budgets = budgets)
        create(fixture, "old", draft("memory:old", observedAt = 10L, text = "old page"))
        fixture.time.now = 200L
        create(fixture, "middle", draft("memory:middle", observedAt = 190L, text = "middle page"))
        fixture.time.now = 300L
        create(fixture, "new", draft("memory:new", observedAt = 295L, text = "new page"))

        val hot = fixture.service.query(
            MemoryQuery(sessionScope, memoryClasses = setOf(MemoryClass.RUNTIME_HINT), limit = 10),
        )

        assertEquals(setOf("memory:middle", "memory:new"), hot.map { it.recordId }.toSet())
        assertNull(fixture.provider.get(MemoryRecordRef("memory:old", sessionScope)))
        assertEquals(2, fixture.provider.diagnostics().missionHotRecords)
    }

    @Test
    fun oversizedHotRecordIsRejectedWithoutEvictingKnownGoodMemory() {
        val fixture = fixture(
            budgets = TieredMemoryBudgets(
                missionHot = TierBudget(2, 64, 20),
            ),
        )
        create(fixture, "safe", draft("memory:safe", text = "short"))
        val proposed = fixture.service.proposeWrite(
            MemoryWriteProposalRequest(
                "proposal:large-hot",
                draft("memory:large", text = "this record is much larger than twenty bytes"),
            ),
        )
        assertEquals(MemoryProposalStatus.READY, proposed.status)
        val committed = fixture.service.commitApprovedWrite("proposal:large-hot")

        assertEquals(MemoryMutationStatus.PROVIDER_FAILED, committed.status)
        assertEquals("MISSION_HOT_RECORD_BUDGET", committed.reasonCode)
        assertNotNull(fixture.provider.get(MemoryRecordRef("memory:safe", sessionScope)))
        assertNull(fixture.provider.get(MemoryRecordRef("memory:large", sessionScope)))
    }

    @Test
    fun staleHistoricalMemoryCannotDisplaceFreshFactsAtServiceVisibleLimit() {
        val fixture = fixture(
            freshness = TieredFreshnessPolicy(
                runtimeHintMaxAgeMillis = 100,
                documentMaxAgeMillis = 1_000,
                structuralMaxAgeMillis = 1_000,
            ),
        )
        create(
            fixture,
            "historical",
            draft(
                "memory:historical",
                observedAt = 0L,
                text = "settings display historical",
                confidence = 0.99,
                verification = MemoryVerificationState.VERIFIED,
            ),
        )
        fixture.time.now = 950L
        create(
            fixture,
            "observed",
            draft(
                "memory:observed",
                observedAt = 940L,
                text = "settings display observed",
                confidence = 0.4,
                verification = MemoryVerificationState.OBSERVED,
            ),
        )
        fixture.time.now = 1_000L
        create(
            fixture,
            "instruction",
            draft(
                "memory:instruction",
                observedAt = 995L,
                text = "settings display current instruction",
                actor = MemoryActor("user.local", MemorySourceKind.USER),
                confidence = 0.2,
                verification = MemoryVerificationState.UNVERIFIED,
            ),
        )

        val serviceVisible = fixture.service.recall(
            MemoryRecallRequest(sessionScope, setOf("display"), limit = 2),
        )
        val providerDiagnostic = fixture.provider.recall(
            MemoryRecallRequest(sessionScope, setOf("display"), limit = 10),
        )

        assertEquals(setOf("memory:instruction", "memory:observed"), serviceVisible.map { it.recordId }.toSet())
        assertEquals("memory:instruction", providerDiagnostic.first().recordId)
        assertEquals(
            MemoryVerificationState.STALE,
            providerDiagnostic.single { it.recordId == "memory:historical" }.verificationState,
        )
    }

    @Test
    fun localProviderSurvivesRestartAndKeepsOptimisticVersions() {
        val root = temporaryRoot()
        val time = MutableTime(100L)
        val first = fixture(root = root, time = time)
        create(first, "durable", draft("memory:durable", MemoryClass.DOCUMENT_REFERENCE, "durable report"))
        assertEquals(LocalMemoryLoadState.READY, first.provider.diagnostics().loadState)

        val second = fixture(root = root, time = time)
        val loaded = second.service.query(
            MemoryQuery(sessionScope, memoryClasses = setOf(MemoryClass.DOCUMENT_REFERENCE)),
        ).single()
        assertEquals(1, loaded.recordVersion)
        assertEquals("durable report", loaded.content.fields["text"])

        time.now = 200L
        val replaced = second.service.replace(
            MemoryReplaceRequest(
                "replace:durable",
                expectedVersion = 1,
                draft("memory:durable", MemoryClass.DOCUMENT_REFERENCE, "updated durable report"),
            ),
        )
        assertEquals(MemoryMutationStatus.COMMITTED, replaced.status)
        assertEquals(2, replaced.record!!.recordVersion)

        val third = LocalTieredMemoryProvider(root, clock = time)
        assertEquals(2, third.get(MemoryRecordRef("memory:durable", sessionScope))!!.recordVersion)
        assertEquals("updated durable report", third.get(MemoryRecordRef("memory:durable", sessionScope))!!.content.fields["text"])
    }

    @Test
    fun providerAtomicallyRejectsFingerprintRaceAcrossTwoServiceInstances() {
        val root = temporaryRoot()
        val time = MutableTime(100L)
        val provider = LocalTieredMemoryProvider(root, clock = time)
        val first = service(provider, time)
        val second = service(provider, time)
        assertEquals(
            MemoryProposalStatus.READY,
            first.proposeWrite(
                MemoryWriteProposalRequest("proposal:race-one", draft("memory:race-one", text = "same content")),
            ).status,
        )
        assertEquals(
            MemoryProposalStatus.READY,
            second.proposeWrite(
                MemoryWriteProposalRequest("proposal:race-two", draft("memory:race-two", text = "same content")),
            ).status,
        )

        val winner = first.commitApprovedWrite("proposal:race-one")
        val duplicate = second.commitApprovedWrite("proposal:race-two")

        assertEquals(MemoryMutationStatus.COMMITTED, winner.status)
        assertEquals(MemoryMutationStatus.DUPLICATE, duplicate.status)
        assertEquals("PROVIDER_ALREADY_EXISTS", duplicate.reasonCode)
        assertEquals(1, provider.count(sessionScope))
    }

    @Test
    fun scopedReadsNeverLeakRecordsFromAnotherMission() {
        val fixture = fixture()
        create(fixture, "one", draft("memory:one", text = "shared term"))
        val otherScope = MemoryScope(MemoryScopeKind.SESSION, "mission:other")
        create(
            fixture,
            "other",
            draft("memory:other", text = "shared term", scope = otherScope),
        )

        val result = fixture.service.recall(MemoryRecallRequest(sessionScope, setOf("shared"), limit = 10))

        assertEquals(listOf("memory:one"), result.map { it.recordId })
        assertFalse(result.any { it.scope == otherScope })
    }

    @Test
    fun appGraphStructuralMemoryRequiresReferenceProjectionNotGraphCopy() {
        val fixture = fixture()
        val invalid = fixture.service.proposeWrite(
            MemoryWriteProposalRequest(
                "proposal:graph-blob",
                draft(
                    "memory:graph-blob",
                    MemoryClass.STRUCTURAL_KNOWLEDGE,
                    text = "ignored",
                    sourceSystem = "app.graph.v2",
                    content = MemoryContent(
                        mapOf(
                            "authority" to "app_graph",
                            "reference" to "graph:screen:1",
                            "nodes_json" to "[{copied graph data}]",
                        ),
                    ),
                ),
            ),
        )
        assertEquals(MemoryProposalStatus.READY, invalid.status)
        val rejected = fixture.service.commitApprovedWrite("proposal:graph-blob")
        assertEquals(MemoryMutationStatus.PROVIDER_FAILED, rejected.status)
        assertEquals("APP_GRAPH_BLOB_NOT_ALLOWED", rejected.reasonCode)

        val projection = AppGraphMemoryReference(
            reference = "graph:screen:settings-display",
            projectionType = "page_reference",
            summary = "Verified settings display page",
        )
        create(
            fixture,
            "graph-reference",
            draft(
                "memory:graph-reference",
                MemoryClass.STRUCTURAL_KNOWLEDGE,
                text = "ignored",
                sourceSystem = "app.graph.v2",
                content = projection.toMemoryContent(),
            ),
        )

        val stored = fixture.provider.get(MemoryRecordRef("memory:graph-reference", sessionScope))!!
        assertEquals("graph:screen:settings-display", stored.content.fields["reference"])
        assertFalse(stored.content.fields.keys.any { it.contains("nodes") || it.contains("edges") })
        assertEquals(1, fixture.provider.diagnostics().structuralRecords)
    }

    @Test
    fun corruptLocalSnapshotFailsClosedInsteadOfBeingOverwritten() {
        val root = temporaryRoot()
        Files.write(root.resolve("tiered-memory-v1.bin"), byteArrayOf(1, 2, 3, 4))
        val fixture = fixture(root = root)
        assertEquals(LocalMemoryLoadState.CORRUPT, fixture.provider.diagnostics().loadState)

        val proposal = fixture.service.proposeWrite(
            MemoryWriteProposalRequest("proposal:after-corruption", draft("memory:new")),
        )
        assertEquals(MemoryProposalStatus.READY, proposal.status)
        val result = fixture.service.commitApprovedWrite("proposal:after-corruption")

        assertEquals(MemoryMutationStatus.PROVIDER_FAILED, result.status)
        assertEquals("LOCAL_STORE_CORRUPT", result.reasonCode)
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), Files.readAllBytes(root.resolve("tiered-memory-v1.bin")).toList())
    }

    private fun create(fixture: Fixture, id: String, draft: MemoryDraft) {
        val proposalId = "proposal:$id"
        assertEquals(
            MemoryProposalStatus.READY,
            fixture.service.proposeWrite(MemoryWriteProposalRequest(proposalId, draft)).status,
        )
        val result = fixture.service.commitApprovedWrite(proposalId)
        assertEquals("Commit $proposalId failed: ${result.reasonCode}", MemoryMutationStatus.COMMITTED, result.status)
    }

    private fun fixture(
        root: Path = temporaryRoot(),
        time: MutableTime = MutableTime(100L),
        budgets: TieredMemoryBudgets = TieredMemoryBudgets(),
        freshness: TieredFreshnessPolicy = TieredFreshnessPolicy(),
    ): Fixture {
        val provider = LocalTieredMemoryProvider(root, budgets, freshness, time)
        val service = service(provider, time)
        return Fixture(root, time, provider, service)
    }

    private fun service(
        provider: LocalTieredMemoryProvider,
        time: MutableTime,
    ) = DefaultCycloneMemoryService(
            provider,
            MemoryWritePolicyGate { MemoryPolicyResult(MemoryPolicyDecision.ALLOW, "ALLOW") },
            MemoryApprovalVerifier { _, _ -> false },
            InMemoryMemoryAuditJournal(),
            budgets = MemoryBudgetPolicy(
                maxRecordBytes = 32 * 1024,
                maxRecordsPerScope = 10_000,
                maxQueryResults = 100,
            ),
            clock = time,
        )

    private fun draft(
        recordId: String,
        memoryClass: MemoryClass = MemoryClass.RUNTIME_HINT,
        text: String = "current page",
        observedAt: Long = 90L,
        actor: MemoryActor = MemoryActor("runtime.observer", MemorySourceKind.CYCLONE_RUNTIME),
        confidence: Double = 0.8,
        verification: MemoryVerificationState = MemoryVerificationState.OBSERVED,
        scope: MemoryScope = sessionScope,
        sourceSystem: String = "page.awareness",
        content: MemoryContent = MemoryContent(mapOf("text" to text)),
    ) = MemoryDraft(
        recordId,
        1,
        actor,
        MemoryProvenance(sourceSystem, setOf("evidence:$recordId"), observedAt),
        confidence,
        verification,
        scope,
        MemorySensitivity.INTERNAL,
        memoryClass,
        content,
    )

    private fun temporaryRoot(): Path = Files.createTempDirectory("cyclone-tiered-memory-test-")

    private data class Fixture(
        val root: Path,
        val time: MutableTime,
        val provider: LocalTieredMemoryProvider,
        val service: DefaultCycloneMemoryService,
    )

    private class MutableTime(var now: Long) : MemoryClock, TieredMemoryClock {
        override fun nowEpochMillis(): Long = now
    }
}
