package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.brain.graphv2.*

/** Per-run, one-door navigation. A saved selector is useful only when it matches one live target. */
class AtlasNavigator {
    data class Target(val elementId: String, val selectorKeys: Set<String>, val safe: Boolean)
    data class Step(
        val placeId: String,
        val fromRoom: String,
        val expectedRoom: String,
        val targetRoom: String,
        val elementId: String,
        val observationId: String,
        val edgeId: String,
        val objective: String,
        val rerouted: Boolean = false,
    )

    private val attempted = mutableSetOf<String>()
    private var lookAfterMiss = false
    val needsLook: Boolean get() = lookAfterMiss
    private var recovery: Step? = null

    fun next(
        store: AtlasStore,
        placeId: String,
        roomId: String,
        goal: String,
        observationId: String,
        targets: List<Target>,
    ): Step? {
        val reroute = recovery?.takeIf { it.placeId == placeId && it.objective == goal }
        recovery = null
        if (lookAfterMiss && reroute == null) {
            lookAfterMiss = false
            return null
        }
        lookAfterMiss = false
        if (observationId.isBlank()) return null
        val room = runCatching { GraphNodeId(roomId) }.getOrNull() ?: return null
        val snapshots = store.places().filter { it.place.id == placeId }
            .mapNotNull { store.snapshot(it.place.key) }
            .filter { it.place.mapStatus != AtlasMapStatus.STALE && it.screens.any { s -> s.screenId == room } }
            .sortedWith(compareByDescending<AtlasGraphSnapshot> { it.screens.size }
                .thenBy { if (it.place.persona == AtlasPersona.LIVE) 0 else 1 })
        for (snapshot in snapshots) {
            val destination = reroute?.targetRoom?.let(::GraphNodeId)
                ?: AtlasRetriever(store).findHint(snapshot.place.key, goal, room)?.destinationScreen ?: continue
            val eligible = snapshot.edges.filter { safe(it, snapshot) &&
                "$placeId|${AtlasGraphIds.wireEdgeId(it.key)}" !in attempted }
            val edges = AtlasRoutes.shortest(room, eligible)[destination] ?: continue
            val edge = edges.firstOrNull() ?: continue
            val meta = snapshot.edgeMetadata.singleOrNull { it.key == edge.key } ?: continue
            val edgeId = AtlasGraphIds.wireEdgeId(edge.key)
            if ("$placeId|$edgeId" in attempted) continue
            val selectorKey = meta.selectorKey ?: continue
            // Count unsafe matches too: a duplicate cannot be made unique by filtering one away.
            val target = targets.filter { selectorKey in it.selectorKeys }.singleOrNull()
                ?.takeIf { it.safe } ?: continue
            return Step(placeId, roomId, edge.key.to.value, destination.value,
                target.elementId, observationId, edgeId, goal, reroute != null)
        }
        return null
    }

    fun dispatched(step: Step) { attempted += "${step.placeId}|${step.edgeId}" }

    fun verified(step: Step, placeId: String?, roomId: String?, accepted: Boolean): Boolean {
        val matches = accepted && placeId == step.placeId && roomId == step.expectedRoom
        if (!matches) {
            lookAfterMiss = true
            if (accepted && placeId == step.placeId && roomId != null && roomId != step.fromRoom) recovery = step
        }
        return matches
    }

    private fun safe(edge: TemporalKnowledgeEdge, snapshot: AtlasGraphSnapshot): Boolean {
        if (edge.key.type !in setOf(GraphEdgeType.NAVIGATES_TO, GraphEdgeType.OPENS)) return false
        val evidence = edge.evidence
        if (!evidence.source.kind.deterministic || evidence.staleness != GraphStaleness.CURRENT ||
            evidence.verificationState !in setOf(GraphVerificationState.VERIFIED, GraphVerificationState.OBSERVED) ||
            evidence.successCount < 1 || evidence.lastSucceededAtEpochMillis == null ||
            (evidence.lastFailedAtEpochMillis ?: -1) >= evidence.lastSucceededAtEpochMillis ||
            evidence.confidence < .68) return false
        val meta = snapshot.edgeMetadata.singleOrNull { it.key == edge.key } ?: return false
        return meta.danger == AtlasDanger.NONE && meta.confidence >= .70 &&
            listOf(edge.key.from, edge.key.to).all { id ->
                snapshot.screens.singleOrNull { it.screenId == id }?.let {
                    it.danger == AtlasDanger.NONE && it.confidence >= .65
                } == true
            }
    }
}
