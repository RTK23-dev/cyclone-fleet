package com.cyclone.mobile.gesture

import kotlin.math.hypot

/** Public humanization levels. OFF is the compatibility/precision baseline. */
enum class HumanizeProfile {
    OFF,
    LIGHT,
    NORMAL,
}

/** Platform-neutral 2D point used by the gesture planner. */
data class GesturePoint(
    val x: Float,
    val y: Float,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "GesturePoint coordinates must be finite" }
    }

    fun distanceTo(other: GesturePoint): Float = hypot(
        (other.x - x).toDouble(),
        (other.y - y).toDouble(),
    ).toFloat()

    companion object {
        fun lerp(a: GesturePoint, b: GesturePoint, t: Float): GesturePoint {
            require(t.isFinite()) { "Interpolation fraction must be finite" }
            return GesturePoint(
                x = a.x + (b.x - a.x) * t,
                y = a.y + (b.y - a.y) * t,
            )
        }
    }
}

/** Axis-aligned bounds. Coordinates may be off-screen; planners clip against the viewport. */
data class GestureBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()) {
            "GestureBounds coordinates must be finite"
        }
        require(right >= left && bottom >= top) { "GestureBounds must not have negative size" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val center: GesturePoint get() = GesturePoint((left + right) * 0.5f, (top + bottom) * 0.5f)

    fun intersect(other: GestureBounds): GestureBounds? {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (r > l && b > t) GestureBounds(l, t, r, b) else null
    }

    fun contains(point: GesturePoint): Boolean =
        point.x in left..right && point.y in top..bottom

    /**
     * Clamp a point to these bounds while keeping a requested margin where possible.
     * The margin is automatically reduced for tiny viewports.
     */
    fun clamp(point: GesturePoint, margin: Float = 0f): GesturePoint {
        require(margin.isFinite() && margin >= 0f) { "Margin must be finite and non-negative" }
        require(width > 0f && height > 0f) { "Cannot clamp into empty bounds" }
        val mx = minOf(margin, width * 0.499f)
        val my = minOf(margin, height * 0.499f)
        return GesturePoint(
            point.x.coerceIn(left + mx, right - mx),
            point.y.coerceIn(top + my, bottom - my),
        )
    }

    /** Insets without ever inverting the bounds. */
    fun insetCapped(amount: Float): GestureBounds {
        require(amount.isFinite() && amount >= 0f) { "Inset must be finite and non-negative" }
        val dx = minOf(amount, width * 0.499f)
        val dy = minOf(amount, height * 0.499f)
        return GestureBounds(left + dx, top + dy, right - dx, bottom - dy)
    }
}

data class TapPlan(
    val point: GesturePoint,
    val durationMs: Long,
    val profile: HumanizeProfile,
) {
    init {
        require(durationMs > 0L) { "Tap duration must be positive" }
    }
}

/**
 * Platform-neutral cubic stroke. Android integration can render the four points into Path without
 * making the planner itself depend on Android graphics APIs.
 */
data class StrokePlan(
    val start: GesturePoint,
    val control1: GesturePoint,
    val control2: GesturePoint,
    val end: GesturePoint,
    val durationMs: Long,
    val profile: HumanizeProfile,
) {
    init {
        require(durationMs > 0L) { "Stroke duration must be positive" }
    }

    fun sampleAt(fraction: Float): GesturePoint {
        require(fraction.isFinite()) { "Sample fraction must be finite" }
        val t = fraction.coerceIn(0f, 1f)
        // De Casteljau interpolation is numerically stable and preserves the cubic's convex hull.
        val a = GesturePoint.lerp(start, control1, t)
        val b = GesturePoint.lerp(control1, control2, t)
        val c = GesturePoint.lerp(control2, end, t)
        val d = GesturePoint.lerp(a, b, t)
        val e = GesturePoint.lerp(b, c, t)
        return GesturePoint.lerp(d, e, t)
    }

    /** Includes both endpoints. Intended for tests, traces, and lightweight inspection. */
    fun sample(segments: Int = 24): List<GesturePoint> {
        require(segments in 1..4096) { "segments must be in 1..4096" }
        return List(segments + 1) { index -> sampleAt(index.toFloat() / segments.toFloat()) }
    }
}
