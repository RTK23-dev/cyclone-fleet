package com.cyclone.mobile

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.HandlerThread
import com.cyclone.mobile.fastpath.FastPathTimings
import com.cyclone.mobile.gesture.AndroidGestureRenderer
import com.cyclone.mobile.gesture.GestureBounds
import com.cyclone.mobile.gesture.GesturePoint
import com.cyclone.mobile.gesture.HumanGestureEngine
import com.cyclone.mobile.gesture.HumanGestureRuntimePolicy
import com.cyclone.mobile.gesture.HumanGestureSeed
import com.cyclone.mobile.gesture.HumanizePreference
import com.cyclone.mobile.gesture.HumanizeProfile
import com.cyclone.mobile.gesture.RuntimeGestureKind
import com.cyclone.mobile.ui.overlay.OverlayGesturePassthrough
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class HumanGestureDispatchTrace(
    val profile: HumanizeProfile,
    val dispatchMode: String,
    val durationMs: Long,
    val accepted: Boolean,
    val reason: String? = null,
    val displayId: Int = 0,
)

private data class GestureDispatchOutcome(
    val accepted: Boolean,
    val reason: String? = null,
)

/**
 * Android execution adapter for already-authorized physical touches.
 *
 * This object deliberately owns no GATE, stale-observation, duplicate, confirmation, control-owner,
 * or SessionContract decisions. Callers reach it only after those existing authorities have allowed
 * the mutation. Named virtual displays use the same cubic [GestureDescription] path via
 * [GestureDescription.Builder.setDisplayId]; they do not fall back to `/system/bin/input` swipe.
 *
 * `dispatchGesture` is queued, not completed. This adapter waits for [AccessibilityService.GestureResultCallback]
 * before returning so Fast Path settle observes the screen after the stroke has landed.
 */
object HumanGestureDispatch {
    const val REASON_TIMEOUT = "gesture_timeout"
    const val REASON_CANCELLED = "gesture_cancelled"
    const val REASON_NOT_QUEUED = "not_queued"
    const val REASON_UNKNOWN = "gesture_unknown"

    private val localOrdinal = AtomicLong(0L)
    private val traces = object : LinkedHashMap<String, HumanGestureDispatchTrace>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HumanGestureDispatchTrace>?): Boolean = size > 128
    }
    private val callbackLooper by lazy {
        HandlerThread("cyclone-hg-callback").apply {
            isDaemon = true
            start()
        }.looper
    }

    @Synchronized
    private fun record(commandId: String?, trace: HumanGestureDispatchTrace): Boolean {
        if (!commandId.isNullOrBlank()) traces[commandId] = trace
        return trace.accepted
    }

    /** Non-consuming read used only when result serialization needs to distinguish semantic click from fallback touch. */
    @Synchronized
    fun peekTrace(commandId: String?): HumanGestureDispatchTrace? =
        commandId?.takeIf { it.isNotBlank() }?.let(traces::get)

    @Synchronized
    fun consumeTrace(commandId: String?): HumanGestureDispatchTrace? =
        commandId?.takeIf { it.isNotBlank() }?.let(traces::remove)

    fun incomplete(trace: HumanGestureDispatchTrace?): Boolean =
        trace != null && !trace.accepted

    fun tap(
        service: CycloneAccessibilityService,
        x: Float,
        y: Float,
        preference: HumanizePreference,
        kind: RuntimeGestureKind,
        commandId: String? = null,
        targetBounds: UiBounds? = null,
        displayId: Int = 0,
        viewportBounds: GestureBounds? = null,
    ): Boolean {
        val profile = HumanGestureRuntimePolicy.resolve(preference, kind)
        if (profile == HumanizeProfile.OFF && targetBounds == null) {
            val outcome = legacyTap(service, x, y, displayId)
            return record(commandId, HumanGestureDispatchTrace(profile, "legacy_straight", 80L, outcome.accepted, outcome.reason, displayId))
        }
        val viewport = viewportBounds ?: viewport(service)
        val target = targetBounds?.takeIf { it.width > 0 && it.height > 0 }?.toGestureBounds()
            ?: pointBounds(x, y)
        val ordinal = localOrdinal.incrementAndGet()
        val plan = runCatching {
            HumanGestureEngine.planTap(
                target = target,
                viewport = viewport,
                profile = profile,
                seed = HumanGestureSeed.derive(commandId, kind.name, ordinal, floatArrayOf(x, y), 80L),
                preferredDurationMs = 80L,
            )
        }.getOrElse {
            return record(commandId, HumanGestureDispatchTrace(profile, "plan_rejected", 0L, false, it.message, displayId))
        }
        val path = AndroidGestureRenderer.tapPath(plan)
        val outcome = dispatchAndAwait(service, description(path, plan.durationMs, displayId), plan.durationMs, displayId)
        return record(commandId, HumanGestureDispatchTrace(profile, "humanized_path", plan.durationMs, outcome.accepted, outcome.reason, displayId))
    }

    fun longPress(
        service: CycloneAccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long,
        preference: HumanizePreference,
        kind: RuntimeGestureKind,
        commandId: String? = null,
        targetBounds: UiBounds? = null,
        displayId: Int = 0,
        viewportBounds: GestureBounds? = null,
    ): Boolean {
        val profile = HumanGestureRuntimePolicy.resolve(preference, kind)
        if (profile == HumanizeProfile.OFF && targetBounds == null) {
            val boundedDuration = durationMs.coerceIn(450L, 3_000L)
            val outcome = legacyLongPress(service, x, y, boundedDuration, displayId)
            return record(commandId, HumanGestureDispatchTrace(profile, "legacy_straight", boundedDuration, outcome.accepted, outcome.reason, displayId))
        }
        val viewport = viewportBounds ?: viewport(service)
        val target = targetBounds?.takeIf { it.width > 0 && it.height > 0 }?.toGestureBounds()
            ?: pointBounds(x, y)
        val ordinal = localOrdinal.incrementAndGet()
        val tapPlan = runCatching {
            HumanGestureEngine.planTap(
                target = target,
                viewport = viewport,
                profile = profile,
                seed = HumanGestureSeed.derive(commandId, kind.name, ordinal, floatArrayOf(x, y), durationMs),
                preferredDurationMs = 80L,
            )
        }.getOrElse {
            return record(commandId, HumanGestureDispatchTrace(profile, "plan_rejected", 0L, false, it.message, displayId))
        }
        val path = AndroidGestureRenderer.tapPath(tapPlan)
        val boundedDuration = durationMs.coerceIn(450L, 3_000L)
        val outcome = dispatchAndAwait(service, description(path, boundedDuration, displayId), boundedDuration, displayId)
        return record(commandId, HumanGestureDispatchTrace(profile, "humanized_path", boundedDuration, outcome.accepted, outcome.reason, displayId))
    }

    fun swipe(
        service: CycloneAccessibilityService,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        preference: HumanizePreference,
        kind: RuntimeGestureKind,
        commandId: String? = null,
        displayId: Int = 0,
        viewportBounds: GestureBounds? = null,
    ): Boolean {
        val profile = HumanGestureRuntimePolicy.resolve(preference, kind)
        if (profile == HumanizeProfile.OFF) {
            val boundedDuration = durationMs.coerceIn(100L, 3_000L)
            val outcome = legacySwipe(service, x1, y1, x2, y2, boundedDuration, displayId)
            return record(commandId, HumanGestureDispatchTrace(profile, "legacy_straight", boundedDuration, outcome.accepted, outcome.reason, displayId))
        }
        val viewport = viewportBounds ?: viewport(service)
        val ordinal = localOrdinal.incrementAndGet()
        val plan = runCatching {
            HumanGestureEngine.planSwipe(
                start = GesturePoint(x1, y1),
                end = GesturePoint(x2, y2),
                viewport = viewport,
                profile = profile,
                seed = HumanGestureSeed.derive(
                    commandId,
                    kind.name,
                    ordinal,
                    floatArrayOf(x1, y1, x2, y2),
                    durationMs,
                ),
                preferredDurationMs = durationMs,
            )
        }.getOrElse {
            return record(commandId, HumanGestureDispatchTrace(profile, "plan_rejected", 0L, false, it.message, displayId))
        }
        val path = AndroidGestureRenderer.strokePath(plan)
        val outcome = dispatchAndAwait(service, description(path, plan.durationMs, displayId), plan.durationMs, displayId)
        return record(commandId, HumanGestureDispatchTrace(profile, "humanized_path", plan.durationMs, outcome.accepted, outcome.reason, displayId))
    }

    private fun viewport(service: CycloneAccessibilityService): GestureBounds {
        val metrics = service.resources.displayMetrics
        return GestureBounds(0f, 0f, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
    }

    private fun pointBounds(x: Float, y: Float): GestureBounds = GestureBounds(
        left = x - 0.5f,
        top = y - 0.5f,
        right = x + 0.5f,
        bottom = y + 0.5f,
    )

    private fun UiBounds.toGestureBounds(): GestureBounds = GestureBounds(
        left.toFloat(),
        top.toFloat(),
        right.toFloat(),
        bottom.toFloat(),
    )

    private fun description(path: Path, durationMs: Long, displayId: Int): GestureDescription {
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        if (displayId > 0) builder.setDisplayId(displayId)
        return builder.build()
    }

    private fun legacyTap(
        service: CycloneAccessibilityService,
        x: Float,
        y: Float,
        displayId: Int,
    ): GestureDispatchOutcome {
        val path = Path().apply { moveTo(x, y) }
        return dispatchAndAwait(service, description(path, 80L, displayId), 80L, displayId)
    }

    private fun legacyLongPress(
        service: CycloneAccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long,
        displayId: Int,
    ): GestureDispatchOutcome {
        val bounded = durationMs.coerceIn(450L, 3_000L)
        val path = Path().apply { moveTo(x, y) }
        return dispatchAndAwait(service, description(path, bounded, displayId), bounded, displayId)
    }

    private fun legacySwipe(
        service: CycloneAccessibilityService,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        displayId: Int,
    ): GestureDispatchOutcome {
        val bounded = durationMs.coerceIn(100L, 3_000L)
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        return dispatchAndAwait(service, description(path, bounded, displayId), bounded, displayId)
    }

    /**
     * Queue the stroke, yield overlay chrome on display 0, then wait for completion.
     *
     * Named virtual displays are not covered by the Ask overlay; they skip overlay yield and
     * target [GestureDescription.Builder.setDisplayId] instead of `/system/bin/input`.
     *
     * `dispatchGesture` returning true means Android queued the stroke, not that it landed.
     * Only [AccessibilityService.GestureResultCallback.onCompleted] is success.
     */
    private fun dispatchAndAwait(
        service: CycloneAccessibilityService,
        gesture: GestureDescription,
        durationMs: Long,
        displayId: Int,
    ): GestureDispatchOutcome =
        if (displayId > 0) dispatchQueued(service, gesture, durationMs)
        else OverlayGesturePassthrough.withHostPassthrough {
            dispatchQueued(service, gesture, durationMs)
        }

    private fun dispatchQueued(
        service: CycloneAccessibilityService,
        gesture: GestureDescription,
        durationMs: Long,
    ): GestureDispatchOutcome {
        val done = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                done.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                cancelled.set(true)
                done.countDown()
            }
        }
        val queued = service.dispatchGesture(gesture, callback, Handler(callbackLooper))
        if (!queued) return GestureDispatchOutcome(false, REASON_NOT_QUEUED)
        val finished = done.await(FastPathTimings.gestureAwaitBudgetMs(durationMs), TimeUnit.MILLISECONDS)
        return when {
            completed.get() -> GestureDispatchOutcome(true)
            cancelled.get() -> GestureDispatchOutcome(false, REASON_CANCELLED)
            !finished -> GestureDispatchOutcome(false, REASON_TIMEOUT)
            else -> GestureDispatchOutcome(false, REASON_UNKNOWN)
        }
    }
}
