package com.cyclone.mobile.ai.vision.live

import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.runtime.session.ExecutionSessionStore

enum class FrameSourceType {
    ACCESSIBILITY_SCREENSHOT,
    SCRCPY,
    MEDIA_PROJECTION,
    VIRTUAL_DISPLAY_SURFACE,
}

data class LiveFrame(
    val sessionId: String,
    val displayId: Int,
    val frameId: Long,
    val capturedAtMonotonicMs: Long,
    val width: Int,
    val height: Int,
    val source: FrameSourceType,
    val payloadHandle: String? = null,
    val correlationId: String? = null,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId required" }
        require(displayId >= 0) { "displayId must be non-negative" }
        require(frameId >= 0L) { "frameId must be non-negative; 0 means unassigned" }
        require(capturedAtMonotonicMs >= 0L) { "capturedAtMonotonicMs must be non-negative" }
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
    }

    companion object {
        const val UNASSIGNED_FRAME_ID = 0L
    }
}

data class FrameSourceStatus(
    val sessionId: String,
    val displayId: Int,
    val type: FrameSourceType,
    val running: Boolean,
    val available: Boolean,
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId required" }
        require(displayId >= 0) { "displayId must be non-negative" }
    }
}

interface FrameSource {
    val status: FrameSourceStatus

    /** Publishes an already-acquired frame. This contract does not initiate capture. */
    fun publishInto(broker: LiveFrameBroker, frame: LiveFrame): LiveFrame
}

class AccessibilityScreenshotFrameSource(
    sessionId: String = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
    displayId: Int = ExecutionSession.DEFAULT_DISPLAY_ID,
) : FrameSource {
    override val status = FrameSourceStatus(
        sessionId = sessionId,
        displayId = displayId,
        type = FrameSourceType.ACCESSIBILITY_SCREENSHOT,
        running = false,
        available = true,
    )

    override fun publishInto(broker: LiveFrameBroker, frame: LiveFrame): LiveFrame {
        require(frame.sessionId == status.sessionId) { "frame/source session mismatch" }
        require(frame.displayId == status.displayId) { "frame/source display mismatch" }
        require(frame.source == status.type) { "frame/source type mismatch" }
        return broker.publish(frame)
    }

    fun adaptOneShot(
        broker: LiveFrameBroker,
        width: Int,
        height: Int,
        handle: String,
        capturedAtMonotonicMs: Long,
        frameId: Long = LiveFrame.UNASSIGNED_FRAME_ID,
    ): LiveFrame {
        require(handle.isNotBlank()) { "screenshot handle required" }
        return publishInto(
            broker,
            LiveFrame(
                sessionId = status.sessionId,
                displayId = status.displayId,
                frameId = frameId,
                capturedAtMonotonicMs = capturedAtMonotonicMs,
                width = width,
                height = height,
                source = FrameSourceType.ACCESSIBILITY_SCREENSHOT,
                payloadHandle = handle,
            ),
        )
    }
}

interface LiveFrameBroker {
    /** Stores an already-acquired frame and returns the canonical stored frame (including any assigned ID). */
    fun publish(frame: LiveFrame): LiveFrame
    fun latest(sessionId: String): LiveFrame?
    fun latestAfter(sessionId: String, frameId: Long): LiveFrame?
    fun framesSince(sessionId: String, frameId: Long): List<LiveFrame>
    fun clear(sessionId: String)
    fun ageMs(sessionId: String, nowMonotonicMs: Long): Long?
}

class InMemoryLiveFrameBroker(
    private val capacityPerSession: Int = DEFAULT_CAPACITY,
    private val sessions: ExecutionSessionStore = ExecutionSessionStore(),
) : LiveFrameBroker {
    private val lock = Any()
    private val buffers = mutableMapOf<String, ArrayDeque<LiveFrame>>()
    private val lastAssignedIds = mutableMapOf<String, Long>()

    init {
        require(capacityPerSession > 0) { "capacityPerSession must be positive" }
    }

    override fun publish(frame: LiveFrame): LiveFrame = synchronized(lock) {
        val session = sessions.requireSessionDisplay(frame.sessionId, frame.displayId)
        val previousId = lastAssignedIds[session.sessionId] ?: 0L
        val assignedId = when {
            frame.frameId == LiveFrame.UNASSIGNED_FRAME_ID -> previousId + 1L
            frame.frameId <= previousId -> throw IllegalArgumentException(
                "frameId must be strictly monotonic for session ${session.sessionId}: ${frame.frameId} <= $previousId",
            )
            else -> frame.frameId
        }
        val stored = if (assignedId == frame.frameId) frame else frame.copy(frameId = assignedId)
        lastAssignedIds[session.sessionId] = assignedId

        val queue = buffers.getOrPut(session.sessionId) { ArrayDeque() }
        queue.addLast(stored)
        while (queue.size > capacityPerSession) {
            queue.removeFirst()
        }
        stored
    }

    override fun latest(sessionId: String): LiveFrame? = synchronized(lock) {
        buffers[resolveSessionId(sessionId)]?.lastOrNull()
    }

    override fun latestAfter(sessionId: String, frameId: Long): LiveFrame? = synchronized(lock) {
        buffers[resolveSessionId(sessionId)]?.firstOrNull { it.frameId > frameId }
    }

    override fun framesSince(sessionId: String, frameId: Long): List<LiveFrame> = synchronized(lock) {
        buffers[resolveSessionId(sessionId)]?.filter { it.frameId > frameId }.orEmpty()
    }

    override fun clear(sessionId: String) = synchronized(lock) {
        buffers.remove(resolveSessionId(sessionId))
        // Keep lastAssignedIds so IDs remain monotonic if this session publishes again after a clear.
        Unit
    }

    override fun ageMs(sessionId: String, nowMonotonicMs: Long): Long? = synchronized(lock) {
        require(nowMonotonicMs >= 0L) { "nowMonotonicMs must be non-negative" }
        buffers[resolveSessionId(sessionId)]?.lastOrNull()?.let { nowMonotonicMs - it.capturedAtMonotonicMs }
    }

    private fun resolveSessionId(sessionId: String): String = sessions.lookup(sessionId).sessionId

    companion object {
        const val DEFAULT_CAPACITY = 8
    }
}
