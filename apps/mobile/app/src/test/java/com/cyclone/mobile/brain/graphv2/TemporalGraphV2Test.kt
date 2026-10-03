package com.cyclone.mobile.brain.graphv2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporalGraphV2Test {
    @Test
    fun temporalHistoryRetainsOldEvidenceAndCurrentCanBecomeStale() {
        val store = storeWith(PageNode(id("page:a"), "com.shop", "a", "A"), PageNode(id("page:b"), "com.shop", "b", "B"))
        val key = GraphEdgeKey(id("page:a"), GraphEdgeType.NAVIGATES_TO, id("page:b"))
        assertTrue(store.record(edge(key, "observed-1", 100, staleness = GraphStaleness.CURRENT)) is EdgeRecordResult.Recorded)
        assertTrue(store.record(edge(key, "failed-2", 200, failures = 1, staleness = GraphStaleness.STALE)) is EdgeRecordResult.Recorded)

        assertEquals(listOf("observed-1", "failed-2"), store.history(key).map { it.evidence.source.evidenceId })
        assertEquals(GraphStaleness.STALE, store.currentEdges(includeStale = true).single().evidence.staleness)
        assertTrue(store.currentEdges().isEmpty())
    }

    @Test
    fun reachabilityIsDeterministicAndTerminatesAcrossCycles() {
        val a = PageNode(id("page:a"), "com.shop", "a", "A")
        val b = PageNode(id("page:b"), "com.shop", "b", "B")
        val c = PageNode(id("page:c"), "com.shop", "c", "C")
        val store = storeWith(c, a, b)
        listOf(
            GraphEdgeKey(a.id, GraphEdgeType.NAVIGATES_TO, b.id),
            GraphEdgeKey(b.id, GraphEdgeType.NAVIGATES_TO, a.id),
            GraphEdgeKey(b.id, GraphEdgeType.NAVIGATES_TO, c.id),
        ).reversed().forEachIndexed { index, key -> store.record(edge(key, "route-$index", 100L + index)) }

        val reachable = GraphV2Queries(store).pagesThatCanReach(c.id)

        assertEquals(listOf("page:b", "page:a"), reachable.map { it.page.id.value })
        assertEquals(listOf(1, 2), reachable.map { it.distance })
    }

    @Test
    fun validSelectorsExcludeLatestStaleRelation() {
        val page = PageNode(id("page:home"), "com.shop", "home", "Home")
        val element = ElementNode(id("element:orders"), "orders", "Orders")
        val currentSelector = SelectorNode(id("selector:current"), "resource:orders", "Orders selector")
        val staleSelector = SelectorNode(id("selector:stale"), "text:old", "Old selector")
        val store = storeWith(page, element, currentSelector, staleSelector)
        store.record(edge(GraphEdgeKey(page.id, GraphEdgeType.CONTAINS, element.id), "contains", 100))
        store.record(edge(GraphEdgeKey(currentSelector.id, GraphEdgeType.SELECTOR_MATCHES, element.id), "current", 100))
        val staleKey = GraphEdgeKey(staleSelector.id, GraphEdgeType.SELECTOR_MATCHES, element.id)
        store.record(edge(staleKey, "stale-before", 100))
        store.record(edge(staleKey, "stale-now", 200, staleness = GraphStaleness.STALE))

        assertEquals(listOf(currentSelector.id), GraphV2Queries(store).validSelectorsHere(page.id).map { it.id })
    }

    @Test
    fun blastRadiusFindsElementsSelectorsAndDependentRoutines() {
        val page = PageNode(id("page:checkout"), "com.shop", "checkout", "Checkout")
        val element = ElementNode(id("element:submit"), "submit", "Submit")
        val selector = SelectorNode(id("selector:submit"), "resource:submit", "Submit selector")
        val routine = RoutineNode(id("routine:buy"), "buy", "Buy item")
        val store = storeWith(routine, selector, element, page)
        store.record(edge(GraphEdgeKey(page.id, GraphEdgeType.CONTAINS, element.id), "contains", 100))
        store.record(edge(GraphEdgeKey(selector.id, GraphEdgeType.SELECTOR_MATCHES, element.id), "matches", 100))
        store.record(edge(GraphEdgeKey(selector.id, GraphEdgeType.USED_BY_ROUTINE, routine.id), "used", 100))

        val blast = GraphV2Queries(store).blastRadius(page.id)

        assertEquals(listOf(element.id, selector.id, routine.id), blast.map { it.node.id })
        assertEquals(listOf(1, 2, 3), blast.map { it.distance })
        assertEquals(listOf(routine), GraphV2Queries(store).routinesDependingOn(selector.id))
    }

    @Test
    fun appUpdateQuerySurfacesRelationsObservedOnOlderVersion() {
        val pageA = PageNode(id("page:a"), "com.shop", "a", "A")
        val pageB = PageNode(id("page:b"), "com.shop", "b", "B")
        val store = storeWith(pageA, pageB)
        val evidence = evidence("v10-route", 100).copy(
            appVersion = AppVersionEvidence("com.shop", versionName = "10.0", versionCode = 10),
        )
        store.record(TemporalKnowledgeEdge(GraphEdgeKey(pageA.id, GraphEdgeType.NAVIGATES_TO, pageB.id), evidence))

        val changes = GraphV2Queries(store).changesAfterAppUpdate("com.shop", currentVersionCode = 11)

        assertEquals(1, changes.size)
        assertEquals("10", changes.single().edge.evidence.appVersion?.stableIdentity)
    }

    @Test
    fun modelInferenceCannotBeSoleStructuralEvidence() {
        val pageA = PageNode(id("page:a"), "com.shop", "a", "A")
        val pageB = PageNode(id("page:b"), "com.shop", "b", "B")
        val store = storeWith(pageA, pageB)
        val key = GraphEdgeKey(pageA.id, GraphEdgeType.NAVIGATES_TO, pageB.id)
        val inferred = edge(key, "model-only", 100, sourceKind = GraphEvidenceKind.MODEL_INFERENCE)

        assertEquals(
            EdgeRejectionReason.INFERENCE_CANNOT_CREATE_STRUCTURE,
            (store.record(inferred) as EdgeRecordResult.Rejected).reason,
        )
        assertTrue(store.record(edge(key, "after-state", 101)) is EdgeRecordResult.Recorded)
        assertTrue(store.record(inferred.copy(evidence = inferred.evidence.copy(observedAtEpochMillis = 102))) is EdgeRecordResult.Recorded)
        val modelVerified = verifiedEdge(
            key,
            "model-verified",
            GraphEvidenceKind.MODEL_INFERENCE,
            physicalEvidence = false,
            scope = GraphVerificationScope.LOCAL_DEVICE,
        )
        assertEquals(
            EdgeRejectionReason.VERIFICATION_REQUIRES_DETERMINISTIC_EVIDENCE,
            (store.record(modelVerified) as EdgeRecordResult.Rejected).reason,
        )
    }

    @Test
    fun ciEvidenceCannotPromotePhysicalVerifiedButPhysicalEvidenceCan() {
        val pageA = PageNode(id("page:a"), "com.shop", "a", "A")
        val pageB = PageNode(id("page:b"), "com.shop", "b", "B")
        val store = storeWith(pageA, pageB)
        val key = GraphEdgeKey(pageA.id, GraphEdgeType.NAVIGATES_TO, pageB.id)
        val ciClaim = verifiedEdge(key, "ci", GraphEvidenceKind.CI_FIXTURE, physicalEvidence = false)

        assertEquals(
            EdgeRejectionReason.PHYSICAL_VERIFICATION_NOT_PROVEN,
            (store.record(ciClaim) as EdgeRecordResult.Rejected).reason,
        )
        val physical = verifiedEdge(key, "device", GraphEvidenceKind.ACTION_AFTER_STATE, physicalEvidence = true)
        assertTrue(store.record(physical) is EdgeRecordResult.Recorded)
        assertEquals(GraphVerificationScope.PHYSICAL_DEVICE, store.currentEdges().single().evidence.verificationScope)
    }

    @Test
    fun duplicateEvidenceIsIdempotentButConflictingReuseFails() {
        val pageA = PageNode(id("page:a"), "com.shop", "a", "A")
        val pageB = PageNode(id("page:b"), "com.shop", "b", "B")
        val store = storeWith(pageA, pageB)
        val edge = edge(GraphEdgeKey(pageA.id, GraphEdgeType.NAVIGATES_TO, pageB.id), "same", 100)
        assertTrue(store.record(edge) is EdgeRecordResult.Recorded)
        assertTrue(store.record(edge) is EdgeRecordResult.AlreadyRecorded)
        assertEquals(
            EdgeRejectionReason.DUPLICATE_EVIDENCE_CONFLICT,
            (store.record(edge.copy(evidence = edge.evidence.copy(confidence = 0.1))) as EdgeRecordResult.Rejected).reason,
        )
    }

    private fun storeWith(vararg nodes: GraphNode) = InMemoryTemporalGraphStore().also { store ->
        nodes.forEach(store::registerNode)
    }

    private fun edge(
        key: GraphEdgeKey,
        evidenceId: String,
        observedAt: Long,
        failures: Int = 0,
        staleness: GraphStaleness = GraphStaleness.CURRENT,
        sourceKind: GraphEvidenceKind = GraphEvidenceKind.ACTION_AFTER_STATE,
    ) = TemporalKnowledgeEdge(key, evidence(evidenceId, observedAt).copy(
        source = GraphEvidenceSource(sourceKind, evidenceId, "fixture"),
        lastFailedAtEpochMillis = observedAt.takeIf { failures > 0 },
        failureCount = failures,
        staleness = staleness,
    ))

    private fun evidence(evidenceId: String, observedAt: Long) = TemporalEdgeEvidence(
        source = GraphEvidenceSource(GraphEvidenceKind.ACTION_AFTER_STATE, evidenceId, "fixture"),
        confidence = 0.8,
        observedAtEpochMillis = observedAt,
        lastSucceededAtEpochMillis = null,
        lastFailedAtEpochMillis = null,
        successCount = 0,
        failureCount = 0,
        appVersion = null,
        verificationState = GraphVerificationState.OBSERVED,
        verificationScope = GraphVerificationScope.NONE,
        staleness = GraphStaleness.CURRENT,
    )

    private fun verifiedEdge(
        key: GraphEdgeKey,
        evidenceId: String,
        kind: GraphEvidenceKind,
        physicalEvidence: Boolean,
        scope: GraphVerificationScope = GraphVerificationScope.PHYSICAL_DEVICE,
    ) = TemporalKnowledgeEdge(
        key,
        TemporalEdgeEvidence(
            source = GraphEvidenceSource(kind, evidenceId, "fixture", physicalEvidence),
            confidence = 0.95,
            observedAtEpochMillis = 100,
            lastSucceededAtEpochMillis = 100,
            lastFailedAtEpochMillis = null,
            successCount = 1,
            failureCount = 0,
            appVersion = null,
            verificationState = GraphVerificationState.VERIFIED,
            verificationScope = scope,
            staleness = GraphStaleness.CURRENT,
        ),
    )

    private fun id(value: String) = GraphNodeId(value)
}
