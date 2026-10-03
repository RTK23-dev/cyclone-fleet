package com.cyclone.mobile.applearner.graphv2

import com.cyclone.mobile.applearner.AppGraphSnapshot
import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.brain.graphv2.ActivityNode
import com.cyclone.mobile.brain.graphv2.AppNode
import com.cyclone.mobile.brain.graphv2.AppVersionEvidence
import com.cyclone.mobile.brain.graphv2.EdgeRecordResult
import com.cyclone.mobile.brain.graphv2.ElementNode
import com.cyclone.mobile.brain.graphv2.GraphEdgeKey
import com.cyclone.mobile.brain.graphv2.GraphEdgeType
import com.cyclone.mobile.brain.graphv2.GraphEvidenceKind
import com.cyclone.mobile.brain.graphv2.GraphEvidenceSource
import com.cyclone.mobile.brain.graphv2.GraphNode
import com.cyclone.mobile.brain.graphv2.GraphNodeId
import com.cyclone.mobile.brain.graphv2.GraphStaleness
import com.cyclone.mobile.brain.graphv2.GraphVerificationScope
import com.cyclone.mobile.brain.graphv2.GraphVerificationState
import com.cyclone.mobile.brain.graphv2.PageNode
import com.cyclone.mobile.brain.graphv2.SelectorNode
import com.cyclone.mobile.brain.graphv2.TemporalEdgeEvidence
import com.cyclone.mobile.brain.graphv2.TemporalGraphStore
import com.cyclone.mobile.brain.graphv2.TemporalKnowledgeEdge
import com.cyclone.mobile.brain.graphv2.TransitionNode
import java.security.MessageDigest
import java.util.Base64

data class GraphV2Projection(
    val nodes: List<GraphNode>,
    val edges: List<TemporalKnowledgeEdge>,
) {
    fun importInto(store: TemporalGraphStore): List<EdgeRecordResult> {
        nodes.sortedBy { it.id }.forEach(store::registerNode)
        return edges.sortedWith(compareBy({ it.key }, { it.evidence.source.evidenceId })).map(store::record)
    }
}

/**
 * Read-only projection from the current App Graph snapshot. It does not access, migrate, mutate or
 * delete the legacy SQLite database. Legacy VERIFIED is conservatively imported as OBSERVED because
 * the old record alone does not prove the evidence came from a physical-device acceptance run.
 */
object LegacyAppGraphV2Adapter {
    fun project(snapshot: AppGraphSnapshot): GraphV2Projection {
        val nodes = linkedMapOf<GraphNodeId, GraphNode>()
        val edges = mutableListOf<TemporalKnowledgeEdge>()
        val appId = id("app", snapshot.app.packageName)
        nodes[appId] = AppNode(appId, snapshot.app.packageName, snapshot.app.label)
        val version = AppVersionEvidence(
            packageName = snapshot.app.packageName,
            versionName = snapshot.app.versionName,
            versionCode = snapshot.app.versionCode,
        )

        snapshot.screens.sortedBy { it.id }.forEach { screen ->
            val pageId = id("page", screen.id)
            nodes[pageId] = PageNode(pageId, screen.packageName, screen.identity, screen.title)
            val className = screen.recognition.className?.takeIf { it.isNotBlank() }
            val parent = if (className == null) appId else id("activity", "${screen.packageName}:$className").also {
                if (nodes[it] == null) {
                    nodes[it] = ActivityNode(it, screen.packageName, className)
                    edges += edge(
                        appId, GraphEdgeType.CONTAINS, it,
                        evidenceId = "legacy:activity:$className:${screen.lastSeenAt}",
                        confidence = screen.confidence,
                        observedAt = screen.lastSeenAt,
                        appVersion = version,
                        state = screen.knowledgeState,
                    )
                }
            }
            edges += edge(
                parent, GraphEdgeType.CONTAINS, pageId,
                evidenceId = "legacy:page:${screen.id}:${screen.lastSeenAt}",
                confidence = screen.confidence,
                observedAt = screen.lastSeenAt,
                lastSuccessAt = screen.lastVerifiedAt,
                successCount = if (screen.lastVerifiedAt == null) 0 else 1,
                appVersion = version,
                state = screen.knowledgeState,
            )
        }

        snapshot.actions.sortedBy { it.id }.forEach { action ->
            val pageId = id("page", action.screenId)
            if (nodes[pageId] == null) return@forEach
            val elementId = id("element", action.id)
            val selectorId = id("selector", action.id)
            nodes[elementId] = ElementNode(elementId, action.semanticName, action.label)
            nodes[selectorId] = SelectorNode(
                selectorId,
                selectorKey = "sha256:${sha256(action.selectorJson)}",
                displayName = "Selector for ${action.semanticName}",
            )
            val actionObservedAt = maxOf(
                action.lastSuccessAt ?: 0L,
                action.lastFailureAt ?: 0L,
                snapshot.app.lastLearnedAt,
            )
            edges += edge(
                pageId, GraphEdgeType.CONTAINS, elementId,
                evidenceId = "legacy:action:${action.id}:$actionObservedAt",
                confidence = action.confidence,
                observedAt = actionObservedAt,
                lastSuccessAt = action.lastSuccessAt,
                lastFailureAt = action.lastFailureAt,
                successCount = if (action.lastSuccessAt == null) 0 else 1,
                failureCount = action.failureCount,
                appVersion = version,
                state = action.knowledgeState,
            )
            edges += edge(
                selectorId, GraphEdgeType.SELECTOR_MATCHES, elementId,
                evidenceId = "legacy:selector:${action.id}:$actionObservedAt",
                confidence = action.confidence,
                observedAt = actionObservedAt,
                lastSuccessAt = action.lastSuccessAt,
                lastFailureAt = action.lastFailureAt,
                successCount = if (action.lastSuccessAt == null) 0 else 1,
                failureCount = action.failureCount,
                appVersion = version,
                state = action.knowledgeState,
            )
        }

        val actionById = snapshot.actions.associateBy { it.id }
        snapshot.transitions.sortedBy { it.id }.forEach { transition ->
            val from = id("page", transition.fromScreenId)
            val to = id("page", transition.toScreenId)
            if (nodes[from] == null || nodes[to] == null) return@forEach
            val transitionId = id("transition", transition.id)
            val action = actionById[transition.actionId]
            nodes[transitionId] = TransitionNode(
                transitionId,
                actionName = action?.semanticName ?: transition.actionId,
                displayName = action?.label ?: transition.actionId,
            )
            val failures = (transition.observedCount - transition.successfulCount).coerceAtLeast(0)
            val common = EdgeInput(
                evidenceId = "legacy:transition:${transition.id}:${transition.lastObservedAt}:${transition.observedCount}",
                confidence = transition.confidence,
                observedAt = transition.lastObservedAt,
                lastSuccessAt = transition.lastObservedAt.takeIf { transition.successfulCount > 0 },
                lastFailureAt = transition.lastObservedAt.takeIf { failures > 0 },
                successCount = transition.successfulCount,
                failureCount = failures,
                appVersion = version,
                state = transition.knowledgeState,
            )
            edges += edge(from, GraphEdgeType.CONTAINS, transitionId, common.copy(evidenceId = "${common.evidenceId}:node"))
            edges += edge(from, GraphEdgeType.NAVIGATES_TO, to, common.copy(evidenceId = "${common.evidenceId}:route"))
            edges += edge(transitionId, GraphEdgeType.OPENS, to, common.copy(evidenceId = "${common.evidenceId}:opens"))
            action?.let {
                val selectorId = id("selector", it.id)
                if (nodes[selectorId] != null) {
                    edges += edge(
                        transitionId,
                        GraphEdgeType.REQUIRES,
                        selectorId,
                        common.copy(evidenceId = "${common.evidenceId}:selector"),
                    )
                }
            }
        }
        return GraphV2Projection(nodes.values.sortedBy { it.id }, edges.sortedBy { it.key })
    }

    private data class EdgeInput(
        val evidenceId: String,
        val confidence: Double,
        val observedAt: Long,
        val lastSuccessAt: Long? = null,
        val lastFailureAt: Long? = null,
        val successCount: Int = 0,
        val failureCount: Int = 0,
        val appVersion: AppVersionEvidence,
        val state: KnowledgeState,
    )

    private fun edge(
        from: GraphNodeId,
        type: GraphEdgeType,
        to: GraphNodeId,
        input: EdgeInput,
    ): TemporalKnowledgeEdge = edge(
        from, type, to, input.evidenceId, input.confidence, input.observedAt,
        input.lastSuccessAt, input.lastFailureAt, input.successCount, input.failureCount,
        input.appVersion, input.state,
    )

    private fun edge(
        from: GraphNodeId,
        type: GraphEdgeType,
        to: GraphNodeId,
        evidenceId: String,
        confidence: Double,
        observedAt: Long,
        lastSuccessAt: Long? = null,
        lastFailureAt: Long? = null,
        successCount: Int = 0,
        failureCount: Int = 0,
        appVersion: AppVersionEvidence,
        state: KnowledgeState,
    ) = TemporalKnowledgeEdge(
        GraphEdgeKey(from, type, to),
        TemporalEdgeEvidence(
            source = GraphEvidenceSource(GraphEvidenceKind.LEGACY_GRAPH_IMPORT, evidenceId, "app-graph-v1-adapter"),
            confidence = confidence.coerceIn(0.0, 1.0),
            observedAtEpochMillis = observedAt.coerceAtLeast(0L),
            lastSucceededAtEpochMillis = lastSuccessAt?.coerceAtMost(observedAt),
            lastFailedAtEpochMillis = lastFailureAt?.coerceAtMost(observedAt),
            successCount = successCount.coerceAtLeast(0),
            failureCount = failureCount.coerceAtLeast(0),
            appVersion = appVersion,
            verificationState = when (state) {
                KnowledgeState.UNKNOWN -> GraphVerificationState.UNVERIFIED
                else -> GraphVerificationState.OBSERVED
            },
            verificationScope = GraphVerificationScope.NONE,
            staleness = when (state) {
                KnowledgeState.STALE -> GraphStaleness.STALE
                else -> GraphStaleness.CURRENT
            },
        ),
    )

    private fun id(kind: String, raw: String): GraphNodeId {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(Charsets.UTF_8))
        return GraphNodeId("$kind:$encoded")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
