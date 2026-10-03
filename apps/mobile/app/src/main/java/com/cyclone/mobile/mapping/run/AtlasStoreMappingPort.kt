package com.cyclone.mobile.mapping.run

import com.cyclone.mobile.brain.graphv2.AppNode
import com.cyclone.mobile.brain.graphv2.AppVersionEvidence
import com.cyclone.mobile.brain.graphv2.AtlasDanger
import com.cyclone.mobile.brain.graphv2.AtlasEdgeMetadata
import com.cyclone.mobile.brain.graphv2.AtlasGraphIds
import com.cyclone.mobile.brain.graphv2.AtlasMapStatus
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.brain.graphv2.AtlasPlace
import com.cyclone.mobile.brain.graphv2.AtlasPlaceKey
import com.cyclone.mobile.brain.graphv2.AtlasScreenMetadata
import com.cyclone.mobile.brain.graphv2.AtlasStore
import com.cyclone.mobile.brain.graphv2.GraphEdgeKey
import com.cyclone.mobile.brain.graphv2.GraphEdgeType
import com.cyclone.mobile.brain.graphv2.GraphEvidenceKind
import com.cyclone.mobile.brain.graphv2.GraphEvidenceSource
import com.cyclone.mobile.brain.graphv2.GraphNodeId
import com.cyclone.mobile.brain.graphv2.GraphStaleness
import com.cyclone.mobile.brain.graphv2.GraphVerificationScope
import com.cyclone.mobile.brain.graphv2.GraphVerificationState
import com.cyclone.mobile.brain.graphv2.PageNode
import com.cyclone.mobile.brain.graphv2.TemporalEdgeEvidence
import com.cyclone.mobile.brain.graphv2.TemporalKnowledgeEdge
import com.cyclone.mobile.mapping.crawl.MAPPING_PERSONA
import com.cyclone.mobile.mapping.crawl.MappingAtlasHint
import com.cyclone.mobile.mapping.crawl.MappingAtlasPort
import com.cyclone.mobile.mapping.crawl.MappingDanger
import com.cyclone.mobile.mapping.crawl.MappingDoorKind
import com.cyclone.mobile.mapping.crawl.MappingObservation
import com.cyclone.mobile.mapping.crawl.StructuralRoomClassifier
import com.cyclone.mobile.mapping.crawl.VerifiedStructure
import com.cyclone.mobile.mapping.session.AtlasStructuralChange
import com.cyclone.mobile.mapping.session.AtlasStructuralChangeKind
import com.cyclone.mobile.mapping.session.AtlasStructuralEntity

/**
 * Production walker → Atlas port. Writes only the `mapping` persona of one package place, and only
 * structural identity: room keys, door-kind actions and digests. No labels, text or values.
 *
 * Every committed change is also queued as an [AtlasStructuralChange] so the driver can publish it
 * to `atlas.diff` for Glass.
 */
class AtlasStoreMappingPort(
    private val store: AtlasStore,
    private val placeId: String,
    placeLabel: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Installed app version during this pass; every door learned is stamped with it (Glass Versions, needs-remap). */
    private val appVersion: AppVersionEvidence? = null,
    /** Told about each verified door (the App Manual names places and panels from it). Never fails the pass. */
    private val onVerified: (VerifiedStructure) -> Unit = {},
) : MappingAtlasPort {
    private val packageName = placeId.removePrefix("package:")
    private val key = AtlasPlaceKey(placeId, AtlasPersona.MAPPING)
    private val appNode = AppNode(AtlasGraphIds.encoded("app", packageName), packageName, placeLabel)
    private val knownDoors = linkedMapOf<String, MutableSet<String>>()
    private val darkDoors = linkedMapOf<String, MutableSet<String>>()
    private val pending = mutableListOf<AtlasStructuralChange>()

    init {
        require(placeId.startsWith("package:")) { "Autonomous mapping supports package places only" }
        store.registerPlace(
            AtlasPlace.packagePlace(
                packageName = packageName,
                label = placeLabel,
                persona = AtlasPersona.MAPPING,
                mapStatus = AtlasMapStatus.PARTIAL,
                lastObservedAtEpochMillis = clock(),
            ),
        )
        store.mutateGraph(key) { it.registerNode(appNode) }
        // Doors verified by earlier passes stay known; a new pass explores what is still dark.
        store.snapshot(key)?.edgeMetadata?.forEach { meta ->
            meta.selectorKey?.let { knownDoors.getOrPut(meta.key.from.value) { linkedSetOf() } += it }
        }
        pending += placeChange(AtlasMapStatus.PARTIAL)
    }

    /** Structural changes committed since the last drain, in commit order. */
    @Synchronized
    fun drainChanges(): List<AtlasStructuralChange> = pending.toList().also { pending.clear() }

    @Synchronized
    fun darkDoorCount(): Int = darkDoors.values.sumOf { it.size }

    @Synchronized
    fun roomCount(): Int = store.snapshot(key)?.screens?.size ?: 0

    @Synchronized
    fun doorCount(): Int = store.snapshot(key)?.edgeMetadata?.size ?: 0

    @Synchronized
    override fun hint(placeId: String, persona: String, observation: MappingObservation): MappingAtlasHint {
        requireScope(placeId, persona)
        val nodeKey = StructuralRoomClassifier.nodeKey(observation)
        // Seeing a room is enough for it to appear on the board, before any door is walked.
        ensureScreen(nodeKey)
        val known = knownDoors[nodeKey].orEmpty()
        val dark = darkDoors[nodeKey].orEmpty()
        return MappingAtlasHint(
            currentNodeKey = nodeKey,
            knownDoorKeys = observation.doors.filter { doorDigest(it.key) in known }.mapTo(linkedSetOf()) { it.key },
            darkDoorKeys = observation.doors.filter { doorDigest(it.key) in dark }.mapTo(linkedSetOf()) { it.key },
        )
    }

    @Synchronized
    override fun recordVerified(placeId: String, persona: String, structure: VerifiedStructure) {
        requireScope(placeId, persona)
        val now = clock()
        ensureScreen(structure.fromNodeKey)
        ensureScreen(structure.toNodeKey)
        knownDoors.getOrPut(structure.fromNodeKey) { linkedSetOf() } += doorDigest(structure.doorKey)
        runCatching { onVerified(structure) }
        if (structure.fromNodeKey == structure.toNodeKey) return

        val routeKey = GraphEdgeKey(
            GraphNodeId(structure.fromNodeKey),
            GraphEdgeType.NAVIGATES_TO,
            GraphNodeId(structure.toNodeKey),
        )
        val isNew = store.snapshot(key)?.edgeMetadata?.none { it.key == routeKey } ?: true
        store.mutateGraph(key) { graph ->
            graph.record(TemporalKnowledgeEdge(routeKey, evidence(routeKey, now)))
        }
        store.upsertEdgeMetadata(
            key,
            AtlasEdgeMetadata(
                key = routeKey,
                action = actionFor(structure.doorKind),
                selectorKey = doorDigest(structure.doorKey),
                confidence = 0.7,
                lastObservedAtEpochMillis = now,
            ),
        )
        if (isNew) {
            pending += AtlasStructuralChange(
                entity = AtlasStructuralEntity.EDGE,
                change = AtlasStructuralChangeKind.UPSERT,
                id = AtlasGraphIds.wireEdgeId(routeKey),
                fromScreenId = structure.fromNodeKey,
                toScreenId = structure.toNodeKey,
            )
        }
    }

    @Synchronized
    override fun markDanger(placeId: String, persona: String, nodeKey: String, doorKey: String, danger: MappingDanger) {
        requireScope(placeId, persona)
        if (danger == MappingDanger.NONE) return
        darkDoors.getOrPut(nodeKey) { linkedSetOf() } += doorDigest(doorKey)
        val existing = store.snapshot(key)?.screens?.firstOrNull { it.screenId.value == nodeKey } ?: return
        val atlasDanger = atlasDanger(danger)
        if (dangerRank(atlasDanger) > dangerRank(existing.danger)) {
            store.upsertScreen(key, existing.copy(danger = atlasDanger))
        }
    }

    @Synchronized
    override fun markPartial(placeId: String, persona: String, reason: String) {
        requireScope(placeId, persona)
        finish(mapped = false)
    }

    /** Final place status: mapped only when the pass left no dangerous or dark doors behind. */
    @Synchronized
    fun finish(mapped: Boolean): AtlasMapStatus {
        val status = if (mapped && darkDoorCount() == 0) AtlasMapStatus.MAPPED else AtlasMapStatus.PARTIAL
        store.setMapStatus(key, status)
        pending += placeChange(status)
        return status
    }

    private fun ensureScreen(nodeKey: String) {
        val id = GraphNodeId(nodeKey)
        val snapshot = store.snapshot(key)
        if (snapshot?.screens?.any { it.screenId == id } == true) return
        val purpose = purposeFor(nodeKey)
        store.mutateGraph(key) { graph ->
            graph.registerNode(PageNode(id, packageName, nodeKey, purpose))
            graph.record(
                TemporalKnowledgeEdge(
                    GraphEdgeKey(appNode.id, GraphEdgeType.CONTAINS, id),
                    evidence(GraphEdgeKey(appNode.id, GraphEdgeType.CONTAINS, id), clock()),
                ),
            )
        }
        val layout = AtlasGraphIds.stableLayout(placeId + ":" + MAPPING_PERSONA + ":" + nodeKey, snapshot?.screens?.size ?: 0)
        store.upsertScreen(
            key,
            AtlasScreenMetadata(
                screenId = id,
                purpose = purpose,
                confidence = 0.7,
                lastObservedAtEpochMillis = clock(),
                layoutX = layout.first,
                layoutY = layout.second,
            ),
        )
        pending += AtlasStructuralChange(
            entity = AtlasStructuralEntity.SCREEN,
            change = AtlasStructuralChangeKind.UPSERT,
            id = nodeKey,
            layoutX = layout.first,
            layoutY = layout.second,
        )
    }

    private fun placeChange(status: AtlasMapStatus) = AtlasStructuralChange(
        entity = AtlasStructuralEntity.PLACE,
        change = AtlasStructuralChangeKind.UPSERT,
        id = "place",
        mapStatus = status.wireValue,
    )

    private fun evidence(edge: GraphEdgeKey, now: Long) = TemporalEdgeEvidence(
        source = GraphEvidenceSource(
            kind = GraphEvidenceKind.ACTION_AFTER_STATE,
            evidenceId = "mapper:" + AtlasGraphIds.wireEdgeId(edge) + ":" + now,
            producer = "cyclone-mapper",
            physicalDeviceEvidence = false,
        ),
        confidence = 0.7,
        observedAtEpochMillis = now,
        lastSucceededAtEpochMillis = now,
        lastFailedAtEpochMillis = null,
        successCount = 1,
        failureCount = 0,
        appVersion = appVersion,
        verificationState = GraphVerificationState.OBSERVED,
        verificationScope = GraphVerificationScope.NONE,
        staleness = GraphStaleness.CURRENT,
    )

    private fun requireScope(placeId: String, persona: String) {
        require(placeId == this.placeId) { "Mapping Atlas port is bound to one place" }
        require(persona == MAPPING_PERSONA) { "Autonomous mapping writes only the mapping persona" }
    }

    internal companion object {
        fun doorDigest(doorKey: String): String = AtlasGraphIds.selectorDigest("mapping-door|$doorKey")

        /** `screen:<purpose>:<digest>` → a frozen structural purpose word, never page content. */
        fun purposeFor(nodeKey: String): String = when (nodeKey.split(':').getOrNull(1)) {
            "home" -> "Home"
            "settings" -> "Settings"
            "account" -> "Account"
            "search" -> "Search"
            "menu" -> "Menu"
            "list" -> "List"
            "detail" -> "Detail"
            "login" -> "Login"
            "permissions" -> "Permissions"
            else -> "Screen"
        }

        fun actionFor(kind: MappingDoorKind): String = when (kind) {
            MappingDoorKind.TAB -> "Open tab"
            MappingDoorKind.MENU -> "Open menu"
            MappingDoorKind.NAV_DRAWER -> "Open navigation"
            MappingDoorKind.SETTINGS -> "Open settings"
            MappingDoorKind.SEARCH -> "Open search"
            MappingDoorKind.ACCOUNT -> "Open account"
            MappingDoorKind.BACK -> "Go back"
            MappingDoorKind.HOME -> "Go home"
            MappingDoorKind.REVEAL -> "Open panel"
            MappingDoorKind.STRUCTURAL_SAMPLE -> "Open item"
            MappingDoorKind.CONTENT_ROW, MappingDoorKind.UNKNOWN -> "Navigate"
        }

        fun atlasDanger(danger: MappingDanger): AtlasDanger = when (danger) {
            MappingDanger.NONE -> AtlasDanger.NONE
            MappingDanger.PAY -> AtlasDanger.PAYMENT
            MappingDanger.SEND_PUBLIC -> AtlasDanger.SEND_PUBLIC
            MappingDanger.DELETE -> AtlasDanger.DELETE_ACCOUNT
            MappingDanger.LOGOUT_ALL -> AtlasDanger.LOGOUT_ALL
            MappingDanger.GRANT -> AtlasDanger.PERMISSION
            MappingDanger.REVIEW_BOUNDARY -> AtlasDanger.UNKNOWN
            MappingDanger.SETTING_CHANGE -> AtlasDanger.UNKNOWN
            MappingDanger.STATE_CHANGE -> AtlasDanger.UNKNOWN
            MappingDanger.SECURITY -> AtlasDanger.AUTHENTICATION
            MappingDanger.ACCOUNT -> AtlasDanger.AUTHENTICATION
        }

        private fun dangerRank(value: AtlasDanger): Int = when (value) {
            AtlasDanger.NONE -> 0
            AtlasDanger.AUTHENTICATION -> 1
            AtlasDanger.PERMISSION -> 2
            AtlasDanger.UNKNOWN -> 3
            AtlasDanger.SEND_PUBLIC -> 4
            AtlasDanger.LOGOUT_ALL -> 5
            AtlasDanger.DELETE_ACCOUNT -> 6
            AtlasDanger.PAYMENT -> 7
        }
    }
}
