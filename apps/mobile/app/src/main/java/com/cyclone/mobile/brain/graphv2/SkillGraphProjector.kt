package com.cyclone.mobile.brain.graphv2

/**
 * Projects verified skill paths onto the existing Graph V2 store.
 *
 * Duplicate page events reuse the same page identity (`page:{package}:{pageKey}`).
 * A failing edge is recorded against that edge only — siblings stay current.
 */
object SkillGraphProjector {
    fun appNodeId(packageName: String) = GraphNodeId("app:${sanitize(packageName)}")
    fun pageNodeId(packageName: String, pageKey: String) = GraphNodeId("page:${sanitize(packageName)}:${sanitize(pageKey)}")

    fun mergeApp(graph: TemporalGraphStore, packageName: String, displayName: String = packageName): AppNode {
        val node = AppNode(appNodeId(packageName), packageName, displayName)
        val existing = graph.node(node.id)
        if (existing == null) graph.registerNode(node)
        return (graph.node(node.id) as? AppNode) ?: node
    }

    fun mergePage(graph: TemporalGraphStore, packageName: String, pageKey: String, displayName: String = pageKey): PageNode {
        mergeApp(graph, packageName)
        val node = PageNode(pageNodeId(packageName, pageKey), packageName, pageKey, displayName)
        val existing = graph.node(node.id)
        if (existing == null) graph.registerNode(node)
        return (graph.node(node.id) as? PageNode) ?: node
    }

    fun recordVerifiedHop(
        graph: TemporalGraphStore,
        packageName: String,
        fromPageKey: String,
        toPageKey: String,
        evidenceId: String,
        observedAtEpochMillis: Long,
    ): EdgeRecordResult {
        val from = mergePage(graph, packageName, fromPageKey)
        val to = mergePage(graph, packageName, toPageKey)
        if (from.id == to.id) {
            return EdgeRecordResult.AlreadyRecorded(
                TemporalKnowledgeEdge(
                    GraphEdgeKey(from.id, GraphEdgeType.NAVIGATES_TO, to.id),
                    hopEvidence(evidenceId, observedAtEpochMillis, failures = 0, success = true),
                ),
            )
        }
        val key = GraphEdgeKey(from.id, GraphEdgeType.NAVIGATES_TO, to.id)
        return graph.record(
            TemporalKnowledgeEdge(key, hopEvidence(evidenceId, observedAtEpochMillis, failures = 0, success = true)),
        )
    }

    fun lowerEdgeOnly(
        graph: TemporalGraphStore,
        packageName: String,
        fromPageKey: String,
        toPageKey: String,
        evidenceId: String,
        observedAtEpochMillis: Long,
    ): EdgeRecordResult {
        val from = mergePage(graph, packageName, fromPageKey)
        val to = mergePage(graph, packageName, toPageKey)
        val key = GraphEdgeKey(from.id, GraphEdgeType.NAVIGATES_TO, to.id)
        return graph.record(
            TemporalKnowledgeEdge(key, hopEvidence(evidenceId, observedAtEpochMillis, failures = 1, success = false)),
        )
    }

    fun pageCount(graph: TemporalGraphStore, packageName: String): Int =
        graph.nodes().count { it is PageNode && it.packageName == packageName }

    fun appCount(graph: TemporalGraphStore, packageName: String): Int =
        graph.nodes().count { it is AppNode && it.packageName == packageName }

    private fun hopEvidence(
        evidenceId: String,
        observedAt: Long,
        failures: Int,
        success: Boolean,
    ) = TemporalEdgeEvidence(
        source = GraphEvidenceSource(GraphEvidenceKind.ACTION_AFTER_STATE, evidenceId, "SkillCompiler"),
        confidence = if (success) 0.9 else 0.2,
        observedAtEpochMillis = observedAt,
        lastSucceededAtEpochMillis = observedAt.takeIf { success },
        lastFailedAtEpochMillis = observedAt.takeIf { failures > 0 },
        successCount = if (success) 1 else 0,
        failureCount = failures,
        appVersion = null,
        verificationState = if (success) GraphVerificationState.VERIFIED else GraphVerificationState.REJECTED,
        verificationScope = if (success) GraphVerificationScope.CI_CONTRACT else GraphVerificationScope.NONE,
        staleness = if (success) GraphStaleness.CURRENT else GraphStaleness.SUSPECT,
    )

    private fun sanitize(value: String): String = value.trim().ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9._:/=-]"), "_")
}
