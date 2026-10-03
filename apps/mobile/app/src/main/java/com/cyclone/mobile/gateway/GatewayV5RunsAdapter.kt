package com.cyclone.mobile.gateway

import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.AiTraceEvent
import com.cyclone.mobile.ai.AiTraceSession
import com.cyclone.mobile.ai.RunInsight
import org.json.JSONArray
import org.json.JSONObject

/**
 * V5 `runs.list` / `runs.get` for Cyclone Glass's Runs page and run inspector (V5 plan 11).
 *
 * Read-only. Data is the phone's user-visible run trace plus [RunInsight]'s steps and cause of death; no hidden
 * provider reasoning, no screenshots, no typed values.
 */
internal object GatewayV5RunsAdapter {
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200
    private val runId = Regex("^[A-Za-z0-9_-]{4,120}$")
    private val filters = setOf("all", "failed", "completed", "stopped")

    /** Seams for JVM tests; production reads the trace database. */
    @Volatile internal var sessions: (Int) -> List<AiTraceSession> = { limit ->
        if (AgentTraceRuntime.isReady()) AgentTraceRuntime.store.listSessions(limit) else emptyList()
    }
    @Volatile internal var session: (String) -> AiTraceSession? = { id ->
        if (AgentTraceRuntime.isReady()) AgentTraceRuntime.store.session(id) else null
    }
    @Volatile internal var events: (String) -> List<AiTraceEvent> = { id ->
        if (AgentTraceRuntime.isReady()) AgentTraceRuntime.store.events(id) else emptyList()
    }

    /**
     * Runs the developer marked as expected (for example a GATE stop they wanted). They stay in Runs but stop counting
     * against scenario health. Stored on the phone; only run ids, no content.
     */
    @Volatile internal var marks: RunMarks = RunMarks.InMemory()

    /** Doors out of a room in the mapping pass, or null when the room is not on the map (seam; Atlas in production). */
    @Volatile internal var doorsOut: (placeId: String, roomId: String) -> Int? = { _, _ -> null }

    /** Rooms the map's doors lead to from a room, or null when the room is not on the map (seam; Atlas in production). */
    @Volatile internal var doorTargets: (placeId: String, roomId: String) -> Set<String>? = { _, _ -> null }

    fun install(context: android.content.Context) {
        if (marks is RunMarks.InMemory) marks = RunMarks.Prefs(context.applicationContext)
        doorTargets = { placeId, roomId ->
            runCatching {
                com.cyclone.mobile.applearner.graphv2.AtlasRuntime.initialize(context.applicationContext)
                val room = com.cyclone.mobile.brain.graphv2.GraphNodeId(roomId)
                val snapshots = com.cyclone.mobile.brain.graphv2.AtlasPersona.values().mapNotNull { persona ->
                    com.cyclone.mobile.applearner.graphv2.AtlasRuntime.store.snapshot(com.cyclone.mobile.brain.graphv2.AtlasPlaceKey(placeId, persona))
                }.filter { snapshot -> snapshot.screens.any { it.screenId == room } }
                if (snapshots.isEmpty()) return@runCatching null
                snapshots.flatMap { snapshot -> snapshot.edges.filter { it.key.from == room && it.key.type in NAVIGATION }.map { it.key.to.value } }.toSet()
            }.getOrNull()
        }
        doorsOut = { placeId, roomId ->
            runCatching {
                com.cyclone.mobile.applearner.graphv2.AtlasRuntime.initialize(context.applicationContext)
                val snapshot = com.cyclone.mobile.applearner.graphv2.AtlasRuntime.store.snapshot(
                    com.cyclone.mobile.brain.graphv2.AtlasPlaceKey(placeId, com.cyclone.mobile.brain.graphv2.AtlasPersona.MAPPING),
                ) ?: return@runCatching null
                val room = com.cyclone.mobile.brain.graphv2.GraphNodeId(roomId)
                if (snapshot.screens.none { it.screenId == room }) return@runCatching null
                snapshot.edges.count { it.key.from == room && it.key.type in NAVIGATION }
            }.getOrNull()
        }
    }

    private val NAVIGATION = setOf(
        com.cyclone.mobile.brain.graphv2.GraphEdgeType.NAVIGATES_TO,
        com.cyclone.mobile.brain.graphv2.GraphEdgeType.OPENS,
        com.cyclone.mobile.brain.graphv2.GraphEdgeType.SUBMITS,
    )

    /**
     * `door-missing` (plan 11): the run gave up or ran out of time in a room the map knows but that has no door onward.
     * Needs the Atlas, so it is decided here rather than in [RunInsight].
     */
    fun withMapCause(summary: JSONObject, steps: List<RunInsight.Step>): JSONObject {
        val cause = summary.optJSONObject("cause") ?: return summary
        wrongRoom(cause, steps)?.let { return summary.put("cause", it) }
        if (cause.optString("kind") !in setOf("model-gave-up", "timeout", "unknown", "verification-failed")) return summary
        val last = steps.lastOrNull { it.placeId != null && (it.roomAfter ?: it.roomId) != null } ?: return summary
        val room = last.roomAfter ?: last.roomId ?: return summary
        val doors = runCatching { doorsOut(last.placeId!!, room) }.getOrNull() ?: return summary
        if (doors > 0) return summary
        summary.put("cause", JSONObject()
            .put("kind", "door-missing")
            .put("stepIndex", last.index)
            .put("headline", "The map has no door onward from this room")
            .put("detail", cause.optString("detail"))
            .put("fix", "Map this room (Remap) or teach the way on, so Cyclone knows where to go next."))
        return summary
    }

    /**
     * `wrong-room` (plan 11): a step the map chose left a mapped room for a room none of that room's doors lead to.
     * Only replaces generic causes; a login wall, GATE stop or your own stop keeps its own cause.
     */
    private fun wrongRoom(cause: JSONObject, steps: List<RunInsight.Step>): JSONObject? {
        if (cause.optString("kind") !in setOf("model-gave-up", "timeout", "unknown", "verification-failed", "unchanged", "element-not-found", "wrong-room")) return null
        val step = steps.lastOrNull { step ->
            val from = step.roomId
            val to = step.roomAfter
            if (step.decisionSource != "map" || step.placeId == null || from == null || to == null || from == to) return@lastOrNull false
            val targets = runCatching { doorTargets(step.placeId, from) }.getOrNull() ?: return@lastOrNull false
            targets.isNotEmpty() && to !in targets
        } ?: return null
        return JSONObject()
            .put("kind", "wrong-room")
            .put("stepIndex", step.index)
            .put("headline", "A known door led to a different room")
            .put("detail", cause.optString("detail"))
            .put("fix", "Remap this room; the app now sends this door somewhere else.")
    }

    fun dispatch(op: String, args: JSONObject): JSONObject = when (op) {
        "runs.list" -> list(args)
        "runs.get" -> get(args)
        "runs.mark" -> mark(args)
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported runs operation: $op")
    }

    fun list(args: JSONObject): JSONObject {
        requireOnly(args, setOf("limit", "filter"))
        val limit = when (val raw = args.opt("limit")) {
            null, JSONObject.NULL -> DEFAULT_LIMIT
            is Number -> raw.toInt().takeIf { it in 1..MAX_LIMIT }
                ?: throw GatewayProtocolException("INVALID_REQUEST", "limit must be 1..$MAX_LIMIT.")
            else -> throw GatewayProtocolException("INVALID_REQUEST", "limit must be a number.")
        }
        val filter = (args.opt("filter") as? String ?: "all").takeIf { it in filters }
            ?: throw GatewayProtocolException("INVALID_REQUEST", "filter must be one of $filters.")
        val runs = sessions(MAX_LIMIT)
            .map { session ->
                val events = events(session.id)
                navigationRecord(withMapCause(RunInsight.summaryJson(session, events), RunInsight.steps(events)), events)
                    .put("expected", marks.isExpected(session.id))
            }
            .filter { run ->
                when (filter) {
                    "failed" -> run.getString("status") == "failed"
                    "completed" -> run.getString("status") == "completed"
                    "stopped" -> run.getString("status") in setOf("cancelled", "suspended")
                    else -> true
                }
            }
            .take(limit)
        return JSONObject().put("runs", JSONArray(runs))
    }

    fun get(args: JSONObject): JSONObject {
        requireOnly(args, setOf("runId"))
        val id = (args.opt("runId") as? String)?.takeIf { runId.matches(it) }
            ?: throw GatewayProtocolException("INVALID_REQUEST", "runId is malformed.")
        val found = session(id) ?: throw GatewayProtocolException("RUN_NOT_FOUND", "No run with that id on this phone.")
        val events = events(id)
        return navigationRecord(withMapCause(RunInsight.detailJson(found, events), RunInsight.steps(events)), events)
            .put("expected", marks.isExpected(id))
    }

    private fun navigationRecord(record: JSONObject, events: List<AiTraceEvent>): JSONObject =
        com.cyclone.mobile.agent.nav.NavigationRunRecord.enrich(record, events, RunInsight.steps(events))

    fun mark(args: JSONObject): JSONObject {
        requireOnly(args, setOf("runId", "expected"))
        val id = (args.opt("runId") as? String)?.takeIf { runId.matches(it) }
            ?: throw GatewayProtocolException("INVALID_REQUEST", "runId is malformed.")
        val expected = args.opt("expected") as? Boolean
            ?: throw GatewayProtocolException("INVALID_REQUEST", "expected must be true or false.")
        session(id) ?: throw GatewayProtocolException("RUN_NOT_FOUND", "No run with that id on this phone.")
        marks.set(id, expected)
        return JSONObject().put("runId", id).put("expected", marks.isExpected(id))
    }

    private fun requireOnly(args: JSONObject, allowed: Set<String>) {
        if (args.keys().asSequence().any { it !in allowed }) {
            throw GatewayProtocolException("INVALID_REQUEST", "Unexpected runs field.")
        }
    }
}

/** Run ids marked "expected" by the developer. Bounded; oldest marks fall off first. */
internal sealed class RunMarks {
    abstract fun isExpected(runId: String): Boolean
    abstract fun set(runId: String, expected: Boolean)

    class InMemory : RunMarks() {
        private val ids = LinkedHashSet<String>()
        @Synchronized override fun isExpected(runId: String) = runId in ids
        @Synchronized override fun set(runId: String, expected: Boolean) {
            if (expected) ids += runId else ids -= runId
            while (ids.size > MAX) ids.remove(ids.first())
        }
    }

    class Prefs(context: android.content.Context) : RunMarks() {
        private val prefs = context.getSharedPreferences("cyclone_run_marks_v1", android.content.Context.MODE_PRIVATE)
        @Synchronized override fun isExpected(runId: String) = runId in ids()
        @Synchronized override fun set(runId: String, expected: Boolean) {
            val next = ids().toMutableList().apply { remove(runId); if (expected) add(runId) }.takeLast(MAX)
            prefs.edit().putString(KEY, next.joinToString(",")).apply()
        }
        private fun ids(): List<String> = prefs.getString(KEY, "").orEmpty().split(',').filter { it.isNotBlank() }
    }

    companion object {
        const val MAX = 500
        private const val KEY = "expected"
    }
}
