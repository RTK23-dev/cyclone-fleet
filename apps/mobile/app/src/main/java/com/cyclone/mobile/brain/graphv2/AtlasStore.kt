package com.cyclone.mobile.brain.graphv2

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable, phone-local Atlas. Each (place, persona) owns an isolated Graph-v2 store; callers cannot
 * obtain an unscoped graph. The on-disk snapshot contains only Atlas/Graph-v2 fields and never
 * legacy dynamic_json, screenshot paths, raw frames, or typed values.
 */
class AtlasStore(
    private val file: File,
) : Closeable {
    private data class Record(
        var place: AtlasPlace,
        val graph: InMemoryTemporalGraphStore = InMemoryTemporalGraphStore(),
        val screens: LinkedHashMap<GraphNodeId, AtlasScreenMetadata> = linkedMapOf(),
        val edgeMetadata: LinkedHashMap<GraphEdgeKey, AtlasEdgeMetadata> = linkedMapOf(),
    )

    private val records = linkedMapOf<AtlasPlaceKey, Record>()
    private var batchDepth = 0
    private var dirty = false

    init {
        load()
    }

    @Synchronized
    fun registerPlace(place: AtlasPlace): AtlasPlace {
        val safe = place.copy(
            label = AtlasPrivacy.structuralLabel(place.label, place.packageName ?: place.origin ?: "App"),
        )
        val existing = records[safe.key]
        if (existing == null) {
            records[safe.key] = Record(safe)
            markDirty()
            return safe
        }
        require(existing.place.kind == safe.kind) { "Atlas place kind cannot change" }
        require(existing.place.packageName == safe.packageName) { "Atlas package identity cannot change" }
        require(existing.place.origin == safe.origin) { "Atlas origin identity cannot change" }
        existing.place = safe.copy(
            mapStatus = mergeStatus(existing.place.mapStatus, safe.mapStatus),
            lastObservedAtEpochMillis = maxNullable(existing.place.lastObservedAtEpochMillis, safe.lastObservedAtEpochMillis),
            lastVerifiedAtEpochMillis = maxNullable(existing.place.lastVerifiedAtEpochMillis, safe.lastVerifiedAtEpochMillis),
        )
        markDirty()
        return existing.place
    }

    @Synchronized
    fun ensurePackagePlace(
        packageName: String,
        label: String,
        persona: AtlasPersona,
    ): AtlasPlace = registerPlace(AtlasPlace.packagePlace(packageName, label, persona))

    @Synchronized
    fun ensureChromeOrigin(
        origin: String,
        label: String,
        persona: AtlasPersona,
    ): AtlasPlace = registerPlace(AtlasPlace.chromeOrigin(origin, label, persona))

    @Synchronized
    fun place(key: AtlasPlaceKey): AtlasPlace? = records[key]?.place

    @Synchronized
    fun places(persona: AtlasPersona? = null): List<AtlasPlaceSummary> =
        records.values.asSequence()
            .filter { persona == null || it.place.persona == persona }
            .map { record ->
                AtlasPlaceSummary(
                    place = record.place,
                    screenCount = record.screens.size,
                    edgeCount = currentNavigationEdges(record).size,
                )
            }
            .sortedWith(compareBy({ it.place.label.lowercase() }, { it.place.id }, { it.place.persona.wireValue }))
            .toList()

    /**
     * Returns a Graph-v2 view that is permanently scoped to one persona and one place. There is no
     * API for recording an edge without an AtlasPlaceKey.
     */
    @Synchronized
    fun graph(key: AtlasPlaceKey): TemporalGraphStore {
        require(records.containsKey(key)) { "Register Atlas place before requesting its graph" }
        return ScopedGraph(key)
    }

    /** Alpha 91: many changes, one write to disk (the legacy import wrote and fsynced once per screen). */
    @Synchronized
    fun <T> batch(block: () -> T): T {
        batchDepth += 1
        return try {
            block()
        } finally {
            batchDepth -= 1
            flushIfNeeded()
        }
    }

    @Synchronized
    fun <T> mutateGraph(key: AtlasPlaceKey, block: (TemporalGraphStore) -> T): T {
        require(records.containsKey(key)) { "Register Atlas place before mutating its graph" }
        batchDepth += 1
        return try {
            block(ScopedGraph(key))
        } finally {
            batchDepth -= 1
            flushIfNeeded()
        }
    }

    @Synchronized
    fun upsertScreen(key: AtlasPlaceKey, metadata: AtlasScreenMetadata) {
        val record = records[key] ?: error("Unknown Atlas place")
        require(record.graph.node(metadata.screenId) is PageNode) {
            "Atlas screen metadata requires a registered Graph-v2 PAGE node"
        }
        record.screens[metadata.screenId] = metadata.copy(
            purpose = AtlasPrivacy.structuralScreenPurpose(metadata.purpose),
            capabilities = metadata.capabilities.map(::sanitizeCapability).filter(String::isNotBlank).toSet(),
            factSlots = metadata.factSlots.map(::sanitizeFactSlot),
        )
        record.place = record.place.copy(
            mapStatus = if (metadata.danger == AtlasDanger.UNKNOWN) AtlasMapStatus.PARTIAL else mergeStatus(record.place.mapStatus, AtlasMapStatus.PARTIAL),
            lastObservedAtEpochMillis = maxNullable(record.place.lastObservedAtEpochMillis, metadata.lastObservedAtEpochMillis),
            lastVerifiedAtEpochMillis = maxNullable(record.place.lastVerifiedAtEpochMillis, metadata.lastVerifiedAtEpochMillis),
        )
        markDirty()
    }

    @Synchronized
    fun upsertEdgeMetadata(key: AtlasPlaceKey, metadata: AtlasEdgeMetadata) {
        val record = records[key] ?: error("Unknown Atlas place")
        require(record.graph.node(metadata.key.from) != null && record.graph.node(metadata.key.to) != null) {
            "Atlas edge metadata requires registered Graph-v2 nodes"
        }
        record.edgeMetadata[metadata.key] = metadata.copy(
            action = AtlasPrivacy.structuralControlLabel(metadata.action, "Navigate"),
            selectorKey = metadata.selectorKey?.let {
                if (it.startsWith("sha256:")) it else AtlasGraphIds.selectorDigest(it)
            },
        )
        record.place = record.place.copy(
            mapStatus = mergeStatus(record.place.mapStatus, AtlasMapStatus.PARTIAL),
            lastObservedAtEpochMillis = maxNullable(record.place.lastObservedAtEpochMillis, metadata.lastObservedAtEpochMillis),
            lastVerifiedAtEpochMillis = maxNullable(record.place.lastVerifiedAtEpochMillis, metadata.lastVerifiedAtEpochMillis),
        )
        markDirty()
    }

    @Synchronized
    fun setMapStatus(key: AtlasPlaceKey, status: AtlasMapStatus) {
        val record = records[key] ?: error("Unknown Atlas place")
        record.place = record.place.copy(mapStatus = status)
        markDirty()
    }

    @Synchronized
    fun snapshot(key: AtlasPlaceKey): AtlasGraphSnapshot? {
        val record = records[key] ?: return null
        return AtlasGraphSnapshot(
            place = record.place,
            nodes = record.graph.nodes(),
            edges = record.graph.currentEdges(includeStale = true),
            screens = record.screens.values.sortedBy { it.screenId.value },
            edgeMetadata = record.edgeMetadata.values.sortedWith(compareBy({ it.key }, { it.action })),
        )
    }

    @Synchronized
    override fun close() {
        flushIfNeeded(force = true)
    }

    private inner class ScopedGraph(
        private val key: AtlasPlaceKey,
    ) : TemporalGraphStore {
        private fun delegate(): InMemoryTemporalGraphStore = records[key]?.graph
            ?: error("Atlas graph scope disappeared")

        override fun registerNode(node: GraphNode) {
            synchronized(this@AtlasStore) {
                delegate().registerNode(AtlasPrivacy.safeNode(node))
                markDirty()
            }
        }

        override fun node(id: GraphNodeId): GraphNode? = synchronized(this@AtlasStore) {
            delegate().node(id)
        }

        override fun nodes(): List<GraphNode> = synchronized(this@AtlasStore) {
            delegate().nodes()
        }

        override fun record(edge: TemporalKnowledgeEdge): EdgeRecordResult = synchronized(this@AtlasStore) {
            val result = delegate().record(edge)
            if (result is EdgeRecordResult.Recorded) markDirty()
            result
        }

        override fun history(key: GraphEdgeKey): List<TemporalKnowledgeEdge> = synchronized(this@AtlasStore) {
            delegate().history(key)
        }

        override fun currentEdges(includeStale: Boolean): List<TemporalKnowledgeEdge> = synchronized(this@AtlasStore) {
            delegate().currentEdges(includeStale)
        }
    }

    @Synchronized
    private fun markDirty() {
        dirty = true
        flushIfNeeded()
    }

    @Synchronized
    private fun flushIfNeeded(force: Boolean = false) {
        if (!dirty || (!force && batchDepth > 0)) return
        persist()
        dirty = false
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile ?: File("."), file.name + ".tmp")
        val bytes = encode().toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        runCatching {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun load() {
        if (!file.exists() || file.length() == 0L) return
        val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        if (root.optInt("version", 0) != FORMAT_VERSION) return
        val entries = root.optJSONArray("records") ?: return
        for (i in 0 until entries.length()) {
            val json = entries.optJSONObject(i) ?: continue
            val place = decodePlace(json.optJSONObject("place") ?: continue) ?: continue
            val record = Record(place)
            val nodes = json.optJSONArray("nodes") ?: JSONArray()
            for (n in 0 until nodes.length()) {
                decodeNode(nodes.optJSONObject(n) ?: continue)?.let { record.graph.registerNode(it) }
            }
            val edges = json.optJSONArray("edges") ?: JSONArray()
            for (e in 0 until edges.length()) {
                decodeEdge(edges.optJSONObject(e) ?: continue)?.let { record.graph.record(it) }
            }
            val screens = json.optJSONArray("screens") ?: JSONArray()
            for (s in 0 until screens.length()) {
                decodeScreen(screens.optJSONObject(s) ?: continue)?.let { record.screens[it.screenId] = it }
            }
            val edgeMeta = json.optJSONArray("edgeMetadata") ?: JSONArray()
            for (m in 0 until edgeMeta.length()) {
                decodeEdgeMetadata(edgeMeta.optJSONObject(m) ?: continue)?.let { record.edgeMetadata[it.key] = it }
            }
            records[place.key] = record
        }
        dirty = false
    }

    private fun encode(): JSONObject = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("records", JSONArray().also { out ->
            records.values.sortedWith(compareBy({ it.place.id }, { it.place.persona.wireValue })).forEach { record ->
                out.put(JSONObject()
                    .put("place", encodePlace(record.place))
                    .put("nodes", JSONArray().also { a -> record.graph.nodes().forEach { a.put(encodeNode(it)) } })
                    .put("edges", JSONArray().also { a ->
                        record.graph.currentEdges(includeStale = true).forEach { a.put(encodeEdge(it)) }
                    })
                    .put("screens", JSONArray().also { a ->
                        record.screens.values.sortedBy { it.screenId.value }.forEach { a.put(encodeScreen(it)) }
                    })
                    .put("edgeMetadata", JSONArray().also { a ->
                        record.edgeMetadata.values.sortedBy { it.key }.forEach { a.put(encodeEdgeMetadata(it)) }
                    }))
            }
        })

    private fun encodePlace(place: AtlasPlace) = JSONObject()
        .put("id", place.id)
        .put("kind", place.kind.wireValue)
        .put("label", place.label)
        .put("packageName", place.packageName ?: JSONObject.NULL)
        .put("origin", place.origin ?: JSONObject.NULL)
        .put("persona", place.persona.wireValue)
        .put("mapStatus", place.mapStatus.wireValue)
        .put("lastObservedAt", place.lastObservedAtEpochMillis ?: JSONObject.NULL)
        .put("lastVerifiedAt", place.lastVerifiedAtEpochMillis ?: JSONObject.NULL)

    private fun decodePlace(json: JSONObject): AtlasPlace? = runCatching {
        AtlasPlace(
            id = json.getString("id"),
            kind = AtlasPlaceKind.entries.first { it.wireValue == json.getString("kind") },
            label = json.getString("label"),
            packageName = json.nullableString("packageName"),
            origin = json.nullableString("origin"),
            persona = AtlasPersona.fromWire(json.getString("persona")),
            mapStatus = AtlasMapStatus.entries.first { it.wireValue == json.getString("mapStatus") },
            lastObservedAtEpochMillis = json.nullableLong("lastObservedAt"),
            lastVerifiedAtEpochMillis = json.nullableLong("lastVerifiedAt"),
        )
    }.getOrNull()

    private fun encodeNode(node: GraphNode): JSONObject = JSONObject()
        .put("id", node.id.value)
        .put("type", node.type.name)
        .put("displayName", node.displayName)
        .apply {
            when (node) {
                is AppNode -> put("packageName", node.packageName)
                is ActivityNode -> put("packageName", node.packageName).put("className", node.className)
                is PageNode -> put("packageName", node.packageName).put("identity", node.identity)
                is ElementNode -> put("semanticName", node.semanticName)
                is SelectorNode -> put("selectorKey", node.selectorKey)
                is TransitionNode -> put("actionName", node.actionName)
                is RoutineNode -> put("routineId", node.routineId)
                is CapabilityNode -> put("capabilityId", node.capabilityId)
            }
        }

    private fun decodeNode(json: JSONObject): GraphNode? = runCatching {
        val id = GraphNodeId(json.getString("id"))
        val display = json.getString("displayName")
        when (GraphNodeType.valueOf(json.getString("type"))) {
            GraphNodeType.APP -> AppNode(id, json.getString("packageName"), display)
            GraphNodeType.ACTIVITY -> ActivityNode(id, json.getString("packageName"), json.getString("className"), display)
            GraphNodeType.PAGE -> PageNode(id, json.getString("packageName"), json.getString("identity"), display)
            GraphNodeType.ELEMENT -> ElementNode(id, json.getString("semanticName"), display)
            GraphNodeType.SELECTOR -> SelectorNode(id, json.getString("selectorKey"), display)
            GraphNodeType.TRANSITION -> TransitionNode(id, json.getString("actionName"), display)
            GraphNodeType.ROUTINE -> RoutineNode(id, json.getString("routineId"), display)
            GraphNodeType.CAPABILITY -> CapabilityNode(id, json.getString("capabilityId"), display)
        }
    }.getOrNull()

    private fun encodeEdge(edge: TemporalKnowledgeEdge): JSONObject = JSONObject()
        .put("from", edge.key.from.value)
        .put("type", edge.key.type.name)
        .put("to", edge.key.to.value)
        .put("sourceKind", edge.evidence.source.kind.name)
        .put("evidenceId", edge.evidence.source.evidenceId)
        .put("producer", edge.evidence.source.producer)
        .put("physicalDeviceEvidence", edge.evidence.source.physicalDeviceEvidence)
        .put("confidence", edge.evidence.confidence)
        .put("observedAt", edge.evidence.observedAtEpochMillis)
        .put("lastSucceededAt", edge.evidence.lastSucceededAtEpochMillis ?: JSONObject.NULL)
        .put("lastFailedAt", edge.evidence.lastFailedAtEpochMillis ?: JSONObject.NULL)
        .put("successCount", edge.evidence.successCount)
        .put("failureCount", edge.evidence.failureCount)
        .put("verificationState", edge.evidence.verificationState.name)
        .put("verificationScope", edge.evidence.verificationScope.name)
        .put("staleness", edge.evidence.staleness.name)
        .put("appVersion", edge.evidence.appVersion?.let {
            JSONObject().put("packageName", it.packageName)
                .put("versionName", it.versionName ?: JSONObject.NULL)
                .put("versionCode", it.versionCode ?: JSONObject.NULL)
        } ?: JSONObject.NULL)

    private fun decodeEdge(json: JSONObject): TemporalKnowledgeEdge? = runCatching {
        val version = json.optJSONObject("appVersion")?.let {
            AppVersionEvidence(
                packageName = it.getString("packageName"),
                versionName = it.nullableString("versionName"),
                versionCode = it.nullableLong("versionCode"),
            )
        }
        TemporalKnowledgeEdge(
            key = GraphEdgeKey(
                GraphNodeId(json.getString("from")),
                GraphEdgeType.valueOf(json.getString("type")),
                GraphNodeId(json.getString("to")),
            ),
            evidence = TemporalEdgeEvidence(
                source = GraphEvidenceSource(
                    kind = GraphEvidenceKind.valueOf(json.getString("sourceKind")),
                    evidenceId = json.getString("evidenceId"),
                    producer = json.getString("producer"),
                    physicalDeviceEvidence = json.optBoolean("physicalDeviceEvidence", false),
                ),
                confidence = json.getDouble("confidence"),
                observedAtEpochMillis = json.getLong("observedAt"),
                lastSucceededAtEpochMillis = json.nullableLong("lastSucceededAt"),
                lastFailedAtEpochMillis = json.nullableLong("lastFailedAt"),
                successCount = json.getInt("successCount"),
                failureCount = json.getInt("failureCount"),
                appVersion = version,
                verificationState = GraphVerificationState.valueOf(json.getString("verificationState")),
                verificationScope = GraphVerificationScope.valueOf(json.getString("verificationScope")),
                staleness = GraphStaleness.valueOf(json.getString("staleness")),
            ),
        )
    }.getOrNull()

    private fun encodeScreen(screen: AtlasScreenMetadata) = JSONObject()
        .put("screenId", screen.screenId.value)
        .put("purpose", screen.purpose)
        .put("capabilities", JSONArray(screen.capabilities.sorted()))
        .put("danger", screen.danger.wireValue)
        .put("confidence", screen.confidence)
        .put("lastObservedAt", screen.lastObservedAtEpochMillis)
        .put("lastVerifiedAt", screen.lastVerifiedAtEpochMillis ?: JSONObject.NULL)
        .put("layoutX", screen.layoutX)
        .put("layoutY", screen.layoutY)
        .put("factSlots", JSONArray().also { a ->
            screen.factSlots.sortedBy { it.name }.forEach { slot ->
                a.put(JSONObject()
                    .put("name", slot.name)
                    .put("screenId", slot.screenId.value)
                    .put("selectorKey", slot.selectorKey ?: JSONObject.NULL)
                    .put("purpose", slot.purpose)
                    .put("confidence", slot.confidence))
            }
        })

    private fun decodeScreen(json: JSONObject): AtlasScreenMetadata? = runCatching {
        val slots = json.optJSONArray("factSlots") ?: JSONArray()
        val screenId = GraphNodeId(json.getString("screenId"))
        AtlasScreenMetadata(
            screenId = screenId,
            purpose = json.getString("purpose"),
            capabilities = json.optJSONArray("capabilities").strings().toSet(),
            factSlots = buildList {
                for (i in 0 until slots.length()) {
                    val slot = slots.getJSONObject(i)
                    add(AtlasFactSlot(
                        name = slot.getString("name"),
                        screenId = screenId,
                        selectorKey = slot.nullableString("selectorKey"),
                        purpose = slot.getString("purpose"),
                        confidence = slot.getDouble("confidence"),
                    ))
                }
            },
            danger = AtlasDanger.entries.first { it.wireValue == json.getString("danger") },
            confidence = json.getDouble("confidence"),
            lastObservedAtEpochMillis = json.getLong("lastObservedAt"),
            lastVerifiedAtEpochMillis = json.nullableLong("lastVerifiedAt"),
            layoutX = json.getDouble("layoutX"),
            layoutY = json.getDouble("layoutY"),
        )
    }.getOrNull()

    private fun encodeEdgeMetadata(meta: AtlasEdgeMetadata) = JSONObject()
        .put("from", meta.key.from.value)
        .put("type", meta.key.type.name)
        .put("to", meta.key.to.value)
        .put("action", meta.action)
        .put("selectorKey", meta.selectorKey ?: JSONObject.NULL)
        .put("danger", meta.danger.wireValue)
        .put("confidence", meta.confidence)
        .put("lastObservedAt", meta.lastObservedAtEpochMillis)
        .put("lastVerifiedAt", meta.lastVerifiedAtEpochMillis ?: JSONObject.NULL)

    private fun decodeEdgeMetadata(json: JSONObject): AtlasEdgeMetadata? = runCatching {
        AtlasEdgeMetadata(
            key = GraphEdgeKey(
                GraphNodeId(json.getString("from")),
                GraphEdgeType.valueOf(json.getString("type")),
                GraphNodeId(json.getString("to")),
            ),
            action = json.getString("action"),
            selectorKey = json.nullableString("selectorKey"),
            danger = AtlasDanger.entries.first { it.wireValue == json.getString("danger") },
            confidence = json.getDouble("confidence"),
            lastObservedAtEpochMillis = json.getLong("lastObservedAt"),
            lastVerifiedAtEpochMillis = json.nullableLong("lastVerifiedAt"),
        )
    }.getOrNull()

    private fun currentNavigationEdges(record: Record): List<TemporalKnowledgeEdge> =
        record.graph.currentEdges(includeStale = true)
            .filter { it.key.type in NAVIGATION_EDGES && record.graph.node(it.key.from) is PageNode && record.graph.node(it.key.to) is PageNode }

    private fun sanitizeFactSlot(slot: AtlasFactSlot): AtlasFactSlot = slot.copy(
        selectorKey = slot.selectorKey?.let {
            if (it.startsWith("sha256:")) it else AtlasGraphIds.selectorDigest(it)
        },
        // Fact definitions describe a slot, never the value/content observed in that slot.
        purpose = "Read " + slot.name.replace('_', ' ') + " from this screen",
    )

    private fun sanitizeCapability(value: String): String =
        value.uppercase().replace(Regex("[^A-Z0-9_]+"), "_").trim('_').take(80)

    private fun mergeStatus(a: AtlasMapStatus, b: AtlasMapStatus): AtlasMapStatus {
        if (a == AtlasMapStatus.STALE || b == AtlasMapStatus.STALE) return AtlasMapStatus.STALE
        return if (statusRank(b) > statusRank(a)) b else a
    }

    private fun statusRank(status: AtlasMapStatus): Int = when (status) {
        AtlasMapStatus.UNMAPPED -> 0
        AtlasMapStatus.PARTIAL -> 1
        AtlasMapStatus.MAPPED -> 2
        AtlasMapStatus.STALE -> 3
    }

    private fun maxNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

    private fun JSONObject.nullableLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name)

    private fun JSONArray.strings(): List<String> = buildList {
        for (i in 0 until length()) optString(i).takeIf(String::isNotBlank)?.let(::add)
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private val NAVIGATION_EDGES = setOf(GraphEdgeType.NAVIGATES_TO, GraphEdgeType.OPENS, GraphEdgeType.SUBMITS)
    }
}
