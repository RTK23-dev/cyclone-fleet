package com.cyclone.mobile.agent

import org.json.JSONObject

/** Lightweight metadata samples surround exactly one semantic traversal. No projection can recapture. */
data class ObservationSurface(
    val sessionId: String, val displayId: Int, val scope: String,
    val windowSignature: String, val width: Int, val height: Int, val rotation: Int,
    val revision: Long, val profileId: Int? = null,
)

class CaptureChanged : IllegalStateException("OBSERVATION_CHANGED_DURING_CAPTURE")

data class CapturedSemantic<T>(
    val semantic: T, val surface: ObservationSurface, val startMs: Long, val endMs: Long,
    val image: JSONObject?, val imageStartMs: Long?, val imageEndMs: Long?,
)

object SemanticCaptureBoundary {
    fun workspaceProfile(plane: com.cyclone.mobile.runtime.session.SessionPlane,
        holder: com.cyclone.mobile.runtime.workspaces.WorkspaceLease?,
        workspace: com.cyclone.mobile.runtime.workspaces.Workspace?): Int? {
        val id = plane.workspaceId ?: return null
        if (holder?.workspaceId != id || holder.generation != plane.workspaceGeneration || workspace?.id != id) throw CaptureChanged()
        return workspace.androidUserId
    }
    fun windowSignature(windows: List<com.cyclone.mobile.UiWindowSnapshot>): String = windows
        .filter { it.type != 4 && it.id != 0x0C4C0E }.sortedBy { it.id }.joinToString("|") {
            "${it.id}:${it.type}:${it.layer}:${it.active}:${it.focused}:${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}"
        }
    private fun imageGeometryMatches(image: JSONObject, surface: ObservationSurface): Boolean {
        val bounds = image.optJSONObject("displayBounds")
        if (bounds == null) return image.optInt("width") == surface.width && image.optInt("height") == surface.height
        val left = bounds.optInt("left", -1); val top = bounds.optInt("top", -1)
        val right = bounds.optInt("right", -1); val bottom = bounds.optInt("bottom", -1)
        return left >= 0 && top >= 0 && right <= surface.width && bottom <= surface.height &&
            right > left && bottom > top && image.optInt("width") == right - left && image.optInt("height") == bottom - top
    }
    /**
     * [tolerateContentChange]: accept a capture during which only the content revision moved (an app that animates
     * all the time) while the window set, size, rotation and scope stayed the same. Only the mapper's last attempt
     * asks for this; its taps still go through PhoneToolExecutor's own freshness checks.
     */
    fun <T> capture(surface: () -> ObservationSurface, semantic: () -> T,
        image: (() -> JSONObject)? = null, clock: () -> Long = { android.os.SystemClock.uptimeMillis() },
        settle: () -> Unit = {}, tolerateContentChange: Boolean = false): CapturedSemantic<T> {
        settle()
        val start = clock()
        val before = surface()
        val tree = semantic()
        val end = clock()
        val after = surface()
        if (before != after && !(tolerateContentChange && before.copy(revision = 0) == after.copy(revision = 0))) throw CaptureChanged()
        val imageStart = image?.let { clock() }
        val pixels = image?.let { capture -> runCatching { capture() }.getOrElse {
            if (it is java.util.concurrent.CancellationException || it is InterruptedException) throw it
            JSONObject().put("available", false).put("errorCode", "SCREENSHOT_FAILED")
        } }
        val imageEnd = image?.let { clock() }
        if (image != null && before != surface()) throw CaptureChanged()
        val accepted = pixels?.let {
            val frameTime = if (it.isNull("capturedAtMonotonicMs")) -1L else it.optLong("capturedAtMonotonicMs", -1)
            val valid = it.optBoolean("available", true) && it.optString("sessionId") == before.sessionId &&
                it.optInt("displayId", -1) == before.displayId && imageGeometryMatches(it, before) && frameTime in imageStart!!..imageEnd!! &&
                imageEnd - start in 0..ObservationCoherence.MAX_CAPTURE_SKEW_MS
            if (valid) JSONObject(it.toString()).put("available", true).put("association", "same_generation")
            else JSONObject().put("available", false).put("errorCode", if (it.optBoolean("available", true)) "IMAGE_CAPTURE_SKEW" else "SCREENSHOT_FAILED")
        }
        return CapturedSemantic(tree, before, start, end, accepted, imageStart, imageEnd)
    }
}
