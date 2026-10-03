package com.cyclone.mobile.ui.overlay

import androidx.compose.runtime.compositionLocalOf

/**
 * Overlay windows do not resize with the IME the way activities do. Gravity.BOTTOM + y is the
 * lift: rest above the nav bar, then ride 8dp above the keyboard once it reaches the composer.
 */
internal object OverlayImeLift {
    const val KEYBOARD_GAP_DP = 8

    /** Only docked IME windows can occlude the full bottom composer; floating IMEs do not lift it. */
    fun dockedHeight(screenWidth: Int, screenBottom: Int, left: Int, top: Int, right: Int,
        bottom: Int, navigationBottom: Int): Int =
        if (right - left >= screenWidth * .8f && bottom >= screenBottom - navigationBottom - 2 &&
            top in 1 until screenBottom) (screenBottom - top).coerceAtLeast(0) else 0

    fun resolvedHeight(insetHeight: Int, dockedHeight: Int): Int = maxOf(0, insetHeight, dockedHeight)


    fun windowY(
        followKeyboard: Boolean,
        specBottomMarginPx: Int,
        imeBottomPx: Int,
        navigationBottomPx: Int,
        restGapPx: Int,
        keyboardGapPx: Int,
    ): Int {
        if (!followKeyboard) return specBottomMarginPx.coerceAtLeast(0)
        val rest = (navigationBottomPx + restGapPx).coerceAtLeast(0)
        val keyed = (imeBottomPx + keyboardGapPx).coerceAtLeast(0)
        return maxOf(rest, keyed)
    }
}

internal val LocalOverlayImeBottomPx = compositionLocalOf { 0 }
