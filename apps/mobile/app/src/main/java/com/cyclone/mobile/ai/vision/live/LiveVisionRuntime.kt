package com.cyclone.mobile.ai.vision.live

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.UiBounds
import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.runtime.session.ExecutionSessionStore
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Short operational pixel buffer; disk output is created only when a consumer requests evidence. */
object LiveVisionRuntime {
    val sessions = ExecutionSessionStore()
    val broker: LiveFrameBroker = InMemoryLiveFrameBroker(3, sessions)
    private val lock = Object()
    private val pixels = linkedMapOf<String, Bitmap>()
    private val boundaries = mutableMapOf<String, ActionFrameBoundary>()
    private val sources = mutableMapOf<String, FrameSourceType>()
    private val revisions = mutableMapOf<String, Long>()
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    fun startSource(sessionId: String, displayId: Int, source: FrameSourceType): Long = synchronized(lock) {
        sessions.requireSessionDisplay(sessionId, displayId)
        stopSource(sessionId)
        sources[sessionId] = source
        revisions.getValue(sessionId)
    }

    fun stopSource(sessionId: String) = synchronized(lock) {
        sources.remove(sessionId)
        revisions[sessionId] = (revisions[sessionId] ?: 0L) + 1
        val handles = broker.framesSince(sessionId, 0).mapNotNull { it.payloadHandle }
        handles.forEach { pixels.remove(it)?.recycle() }
        broker.clear(sessionId)
        lock.notifyAll()
    }

    /** Ownership of bitmap transfers to this buffer, including when the frame is rejected. */
    fun publish(sessionId: String, displayId: Int, source: FrameSourceType, revision: Long,
                capturedAtMs: Long, bitmap: Bitmap): Boolean = synchronized(lock) {
        if (sources[sessionId] != source || revisions[sessionId] != revision ||
            capturedAtMs < 0 || SystemClock.uptimeMillis() - capturedAtMs !in 0..2_000) {
            bitmap.recycle()
            return false
        }
        val handle = UUID.randomUUID().toString()
        val frame = try {
            broker.publish(LiveFrame(sessionId, displayId, 0, capturedAtMs, bitmap.width, bitmap.height, source, handle))
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            return false
        }
        pixels[handle] = bitmap
        val retained = sessions.snapshot().flatMap { broker.framesSince(it.sessionId, 0) }
            .mapNotNull { it.payloadHandle }.toSet()
        pixels.keys.toList().filter { it !in retained }.forEach { pixels.remove(it)?.recycle() }
        lock.notifyAll()
        frame.frameId > 0
    }

    fun mutationFinished(sessionId: String = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) = synchronized(lock) {
        if (sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID)
            com.cyclone.mobile.capture.LiveCaptureService.sampler.requestBurst(SystemClock.uptimeMillis())
        val session = sessions.lookup(sessionId)
        boundaries[sessionId] = ActionFrameBoundary(sessionId, session.displayId,
            broker.latest(sessionId)?.frameId ?: 0, SystemClock.uptimeMillis())
    }

    /**
     * Plan 26 (A42-7): the latest frame of a background screen for the approval card, however old (a still page sends
     * no new frames). UI-only, no capture, no side effects; the caller recycles it.
     */
    fun glimpse(sessionId: String, maxWidth: Int = 360): Bitmap? = synchronized(lock) {
        if (!sources.containsKey(sessionId)) return null
        val frame = broker.latest(sessionId) ?: return null
        if (frame.sessionId != sessionId) return null
        val source = pixels[frame.payloadHandle] ?: return null
        val scale = minOf(1f, maxWidth.toFloat() / source.width.coerceAtLeast(1))
        Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
            .let { if (it === source) source.copy(Bitmap.Config.ARGB_8888, false) else it }
    }

    /** A frame arrived since this source started (it may be old: a still screen sends no new frames). */
    fun hasFrame(sessionId: String): Boolean = synchronized(lock) {
        sources.containsKey(sessionId) && broker.latest(sessionId) != null
    }

    fun healthy(sessionId: String = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID): Boolean = synchronized(lock) {
        sources.containsKey(sessionId) && broker.ageMs(sessionId, SystemClock.uptimeMillis())?.let { it in 0..750 } == true
    }

    /** UI-only copy: no new capture, no disk writes and no ownership/action side effects. Caller recycles. */
    fun preview(sessionId: String): Bitmap? = synchronized(lock) {
        if (!healthy(sessionId)) return null
        val session = sessions.lookup(sessionId)
        val frame = broker.latest(sessionId) ?: return null
        if (!FrameSelection.eligible(frame, sessionId, session.displayId, SystemClock.uptimeMillis(), 750, null)) return null
        val source = pixels[frame.payloadHandle] ?: return null
        val scale = minOf(1f, 480f / source.width.coerceAtLeast(1))
        val scaled = Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled === source) source.copy(Bitmap.Config.ARGB_8888, false) else scaled
    }

    /**
     * Wi-Fi screen share copy: the newest whole-display frame after [afterFrameId], scaled to [maxLongEdge]. Like
     * [preview] it takes no new capture and has no side effects. Caller recycles the bitmap.
     */
    fun streamFrame(sessionId: String, afterFrameId: Long, maxLongEdge: Int): Pair<Long, Bitmap>? = synchronized(lock) {
        if (!healthy(sessionId)) return null
        val frame = broker.latest(sessionId) ?: return null
        if (frame.frameId <= afterFrameId) return null
        val source = pixels[frame.payloadHandle] ?: return null
        val scale = minOf(1f, maxLongEdge.toFloat() / maxOf(source.width, source.height).coerceAtLeast(1))
        val copy = if (scale >= 1f) source.copy(Bitmap.Config.ARGB_8888, false)
        else Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
        frame.frameId to copy
    }

    fun capture(cacheDir: File, crop: UiBounds? = null,
                sessionId: String = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
                waitMs: Long = 800, minCapturedAtMonotonicMs: Long? = null): CycloneAccessibilityService.ScreenshotArtifact? {
        val requestDeadline = SystemClock.uptimeMillis() + waitMs.coerceIn(0, 2_000)
        if (minCapturedAtMonotonicMs != null && sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID)
            com.cyclone.mobile.capture.LiveCaptureService.sampler.requestBurst(SystemClock.uptimeMillis())
        // Window capture excludes Cyclone's own overlay even when full-display live capture is active.
        if (sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID && crop == null) {
            captureForegroundWindowBelowOverlay(cacheDir, if (minCapturedAtMonotonicMs == null) 2_000 else waitMs.coerceIn(0, 2_000))
                ?.takeIf { minCapturedAtMonotonicMs == null || (it.capturedAtMonotonicMs ?: -1) >= minCapturedAtMonotonicMs }
                ?.let { return it }
        }
        // Live frames are full-display: they include the non-secure Trace Field unless it is hidden
        // first and only frames composited after the hide are accepted.
        val releaseField = if (sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID &&
            com.cyclone.mobile.ui.overlay.tracefield.TraceFieldRuntime.isShowing()) {
            com.cyclone.mobile.ui.overlay.tracefield.TraceFieldRuntime.hideForCaptureBlocking()
        } else null
        val minFrameAtMs = if (releaseField != null) {
            com.cyclone.mobile.capture.LiveCaptureService.sampler.requestBurst(SystemClock.uptimeMillis())
            maxOf(minCapturedAtMonotonicMs ?: 0L, SystemClock.uptimeMillis())
        } else minCapturedAtMonotonicMs
        val frameDeadline = if (releaseField != null) SystemClock.uptimeMillis() + waitMs.coerceIn(0, 2_000) else requestDeadline
        val selected: Pair<LiveFrame, Bitmap>? = try { synchronized(lock) {
            val session = sessions.lookup(sessionId)
            if (!sources.containsKey(sessionId)) {
                null
            } else {
                val revision = revisions[sessionId]
                val remaining = if (minFrameAtMs == null) waitMs else (frameDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
                val frame = FrameSelection.awaitFresh(sessionId, session.displayId, boundaries[sessionId], minFrameAtMs,
                    remaining, { broker.framesSince(sessionId, 0) }, { revisions[sessionId] == revision && sources.containsKey(sessionId) },
                    { SystemClock.uptimeMillis() }, { lock.wait(it) })
                frame?.let { candidate -> pixels[candidate.payloadHandle]?.copy(Bitmap.Config.ARGB_8888, false)?.let { candidate to it } }
            }
        } } finally { releaseField?.invoke() }

        if (selected == null) return null

        val (frame, bitmap) = selected
        try {
            val bounds = crop?.let {
                UiBounds(it.left.coerceIn(0, bitmap.width), it.top.coerceIn(0, bitmap.height),
                    it.right.coerceIn(0, bitmap.width), it.bottom.coerceIn(0, bitmap.height))
            }?.takeIf { it.width > 0 && it.height > 0 }
            val output = bounds?.let { Bitmap.createBitmap(bitmap, it.left, it.top, it.width, it.height) } ?: bitmap
            try {
                val directory = File(cacheDir, "live-evidence").apply { mkdirs() }
                val file = File(directory, "${UUID.randomUUID()}.png")
                file.outputStream().use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
                return CycloneAccessibilityService.ScreenshotArtifact(file, output.width, output.height, bounds,
                    System.currentTimeMillis(), frame, bounds ?: UiBounds(0, 0, output.width, output.height))
            } finally { if (output !== bitmap) output.recycle() }
        } finally { bitmap.recycle() }
    }

    /**
     * API 34+ can capture the actual application window even while Cyclone's accessibility overlay
     * is visually above it. Android explicitly provides takeScreenshotOfWindow for this case, so
     * foreground agents no longer need the overlay to disappear before they can see the host app.
     */
    private fun captureForegroundWindowBelowOverlay(cacheDir: File, waitMs: Long): CycloneAccessibilityService.ScreenshotArtifact? {
        if (Build.VERSION.SDK_INT < 34) return null
        val service = CycloneAccessibilityService.instance ?: return null
        val targetId = service.foregroundTaskWindowId() ?: return null
        val target = service.windowsOnAllDisplays.get(0).orEmpty().firstOrNull { it.id == targetId } ?: return null

        val rect = android.graphics.Rect().also { target.getBoundsInScreen(it) }
        val latch = CountDownLatch(1)
        var captured: CycloneAccessibilityService.ScreenshotArtifact? = null
        service.takeScreenshotOfWindow(target.id, screenshotExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                try {
                    val wrapped = Bitmap.wrapHardwareBuffer(
                        result.hardwareBuffer,
                        result.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB),
                    ) ?: return
                    val bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: wrapped
                    // Reuse this observation for the Trace Field's colour grid; no extra capture.
                    com.cyclone.mobile.ui.overlay.tracefield.TraceFieldBackdrop.ingest(bitmap)
                    try {
                        val directory = File(cacheDir, "live-evidence").apply { mkdirs() }
                        val file = File(directory, "${UUID.randomUUID()}.png")
                        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
                        captured = CycloneAccessibilityService.ScreenshotArtifact(
                            file = file,
                            width = bitmap.width,
                            height = bitmap.height,
                            crop = null,
                            timestampMs = System.currentTimeMillis(),
                            displayBounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom),
                            capturedAtMonotonicMs = result.timestamp,
                        )
                    } finally {
                        if (bitmap !== wrapped) bitmap.recycle()
                    }
                } finally {
                    result.hardwareBuffer.close()
                    latch.countDown()
                }
            }

            override fun onFailure(errorCode: Int) {
                latch.countDown()
            }
        })
        if (!latch.await(waitMs, TimeUnit.MILLISECONDS)) return null
        return captured
    }
}
