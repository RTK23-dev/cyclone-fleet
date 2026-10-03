package com.cyclone.mobile.ai.vision.live

/** A frame ID alone cannot prove post-action capture: a decoder may publish a queued old image. */
data class ActionFrameBoundary(
    val sessionId: String,
    val displayId: Int,
    val lastFrameId: Long,
    val mutationFinishedAtMonotonicMs: Long,
)

object FrameSelection {
    fun eligible(
        frame: LiveFrame,
        sessionId: String,
        displayId: Int,
        nowMonotonicMs: Long,
        maxAgeMs: Long,
        after: ActionFrameBoundary? = null,
        minCapturedAtMonotonicMs: Long? = null,
    ): Boolean {
        require(maxAgeMs >= 0)
        if (frame.sessionId != sessionId || frame.displayId != displayId) return false
        val age = nowMonotonicMs - frame.capturedAtMonotonicMs
        if (age < 0 || age > maxAgeMs) return false
        if (minCapturedAtMonotonicMs != null && frame.capturedAtMonotonicMs < minCapturedAtMonotonicMs) return false
        if (after == null) return true
        return after.sessionId == sessionId && after.displayId == displayId &&
            frame.frameId > after.lastFrameId &&
            frame.capturedAtMonotonicMs > after.mutationFinishedAtMonotonicMs
    }

    /** Shared production wait/selection loop; a buffered pre-request frame is never a fresh capture. */
    fun awaitFresh(sessionId: String, displayId: Int, after: ActionFrameBoundary?, minCapturedAtMonotonicMs: Long?,
        waitMs: Long, frames: () -> List<LiveFrame>, active: () -> Boolean,
        clock: () -> Long, awaitUpdate: (Long) -> Unit): LiveFrame? {
        val deadline = clock() + waitMs.coerceIn(0, 2_000)
        while (active()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val now = clock()
            if (now > deadline) return null
            frames().lastOrNull { eligible(it, sessionId, displayId, now, 750, after, minCapturedAtMonotonicMs) }?.let { return it }
            val remaining = deadline - now
            if (remaining <= 0) return null
            awaitUpdate(remaining)
        }
        return null
    }
}
