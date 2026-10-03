package com.cyclone.mobile.guided

import android.content.Context
import org.json.JSONObject
import java.io.File

data class TeachingGestureEvidence(
    val timestampMs: Long,
    val packageName: String,
    val fromPageKey: String,
    val fromTitle: String,
    val toPageKey: String,
    val toTitle: String,
    val direction: String,
    val params: JSONObject,
)

/** Compact local-only gesture timeline used to merge Follow Me swipes into routine compilation. */
object TeachingGestureEvidenceV292 {
    private val lock = Any()

    fun append(
        context: Context,
        sessionId: String,
        timestampMs: Long,
        packageName: String,
        fromPageKey: String,
        fromTitle: String,
        toPageKey: String,
        toTitle: String,
        direction: String,
        params: JSONObject,
    ) {
        synchronized(lock) {
            val file = file(context, sessionId)
            file.parentFile?.mkdirs()
            file.appendText(
                JSONObject()
                    .put("timestampMs", timestampMs)
                    .put("packageName", packageName)
                    .put("fromPageKey", fromPageKey)
                    .put("fromTitle", fromTitle)
                    .put("toPageKey", toPageKey)
                    .put("toTitle", toTitle)
                    .put("direction", direction)
                    .put("params", params)
                    .toString() + "\n",
            )
        }
    }

    fun list(context: Context, sessionId: String): List<TeachingGestureEvidence> = synchronized(lock) {
        val file = file(context, sessionId)
        if (!file.exists()) return@synchronized emptyList()
        file.readLines().mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                TeachingGestureEvidence(
                    timestampMs = json.optLong("timestampMs"),
                    packageName = json.optString("packageName"),
                    fromPageKey = json.optString("fromPageKey"),
                    fromTitle = json.optString("fromTitle"),
                    toPageKey = json.optString("toPageKey"),
                    toTitle = json.optString("toTitle"),
                    direction = json.optString("direction"),
                    params = json.optJSONObject("params") ?: JSONObject(),
                )
            }.getOrNull()
        }
    }

    fun clear(context: Context, sessionId: String) = synchronized(lock) {
        runCatching { file(context, sessionId).delete() }
    }

    private fun file(context: Context, sessionId: String): File =
        File(context.filesDir, "cyclone-v292-gesture-evidence/$sessionId.jsonl")
}
