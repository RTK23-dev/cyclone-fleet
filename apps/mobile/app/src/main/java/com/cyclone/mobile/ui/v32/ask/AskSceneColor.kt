package com.cyclone.mobile.ui.v32.ask

/** The scene's colour maths (pure, tested): how a decoded pixel becomes a cell's light and colour. */
object AskSceneColor {
    /** BT.709 limited-range YCbCr to opaque ARGB, as `ask_scene.mp4` is encoded. */
    fun bt709(y: Int, cb: Int, cr: Int): Int {
        val l = (y - 16) * 1.164f
        val u = cb - 128
        val v = cr - 128
        val r = (l + 1.793f * v).toInt().coerceIn(0, 255)
        val g = (l - 0.213f * u - 0.533f * v).toInt().coerceIn(0, 255)
        val b = (l + 2.112f * u).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** A cell's light: the brightest channel (the bake stores colour × light with colour's brightest channel at 1). */
    fun light(argb: Int): Float = maxOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF) / 255f
}
