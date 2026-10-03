package com.cyclone.mobile.runtime.background

/** Plan 26 (A42-3): the background screen's size, from the phone's own screen. Pure. */
object WorkspaceShape {
    const val MAX_WIDTH = 1080
    private val FALLBACK = Triple(720, 1280, 240)

    /** Portrait, at most [MAX_WIDTH] wide, same aspect; density scaled with the width and kept in 120..640. */
    fun of(realWidth: Int, realHeight: Int, densityDpi: Int): Triple<Int, Int, Int> {
        if (realWidth <= 0 || realHeight <= 0 || densityDpi <= 0) return FALLBACK
        val w = minOf(realWidth, realHeight)
        val h = maxOf(realWidth, realHeight)
        val scale = minOf(1.0, MAX_WIDTH.toDouble() / w)
        val width = (w * scale).toInt().coerceIn(320, 2160) and 1.inv()
        val height = (h * scale).toInt().coerceIn(320, 3840) and 1.inv()
        val density = (densityDpi * scale).toInt().coerceIn(120, 640)
        return Triple(width, height, density)
    }
}
