package com.cyclone.mobile.agent.plan

import com.cyclone.mobile.ai.model.BoundedJsonRepair
import org.json.JSONArray
import org.json.JSONObject

/** One-shot long-horizon waypoint plan. Destinations only, never click scripts. */
object HorizonPlanner {
    val SYSTEM_PROMPT: String = """
You are Cyclone Horizon Planner. Map a difficult multi-app Android task into waypoints.

Return strict JSON only. Do not expose chain-of-thought. Do not invent click coordinates or selectors.

Rules:
1. Waypoints are destinations and until-conditions, not UI scripts.
2. Prefer phone.open_app / phone.launch_intent for landing. Never hunt launcher icons.
3. Insert local_interruptions after landings so cookie/notice banners stay local.
4. Authentication, payment, send, delete and other consequential walls are stop_human.
5. At most 8 waypoints. Keep summaries user-facing and short.
6. scene means the existing page agent should operate one app until the until-condition.
7. done means the original user goal is independently verifiable.

Schema:
{
  "from":"current scene in a few words",
  "to":"verified end state",
  "waypoints":[
    {"do":"open_app|launch_intent|local_interruptions|scene|stop_human|done","package":"optional","uri":"optional https","until":"app_foreground|host_visible|account_observed|destination_ready|login_wall|goal_contract","summary":"short user sentence"}
  ]
}
""".trimIndent()

    fun parse(raw: String, goal: String, fromPage: String): TaskTrajectory? {
        val json = runCatching {
            JSONObject(BoundedJsonRepair.extractSingleObject(raw) ?: raw.trim())
        }.getOrNull() ?: return null
        val array = json.optJSONArray("waypoints") ?: JSONArray()
        val waypoints = mutableListOf<TaskWaypoint>()
        for (i in 0 until minOf(array.length(), 8)) {
            val row = array.optJSONObject(i) ?: continue
            val kind = when (row.optString("do").trim().lowercase()) {
                "open_app" -> WaypointKind.OPEN_APP
                "launch_intent" -> WaypointKind.LAUNCH_INTENT
                "local_interruptions" -> WaypointKind.LOCAL_INTERRUPTIONS
                "scene" -> WaypointKind.SCENE
                "stop_human" -> WaypointKind.STOP_HUMAN
                "done" -> WaypointKind.DONE
                else -> continue
            }
            val summary = row.optString("summary").trim().ifBlank { kind.name.lowercase().replace('_', ' ') }
            waypoints += TaskWaypoint(
                kind = kind,
                packageName = row.optString("package").trim().takeIf { it.isNotBlank() },
                uri = row.optString("uri").trim().takeIf { it.isNotBlank() },
                until = row.optString("until").trim().ifBlank { defaultUntil(kind) },
                summary = summary.take(160),
            )
        }
        if (waypoints.isEmpty()) return null
        return TaskTrajectory(
            tier = TaskDifficultyTier.HARD,
            from = json.optString("from").trim().ifBlank { fromPage }.take(80),
            to = json.optString("to").trim().ifBlank { goal }.take(180),
            waypoints = waypoints,
            horizonPlanned = true,
        )
    }

    private fun defaultUntil(kind: WaypointKind): String = when (kind) {
        WaypointKind.OPEN_APP -> "app_foreground"
        WaypointKind.LAUNCH_INTENT -> "host_visible"
        WaypointKind.STOP_HUMAN -> "login_wall"
        else -> "goal_contract"
    }
}
