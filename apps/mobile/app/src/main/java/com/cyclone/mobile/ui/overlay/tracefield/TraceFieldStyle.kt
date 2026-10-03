package com.cyclone.mobile.ui.overlay.tracefield

import kotlin.math.max
import kotlin.math.min

/** How the Trace Field digits look. The shader branches on [shaderIndex]. */
enum class TraceFieldStyle(val wire: String, val label: String, val blurb: String, val shaderIndex: Float) {
    OBSIDIAN("obsidian", "Obsidian", "Outlined digits that turn to ink on light screens, with a glass fringe on the lens edge.", 0f),
    FORGE("forge", "Forge", "Digits appear white-hot, cool to blue and fade out like embers.", 1f),
    CHAMELEON("chameleon", "Chameleon", "Takes on the colour of the app it is working in, with soft depth of field.", 2f),
    SIGNAL("signal", "Signal", "Digits break into print dots at the lens edge; the finish shimmers.", 3f),
    ;

    companion object {
        fun parse(raw: String?): TraceFieldStyle =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: OBSIDIAN
    }
}

/** Pure colour helpers (no Android types) so the accent logic is unit-testable. */
object TraceFieldColor {
    const val CYCLONE_BLUE = 0xFF4A8DFF.toInt()

    /**
     * Turns an averaged app colour into a usable glyph accent. Greys, near-black and near-white
     * carry no identity, so they fall back to Cyclone blue; real colours get saturation and
     * brightness lifted so the digits read as that colour rather than as mud.
     */
    fun accentFrom(argb: Int): Int {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val saturation = if (maxC == 0f) 0f else delta / maxC
        if (saturation < 0.25f || maxC < 0.15f) return CYCLONE_BLUE
        val hue = when {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta).mod(6f))
            maxC == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return hsvToArgb(hue, max(saturation, 0.7f), max(maxC, 0.9f))
    }

    /** Linear per-channel blend used to glide between accents. */
    fun lerp(from: Int, to: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int): Int {
            val a = from shr shift and 0xFF
            val b = to shr shift and 0xFF
            return (a + (b - a) * k).toInt().coerceIn(0, 255) shl shift
        }
        return ch(24) or ch(16) or ch(8) or ch(0)
    }

    private fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60f).mod(2f) - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun to8(value: Float) = ((value + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
    }
}
