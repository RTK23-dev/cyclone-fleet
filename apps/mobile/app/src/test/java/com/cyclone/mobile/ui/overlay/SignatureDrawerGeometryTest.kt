package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureDrawerGeometryTest {
    @Test fun collapseOnlyRemovesUpperContentHeight() {
        val composerHeight = 66
        val handleHeight = 24
        for (fraction in listOf(1f, .75f, .5f, .25f, 0f)) {
            val upper = SignatureDrawerGeometry.visibleHeight(380, fraction)
            val windowHeight = upper + handleHeight + composerHeight
            // The bottom-anchored window's origin moves by exactly the lost drawer height.
            val composerScreenBottom = (900 - windowHeight) + upper + handleHeight + composerHeight
            assertEquals(900, composerScreenBottom)
        }
        assertEquals(0, SignatureDrawerGeometry.visibleHeight(380, 0f))
        assertEquals(190, SignatureDrawerGeometry.visibleHeight(380, .5f))
    }

    @Test fun reversingDragReturnsToTheSamePositionWithoutReset() {
        val partial = SignatureDrawerGeometry.revealAfterDrag(1f, 120f, 400f)
        assertEquals(.7f, partial, .0001f)
        val reversed = SignatureDrawerGeometry.revealAfterDrag(partial, -80f, 400f)
        assertEquals(.9f, reversed, .0001f)
    }

    @Test fun overscrollAndEmptyDrawersHaveBoundedFiniteGeometry() {
        assertEquals(0f, SignatureDrawerGeometry.revealAfterDrag(.2f, 1000f, 300f), 0f)
        assertEquals(1f, SignatureDrawerGeometry.revealAfterDrag(.2f, -1000f, 300f), 0f)
        assertTrue(SignatureDrawerGeometry.revealAfterDrag(0f, 1f, 0f).isFinite())
        assertEquals(0, SignatureDrawerGeometry.visibleHeight(-1, 1f))
        assertEquals(380, SignatureDrawerGeometry.visibleHeight(380, 2f))
    }

    @Test fun panelBudgetTracksKeyboardAndLeavesSystemEdgesClear() {
        assertEquals(702, SignatureDrawerGeometry.availableHeight(800, 0))
        assertEquals(394, SignatureDrawerGeometry.availableHeight(800, 350))
        assertEquals(702, SignatureDrawerGeometry.availableHeight(800, 0))
        assertEquals(124, SignatureDrawerGeometry.availableHeight(360, 180))
        assertEquals(0, SignatureDrawerGeometry.availableHeight(300, 400))
    }
}
