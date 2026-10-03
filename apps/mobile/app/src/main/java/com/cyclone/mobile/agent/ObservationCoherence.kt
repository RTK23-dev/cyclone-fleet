package com.cyclone.mobile.agent

import com.cyclone.mobile.agent.contract.AgentPageCard
import org.json.JSONObject

/** Bracket pixels with semantic observations. Sequential captures never imply atomicity. */
object ObservationCoherence {
    const val MAX_CAPTURE_SKEW_MS = 1_500L
    fun accepts(before: AgentPageCard, after: AgentPageCard, shot: JSONObject, elapsedMs: Long): Boolean {
        if (elapsedMs !in 0..MAX_CAPTURE_SKEW_MS || !before.actionable || !after.actionable) return false
        if (before.sessionId != after.sessionId || before.displayId != after.displayId ||
            shot.optString("sessionId") != after.sessionId || shot.optInt("displayId", -1) != after.displayId) return false
        if (before.packageName != after.packageName || before.activity != after.activity ||
            before.pageKey != after.pageKey || before.contentKey != after.contentKey ||
            before.accessibilityFingerprint != after.accessibilityFingerprint) return false
        val width = after.pageEvidence.optInt("captureWidth")
        val height = after.pageEvidence.optInt("captureHeight")
        return width > 0 && height > 0 && width == before.pageEvidence.optInt("captureWidth") &&
            height == before.pageEvidence.optInt("captureHeight") &&
            width == shot.optInt("width") && height == shot.optInt("height")
    }
}
