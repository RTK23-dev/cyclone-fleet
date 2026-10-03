package com.cyclone.mobile.gesture

import kotlin.math.ln1p
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Deterministic, allocation-light phone-side natural input planner.
 *
 * This class only plans authorized motion. It does not own GATE, control ownership, Session
 * Contract routing, confirmation, stale-observation policy, or Android mutation authority.
 */
object HumanGestureEngine {
    private const val TAP_MIN_MS = 35L
    private const val TAP_MAX_MS = 220L
    private const val SWIPE_MIN_MS = 70L
    private const val SWIPE_MAX_MS = 1_200L

    fun planTap(
        target: GestureBounds,
        viewport: GestureBounds,
        profile: HumanizeProfile = HumanizeProfile.OFF,
        seed: Long = 0L,
        preferredDurationMs: Long? = null,
    ): TapPlan = planTap(target, viewport, profile, SeededGestureRng(seed), preferredDurationMs)

    fun planTap(
        target: GestureBounds,
        viewport: GestureBounds,
        profile: HumanizeProfile,
        rng: GestureRng,
        preferredDurationMs: Long? = null,
    ): TapPlan {
        requireViewport(viewport)
        require(target.width > 0f && target.height > 0f) { "Tap target must have positive size" }

        val clipped = target.intersect(viewport)
            ?: throw IllegalArgumentException("Tap target does not intersect viewport")
        val minDimension = min(clipped.width, clipped.height)
        val point = when (profile) {
            HumanizeProfile.OFF -> clipped.center
            HumanizeProfile.LIGHT -> {
                val safe = clipped.insetCapped(min(3f, minDimension * 0.12f))
                pointInBounds(safe, rng, samplesPerAxis = 2)
            }
            HumanizeProfile.NORMAL -> {
                val safe = clipped.insetCapped(min(5f, minDimension * 0.18f))
                pointInBounds(safe, rng, samplesPerAxis = 3)
            }
        }
        val duration = tapDurationMs(minDimension, profile, rng, preferredDurationMs)
        return TapPlan(point = viewport.clamp(point), durationMs = duration, profile = profile)
    }

    fun planSwipe(
        start: GesturePoint,
        end: GesturePoint,
        viewport: GestureBounds,
        profile: HumanizeProfile = HumanizeProfile.OFF,
        seed: Long = 0L,
        preferredDurationMs: Long? = null,
    ): StrokePlan = planSwipe(start, end, viewport, profile, SeededGestureRng(seed), preferredDurationMs)

    fun planSwipe(
        start: GesturePoint,
        end: GesturePoint,
        viewport: GestureBounds,
        profile: HumanizeProfile,
        rng: GestureRng,
        preferredDurationMs: Long? = null,
    ): StrokePlan {
        requireViewport(viewport)
        val safeStart = viewport.clamp(start)
        val safeEnd = viewport.clamp(end)
        val distance = safeStart.distanceTo(safeEnd)
        val duration = swipeDurationMs(distance, profile, rng, preferredDurationMs)

        if (profile == HumanizeProfile.OFF || distance < 0.5f) {
            return straightStroke(safeStart, safeEnd, duration, profile)
        }

        val dx = safeEnd.x - safeStart.x
        val dy = safeEnd.y - safeStart.y
        val nx = -dy / distance
        val ny = dx / distance

        val (t1, t2, desiredBow) = when (profile) {
            HumanizeProfile.OFF -> error("handled above")
            HumanizeProfile.LIGHT -> Triple(
                0.34f + (rng.nextSignedUnit() * 0.018).toFloat(),
                0.66f + (rng.nextSignedUnit() * 0.018).toFloat(),
                min(8f, distance * (0.010f + (rng.nextUnit() * 0.010).toFloat())),
            )
            HumanizeProfile.NORMAL -> Triple(
                0.29f + (rng.nextSignedUnit() * 0.035).toFloat(),
                0.71f + (rng.nextSignedUnit() * 0.035).toFloat(),
                min(42f, distance * (0.025f + (rng.nextUnit() * 0.030).toFloat())),
            )
        }

        val base1 = GesturePoint.lerp(safeStart, safeEnd, t1.coerceIn(0.18f, 0.46f))
        val base2 = GesturePoint.lerp(safeStart, safeEnd, t2.coerceIn(0.54f, 0.82f))
        val margin = min(2f, min(viewport.width, viewport.height) * 0.01f)

        val randomSign = if (rng.nextUnit() < 0.5) -1f else 1f
        val positiveCapacity = min(
            maxNormalOffset(base1, nx, ny, viewport, margin, +1f),
            maxNormalOffset(base2, nx, ny, viewport, margin, +1f),
        )
        val negativeCapacity = min(
            maxNormalOffset(base1, nx, ny, viewport, margin, -1f),
            maxNormalOffset(base2, nx, ny, viewport, margin, -1f),
        )
        val randomCapacity = if (randomSign > 0f) positiveCapacity else negativeCapacity
        val otherCapacity = if (randomSign > 0f) negativeCapacity else positiveCapacity
        val sign = if (randomCapacity < desiredBow * 0.35f && otherCapacity > randomCapacity) {
            -randomSign
        } else {
            randomSign
        }
        val available = if (sign > 0f) positiveCapacity else negativeCapacity
        val bow = min(desiredBow, available * 0.82f).coerceAtLeast(0f)

        if (bow < 0.05f) {
            return straightStroke(safeStart, safeEnd, duration, profile)
        }

        val c1Scale = when (profile) {
            HumanizeProfile.LIGHT -> 0.92f + (rng.nextUnit() * 0.12).toFloat()
            HumanizeProfile.NORMAL -> 0.82f + (rng.nextUnit() * 0.24).toFloat()
            HumanizeProfile.OFF -> 1f
        }
        val c2Scale = when (profile) {
            HumanizeProfile.LIGHT -> 0.92f + (rng.nextUnit() * 0.12).toFloat()
            HumanizeProfile.NORMAL -> 0.82f + (rng.nextUnit() * 0.24).toFloat()
            HumanizeProfile.OFF -> 1f
        }
        val control1 = viewport.clamp(
            GesturePoint(base1.x + nx * sign * bow * c1Scale, base1.y + ny * sign * bow * c1Scale),
            margin,
        )
        val control2 = viewport.clamp(
            GesturePoint(base2.x + nx * sign * bow * c2Scale, base2.y + ny * sign * bow * c2Scale),
            margin,
        )
        return StrokePlan(
            start = safeStart,
            control1 = control1,
            control2 = control2,
            end = safeEnd,
            durationMs = duration,
            profile = profile,
        )
    }

    private fun pointInBounds(bounds: GestureBounds, rng: GestureRng, samplesPerAxis: Int): GesturePoint {
        fun centeredUnit(): Float {
            var sum = 0.0
            repeat(samplesPerAxis) { sum += rng.nextUnit() }
            return (sum / samplesPerAxis.toDouble()).toFloat()
        }
        return GesturePoint(
            bounds.left + bounds.width * centeredUnit(),
            bounds.top + bounds.height * centeredUnit(),
        )
    }

    private fun tapDurationMs(
        minTargetDimension: Float,
        profile: HumanizeProfile,
        rng: GestureRng,
        preferredDurationMs: Long?,
    ): Long {
        val baseline = preferredDurationMs?.toDouble() ?: run {
            val sizePenalty = 12.0 * ln1p(44.0 / minTargetDimension.coerceAtLeast(1f).toDouble())
            52.0 + sizePenalty
        }
        val factor = when (profile) {
            HumanizeProfile.OFF -> 1.0
            HumanizeProfile.LIGHT -> 0.97 + rng.nextUnit() * 0.06
            HumanizeProfile.NORMAL -> 0.91 + rng.nextUnit() * 0.18
        }
        return (baseline * factor).roundToLong().coerceIn(TAP_MIN_MS, TAP_MAX_MS)
    }

    private fun swipeDurationMs(
        distance: Float,
        profile: HumanizeProfile,
        rng: GestureRng,
        preferredDurationMs: Long?,
    ): Long {
        val baseline = preferredDurationMs?.toDouble() ?: run {
            95.0 + distance * 0.40 + 24.0 * ln1p(distance.toDouble() / 180.0)
        }
        val factor = when (profile) {
            HumanizeProfile.OFF -> 1.0
            HumanizeProfile.LIGHT -> 0.97 + rng.nextUnit() * 0.06
            HumanizeProfile.NORMAL -> 0.91 + rng.nextUnit() * 0.18
        }
        return (baseline * factor).roundToLong().coerceIn(SWIPE_MIN_MS, SWIPE_MAX_MS)
    }

    private fun straightStroke(
        start: GesturePoint,
        end: GesturePoint,
        durationMs: Long,
        profile: HumanizeProfile,
    ): StrokePlan = StrokePlan(
        start = start,
        control1 = GesturePoint.lerp(start, end, 1f / 3f),
        control2 = GesturePoint.lerp(start, end, 2f / 3f),
        end = end,
        durationMs = durationMs,
        profile = profile,
    )

    /** Maximum positive distance along (normal * sign) before leaving the inset viewport. */
    private fun maxNormalOffset(
        point: GesturePoint,
        normalX: Float,
        normalY: Float,
        viewport: GestureBounds,
        margin: Float,
        sign: Float,
    ): Float {
        val vx = normalX * sign
        val vy = normalY * sign
        var max = Float.POSITIVE_INFINITY
        val left = viewport.left + min(margin, viewport.width * 0.499f)
        val right = viewport.right - min(margin, viewport.width * 0.499f)
        val top = viewport.top + min(margin, viewport.height * 0.499f)
        val bottom = viewport.bottom - min(margin, viewport.height * 0.499f)

        if (vx > 1e-6f) max = min(max, (right - point.x) / vx)
        if (vx < -1e-6f) max = min(max, (point.x - left) / -vx)
        if (vy > 1e-6f) max = min(max, (bottom - point.y) / vy)
        if (vy < -1e-6f) max = min(max, (point.y - top) / -vy)
        return if (max.isFinite()) max.coerceAtLeast(0f) else 0f
    }

    private fun requireViewport(viewport: GestureBounds) {
        require(viewport.width > 0f && viewport.height > 0f) { "Viewport must have positive size" }
    }
}
