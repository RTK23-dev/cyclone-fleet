package com.cyclone.mobile.runtime.session

import org.json.JSONArray
import org.json.JSONObject

/** Capture identity, not a learned page key. Null means unavailable, never a cached substitute. */
data class ObservationIdentity(
    val evidenceId: String,
    val generation: Long,
    val sessionId: String,
    val displayId: Int,
    val capturedAtEpochMs: Long,
    val startedMonotonicMs: Long? = null,
    val endedMonotonicMs: Long? = null,
    val profileId: Int? = null,
    val workspaceId: String? = null,
    val workspaceGeneration: Long? = null,
    val windowIds: List<Int>? = null,
    val windowSignature: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rotation: Int? = null,
    val freshness: String = "current",
    val semanticRevision: Long? = null,
    val captureClock: String? = null,
    val executionGeneration: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("schema", 1).put("evidenceId", evidenceId)
        .put("observationId", evidenceId).put("generation", generation).put("sessionId", sessionId)
        .put("displayId", displayId).put("capturedAtEpochMs", capturedAtEpochMs).put("freshness", freshness)
        .put("captureStartMonotonicMs", startedMonotonicMs ?: JSONObject.NULL)
        .put("captureEndMonotonicMs", endedMonotonicMs ?: JSONObject.NULL)
        .put("profileId", profileId ?: JSONObject.NULL).put("workspaceId", workspaceId ?: JSONObject.NULL)
        .put("workspaceGeneration", workspaceGeneration ?: JSONObject.NULL)
        .put("windowIds", windowIds?.let(::JSONArray) ?: JSONObject.NULL)
        .put("windowSignature", windowSignature ?: JSONObject.NULL)
        .put("width", width ?: JSONObject.NULL).put("height", height ?: JSONObject.NULL)
        .put("rotation", rotation ?: JSONObject.NULL)
        .put("semanticRevision", semanticRevision ?: JSONObject.NULL)
        .put("captureClock", captureClock ?: JSONObject.NULL)
        .put("executionGeneration", executionGeneration ?: JSONObject.NULL)
        .put("fieldState", JSONObject().apply {
            put("tree", freshness)
            put("profile", if (profileId == null) "unavailable" else freshness)
            put("window", if (windowSignature == null) "unavailable" else freshness)
            put("geometry", if (width == null || height == null || rotation == null) "unavailable" else freshness)
            put("captureTiming", if (startedMonotonicMs == null || endedMonotonicMs == null) "unavailable" else freshness)
        })

    companion object {
        fun fromPayload(id: String, generation: Long, execution: ExecutionContext, at: Long, payload: JSONObject): ObservationIdentity {
            val evidence = payload.optJSONObject("pageEvidence") ?: JSONObject()
            val plane = payload.optJSONObject("plane") ?: JSONObject()
            fun long(json: JSONObject, name: String) = if (json.has(name) && !json.isNull(name)) json.optLong(name) else null
            fun int(json: JSONObject, name: String) = long(json, name)?.toInt()
            val windows = payload.optJSONArray("windows")
            val ids = windows?.let { array -> (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.takeIf { it.optInt("type") != 4 && it.optInt("id") != 0x0C4C0E }?.optInt("id")
            }.sorted() }
            return ObservationIdentity(id, generation, execution.sessionId, execution.displayId, at,
                long(evidence, "captureStartMonotonicMs"), long(evidence, "captureEndMonotonicMs"),
                int(evidence, "profileId"), plane.optString("workspaceId").takeUnless { it.isBlank() || it == "null" },
                long(plane, "workspaceGeneration"), ids,
                evidence.optString("windowSignature").takeUnless { it.isBlank() || it == "null" },
                int(evidence, "captureWidth")?.takeIf { it > 0 }, int(evidence, "captureHeight")?.takeIf { it > 0 },
                int(evidence, "rotation"), semanticRevision = long(evidence, "semanticRevision"),
                captureClock = evidence.optString("captureClock").takeIf { it.isNotBlank() },
                executionGeneration = long(payload, "executionGeneration"))
        }
    }
}
