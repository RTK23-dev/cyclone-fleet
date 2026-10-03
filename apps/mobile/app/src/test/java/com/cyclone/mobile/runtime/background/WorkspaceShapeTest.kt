package com.cyclone.mobile.runtime.background

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceShapeTest {
    @Test fun theBackgroundScreenHasThePhonesShape() {
        // Pixel 8: 1080 x 2400 at 420 dpi stays as it is.
        assertEquals(Triple(1080, 2400, 420), WorkspaceShape.of(1080, 2400, 420))
        // A 1440-wide phone is scaled to 1080 wide with the same aspect and a matching density.
        assertEquals(Triple(1080, 2400, 420), WorkspaceShape.of(1440, 3200, 560))
        // Landscape at the moment of creation still gives a portrait screen.
        assertEquals(Triple(1080, 2400, 420), WorkspaceShape.of(2400, 1080, 420))
        // Unknown metrics fall back to the old fixed screen.
        assertEquals(Triple(720, 1280, 240), WorkspaceShape.of(0, 0, 0))
    }
}
