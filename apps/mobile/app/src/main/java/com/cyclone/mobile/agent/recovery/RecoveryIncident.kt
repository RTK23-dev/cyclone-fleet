package com.cyclone.mobile.agent.recovery

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class IncidentEffect { CONSENT_REMOVED, USER_GOAL_VERIFIED }
/** Bounded durable recovery context. No target labels, typed values, coordinates or raw trees. */
data class RecoveryIncident(
    val incidentId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val sessionId: String,
    val displayId: Int,
    val openingGeneration: Long,
    val category: String,
    val intendedEffect: IncidentEffect,
    val strategies: List<String> = emptyList(),
    val resolution: String = "OPEN",
    val packageName: String = "",
) {
    fun attempt(code: String) = copy(strategies = (strategies + safeCode(code)).distinct().takeLast(12))
    fun verified(effect: IncidentEffect, session: String, display: Int) =
        if (effect == intendedEffect && session == sessionId && display == displayId) copy(resolution = "VERIFIED") else this
    fun toJson(): JSONObject = JSONObject().put("schema", 1).put("incidentId", incidentId)
        .put("taskId", taskId).put("sessionId", sessionId).put("displayId", displayId)
        .put("openingGeneration", openingGeneration).put("category", safeCode(category))
        .put("intendedEffect", intendedEffect.name).put("strategies", JSONArray(strategies.map(::safeCode)))
        .put("resolution", resolution).put("packageName", packageName).put("nextEvidence", "fresh_same_scope_observation_then_effect_verification")
    companion object {
        // Only internally-defined reason codes survive persistence; arbitrary messages are omitted.
        private fun safeCode(value: String) = value.takeIf { it.matches(Regex("[A-Za-z_.]{1,100}")) } ?: "REDACTED"
        fun fromJson(json: JSONObject): RecoveryIncident? = runCatching {
            val attempts = json.optJSONArray("strategies") ?: JSONArray()
            RecoveryIncident(json.getString("incidentId"), json.getString("taskId"), json.getString("sessionId"),
                json.getInt("displayId"), json.optLong("openingGeneration"), safeCode(json.getString("category")),
                IncidentEffect.valueOf(json.getString("intendedEffect")),
                ((attempts.length() - 12).coerceAtLeast(0) until attempts.length()).map { safeCode(attempts.optString(it)) },
                json.optString("resolution").takeIf { it in setOf("OPEN", "VERIFIED", "TERMINAL") } ?: "OPEN",
                json.optString("packageName"))
        }.getOrNull()
    }
}
