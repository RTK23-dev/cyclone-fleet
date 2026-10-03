package com.cyclone.mobile.gesture.diagnostics

import com.cyclone.mobile.gesture.GestureBounds
import com.cyclone.mobile.gesture.GesturePoint
import com.cyclone.mobile.gesture.HumanizeProfile
import com.cyclone.mobile.gesture.StrokePlan
import com.cyclone.mobile.gesture.TapPlan

/**
 * Opt-in debug/test bridge from production gesture plans to the normalized trace language used by
 * the Human Gesture lab. Normal planning does not call this adapter and pays no trace allocations.
 *
 * This deliberately returns data, not JSON. Callers may serialize it only when diagnostics are
 * explicitly enabled. The field names map directly to cyclone.human_gesture.trace.v1 concepts.
 */
object HumanGestureTraceAdapter {
    const val SCHEMA: String = "cyclone.human_gesture.trace.v1"
    const val ENGINE_NAME: String = "human-gesture"
    const val ENGINE_VERSION: String = "1"

    fun fromSwipe(
        plan: StrokePlan,
        viewport: GestureBounds,
        seed: Long? = null,
        segments: Int = 24,
    ): NormalizedGestureTrace {
        requireViewport(viewport)
        require(segments in 1..4096) { "segments must be in 1..4096" }
        val samples = plan.sample(segments)
        return NormalizedGestureTrace(
            schema = SCHEMA,
            engineName = ENGINE_NAME,
            engineVersion = ENGINE_VERSION,
            source = "procedural",
            gestureType = "swipe",
            profile = plan.profile,
            seed = seed,
            viewport = TraceViewport(viewport.width.toDouble(), viewport.height.toDouble()),
            durationMs = plan.durationMs,
            target = null,
            points = samples.mapIndexed { index, point ->
                normalizePoint(point, viewport, index.toDouble() / segments.toDouble())
            },
        )
    }

    fun fromTap(
        plan: TapPlan,
        viewport: GestureBounds,
        target: GestureBounds,
        seed: Long? = null,
    ): NormalizedGestureTrace {
        requireViewport(viewport)
        val clippedTarget = target.intersect(viewport)
            ?: throw IllegalArgumentException("Tap target does not intersect viewport")
        require(clippedTarget.width > 0f && clippedTarget.height > 0f) {
            "Tap target must have positive visible size"
        }
        return NormalizedGestureTrace(
            schema = SCHEMA,
            engineName = ENGINE_NAME,
            engineVersion = ENGINE_VERSION,
            source = "procedural",
            gestureType = "tap",
            profile = plan.profile,
            seed = seed,
            viewport = TraceViewport(viewport.width.toDouble(), viewport.height.toDouble()),
            durationMs = plan.durationMs,
            target = normalizeBounds(clippedTarget, viewport),
            points = listOf(normalizePoint(plan.point, viewport, 0.0)),
        )
    }

    private fun normalizePoint(
        point: GesturePoint,
        viewport: GestureBounds,
        t: Double,
    ): NormalizedTracePoint {
        require(viewport.contains(point)) { "Trace point must be inside viewport" }
        val width = viewport.width.toDouble()
        val height = viewport.height.toDouble()
        return NormalizedTracePoint(
            u = ((point.x.toDouble() - viewport.left.toDouble()) / width).coerceIn(0.0, 1.0),
            v = ((point.y.toDouble() - viewport.top.toDouble()) / height).coerceIn(0.0, 1.0),
            t = t.coerceIn(0.0, 1.0),
        )
    }

    private fun normalizeBounds(bounds: GestureBounds, viewport: GestureBounds): NormalizedTraceBounds {
        val width = viewport.width.toDouble()
        val height = viewport.height.toDouble()
        return NormalizedTraceBounds(
            left = ((bounds.left.toDouble() - viewport.left.toDouble()) / width).coerceIn(0.0, 1.0),
            top = ((bounds.top.toDouble() - viewport.top.toDouble()) / height).coerceIn(0.0, 1.0),
            right = ((bounds.right.toDouble() - viewport.left.toDouble()) / width).coerceIn(0.0, 1.0),
            bottom = ((bounds.bottom.toDouble() - viewport.top.toDouble()) / height).coerceIn(0.0, 1.0),
        )
    }

    private fun requireViewport(viewport: GestureBounds) {
        require(viewport.width > 0f && viewport.height > 0f) { "Viewport must have positive size" }
    }
}

data class NormalizedGestureTrace(
    val schema: String,
    val engineName: String,
    val engineVersion: String,
    val source: String,
    val gestureType: String,
    val profile: HumanizeProfile,
    val seed: Long?,
    val viewport: TraceViewport,
    val durationMs: Long,
    val target: NormalizedTraceBounds?,
    val points: List<NormalizedTracePoint>,
)

data class TraceViewport(
    val widthPx: Double,
    val heightPx: Double,
) {
    init {
        require(widthPx.isFinite() && widthPx > 0.0) { "Trace viewport width must be finite and positive" }
        require(heightPx.isFinite() && heightPx > 0.0) { "Trace viewport height must be finite and positive" }
    }
}

data class NormalizedTraceBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(listOf(left, top, right, bottom).all(Double::isFinite)) { "Trace bounds must be finite" }
        require(0.0 <= left && left <= right && right <= 1.0) { "Trace horizontal bounds must be in [0,1]" }
        require(0.0 <= top && top <= bottom && bottom <= 1.0) { "Trace vertical bounds must be in [0,1]" }
    }
}

data class NormalizedTracePoint(
    val u: Double,
    val v: Double,
    val t: Double,
) {
    init {
        require(u.isFinite() && v.isFinite() && t.isFinite()) { "Trace point values must be finite" }
        require(u in 0.0..1.0 && v in 0.0..1.0 && t in 0.0..1.0) {
            "Trace point values must be normalized to [0,1]"
        }
    }
}
