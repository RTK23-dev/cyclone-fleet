package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayImeLiftTest {
    @Test
    fun compactAndGlassKeepTheirOwnMargin() {
        assertEquals(
            84,
            OverlayImeLift.windowY(
                followKeyboard = false,
                specBottomMarginPx = 84,
                imeBottomPx = 800,
                navigationBottomPx = 128,
                restGapPx = 78,
                keyboardGapPx = 24,
            ),
        )
    }

    @Test
    fun expandedRestsAboveTheNavBarUntilTheKeyboardReachesIt() {
        val rest = OverlayImeLift.windowY(
            followKeyboard = true,
            specBottomMarginPx = 0,
            imeBottomPx = 0,
            navigationBottomPx = 128,
            restGapPx = 78,
            keyboardGapPx = 24,
        )
        assertEquals(206, rest)
        assertEquals(
            206,
            OverlayImeLift.windowY(
                followKeyboard = true,
                specBottomMarginPx = 0,
                imeBottomPx = 100,
                navigationBottomPx = 128,
                restGapPx = 78,
                keyboardGapPx = 24,
            ),
        )
    }

    @Test
    fun expandedRidesEightDpAboveTheKeyboard() {
        assertEquals(
            424,
            OverlayImeLift.windowY(
                followKeyboard = true,
                specBottomMarginPx = 0,
                imeBottomPx = 400,
                navigationBottomPx = 128,
                restGapPx = 78,
                keyboardGapPx = 24,
            ),
        )
    }

    @Test fun dockedKeyboardRescuesMissingInsetsAndDismissalReturnsToRest() {
        val height = OverlayImeLift.dockedHeight(1080, 2400, 0, 1500, 1080, 2400, 80)
        assertEquals(900, OverlayImeLift.resolvedHeight(0, height))
        assertEquals(924, OverlayImeLift.windowY(true, 0, height, 80, 78, 24))
        val closed = OverlayImeLift.resolvedHeight(0, 0)
        assertEquals(158, OverlayImeLift.windowY(true, 0, closed, 80, 78, 24))
        // Keyboards that end above the navigation bar use the same screen-space origin.
        assertEquals(900, OverlayImeLift.dockedHeight(1080, 2400, 0, 1500, 1080, 2320, 80))
    }

    @Test fun floatingAndUndockedKeyboardsDoNotLiftComposer() {
        assertEquals(0, OverlayImeLift.dockedHeight(1080, 2400, 300, 1500, 800, 2400, 80))
        assertEquals(0, OverlayImeLift.dockedHeight(1080, 2400, 0, 1500, 1080, 2100, 80))
        assertEquals(0, OverlayImeLift.dockedHeight(1080, 2400, 0, 0, 1080, 2400, 80))
    }

    @Test fun nativeImeHeightIsNotAddedTwiceToGeometryFallback() {
        assertEquals(900, OverlayImeLift.resolvedHeight(900, 900))
        assertEquals(950, OverlayImeLift.resolvedHeight(950, 900))
        assertEquals(0, OverlayImeLift.resolvedHeight(-1, -1))
    }
}
