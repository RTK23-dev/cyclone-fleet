package com.cyclone.mobile.ui.overlay.glass

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Plan 27: the optics of Cyclone's tilt-lit glass, kept pure so they can be tested without a device.
 *
 * The light comes from the direction the phone leans. Rim sections facing the light catch a sharp shine, the opposite
 * rim a weaker one (as real glass refracts). Fingerprint dots hug the edges: crisp where the light falls, soft and
 * faint on the far side.
 */
object GlassOptics {
    /** Where the light rests when the phone is held still in the usual way: from the upper left. */
    const val REST_X = -0.45f
    const val REST_Y = -0.70f

    /** Light direction angle (radians, screen coordinates: x right, y down). */
    fun angle(x: Float, y: Float): Float = atan2(y, x)

    /** How strong the shine is, from how far the light is tilted; never fully off so the glass always reads. */
    fun power(x: Float, y: Float): Float = hypot(x, y).coerceIn(0.45f, 1f)

    /**
     * Light from gravity. [gx] and [gy] are the accelerometer's x and y (m/s²); [baseX]/[baseY] the reading when the
     * owner started holding the phone, so only how they tilt it moves the light, not how they happen to hold it.
     */
    fun lightFromGravity(gx: Float, gy: Float, baseX: Float, baseY: Float): Pair<Float, Float> {
        val x = (REST_X - (gx - baseX) / 3.2f).coerceIn(-1f, 1f)
        val y = (REST_Y + (gy - baseY) / 3.2f).coerceIn(-1f, 1f)
        return x to y
    }

    /**
     * Shine on a rim point whose outward normal is [normal] (radians). [thin] is for small pills: a wider, gentler
     * arc; panels get a narrower, sharper one.
     */
    fun rimIntensity(normal: Float, theta: Float, thin: Boolean): Float {
        val face = cos(normal - theta)
        return if (thin) max(0f, face).pow(3.2f) + 0.7f * max(0f, -face).pow(4.5f)
        else max(0f, face).pow(5.5f) + 0.7f * max(0f, -face).pow(7.5f)
    }

    /**
     * A fingerprint dot at normalized position ([nx], [ny]) from the centre (-1..1), [fall] 1 at the rim and 0 at
     * the inner edge of the band. Returns (alpha, radius scale): lit dots are crisp and bright, far dots swell and
     * fade so they read as out of focus.
     */
    fun dot(nx: Float, ny: Float, fall: Float, lx: Float, ly: Float, power: Float): Pair<Float, Float> {
        val len = hypot(nx, ny).takeIf { it > 0f } ?: 1f
        val ll = hypot(lx, ly).takeIf { it > 0f } ?: 1f
        val dot = (nx * lx + ny * ly) / (len * ll)
        val lit = max(0f, dot)
        val far = max(0f, -dot)
        val sharp = lit.pow(1.3f)
        val soft = 1f - sharp
        val radius = (0.25f + 1.05f * fall.pow(1.4f)) * (1f + 0.9f * soft * far)
        val alpha = min(0.85f, (0.06f + 0.7f * sharp * power + 0.1f * far) * fall.pow(0.8f) * (1f - 0.45f * soft * far))
        return alpha to radius
    }

    /** [dot]'s alpha without allocating (drawn many times per frame). */
    fun dotAlpha(nx: Float, ny: Float, fall: Float, lx: Float, ly: Float, power: Float): Float {
        val d = facing(nx, ny, lx, ly)
        val sharp = max(0f, d).pow(1.3f)
        val far = max(0f, -d)
        return min(0.85f, (0.06f + 0.7f * sharp * power + 0.1f * far) * fall.pow(0.8f) * (1f - 0.45f * (1f - sharp) * far))
    }

    /** [dot]'s radius scale without allocating. */
    fun dotRadius(nx: Float, ny: Float, fall: Float, lx: Float, ly: Float): Float {
        val d = facing(nx, ny, lx, ly)
        val sharp = max(0f, d).pow(1.3f)
        return (0.25f + 1.05f * fall.pow(1.4f)) * (1f + 0.9f * (1f - sharp) * max(0f, -d))
    }

    private fun facing(nx: Float, ny: Float, lx: Float, ly: Float): Float {
        val len = hypot(nx, ny).takeIf { it > 0f } ?: 1f
        val ll = hypot(lx, ly).takeIf { it > 0f } ?: 1f
        return (nx * lx + ny * ly) / (len * ll)
    }

    /** Signed distance from a point to a rounded rectangle's edge (negative inside). */
    fun roundedRectDistance(px: Float, py: Float, w: Float, h: Float, r: Float): Float {
        val qx = abs(px - w / 2f) - (w / 2f - r)
        val qy = abs(py - h / 2f) - (h / 2f - r)
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - r
    }

    /** Sweep-gradient rotation (degrees) that puts a button rim's brightest point towards the light. */
    fun rimRotationDegrees(x: Float, y: Float): Float = Math.toDegrees(angle(x, y).toDouble()).toFloat()
}
