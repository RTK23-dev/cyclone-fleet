package com.cyclone.mobile.applearner.graphv2

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.AppGraphSnapshot
import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.LearnedApp
import com.cyclone.mobile.applearner.LearnedScreen
import com.cyclone.mobile.brain.graphv2.AppNode
import com.cyclone.mobile.brain.graphv2.AppVersionEvidence
import com.cyclone.mobile.brain.graphv2.AtlasDanger
import com.cyclone.mobile.brain.graphv2.AtlasEdgeMetadata
import com.cyclone.mobile.brain.graphv2.AtlasGraphIds
import com.cyclone.mobile.brain.graphv2.AtlasMapStatus
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.brain.graphv2.AtlasPlace
import com.cyclone.mobile.brain.graphv2.AtlasPlaceKey
import com.cyclone.mobile.brain.graphv2.AtlasPrivacy
import com.cyclone.mobile.brain.graphv2.AtlasScreenMetadata
import com.cyclone.mobile.brain.graphv2.AtlasStore
import com.cyclone.mobile.brain.graphv2.EdgeRecordResult
import com.cyclone.mobile.brain.graphv2.ElementNode
import com.cyclone.mobile.brain.graphv2.GraphEdgeKey
import com.cyclone.mobile.brain.graphv2.GraphEdgeType
import com.cyclone.mobile.brain.graphv2.GraphEvidenceKind
import com.cyclone.mobile.brain.graphv2.GraphEvidenceSource
import com.cyclone.mobile.brain.graphv2.GraphStaleness
import com.cyclone.mobile.brain.graphv2.GraphVerificationScope
import com.cyclone.mobile.brain.graphv2.GraphVerificationState
import com.cyclone.mobile.brain.graphv2.PageNode
import com.cyclone.mobile.brain.graphv2.SelectorNode
import com.cyclone.mobile.brain.graphv2.TemporalEdgeEvidence
import com.cyclone.mobile.brain.graphv2.TemporalKnowledgeEdge
import com.cyclone.mobile.brain.graphv2.TransitionNode

data class AtlasLegacyImportResult(
    val place: AtlasPlace,
    val edgeResults: List<EdgeRecordResult>,
)

/**
 * Conservative, non-destructive import of the SQLite-backed 4.8 App Graph. The adapter already
 * hashes legacy selector JSON; this importer intentionally ignores screenshotPath and dynamic_json.
 * Legacy VERIFIED state remains OBSERVED Graph-v2 evidence and never becomes physical verification.
 */
class AtlasLegacyImporter(
    private val atlas: AtlasStore,
) {
    fun import(
        snapshot: AppGraphSnapshot,
        persona: AtlasPersona = AtlasPersona.LIVE,
    ): AtlasLegacyImportResult {
        val status = when {
            snapshot.screens.any { it.knowledgeState == KnowledgeState.STALE } -> AtlasMapStatus.STALE
            snapshot.screens.isEmpty() -> AtlasMapStatus.UNMAPPED
            else -> AtlasMapStatus.PARTIAL
        }
        val place = atlas.registerPlace(
            AtlasPlace.packagePlace(
                packageName = snapshot.app.packageName,
                label = snapshot.app.label,
                persona = persona,
                mapStatus = status,
                lastObservedAtEpochMillis = snapshot.screens.maxOfOrNull { it.lastSeenAt } ?: snapshot.app.lastLearnedAt,
                // Old verification timestamps are retained in legacy SQLite, but are not promoted
                // as V5 verification because the old row does not prove a physical V5 check.
                lastVerifiedAtEpochMillis = null,
            ),
        )
        val projection = LegacyAppGraphV2Adapter.project(snapshot)
        val edgeResults = atlas.mutateGraph(place.key) { projection.importInto(it) }

        val actionsByScreen = snapshot.actions.groupBy { it.screenId }
        snapshot.screens.sortedBy { it.id }.forEachIndexed { index, screen ->
            val pageId = AtlasGraphIds.encoded("page", screen.id)
            val existing = atlas.snapshot(place.key)?.screens?.firstOrNull { it.screenId == pageId }
            val layout = existing?.let { it.layoutX to it.layoutY }
                ?: AtlasGraphIds.stableLayout(place.id + ":" + persona.wireValue, index)
            atlas.upsertScreen(
                place.key,
                AtlasScreenMetadata(
                    screenId = pageId,
                    purpose = AtlasPrivacy.structuralScreenPurpose(screen.purpose, "Learned app screen"),
                    capabilities = semanticCapabilities(actionsByScreen[screen.id].orEmpty()),
                    factSlots = emptyList(),
                    danger = highestDanger(actionsByScreen[screen.id].orEmpty().map { danger(it.risk, it.label) }),
                    confidence = screen.confidence.coerceIn(0.0, 1.0),
                    lastObservedAtEpochMillis = screen.lastSeenAt.coerceAtLeast(0L),
                    lastVerifiedAtEpochMillis = null,
                    layoutX = layout.first,
                    layoutY = layout.second,
                ),
            )
        }

        val actionById = snapshot.actions.associateBy { it.id }
        snapshot.transitions.sortedBy { it.id }.forEach { transition ->
            val action = actionById[transition.actionId] ?: return@forEach
            val key = GraphEdgeKey(
                AtlasGraphIds.encoded("page", transition.fromScreenId),
                GraphEdgeType.NAVIGATES_TO,
                AtlasGraphIds.encoded("page", transition.toScreenId),
            )
            atlas.upsertEdgeMetadata(
                place.key,
                AtlasEdgeMetadata(
                    key = key,
                    action = AtlasPrivacy.structuralControlLabel(action.semanticName, "Navigate"),
                    selectorKey = AtlasGraphIds.selectorDigest(action.selectorJson),
                    danger = danger(action.risk, action.label),
                    confidence = transition.confidence.coerceIn(0.0, 1.0),
                    lastObservedAtEpochMillis = transition.lastObservedAt.coerceAtLeast(0L),
                    lastVerifiedAtEpochMillis = null,
                ),
            )
        }
        return AtlasLegacyImportResult(place, edgeResults)
    }
}

/**
 * Direct Follow Me promotion path. New user demonstrations write the same durable Atlas immediately
 * with FOLLOW_ME_DEMONSTRATION evidence; there is no temporary "Follow Me graph".
 */
class FollowMeAtlasPromoter(
    private val atlas: AtlasStore,
) {
    fun observeScreen(
        app: LearnedApp,
        screen: LearnedScreen,
        actions: List<LearnedAction>,
        persona: AtlasPersona = AtlasPersona.LIVE,
    ): AtlasPlaceKey {
        val place = atlas.registerPlace(
            AtlasPlace.packagePlace(
                packageName = app.packageName,
                label = app.label,
                persona = persona,
                mapStatus = AtlasMapStatus.PARTIAL,
                lastObservedAtEpochMillis = screen.lastSeenAt,
            ),
        )
        val appNode = AppNode(
            AtlasGraphIds.encoded("app", app.packageName),
            app.packageName,
            AtlasPrivacy.structuralLabel(app.label, app.packageName),
        )
        val pageNode = PageNode(
            AtlasGraphIds.encoded("page", screen.id),
            screen.packageName,
            screen.identity,
            AtlasPrivacy.structuralScreenLabel(screen.title, "Screen"),
        )
        val evidence = evidence(
            id = "follow-me:screen:" + screen.id + ":" + screen.lastSeenAt,
            observedAt = screen.lastSeenAt,
            confidence = screen.confidence,
            app = app,
            success = false,
        )
        atlas.mutateGraph(place.key) { graph ->
            graph.registerNode(appNode)
            graph.registerNode(pageNode)
            graph.record(TemporalKnowledgeEdge(GraphEdgeKey(appNode.id, GraphEdgeType.CONTAINS, pageNode.id), evidence))
            actions.forEach { action ->
                val element = ElementNode(
                    AtlasGraphIds.encoded("element", action.id),
                    action.semanticName,
                    AtlasPrivacy.structuralControlLabel(action.semanticName, "Control"),
                )
                val selector = SelectorNode(
                    AtlasGraphIds.encoded("selector", action.id),
                    AtlasGraphIds.selectorDigest(action.selectorJson),
                    "Semantic selector",
                )
                graph.registerNode(element)
                graph.registerNode(selector)
                val actionEvidence = evidence.copy(
                    source = evidence.source.copy(
                        evidenceId = "follow-me:control:" + action.id + ":" + screen.lastSeenAt,
                    ),
                    confidence = action.confidence.coerceIn(0.0, 1.0),
                )
                graph.record(TemporalKnowledgeEdge(GraphEdgeKey(pageNode.id, GraphEdgeType.CONTAINS, element.id), actionEvidence))
                graph.record(TemporalKnowledgeEdge(
                    GraphEdgeKey(selector.id, GraphEdgeType.SELECTOR_MATCHES, element.id),
                    actionEvidence.copy(source = actionEvidence.source.copy(evidenceId = actionEvidence.source.evidenceId + ":selector")),
                ))
            }
        }

        val existing = atlas.snapshot(place.key)?.screens?.firstOrNull { it.screenId == pageNode.id }
        val layout = existing?.let { it.layoutX to it.layoutY }
            ?: AtlasGraphIds.stableLayout(place.id + ":" + persona.wireValue, atlas.snapshot(place.key)?.screens?.size ?: 0)
        atlas.upsertScreen(
            place.key,
            AtlasScreenMetadata(
                screenId = pageNode.id,
                purpose = AtlasPrivacy.structuralScreenPurpose(screen.purpose, "Learned app screen"),
                capabilities = semanticCapabilities(actions),
                factSlots = existing?.factSlots.orEmpty(),
                danger = highestDanger(actions.map { danger(it.risk, it.label) }),
                confidence = maxOf(existing?.confidence ?: 0.0, screen.confidence.coerceIn(0.0, 1.0)),
                lastObservedAtEpochMillis = maxOf(existing?.lastObservedAtEpochMillis ?: 0L, screen.lastSeenAt),
                lastVerifiedAtEpochMillis = existing?.lastVerifiedAtEpochMillis,
                layoutX = layout.first,
                layoutY = layout.second,
            ),
        )
        return place.key
    }

    fun demonstrateTransition(
        app: LearnedApp,
        fromScreen: LearnedScreen,
        action: LearnedAction,
        toScreen: LearnedScreen,
        observedAtEpochMillis: Long,
        persona: AtlasPersona = AtlasPersona.LIVE,
    ) {
        val key = AtlasPlaceKey("package:" + app.packageName, persona)
        if (atlas.place(key) == null) {
            observeScreen(app, fromScreen, listOf(action), persona)
            observeScreen(app, toScreen, emptyList(), persona)
        }
        val from = AtlasGraphIds.encoded("page", fromScreen.id)
        val to = AtlasGraphIds.encoded("page", toScreen.id)
        val transition = TransitionNode(
            AtlasGraphIds.encoded("transition", fromScreen.id + ":" + action.id + ":" + toScreen.id),
            action.semanticName,
            AtlasPrivacy.structuralControlLabel(action.semanticName, "Navigate"),
        )
        val selector = SelectorNode(
            AtlasGraphIds.encoded("selector", action.id),
            AtlasGraphIds.selectorDigest(action.selectorJson),
            "Semantic selector",
        )
        val routeKey = GraphEdgeKey(from, GraphEdgeType.NAVIGATES_TO, to)
        val routeEvidence = evidence(
            id = "follow-me:transition:" + fromScreen.id + ":" + action.id + ":" + toScreen.id + ":" + observedAtEpochMillis,
            observedAt = observedAtEpochMillis,
            confidence = action.confidence.coerceAtLeast(0.82),
            app = app,
            success = true,
        )
        atlas.mutateGraph(key) { graph ->
            if (graph.node(from) == null) {
                graph.registerNode(PageNode(from, fromScreen.packageName, fromScreen.identity, AtlasPrivacy.structuralScreenLabel(fromScreen.title, "Screen")))
            }
            if (graph.node(to) == null) {
                graph.registerNode(PageNode(to, toScreen.packageName, toScreen.identity, AtlasPrivacy.structuralScreenLabel(toScreen.title, "Screen")))
            }
            graph.registerNode(transition)
            graph.registerNode(selector)
            graph.record(TemporalKnowledgeEdge(routeKey, routeEvidence))
            graph.record(TemporalKnowledgeEdge(
                GraphEdgeKey(from, GraphEdgeType.CONTAINS, transition.id),
                routeEvidence.copy(source = routeEvidence.source.copy(evidenceId = routeEvidence.source.evidenceId + ":transition-node")),
            ))
            graph.record(TemporalKnowledgeEdge(
                GraphEdgeKey(transition.id, GraphEdgeType.OPENS, to),
                routeEvidence.copy(source = routeEvidence.source.copy(evidenceId = routeEvidence.source.evidenceId + ":opens")),
            ))
            graph.record(TemporalKnowledgeEdge(
                GraphEdgeKey(transition.id, GraphEdgeType.REQUIRES, selector.id),
                routeEvidence.copy(source = routeEvidence.source.copy(evidenceId = routeEvidence.source.evidenceId + ":selector")),
            ))
        }
        atlas.upsertEdgeMetadata(
            key,
            AtlasEdgeMetadata(
                key = routeKey,
                action = AtlasPrivacy.structuralControlLabel(action.semanticName, "Navigate"),
                selectorKey = AtlasGraphIds.selectorDigest(action.selectorJson),
                danger = danger(action.risk, action.label),
                confidence = action.confidence.coerceIn(0.0, 1.0),
                lastObservedAtEpochMillis = observedAtEpochMillis,
                // A demonstrated route is observed successful navigation, not a V5 physical
                // acceptance/verification claim.
                lastVerifiedAtEpochMillis = null,
            ),
        )
    }

    private fun evidence(
        id: String,
        observedAt: Long,
        confidence: Double,
        app: LearnedApp,
        success: Boolean,
    ) = TemporalEdgeEvidence(
        source = GraphEvidenceSource(
            kind = GraphEvidenceKind.FOLLOW_ME_DEMONSTRATION,
            evidenceId = id,
            producer = "follow-me-atlas",
            physicalDeviceEvidence = false,
        ),
        confidence = confidence.coerceIn(0.0, 1.0),
        observedAtEpochMillis = observedAt.coerceAtLeast(0L),
        lastSucceededAtEpochMillis = observedAt.takeIf { success }?.coerceAtLeast(0L),
        lastFailedAtEpochMillis = null,
        successCount = if (success) 1 else 0,
        failureCount = 0,
        appVersion = AppVersionEvidence(app.packageName, app.versionName, app.versionCode),
        verificationState = GraphVerificationState.OBSERVED,
        verificationScope = GraphVerificationScope.NONE,
        staleness = GraphStaleness.CURRENT,
    )
}

private fun semanticCapabilities(actions: List<LearnedAction>): Set<String> =
    actions.asSequence()
        .filter { it.risk == ActionRisk.SAFE }
        .map { AtlasPrivacy.structuralControlLabel(it.semanticName, "Control") }
        .filter { it != "Control" }
        .map { it.uppercase().replace(Regex("[^A-Z0-9_]+"), "_").trim('_') }
        .filter { it.length >= 3 }
        .distinct()
        .take(24)
        .toCollection(linkedSetOf())

private fun danger(risk: ActionRisk, label: String): AtlasDanger = when (risk) {
    ActionRisk.SAFE -> AtlasDanger.NONE
    ActionRisk.AUTHENTICATION -> AtlasDanger.AUTHENTICATION
    ActionRisk.CROSS_APP -> AtlasDanger.UNKNOWN
    ActionRisk.UNKNOWN -> AtlasDanger.UNKNOWN
    ActionRisk.CONSEQUENTIAL -> {
        val normalized = label.lowercase()
        when {
            listOf("pay", "buy", "purchase", "checkout", "subscribe").any(normalized::contains) -> AtlasDanger.PAYMENT
            listOf("delete", "remove account", "erase").any(normalized::contains) -> AtlasDanger.DELETE_ACCOUNT
            listOf("post", "publish", "send").any(normalized::contains) -> AtlasDanger.SEND_PUBLIC
            listOf("logout", "log out", "sign out").any(normalized::contains) -> AtlasDanger.LOGOUT_ALL
            else -> AtlasDanger.UNKNOWN
        }
    }
}

private fun highestDanger(values: List<AtlasDanger>): AtlasDanger =
    values.maxByOrNull {
        when (it) {
            AtlasDanger.NONE -> 0
            AtlasDanger.AUTHENTICATION -> 1
            AtlasDanger.PERMISSION -> 2
            AtlasDanger.UNKNOWN -> 3
            AtlasDanger.SEND_PUBLIC -> 4
            AtlasDanger.LOGOUT_ALL -> 5
            AtlasDanger.DELETE_ACCOUNT -> 6
            AtlasDanger.PAYMENT -> 7
        }
    } ?: AtlasDanger.NONE
