package com.cyclone.mobile.ui.overlay.tracefield

import android.graphics.Bitmap

/**
 * A 16x32 colour grid averaged from the screenshot Cyclone already takes for each observation.
 * No capture of its own: overlays cannot read other apps' pixels, so this lets the field adapt to
 * light, dark and colourful content. Only the tiny grid is kept, in memory, never persisted.
 */
object TraceFieldBackdrop {
    const val GRID_W = 16
    const val GRID_H = 32

    @Volatile var grid: Bitmap? = null
        private set
    @Volatile var revision: Long = 0
        private set

    /** Cheap (one filtered downscale). Safe from any thread; never throws into the capture path. */
    fun ingest(screen: Bitmap) {
        if (!TraceFieldRuntime.isAttached()) return
        runCatching {
            if (screen.isRecycled || screen.width < GRID_W || screen.height < GRID_H) return
            val source = if (screen.config == Bitmap.Config.HARDWARE) screen.copy(Bitmap.Config.ARGB_8888, false) else screen
            val next = Bitmap.createScaledBitmap(source, GRID_W, GRID_H, true)
            if (source !== screen) source.recycle()
            grid = next
            revision += 1
        }
    }

    /** Averaged colour under a screen point, or null before the first observation. */
    fun colorAt(x: Float, y: Float, screenW: Int, screenH: Int): Int? {
        val current = grid ?: return null
        if (screenW <= 0 || screenH <= 0 || current.isRecycled) return null
        val gx = (x / screenW * GRID_W).toInt().coerceIn(0, GRID_W - 1)
        val gy = (y / screenH * GRID_H).toInt().coerceIn(0, GRID_H - 1)
        return runCatching { current.getPixel(gx, gy) }.getOrNull()
    }

    fun clear() {
        grid = null
        revision += 1
    }
}
