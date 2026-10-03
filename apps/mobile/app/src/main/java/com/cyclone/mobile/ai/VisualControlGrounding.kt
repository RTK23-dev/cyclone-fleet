package com.cyclone.mobile.ai

import org.json.JSONObject

/** Image coordinates are a locator, never an unscoped input command. */
object VisualControlGrounding {
    const val MAX_AGE_MS = 60_000L

    fun bind(decision: PageAgentDecision, frameId: String, shot: JSONObject, card: JSONObject,
             nowMs: Long): PageAgentDecision? {
        // A boundary has no mutation to ground; never lose a human handoff to frame expiry.
        if (decision.status != "act") return decision.copy(actions = emptyList())
        // Two absent identities must never count as matching evidence.
        val sessionId = card.optString("sessionId")
        if (sessionId.isBlank() || card.optString("observationId").isBlank() ||
            card.optInt("displayId", -1) < 0 || frameId.isBlank()) return null
        val age = nowMs - shot.optLong("timestampMs", 0)
        if (age !in 0..MAX_AGE_MS || shot.optString("sessionId") != card.optString("sessionId") ||
            shot.optInt("displayId", -1) != card.optInt("displayId", -2)) return null
        val controls = card.optJSONArray("controls") ?: return null
        if (decision.actions.any { it.tool == "phone.visual_click" } && decision.actions.size != 1) return null
        val actions = decision.actions.map { action ->
            if (action.tool == "phone.click" && (0 until controls.length()).any {
                controls.optJSONObject(it)?.optString("elementId") == action.controlId
            }) return@map action.copy(visualGrounded = true)
            if (action.tool != "phone.visual_click") return@map action
            if (action.params.optString("frameId") != frameId) return null
            val x = action.params.optDouble("normalizedX", Double.NaN)
            val y = action.params.optDouble("normalizedY", Double.NaN)
            if (!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0) return null
            val frame = shot.optJSONObject("displayBounds") ?: return null
            val width = frame.optInt("right") - frame.optInt("left")
            val height = frame.optInt("bottom") - frame.optInt("top")
            if (width <= 0 || height <= 0 || width != shot.optInt("width") || height != shot.optInt("height")) return null
            val px = frame.optInt("left") + x * width
            val py = frame.optInt("top") + y * height
            val matches = (0 until controls.length()).mapNotNull { controls.optJSONObject(it) }.filter { control ->
                val evidence = control.optJSONObject("evidence") ?: control
                val bounds = evidence.optJSONObject("bounds") ?: return@filter false
                val label = control.optString("label").trim()
                evidence.optBoolean("enabled", true) && evidence.optBoolean("visibleToUser", true) &&
                    (evidence.optBoolean("clickable") || control.optString("role") in setOf("button", "link", "menuitem")) &&
                    label.isNotEmpty() && !label.startsWith("Unlabeled", true) &&
                    px >= bounds.optInt("left") && px < bounds.optInt("right") &&
                    py >= bounds.optInt("top") && py < bounds.optInt("bottom")
            }.distinctBy { it.optString("elementId", it.optString("controlId")) }
            // Overlapping controls are not permission to guess which one owns the pixels.
            val target = matches.singleOrNull() ?: return null
            val id = target.optString("elementId", target.optString("controlId"))
            if (id.split(':').getOrNull(1) != card.optString("observationId")) return null
            PageAgentAction("phone.click", id, JSONObject(), true, action.displaySummary, visualGrounded = true)
        }
        return decision.copy(actions = actions)
    }
}
