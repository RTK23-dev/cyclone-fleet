package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.RunInsight
import com.cyclone.mobile.brain.graphv2.AppVersionEvidence
import com.cyclone.mobile.brain.graphv2.AtlasDanger
import com.cyclone.mobile.brain.graphv2.AtlasGraphIds
import com.cyclone.mobile.brain.graphv2.AtlasGraphSnapshot
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.brain.graphv2.AtlasPlaceKey
import com.cyclone.mobile.brain.graphv2.AtlasStore
import com.cyclone.mobile.brain.graphv2.GraphEdgeType
import com.cyclone.mobile.brain.graphv2.GraphNodeId
import com.cyclone.mobile.brain.graphv2.PageNode
import com.cyclone.mobile.brain.graphv2.TemporalKnowledgeEdge
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** One run's walk through one app, for scenario health. Structural room ids only. */
internal data class RunWalk(
    val runId: String,
    val status: String,
    val startedAt: Long,
    val placeId: String,
    val rooms: List<String>,
)

/**
 * V5 `atlas.versions` and `scenarios.list` for Glass's Versions and Scenarios tabs (plan 04).
 *
 * - **Versions**: which app versions the phone's doors were learned or confirmed on, which version is installed, and
 *   which doors were last confirmed on an older version (they may be stale after an update).
 * - **Scenarios**: one known route per destination room, from the app's entry room, with health from the real runs
 *   that walked through that destination (passing / warning / critical / untested). Titles name rooms, never people.
 *
 * Read-only and computed on the phone; Glass only shows the result.
 */
internal object GatewayV5KnowledgeAdapter {
    const val MAX_SCENARIOS = 24
    const val KIND_REACH = "reach"
    const val KIND_SIGN_IN = "sign-in"
    const val KIND_SIGNED_IN = "signed-in"
    const val MAX_STALE_DOORS = 20
    private const val MAX_VERSIONS = 12
    private const val RUNS_CONSIDERED = 60
    private val NAVIGATION = setOf(GraphEdgeType.NAVIGATES_TO, GraphEdgeType.OPENS, GraphEdgeType.SUBMITS)
    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** Seams for JVM tests; production reads the durable Atlas, PackageManager and the run trace. */
    @Volatile internal var snapshot: (String, AtlasPersona) -> AtlasGraphSnapshot? = { _, _ -> null }
    @Volatile internal var installedVersion: (String) -> AppVersionEvidence? = { null }
    @Volatile internal var walks: (String) -> List<RunWalk> = { emptyList() }

    fun install(context: Context, store: AtlasStore) {
        val app = context.applicationContext
        snapshot = { placeId, persona -> runCatching { store.snapshot(AtlasPlaceKey(placeId, persona)) }.getOrNull() }
        installedVersion = { pkg ->
            runCatching {
                val info = app.packageManager.getPackageInfo(pkg, 0)
                AppVersionEvidence(pkg, info.versionName, info.longVersionCode)
            }.getOrNull()
        }
        walks = { placeId -> allWalks(app).filter { it.placeId == placeId } }
        GatewayV5AppsAdapter.scenarioHealth = { placeId -> healthCounts(placeId) }
    }

    @Volatile private var walkCache: Pair<Long, List<RunWalk>>? = null

    /** Every recent run's walk through every app, read once and reused for a few seconds (apps.list asks per app). */
    private fun allWalks(app: Context): List<RunWalk> {
        walkCache?.let { (at, cached) -> if (System.currentTimeMillis() - at < 5_000) return cached }
        AgentTraceRuntime.initialize(app)
        val walks = if (!AgentTraceRuntime.isReady()) emptyList() else AgentTraceRuntime.store.listSessions(RUNS_CONSIDERED)
            .filterNot { GatewayV5RunsAdapter.marks.isExpected(it.id) }
            .flatMap { session ->
                val steps = RunInsight.steps(AgentTraceRuntime.store.events(session.id))
                RunInsight.places(steps).map { place ->
                    val route = place.getJSONArray("route")
                    RunWalk(session.id, RunInsight.wireStatus(session.status), session.startedAt, place.getString("placeId"),
                        (0 until route.length()).map(route::getString))
                }
            }
        walkCache = System.currentTimeMillis() to walks
        return walks
    }

    /** Scenario health counts for one app (Apps page), or null when the app has no scenarios. */
    fun healthCounts(placeId: String): Map<String, Int>? = runCatching {
        val list = scenarios(JSONObject().put("placeId", placeId)).getJSONArray("scenarios")
        if (list.length() == 0) null else (0 until list.length()).map { list.getJSONObject(it).getString("health") }
            .groupingBy { it }.eachCount()
    }.getOrNull()

    fun dispatch(op: String, args: JSONObject): JSONObject = when (op) {
        "atlas.versions" -> versions(args)
        "scenarios.list" -> scenarios(args)
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported knowledge operation: $op")
    }

    fun versions(args: JSONObject): JSONObject {
        requireOnly(args, setOf("placeId"))
        val placeId = packagePlace(args)
        val pkg = placeId.removePrefix("package:")
        val doors = AtlasPersona.values().flatMap { persona -> navigationDoors(snapshot(placeId, persona)) }
        val installed = installedVersion(pkg)
        val installedId = installed?.stableIdentity?.takeIf { it.isNotBlank() }
        val byVersion = doors.filter { it.evidence.appVersion?.stableIdentity?.isNotBlank() == true }
            .groupBy { it.evidence.appVersion!!.stableIdentity }
        val versions = byVersion.map { (_, edges) ->
            val version = edges.first().evidence.appVersion!!
            val rooms = edges.flatMap { listOf(it.key.from.value, it.key.to.value) }.toSet()
            version to JSONObject()
                .put("versionName", version.versionName?.take(64) ?: JSONObject.NULL)
                .put("versionCode", version.versionCode ?: JSONObject.NULL)
                .put("installed", version.stableIdentity == installedId)
                .put("doors", edges.size)
                .put("rooms", rooms.size)
                .put("failingDoors", edges.count(::failing))
                .put("lastSeenAt", edges.maxOf { it.evidence.observedAtEpochMillis })
        }
            .sortedWith(compareByDescending<Pair<AppVersionEvidence, JSONObject>> { it.first.versionCode ?: -1L }
                .thenByDescending { it.first.versionName.orEmpty() })
            .take(MAX_VERSIONS)
        val stale = if (installedId == null) emptyList() else doors.filter { edge ->
            val id = edge.evidence.appVersion?.stableIdentity
            !id.isNullOrBlank() && id != installedId
        }
        val mapped = doors.isNotEmpty()
        return JSONObject()
            .put("placeId", placeId)
            .put("installedVersion", installed?.let {
                JSONObject().put("versionName", it.versionName?.take(64) ?: JSONObject.NULL).put("versionCode", it.versionCode ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
            .put("needsRemap", mapped && installedId != null && versions.none { it.second.getBoolean("installed") })
            .put("versions", JSONArray(versions.map { it.second }))
            .put("staleDoorCount", stale.size)
            .put("staleDoors", JSONArray(stale.sortedBy { it.evidence.observedAtEpochMillis }.take(MAX_STALE_DOORS).map { edge ->
                JSONObject()
                    .put("edgeId", AtlasGraphIds.wireEdgeId(edge.key))
                    .put("fromScreenId", edge.key.from.value)
                    .put("toScreenId", edge.key.to.value)
                    .put("versionName", edge.evidence.appVersion?.versionName?.take(64) ?: JSONObject.NULL)
                    .put("versionCode", edge.evidence.appVersion?.versionCode ?: JSONObject.NULL)
            }))
    }

    fun scenarios(args: JSONObject): JSONObject {
        requireOnly(args, setOf("placeId", "persona"))
        val placeId = packagePlace(args)
        val persona = when (val raw = args.opt("persona")) {
            null, JSONObject.NULL, "mapping" -> AtlasPersona.MAPPING
            "live" -> AtlasPersona.LIVE
            else -> throw GatewayProtocolException("INVALID_REQUEST", "persona must be live or mapping, not $raw.")
        }
        val snap = snapshot(placeId, persona)
            ?: return JSONObject().put("placeId", placeId).put("persona", persona.wireValue).put("entryScreenId", JSONObject.NULL)
                .put("scenarios", JSONArray())
        val pages = snap.nodes.filterIsInstance<PageNode>().map { it.id }.toSet()
        val screens = snap.screens.filter { it.screenId in pages }.associateBy { it.screenId }
        val doors = navigationDoors(snap).filter { it.key.from in screens && it.key.to in screens }
        val entry = entryRoom(screens.keys, doors, snap)
        val meta = snap.edgeMetadata.associateBy { it.key }
        val runWalks = runCatching { walks(placeId) }.getOrDefault(emptyList()).sortedByDescending { it.startedAt }

        fun scenario(kind: String, title: String, start: GraphNodeId, path: List<TemporalKnowledgeEdge>): JSONObject {
            val room = path.last().key.to
            val route = listOf(start.value) + path.map { it.key.to.value }
            val used = runWalks.filter { room.value in it.rooms }
            val danger = path.any { (meta[it.key]?.danger ?: AtlasDanger.NONE) != AtlasDanger.NONE } ||
                (screens[room]?.danger ?: AtlasDanger.NONE) != AtlasDanger.NONE
            val verified = path.mapNotNull { it.evidence.lastSucceededAtEpochMillis }.maxOrNull()
            return JSONObject()
                .put("scenarioId", scenarioId(placeId, persona, if (kind == KIND_REACH) room.value else "$kind|${room.value}"))
                .put("kind", kind)
                .put("title", title)
                .put("startScreenId", start.value)
                .put("endScreenId", room.value)
                .put("route", JSONArray(route))
                .put("steps", path.size)
                .put("danger", danger)
                .put("health", health(used))
                .put("lastVerifiedAt", verified ?: JSONObject.NULL)
                .put("appVersion", path.lastOrNull()?.evidence?.appVersion?.versionName?.take(64) ?: JSONObject.NULL)
                .put("runs", JSONArray(used.take(5).map { walk ->
                    JSONObject().put("runId", walk.runId).put("status", walk.status).put("startedAt", walk.startedAt)
                }))
        }

        val routes = if (entry == null) emptyMap() else com.cyclone.mobile.agent.nav.AtlasRoutes.shortest(entry, doors)
        val signIn = if (entry == null) emptyList() else signInScenarios(entry, routes, doors, screens.mapValues { it.value.purpose })
            .map { (kind, path) -> scenario(kind, if (kind == KIND_SIGN_IN) "Sign in" else "Already signed in", entry, path) }
        val reach = routes.entries
            .filter { (room, path) -> room != entry && path.isNotEmpty() }
            .sortedWith(compareBy({ it.value.size }, { it.key.value }))
            .take(MAX_SCENARIOS - signIn.size)
            .map { (room, path) -> scenario(KIND_REACH, "Reach ${title(screens[room]?.purpose)}", entry!!, path) }
        val scenarios = signIn + reach
        return JSONObject()
            .put("placeId", placeId)
            .put("persona", persona.wireValue)
            .put("entryScreenId", entry?.value ?: JSONObject.NULL)
            .put("scenarios", JSONArray(scenarios))
    }

    /**
     * The two scenarios every app with a login room gets (Glass 1.0 exit criterion 4):
     * **Sign in** walks from the entry through the nearest login room and out of its first door that leads away from login;
     * **Already signed in** is the shortest route from the entry to a room that is not a login room without passing one.
     * Room purposes are structural words (never page content), so this never reads what was typed.
     */
    internal fun signInScenarios(
        entry: GraphNodeId,
        routes: Map<GraphNodeId, List<TemporalKnowledgeEdge>>,
        doors: List<TemporalKnowledgeEdge>,
        purposes: Map<GraphNodeId, String?>,
    ): List<Pair<String, List<TemporalKnowledgeEdge>>> {
        fun login(room: GraphNodeId) = purposes[room].equals("login", ignoreCase = true)
        val result = mutableListOf<Pair<String, List<TemporalKnowledgeEdge>>>()
        val wall = routes.entries.filter { login(it.key) }.minWithOrNull(compareBy({ it.value.size }, { it.key.value }))
        if (wall != null) {
            val out = doors.filter { it.key.from == wall.key && !login(it.key.to) }
                .sortedWith(compareByDescending<TemporalKnowledgeEdge> { it.evidence.confidence }.thenBy { it.key.to.value })
                .firstOrNull()
            if (out != null) result += KIND_SIGN_IN to (wall.value + out)
        }
        if (!login(entry)) {
            routes.entries
                .filter { (room, path) -> room != entry && path.isNotEmpty() && path.none { login(it.key.to) } }
                .minWithOrNull(compareBy({ it.value.size }, { it.key.value }))
                ?.let { result += KIND_SIGNED_IN to it.value }
        }
        return if (wall == null) emptyList() else result
    }

    /** passing: the latest run through here finished; critical: the last two failed; warning: mixed; untested: none. */
    fun health(used: List<RunWalk>): String {
        val ended = used.filter { it.status != "running" }
        if (ended.isEmpty()) return "untested"
        if (ended.first().status == "completed") return "passing"
        if (ended.size >= 2 && ended.take(2).none { it.status == "completed" }) return "critical"
        return "warning"
    }

    private fun navigationDoors(snapshot: AtlasGraphSnapshot?): List<TemporalKnowledgeEdge> =
        snapshot?.edges.orEmpty().filter { it.key.type in NAVIGATION }

    private fun failing(edge: TemporalKnowledgeEdge): Boolean {
        val failed = edge.evidence.lastFailedAtEpochMillis ?: return false
        return failed >= (edge.evidence.lastSucceededAtEpochMillis ?: -1L)
    }

    /** The room without incoming doors that opens the most doors; else the room with the most doors out. */
    private fun entryRoom(rooms: Set<GraphNodeId>, doors: List<TemporalKnowledgeEdge>, snap: AtlasGraphSnapshot): GraphNodeId? {
        if (rooms.isEmpty()) return null
        val incoming = doors.map { it.key.to }.toSet()
        val out = doors.groupingBy { it.key.from }.eachCount()
        val home = snap.screens.firstOrNull { it.screenId in rooms && it.purpose.equals("home", ignoreCase = true) }?.screenId
        return home ?: rooms.filter { it !in incoming }.maxWithOrNull(compareBy<GraphNodeId> { out[it] ?: 0 }.thenByDescending { it.value })
            ?: rooms.maxWithOrNull(compareBy<GraphNodeId> { out[it] ?: 0 }.thenByDescending { it.value })
    }

    private fun title(purpose: String?): String {
        val words = purpose.orEmpty().replace('_', ' ').replace(Regex("\\s+"), " ").trim().take(60)
        return if (words.isBlank()) "a screen" else words.replaceFirstChar { it.uppercase() }
    }

    private fun scenarioId(placeId: String, persona: AtlasPersona, room: String): String =
        "sc_" + MessageDigest.getInstance("SHA-256").digest("$placeId|${persona.wireValue}|$room".toByteArray())
            .take(9).joinToString("") { "%02x".format(it) }

    private fun packagePlace(args: JSONObject): String {
        val placeId = args.opt("placeId") as? String
            ?: throw GatewayProtocolException("INVALID_REQUEST", "placeId is required.")
        if (!placeId.startsWith("package:") || !packageName.matches(placeId.removePrefix("package:")) || placeId.length > 200) {
            throw GatewayProtocolException("INVALID_REQUEST", "Scenarios and versions are for apps (package:…) for now.")
        }
        return placeId
    }

    private fun requireOnly(args: JSONObject, allowed: Set<String>) {
        val extra = args.keys().asSequence().filter { it !in allowed }.toList()
        if (extra.isNotEmpty()) throw GatewayProtocolException("INVALID_REQUEST", "Unexpected arguments: ${extra.sorted().joinToString()}.")
    }

    internal fun resetForTests() {
        snapshot = { _, _ -> null }
        installedVersion = { null }
        walks = { emptyList() }
    }
}
