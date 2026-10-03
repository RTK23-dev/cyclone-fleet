package com.cyclone.mobile.brain.graphv2

import com.cyclone.mobile.places.PlaceResolver
import com.cyclone.mobile.places.ResolvedPlace
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Narrow phone-owned read surface for Agent 001's gateway adapter.
 *
 * The provider emits only the fields accepted by cyclone-atlas-v1.schema.json. Internal Atlas
 * metadata may be richer (for example PARTIAL map status and hashed selector hints), but raw
 * selectors, typed values, screenshots, legacy dynamic_json, and secret-shaped values never cross
 * this boundary.
 */
interface AtlasReadProvider {
    fun places(persona: AtlasPersona? = null): JSONObject
    fun get(placeId: String, persona: AtlasPersona): JSONObject?
}

class StoreBackedAtlasReadProvider(
    private val store: AtlasStore,
) : AtlasReadProvider {
    override fun places(persona: AtlasPersona?): JSONObject = JSONObject()
        .put("places", JSONArray().also { array ->
            store.places(persona).forEach { summary ->
                array.put(placeSummaryJson(summary))
            }
        })

    override fun get(placeId: String, persona: AtlasPersona): JSONObject? {
        val snapshot = store.snapshot(AtlasPlaceKey(placeId, persona)) ?: return null
        val pages = snapshot.nodes.filterIsInstance<PageNode>().associateBy { it.id }
        val screenMeta = snapshot.screens.associateBy { it.screenId }
        val edgeMeta = snapshot.edgeMetadata.associateBy { it.key }
        val navigation = snapshot.edges
            .filter { it.key.type in NAVIGATION_EDGES && it.key.from in pages && it.key.to in pages }
            .sortedBy { it.key }
        val capabilities = snapshot.screens
            .flatMap { it.capabilities }
            .map(::wireCapability)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
            .take(64)
        val confidence = snapshot.screens.minOfOrNull { it.confidence } ?: 0.0

        return JSONObject()
            .put("place", placeJson(snapshot.place))
            .put("persona", snapshot.place.persona.wireValue)
            .put("mapStatus", wireMapStatus(snapshot))
            .put("screens", JSONArray().also { out ->
                pages.values.sortedBy { it.id.value }.forEachIndexed { index, page ->
                    val meta = screenMeta[page.id]
                    val fallback = AtlasGraphIds.stableLayout(
                        snapshot.place.id + ":" + persona.wireValue,
                        index,
                    )
                    out.put(JSONObject()
                        .put("screenId", page.id.value.take(160))
                        .put("label", AtlasPrivacy.structuralScreenLabel(page.displayName, "Screen").take(120))
                        .put("purpose", (meta?.purpose ?: AtlasPrivacy.structuralScreenPurpose(page.displayName)).take(200))
                        .put("factSlots", JSONArray().also { facts ->
                            meta?.factSlots?.sortedBy { it.name }.orEmpty().forEach { slot ->
                                safeFactSlotName(slot.name)?.let {
                                    facts.put(factSlotJson(slot, it))
                                }
                            }
                        })
                        .put("risk", riskJson(meta?.danger ?: AtlasDanger.NONE))
                        .put("confidence", meta?.confidence ?: 0.0)
                        .put("lastObservedAt", timestamp(meta?.lastObservedAtEpochMillis))
                        .put("lastVerifiedAt", timestamp(meta?.lastVerifiedAtEpochMillis))
                        .put("layout", JSONObject()
                            .put("x", meta?.layoutX ?: fallback.first)
                            .put("y", meta?.layoutY ?: fallback.second)))
                }
            })
            .put("edges", JSONArray().also { out ->
                navigation.forEach { edge ->
                    val meta = edgeMeta[edge.key]
                    out.put(JSONObject()
                        .put("edgeId", stableEdgeId(edge.key))
                        .put("fromScreenId", edge.key.from.value.take(160))
                        .put("toScreenId", edge.key.to.value.take(160))
                        .put("actionHint", AtlasPrivacy.structuralControlLabel(meta?.action ?: "navigate", "Navigate").take(160))
                        .put("risk", riskJson(meta?.danger ?: AtlasDanger.NONE))
                        .put("confidence", (meta?.confidence ?: edge.evidence.confidence).coerceIn(0.0, 1.0))
                        .put("lastVerifiedAt", timestamp(meta?.lastVerifiedAtEpochMillis)))
                }
            })
            .put("capabilities", JSONArray(capabilities))
            .put("confidence", confidence.coerceIn(0.0, 1.0))
            .put("lastObservedAt", timestamp(snapshot.place.lastObservedAtEpochMillis))
            .put("lastVerifiedAt", timestamp(snapshot.place.lastVerifiedAtEpochMillis))
    }

    private fun placeSummaryJson(summary: AtlasPlaceSummary): JSONObject {
        val snapshot = store.snapshot(summary.place.key)
        val confidence = snapshot?.screens?.minOfOrNull { it.confidence } ?: 0.0
        return JSONObject()
            .put("place", placeJson(summary.place))
            .put("persona", summary.place.persona.wireValue)
            .put("mapStatus", snapshot?.let(::wireMapStatus) ?: "unmapped")
            .put("confidence", confidence.coerceIn(0.0, 1.0))
            .put("lastObservedAt", timestamp(summary.place.lastObservedAtEpochMillis))
            .put("lastVerifiedAt", timestamp(summary.place.lastVerifiedAtEpochMillis))
    }

    private fun placeJson(place: AtlasPlace): JSONObject = JSONObject()
        .put("placeId", place.id)
        .put("kind", place.kind.wireValue)
        .put("label", AtlasPrivacy.structuralLabel(
            place.label,
            place.packageName ?: place.origin ?: "App",
        ).take(120))
        .apply {
            place.packageName?.let { put("packageName", it) }
            place.origin?.let { put("origin", it) }
        }

    private fun factSlotJson(slot: AtlasFactSlot, safeName: String): JSONObject = JSONObject()
        .put("name", safeName)
        .put("factType", "text")
        .put("required", false)
        .put("description", ("Read " + safeName.replace('_', ' ') + " from this screen").take(160))

    private fun riskJson(danger: AtlasDanger): JSONObject {
        val classes = when (danger) {
            AtlasDanger.NONE -> emptyList()
            AtlasDanger.AUTHENTICATION -> listOf("authentication")
            AtlasDanger.PAYMENT -> listOf("payment")
            AtlasDanger.SEND_PUBLIC -> listOf("send-public")
            AtlasDanger.DELETE_ACCOUNT -> listOf("delete-account")
            AtlasDanger.LOGOUT_ALL -> listOf("logout-all")
            AtlasDanger.PERMISSION -> listOf("permission")
            AtlasDanger.UNKNOWN -> listOf("unknown")
        }
        return JSONObject()
            .put("danger", danger != AtlasDanger.NONE)
            .put("classes", JSONArray(classes))
    }

    /** Wire coverage is truthful: non-empty does not imply mapped. */
    private fun wireMapStatus(snapshot: AtlasGraphSnapshot): String = when (snapshot.place.mapStatus) {
        AtlasMapStatus.UNMAPPED -> "unmapped"
        AtlasMapStatus.PARTIAL -> "partial"
        AtlasMapStatus.MAPPED -> "mapped"
        AtlasMapStatus.STALE -> "stale"
    }

    private fun timestamp(epochMillis: Long?): Any =
        epochMillis?.let { Instant.ofEpochMilli(it).toString() } ?: JSONObject.NULL

    private fun stableEdgeId(key: GraphEdgeKey): String = AtlasGraphIds.wireEdgeId(key)

    private fun wireCapability(value: String): String {
        val clean = value.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '-', '.').take(80)
        return clean.takeIf { it.firstOrNull()?.isLetter() == true } ?: "C_" + clean.take(78)
    }

    private fun safeFactSlotName(value: String): String? {
        val clean = value.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_', '-', '.')
            .take(64)
            .ifBlank { "fact" }
        return clean.takeUnless { SECRET_SLOT_FRAGMENT.containsMatchIn(it) }
    }

    companion object {
        private val SECRET_SLOT_FRAGMENT = Regex(
            "(password|passcode|passwd|pin|otp|token|secret|api_key|authorization|cookie|cvv|credential|typed_text|typed_value)",
        )
        private val NAVIGATION_EDGES = setOf(
            GraphEdgeType.NAVIGATES_TO,
            GraphEdgeType.OPENS,
            GraphEdgeType.SUBMITS,
        )
    }
}

class PlaceCatalog(
    private val store: AtlasStore,
) {
    fun packages(persona: AtlasPersona? = null): List<AtlasPlaceSummary> =
        store.places(persona).filter { it.place.kind == AtlasPlaceKind.PACKAGE }

    fun chromeOrigins(persona: AtlasPersona? = null): List<AtlasPlaceSummary> =
        store.places(persona).filter { it.place.kind == AtlasPlaceKind.CHROME_ORIGIN }

    fun all(persona: AtlasPersona? = null): List<AtlasPlaceSummary> = store.places(persona)

    fun addPackage(packageName: String, label: String, persona: AtlasPersona): AtlasPlace =
        store.ensurePackagePlace(packageName, label, persona)

    fun addChromeOrigin(origin: String, label: String, persona: AtlasPersona): AtlasPlace {
        require(label.isNotBlank()) { "Chrome Place label must not be blank" }
        require(PlaceResolver.canonicalOrigin(origin) == origin) { "Chrome Place requires a canonical origin" }
        // Host-only labels prevent arbitrary page content from entering the catalog.
        return store.ensureChromeOrigin(origin, origin, persona)
    }

    fun recordObserved(place: ResolvedPlace, persona: AtlasPersona): AtlasPlace {
        require(
            (place.origin != null && place.packageName == null &&
                place.id == "chrome:${place.origin}" && PlaceResolver.canonicalOrigin(place.origin) == place.origin) ||
                (place.packageName != null && place.origin == null &&
                    PlaceResolver.packagePlace(place.packageName)?.id == place.id),
        ) { "Invalid observed Place" }
        val key = AtlasPlaceKey(place.id, persona)
        store.place(key)?.let { return it }
        return when {
            place.origin != null && place.id == "chrome:${place.origin}" ->
                addChromeOrigin(place.origin, place.origin, persona)
            place.packageName != null && PlaceResolver.packagePlace(place.packageName)?.id == place.id ->
                addPackage(place.packageName, place.packageName, persona)
            else -> error("Invalid observed Place")
        }
    }
}

/**
 * Atlas retrieval returns data-only hints. It has no dependency on PhoneToolExecutor or
 * AppGraphExecutor and therefore cannot execute a stored route.
 */
class AtlasRetriever(
    private val store: AtlasStore,
) {
    fun findHint(
        key: AtlasPlaceKey,
        goal: String,
        currentScreenId: GraphNodeId? = null,
    ): AtlasNavigationHint? {
        val snapshot = store.snapshot(key) ?: return null
        val normalized = goal.lowercase().trim()
        if (normalized.isBlank()) return null
        val pages = snapshot.nodes.filterIsInstance<PageNode>().associateBy { it.id }
        val meta = snapshot.screens.associateBy { it.screenId }
        val scored = pages.values.map { page ->
            val screen = meta[page.id]
            val haystack = buildString {
                append(page.identity.lowercase()).append(' ')
                append(screen?.purpose?.lowercase().orEmpty()).append(' ')
                append(screen?.capabilities?.joinToString(" ")?.lowercase().orEmpty()).append(' ')
                append(screen?.factSlots?.joinToString(" ") { it.name + " " + it.purpose }?.lowercase().orEmpty())
            }
            page to tokenScore(normalized, haystack)
        }.filter { it.second > 0 }
        val target = scored.maxWithOrNull(
            compareBy<Pair<PageNode, Int>>({ it.second }, { it.first.id.value }),
        )?.first ?: return null

        val path = if (currentScreenId == null || currentScreenId == target.id) {
            listOf(target.id)
        } else {
            shortestPath(snapshot, currentScreenId, target.id) ?: listOf(target.id)
        }
        val targetMeta = meta[target.id]
        val edgeMeta = snapshot.edgeMetadata.associateBy { it.key }
        val confidence = buildList {
            targetMeta?.confidence?.let(::add)
            path.zipWithNext { from, to -> GraphEdgeKey(from, GraphEdgeType.NAVIGATES_TO, to) }
                .mapNotNullTo(this) { edgeMeta[it]?.confidence }
        }.minOrNull() ?: targetMeta?.confidence ?: 0.0
        val danger = buildList {
            targetMeta?.danger?.let(::add)
            path.zipWithNext { from, to -> GraphEdgeKey(from, GraphEdgeType.NAVIGATES_TO, to) }
                .mapNotNullTo(this) { edgeMeta[it]?.danger }
        }.maxByOrNull(::dangerRank) ?: AtlasDanger.NONE
        val matchedSlot = targetMeta?.factSlots?.firstOrNull {
            normalized.contains(it.name.replace('_', ' ')) || it.purpose.lowercase().contains(normalized)
        }?.name
        val matchedCapability = targetMeta?.capabilities?.firstOrNull {
            normalized.split(Regex("\\s+")).any { token ->
                token.length >= 3 && it.lowercase().contains(token)
            }
        }

        return AtlasNavigationHint(
            place = key,
            destinationScreen = target.id,
            candidatePath = path,
            capability = matchedCapability,
            factSlot = matchedSlot,
            confidence = confidence.coerceIn(0.0, 1.0),
            danger = danger,
        )
    }

    private fun shortestPath(
        snapshot: AtlasGraphSnapshot,
        start: GraphNodeId,
        target: GraphNodeId,
    ): List<GraphNodeId>? {
        val pageIds = snapshot.nodes.filterIsInstance<PageNode>().map { it.id }.toSet()
        if (start !in pageIds || target !in pageIds) return null
        val adjacency = snapshot.edges
            .filter { it.key.type in NAVIGATION_EDGES && it.key.from in pageIds && it.key.to in pageIds }
            .groupBy({ it.key.from }, { it.key.to })
        val queue = ArrayDeque<GraphNodeId>().apply { add(start) }
        val previous = linkedMapOf<GraphNodeId, GraphNodeId?>().apply { put(start, null) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current == target) break
            adjacency[current].orEmpty().sortedBy { it.value }.forEach { next ->
                if (next !in previous) {
                    previous[next] = current
                    queue.add(next)
                }
            }
        }
        if (target !in previous) return null
        val reversed = mutableListOf<GraphNodeId>()
        var cursor: GraphNodeId? = target
        while (cursor != null) {
            reversed += cursor
            cursor = previous[cursor]
        }
        return reversed.asReversed()
    }

    private fun tokenScore(needle: String, haystack: String): Int =
        needle.split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .sumOf { token -> if (haystack.contains(token)) token.length else 0 }

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

    companion object {
        private val NAVIGATION_EDGES = setOf(
            GraphEdgeType.NAVIGATES_TO,
            GraphEdgeType.OPENS,
            GraphEdgeType.SUBMITS,
        )
    }
}
