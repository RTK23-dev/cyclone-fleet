package com.cyclone.mobile.agent.plan

import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.fastpath.FastPathLanding
import org.json.JSONArray
import org.json.JSONObject

enum class WaypointKind {
    OPEN_APP,
    LAUNCH_INTENT,
    LOCAL_INTERRUPTIONS,
    SCENE,
    STOP_HUMAN,
    DONE,
}

data class TaskWaypoint(
    val kind: WaypointKind,
    val packageName: String? = null,
    val uri: String? = null,
    val until: String = "",
    val summary: String,
    val clauseId: String? = null,
    val capability: String? = null,
    val doneWhen: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("do", kind.name.lowercase())
        .put("package", packageName ?: JSONObject.NULL)
        .put("uri", uri ?: JSONObject.NULL)
        .put("until", until)
        .put("summary", summary)
        .put("clauseId", clauseId ?: JSONObject.NULL)
        .put("capability", capability ?: JSONObject.NULL)
        .put("doneWhen", doneWhen ?: JSONObject.NULL)
}

data class TaskTrajectory(
    val tier: TaskDifficultyTier,
    val from: String,
    val to: String,
    val waypoints: List<TaskWaypoint>,
    val index: Int = 0,
    val horizonPlanned: Boolean = tier != TaskDifficultyTier.HARD,
) {
    val current: TaskWaypoint? get() = waypoints.getOrNull(index)

    fun remaining(): List<TaskWaypoint> = waypoints.drop(index)

    fun toJson(): JSONObject = JSONObject()
        .put("tier", tier.name)
        .put("from", from)
        .put("to", to)
        .put("index", index)
        .put("horizonPlanned", horizonPlanned)
        .put("current", current?.toJson() ?: JSONObject.NULL)
        .put("waypoints", JSONArray().also { array -> waypoints.forEach { array.put(it.toJson()) } })
        .put("remaining", JSONArray().also { array -> remaining().forEach { array.put(it.toJson()) } })

    fun advanceIfSatisfied(page: PageContext): TaskTrajectory {
        var next = this
        while (true) {
            val waypoint = next.current ?: return next
            if (!satisfied(waypoint, page)) return next
            next = next.copy(index = (next.index + 1).coerceAtMost(next.waypoints.size))
            if (next.index == this.index) return next
            if (next.index >= next.waypoints.size) return next
        }
    }

    companion object {
        fun seed(goal: String, fromPage: String = "current"): TaskTrajectory {
            val assessment = TaskDifficulty.assess(goal)
            val hardPlan = TaskDifficulty.hardWaypoints(goal)
            if (hardPlan != null) {
                return TaskTrajectory(
                    tier = TaskDifficultyTier.HARD,
                    from = fromPage,
                    to = goal.trim().take(180),
                    waypoints = hardPlan,
                    horizonPlanned = true,
                )
            }
            val landing = FastPathLanding.resolve(goal)
            val waypoints = mutableListOf<TaskWaypoint>()
            when (landing?.tool) {
                "phone.open_app" -> waypoints += TaskWaypoint(
                    WaypointKind.OPEN_APP,
                    packageName = landing.packageName,
                    until = "app_foreground",
                    summary = "Open ${landing.packageName}",
                )
                "phone.launch_intent" -> waypoints += TaskWaypoint(
                    WaypointKind.LAUNCH_INTENT,
                    uri = landing.uri,
                    until = "host_visible",
                    summary = "Open ${landing.uri}",
                )
            }
            waypoints += TaskWaypoint(WaypointKind.LOCAL_INTERRUPTIONS, until = "clear", summary = "Dismiss cookie and notice banners")
            when {
                TaskDifficulty.hasAuthenticatedSession(goal) -> waypoints += TaskWaypoint(
                    WaypointKind.STOP_HUMAN,
                    until = "login_wall",
                    summary = "Stop at login so you can sign in",
                )
                assessment.tier == TaskDifficultyTier.EASY -> waypoints += TaskWaypoint(
                    WaypointKind.DONE,
                    until = "goal_contract",
                    summary = "Finish once the app or site is open",
                )
                else -> waypoints += TaskWaypoint(
                    WaypointKind.SCENE,
                    until = "goal_contract",
                    summary = "Continue in the current app until the goal is verified",
                )
            }
            return TaskTrajectory(
                tier = assessment.tier,
                from = fromPage,
                to = goal.trim().take(180),
                waypoints = waypoints,
                horizonPlanned = assessment.tier != TaskDifficultyTier.HARD,
            )
        }

        fun satisfied(waypoint: TaskWaypoint, page: PageContext): Boolean = when (waypoint.kind) {
            WaypointKind.OPEN_APP -> {
                val expected = waypoint.packageName.orEmpty()
                expected.isNotBlank() && FastPathLanding.launchCandidates(expected).any { it == page.packageName }
            }
            WaypointKind.LAUNCH_INTENT -> DestinationAuthority.hostVisible(page, waypoint.uri)
            WaypointKind.LOCAL_INTERRUPTIONS -> true
            WaypointKind.STOP_HUMAN -> DestinationAuthority.stopHumanSatisfied(waypoint, page)
            WaypointKind.SCENE -> DestinationAuthority.sceneSatisfied(waypoint, page)
            WaypointKind.DONE -> false
        }

        fun looksLikeLoginWall(page: PageContext): Boolean {
            val labels = page.controls.map { it.label.trim().lowercase() }
            val signedOut = labels.any { it in setOf("log in", "login", "sign in", "signin") }
            val signedIn = labels.any { it in setOf("log out", "logout", "sign out", "signout") }
            return signedOut && !signedIn
        }
    }
}
