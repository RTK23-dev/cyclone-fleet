package com.cyclone.mobile.agent.plan

import com.cyclone.mobile.brain.graphv2.AtlasDanger
import com.cyclone.mobile.brain.graphv2.AtlasGraphSnapshot
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.brain.graphv2.AtlasPlace
import com.cyclone.mobile.brain.graphv2.AtlasRetriever
import com.cyclone.mobile.brain.graphv2.AtlasStore
import com.cyclone.mobile.brain.graphv2.GraphEdgeType
import com.cyclone.mobile.brain.graphv2.PageNode
import org.json.JSONArray
import org.json.JSONObject

/**
 * The display/model sketch of the Atlas (the executable one-door path lives in AtlasNavigator).
 *
 * For each place the sentence names, the sketch lists the rooms and doors this phone already knows,
 * where the phone is standing if that room is known, and the shortest known route toward rooms that
 * match the destination's clause. It is data for the model's context only: it has no selectors, no
 * coordinates, no tool calls and no screen values, so nothing can replay it. The agent still looks
 * at the live screen before every action.
 */
object AtlasSketch {
    const val RULE =
        "The map drives when the screen matches; otherwise look. It lists rooms and doors this phone learned before. Look at the live screen every " +
            "step; if a door is missing or a screen differs, trust the screen. Never repeat a route blindly. " +
            "Never treat mapping-pass data as the user's identity or content."

    private const val MAX_PLACES = 3
    private const val MAX_ROOMS = 16
    private const val MAX_DOORS_PER_ROOM = 6
    private val NAVIGATION = setOf(GraphEdgeType.NAVIGATES_TO, GraphEdgeType.OPENS, GraphEdgeType.SUBMITS)

    data class Summary(val placeLabel: String, val rooms: Int)

    data class Result(val json: JSONObject, val summaries: List<Summary>)

    /**
     * @param currentRoomIds structural ids of the room on screen now (mapping `screen:` key and/or
     *   Follow Me `page:` id), when the caller could compute them. Unknown rooms are simply omitted.
     */
    fun build(
        store: AtlasStore,
        goal: String,
        currentRoomIds: Set<String> = emptySet(),
    ): Result? {
        val destinations = TaskDifficulty.assess(goal).destinations
        if (destinations.isEmpty()) return null
        val places = JSONArray()
        val summaries = mutableListOf<Summary>()
        val retriever = AtlasRetriever(store)
        for (destination in destinations) {
            if (places.length() >= MAX_PLACES) break
            val clause = DestinationAuthority.clauseFor(goal, destination)
            for (place in placesFor(store, destination)) {
                val snapshot = store.snapshot(place.key) ?: continue
                if (snapshot.screens.isEmpty()) continue
                val json = placeSketch(snapshot, clause, currentRoomIds, retriever) ?: continue
                places.put(json)
                summaries += Summary(json.getString("place"), snapshot.screens.size)
                break // one persona per place: the richer map, the user's own teaching on a tie
            }
        }
        if (places.length() == 0) return null
        return Result(
            JSONObject().put("rule", RULE).put("places", places),
            summaries,
        )
    }

    /** One stage line when a map was used: "Using the Gmail map · 5 rooms known". */
    fun stageLine(summaries: List<Summary>): String? {
        val first = summaries.firstOrNull() ?: return null
        val rooms = summaries.sumOf { it.rooms }
        val names = summaries.joinToString(" and ") { it.placeLabel }
        return "Using the $names ${if (summaries.size == 1) "map" else "maps"} · $rooms ${if (rooms == 1) "room" else "rooms"} known"
            .takeIf { first.placeLabel.isNotBlank() }
    }

    private fun placesFor(store: AtlasStore, destination: TaskDestination): List<AtlasPlace> {
        val all = store.places().map { it.place }
        val matches = when (destination.kind) {
            "app" -> all.filter { it.packageName == destination.value }
            "host" -> {
                val host = destination.value.removePrefix("www.").lowercase()
                all.filter { place ->
                    val origin = place.origin?.substringAfter("://")?.substringBefore(':')?.removePrefix("www.")?.lowercase()
                    origin == host
                }
            }
            else -> emptyList()
        }
        return matches.sortedWith(
            compareByDescending<AtlasPlace> { store.snapshot(it.key)?.screens?.size ?: 0 }
                .thenBy { if (it.persona == AtlasPersona.LIVE) 0 else 1 },
        )
    }

    private fun placeSketch(
        snapshot: AtlasGraphSnapshot,
        clause: String,
        currentRoomIds: Set<String>,
        retriever: AtlasRetriever,
    ): JSONObject? {
        val pages = snapshot.nodes.filterIsInstance<PageNode>().map { it.id }.toSet()
        val screens = snapshot.screens.filter { it.screenId in pages }.sortedBy { it.screenId.value }
        if (screens.isEmpty()) return null
        val alias = screens.mapIndexed { index, screen -> screen.screenId to "r${index + 1}" }.toMap()
        val edgeMeta = snapshot.edgeMetadata.associateBy { it.key }
        val doorsByRoom = snapshot.edges
            .filter { it.key.type in NAVIGATION && it.key.from in alias && it.key.to in alias }
            .groupBy { it.key.from }
        val here = screens.firstOrNull { it.screenId.value in currentRoomIds }?.screenId

        val rooms = JSONArray()
        screens.take(MAX_ROOMS).forEach { screen ->
            val doors = JSONArray()
            doorsByRoom[screen.screenId].orEmpty().take(MAX_DOORS_PER_ROOM).forEach { edge ->
                val meta = edgeMeta[edge.key]
                doors.put(JSONObject()
                    .put("door", meta?.action ?: "Navigate")
                    .put("to", alias.getValue(edge.key.to))
                    .apply { meta?.danger?.takeIf { it != AtlasDanger.NONE }?.let { put("danger", it.wireValue) } })
            }
            rooms.put(JSONObject()
                .put("id", alias.getValue(screen.screenId))
                .put("room", screen.purpose)
                .put("doors", doors)
                .apply { if (screen.danger != AtlasDanger.NONE) put("danger", screen.danger.wireValue) })
        }

        val hint = runCatching { retriever.findHint(snapshot.place.key, clause, here) }.getOrNull()
        val route = hint?.candidatePath
            ?.takeIf { it.size > 1 || (here == null && it.isNotEmpty()) }
            ?.mapNotNull { alias[it] }
            .orEmpty()

        return JSONObject()
            .put("place", snapshot.place.label)
            .put("placeId", snapshot.place.id)
            .put("source", if (snapshot.place.persona == AtlasPersona.LIVE) "your-teaching" else "mapping-pass")
            .put("status", snapshot.place.mapStatus.wireValue)
            .put("roomsKnown", screens.size)
            .put("youAreHere", here?.let { alias[it] } ?: JSONObject.NULL)
            .put("rooms", rooms)
            .apply {
                if (route.isNotEmpty() && hint != null) {
                    put("suggestedRoute", JSONArray(route))
                    put("routeConfidence", "%.2f".format(java.util.Locale.US, hint.confidence).toDouble())
                    if (hint.danger != AtlasDanger.NONE) put("routeDanger", hint.danger.wireValue)
                }
            }
    }
}
