package com.cyclone.mobile.mind.map

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.mind.learn.LearnedReader
import org.json.JSONObject
import java.util.PriorityQueue

/** A learned screen of one app, with the short handle ("s3") the Mind uses to name it. */
data class MapScreen(val id: String, val handle: String, val title: String, val pageKey: String)

/** A learned move: on [from], pressing the control [label] led to [to]. */
data class MapMove(
    val transitionId: String,
    val actionId: String,
    val from: String,
    val to: String,
    val label: String,
    val role: String?,
    val successes: Int,
    val observed: Int,
    /** How much the move is trusted; moves only seen on a test account start at 0.5 until a walk confirms them. */
    val confidence: Double = 0.7,
) {
    val reliability: Double get() = if (observed <= 0) 0.0 else successes.toDouble() / observed
}

/**
 * What Cyclone knows about moving around one app: its learned screens and the moves between them that worked. Built
 * from Learn's app knowledge; only safe, reliable moves are routable. The map is advice: [MapWalker] checks the
 * screen after every move and stops at the first surprise.
 */
class MindMap(val packageName: String, val screens: List<MapScreen>, val moves: List<MapMove>) {
    private val byId = screens.associateBy { it.id }

    fun locate(pageKey: String?): MapScreen? = pageKey?.takeIf { it.isNotBlank() }?.let { key -> screens.firstOrNull { it.pageKey == key } }

    fun screen(id: String): MapScreen? = byId[id]

    /** A screen by handle ("s3") or by name; null when unknown or when a name fits several screens. */
    fun find(name: String): MapScreen? {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null
        screens.firstOrNull { it.handle.equals(wanted, true) }?.let { return it }
        HANDLE_PREFIX.find(wanted)?.let { match -> screens.firstOrNull { it.handle.equals(match.groupValues[1], true) }?.let { return it } }
        val exact = screens.filter { it.title.equals(wanted, true) }
        if (exact.size == 1) return exact.single()
        if (exact.size > 1) return null
        val partial = screens.filter { it.title.contains(wanted, true) }
        return partial.singleOrNull()
    }

    /** The most reliable short route (fewest moves, preferring moves that worked more often), or null. */
    fun route(fromId: String, toId: String, maxMoves: Int = MAX_MOVES): List<MapMove>? {
        if (fromId == toId) return emptyList()
        val outgoing = moves.groupBy { it.from }
        val cost = HashMap<String, Double>().apply { put(fromId, 0.0) }
        val via = HashMap<String, MapMove>()
        val depth = HashMap<String, Int>().apply { put(fromId, 0) }
        val queue = PriorityQueue<Pair<String, Double>>(compareBy { it.second }).apply { add(fromId to 0.0) }
        while (queue.isNotEmpty()) {
            val (at, spent) = queue.poll()
            if (spent > (cost[at] ?: Double.MAX_VALUE)) continue
            if (at == toId) break
            val d = depth[at] ?: 0
            if (d >= maxMoves) continue
            for (move in outgoing[at].orEmpty()) {
                val next = spent + 1.0 + (1.0 - move.reliability) + if (move.confidence < CONFIRMED_CONFIDENCE) UNCONFIRMED_COST else 0.0
                if (next < (cost[move.to] ?: Double.MAX_VALUE)) {
                    cost[move.to] = next
                    via[move.to] = move
                    depth[move.to] = d + 1
                    queue.add(move.to to next)
                }
            }
        }
        if (toId !in via) return null
        val path = ArrayDeque<MapMove>()
        var at = toId
        while (at != fromId) {
            val move = via[at] ?: return null
            path.addFirst(move)
            at = move.from
        }
        return path.toList()
    }

    /**
     * The compact map shown to the Mind the first time it is in this app: every learned screen with its handle and the
     * moves out of it. At most [MAX_CARD_LINES] lines.
     */
    fun card(appLabel: String, here: MapScreen?): String = buildString {
        append("Map of $appLabel (learned from earlier runs; ${screens.size} screen${if (screens.size == 1) "" else "s"}).")
        append(" go_to walks to a screen for you, checking each step; use it instead of tapping your way there.")
        var lines = 0
        val outgoing = moves.groupBy { it.from }
        for (screen in screens) {
            if (lines >= MAX_CARD_LINES) { append("\n  …"); break }
            val exits = outgoing[screen.id].orEmpty().mapNotNull { move -> byId[move.to]?.let { "“${move.label}” → ${it.handle}" } }
                .distinct().take(6)
            append("\n  ${screen.handle} ${screen.title}")
            if (screen == here) append(" (you are here)")
            if (exits.isNotEmpty()) append(": ").append(exits.joinToString(", "))
            lines++
        }
    }

    companion object {
        const val MAX_MOVES = 8
        const val MAX_CARD_LINES = 40
        const val MIN_RELIABILITY = 0.5
        const val CONFIRMED_CONFIDENCE = 0.6
        const val UNCONFIRMED_COST = 1.5
        private val HANDLE_PREFIX = Regex("^(s\\d+)\\b", RegexOption.IGNORE_CASE)
        private val ROUTABLE_RISK = setOf(ActionRisk.SAFE, ActionRisk.UNKNOWN)

        /** The map of [packageName] from learned knowledge, or null when nothing routable is known. */
        fun from(reader: LearnedReader, packageName: String): MindMap? {
            val learned = reader.screens(packageName).filter { it.recognition.semanticFingerprint.isNotBlank() }
            if (learned.isEmpty()) return null
            val actions = reader.actions(packageName).associateBy { it.id }
            val ids = learned.map { it.id }.toSet()
            val moves = reader.transitions(packageName).mapNotNull { t ->
                val action = actions[t.actionId] ?: return@mapNotNull null
                if (t.fromScreenId !in ids || t.toScreenId !in ids || t.fromScreenId == t.toScreenId) return@mapNotNull null
                if (t.successfulCount <= 0 || t.knowledgeState == KnowledgeState.STALE) return@mapNotNull null
                if (action.knowledgeState == KnowledgeState.STALE || action.risk !in ROUTABLE_RISK || action.label.isBlank()) return@mapNotNull null
                val move = MapMove(t.id, action.id, t.fromScreenId, t.toScreenId, action.label,
                    runCatching { JSONObject(action.selectorJson).optString("role").takeIf { it.isNotBlank() } }.getOrNull(),
                    t.successfulCount, t.observedCount.coerceAtLeast(t.successfulCount), t.confidence)
                move.takeIf { it.reliability >= MIN_RELIABILITY }
            }
            // Screens with a move in or out first (they are the useful ones), then the rest; handles are stable per map.
            val connected = moves.flatMap { listOf(it.from, it.to) }.toSet()
            val ordered = learned.sortedWith(compareBy({ it.id !in connected }, { it.title.lowercase() }, { it.id }))
            val screens = ordered.mapIndexed { i, s -> MapScreen(s.id, "s${i + 1}", s.title.ifBlank { "Screen" }, s.recognition.semanticFingerprint) }
            return MindMap(packageName, screens, moves)
        }
    }
}

/** How the walker touches the phone. Production: the Mind toolbox, through the one mutation path. */
interface MapWalkPort {
    /** The page key of the screen as it is now (the walker's caller keeps it fresh after each move). */
    fun currentPageKey(): String?
    /** Presses the control with this label (and role, when known) on the current screen. */
    fun press(label: String, role: String?): MapPress
}

enum class MapPress { DONE, NOT_ON_SCREEN, REFUSED }

/** Where the map's knowledge is confirmed or doubted. Production: the app knowledge store. */
interface MapFeedback {
    fun walked(move: MapMove)
    fun diverged(move: MapMove)
}

sealed class WalkOutcome {
    abstract val moves: Int
    data class Arrived(val at: MapScreen, override val moves: Int) : WalkOutcome()
    data class NotOnMap(override val moves: Int = 0) : WalkOutcome()
    data class NoRoute(val from: MapScreen, val to: MapScreen) : WalkOutcome() { override val moves = 0 }
    data class Diverged(val expected: MapScreen, val at: MapScreen?, val reason: String, override val moves: Int) : WalkOutcome()
}

/**
 * Walks a learned route one move at a time. After every move it reads where it landed: the expected screen continues
 * the walk, a different known screen re-plans from there (at most [MAX_REPLANS] times), anything else stops and hands
 * back to the Mind. No model call is needed on the known part.
 */
class MapWalker(private val map: MindMap, private val port: MapWalkPort, private val feedback: MapFeedback? = null) {
    fun walk(target: MapScreen): WalkOutcome {
        var here = map.locate(port.currentPageKey()) ?: return WalkOutcome.NotOnMap()
        if (here.id == target.id) return WalkOutcome.Arrived(here, 0)
        var moves = 0
        var replans = 0
        var path = map.route(here.id, target.id) ?: return WalkOutcome.NoRoute(here, target)
        while (path.isNotEmpty()) {
            val move = path.first()
            val expected = map.screen(move.to)!!
            when (port.press(move.label, move.role)) {
                MapPress.NOT_ON_SCREEN -> {
                    runCatching { feedback?.diverged(move) }
                    return WalkOutcome.Diverged(expected, here, "“${move.label}” is not on ${here.title} any more", moves)
                }
                MapPress.REFUSED -> return WalkOutcome.Diverged(expected, here, "pressing “${move.label}” was refused", moves)
                MapPress.DONE -> moves++
            }
            val landed = map.locate(port.currentPageKey())
            if (landed?.id == expected.id) {
                runCatching { feedback?.walked(move) }
                here = landed
                path = path.drop(1)
                continue
            }
            runCatching { feedback?.diverged(move) }
            if (landed == null) return WalkOutcome.Diverged(expected, null, "“${move.label}” led to a screen the map does not know", moves)
            if (landed.id == target.id) return WalkOutcome.Arrived(landed, moves)
            if (replans >= MAX_REPLANS) return WalkOutcome.Diverged(expected, landed, "“${move.label}” led to ${landed.title} instead", moves)
            replans++
            here = landed
            path = map.route(here.id, target.id) ?: return WalkOutcome.Diverged(expected, landed, "no known way on from ${landed.title}", moves)
        }
        return WalkOutcome.Arrived(here, moves)
    }

    companion object {
        const val MAX_REPLANS = 2
    }
}
