package com.cyclone.mobile.market

import com.cyclone.mobile.mind.learn.MissionTrail
import com.cyclone.mobile.mind.map.MindMap
import org.json.JSONArray
import org.json.JSONObject

/** A place on an app's map a skill passes through: the learned screen's page key and its structural title. */
data class SkillWaypoint(val pageKey: String, val title: String) {
    fun toJson(): JSONObject = JSONObject().put("pageKey", pageKey).put("title", title)

    companion object {
        fun fromJson(json: JSONObject): SkillWaypoint? =
            json.optString("pageKey").takeIf { it.isNotBlank() }?.let { SkillWaypoint(it, json.optString("title").ifBlank { "Screen" }) }
    }
}

/**
 * Where a skill lives on the map (plan 23): the app, the way in (entry → … → destination) and how many steps of
 * work happen at the destination. Saved from the run the skill came from; page keys and structural titles only,
 * never what was typed or read.
 */
data class SkillAnchor(
    val packageName: String,
    val route: List<SkillWaypoint>,
    val finishSteps: Int,
    val savedAtMs: Long,
) {
    init { require(route.isNotEmpty()) }

    val entry: SkillWaypoint get() = route.first()
    val destination: SkillWaypoint get() = route.last()

    fun toJson(): JSONObject = JSONObject().put("schema", SCHEMA).put("package", packageName)
        .put("route", JSONArray().also { out -> route.forEach { out.put(it.toJson()) } })
        .put("finishSteps", finishSteps).put("savedAt", savedAtMs)

    companion object {
        const val SCHEMA = "cyclone-skill-anchor-v1"
        const val MAX_ROUTE = 12

        fun fromJson(json: JSONObject?): SkillAnchor? = runCatching {
            json ?: return null
            require(json.optString("schema") == SCHEMA)
            val route = json.optJSONArray("route")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(SkillWaypoint::fromJson) } }.orEmpty()
            SkillAnchor(json.getString("package"), route.take(MAX_ROUTE), json.optInt("finishSteps"), json.optLong("savedAt"))
        }.getOrNull()

        /**
         * The anchor of a finished run: the app it worked in (the one with the most successful actions), the
         * destination (the screen of its last successful action there) and the way it got there, with detours and
         * loops removed. Null when the run did nothing in an app.
         */
        fun fromTrail(trail: MissionTrail, nowMs: Long): SkillAnchor? {
            val pkgOf = trail.screens.associate { it.pageKey to it.packageName }
            val titleOf = trail.screens.associate { it.pageKey to it.title }
            val acted = trail.steps.filter { it.ok && it.controlKey != null && it.fromPageKey != null && pkgOf[it.fromPageKey] != null }
            val app = acted.groupingBy { pkgOf.getValue(it.fromPageKey!!) }.eachCount().maxByOrNull { it.value }?.key ?: return null
            val destination = acted.last { pkgOf[it.fromPageKey] == app }.fromPageKey!!

            val visits = mutableListOf<String>()
            fun visit(key: String?) {
                if (key == null || pkgOf[key] != app) return
                val seen = visits.indexOf(key)
                if (seen >= 0) { while (visits.size > seen + 1) visits.removeAt(visits.size - 1); return } // a loop: back to where it was
                visits += key
            }
            var arrivedAt = -1
            for ((index, step) in trail.steps.withIndex()) {
                if (!step.ok) continue
                visit(step.fromPageKey)
                if (step.fromPageKey == destination) { arrivedAt = index; break }
                visit(step.toPageKey)
                if (step.toPageKey == destination) { arrivedAt = index + 1; break }
            }
            if (visits.isEmpty() || visits.last() != destination) visits += destination
            val finish = if (arrivedAt < 0) 0 else trail.steps.drop(arrivedAt).count { it.ok && it.controlKey != null && it.fromPageKey != null && pkgOf[it.fromPageKey] == app }
            val route = visits.takeLast(MAX_ROUTE).map { SkillWaypoint(it, titleOf[it]?.ifBlank { "Screen" } ?: "Screen") }
            return SkillAnchor(app, route, finish, nowMs)
        }
    }
}

/** How well a skill is grounded in the app's map right now. */
enum class SkillGroundState(val wire: String, val label: String) {
    GROUNDED("grounded", "Route known"),
    PARTIAL("partial", "Destination known"),
    NEEDS_RECHECK("needs-recheck", "Needs re-check"),
    NOT_GROUNDED("not-grounded", "Not grounded"),
}

data class SkillHealth(val state: SkillGroundState, val detail: String, val routeMoves: Int? = null, val destinationHandle: String? = null)

object SkillGrounding {
    /**
     * Health from the map as it is now: the destination must still be a screen the map knows; a known way from the
     * entry makes the route itself known. A destination the map no longer knows (the app changed, the screen went
     * stale) needs a re-check, which is one successful run.
     */
    fun health(anchor: SkillAnchor?, map: MindMap?): SkillHealth {
        anchor ?: return SkillHealth(SkillGroundState.NOT_GROUNDED, "Saved before skills were grounded. Run it once and save it again.")
        val destination = map?.locate(anchor.destination.pageKey)
            ?: return SkillHealth(SkillGroundState.NEEDS_RECHECK, "“${anchor.destination.title}” is no longer on the map. The next run finds the way and re-grounds it.")
        val entry = map.locate(anchor.entry.pageKey)
        val route = entry?.let { map.route(it.id, destination.id) }
        return if (route != null) SkillHealth(SkillGroundState.GROUNDED,
            if (route.isEmpty()) "Starts on “${destination.title}”." else "${route.size} known move${if (route.size == 1) "" else "s"} to “${destination.title}”.",
            route.size, destination.handle)
        else SkillHealth(SkillGroundState.PARTIAL, "“${destination.title}” is on the map; the way there is found live.", null, destination.handle)
    }

    /**
     * What the Mind is told when a mission runs a grounded skill: where the work happens and the saved way there, so it
     * walks the map (`go_to`) instead of searching, and finishes the goal itself at the destination.
     */
    fun card(name: String, appLabel: String, anchor: SkillAnchor, map: MindMap?): String {
        val health = health(anchor, map)
        val way = anchor.route.joinToString(" → ") { "“${it.title}”" }
        return buildString {
            append("This mission is your saved skill “$name”. It works in $appLabel on “${anchor.destination.title}”")
            health.destinationHandle?.let { append(" (").append(it).append(" on the map)") }
            append(". Saved way there: ").append(way).append('.')
            when (health.state) {
                SkillGroundState.GROUNDED, SkillGroundState.PARTIAL ->
                    append(" Open $appLabel, go_to ${health.destinationHandle}, then do the rest of the goal there")
                else -> append(" The map no longer knows that screen: find it yourself, then do the rest of the goal there")
            }
            if (anchor.finishSteps > 0) append(" (about ${anchor.finishSteps} step${if (anchor.finishSteps == 1) "" else "s"} last time)")
            append(". Check the real screen at every step; the saved way is advice.")
        }
    }
}
