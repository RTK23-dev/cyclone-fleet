package com.cyclone.mobile.ui.v32.ask

import org.junit.Assert.assertEquals
import org.junit.Test

class AskBackgroundTest {
    @Test
    fun videoCoversTheScreenWithoutStretching() {
        // A 1080x1920 film on a 1080x2400 phone: scaled to the full height, the sides cropped, aspect kept.
        val (sx, sy) = AskBackground.coverScale(1080f, 2400f, 1080f, 1920f)
        assertEquals(1.25f, sx, 1e-4f)
        assertEquals(1f, sy, 1e-4f)
        // A landscape clip on a portrait screen also fills the height.
        val (lx, ly) = AskBackground.coverScale(1080f, 1920f, 1920f, 1080f)
        assertEquals(3.1605f, lx, 1e-3f)
        assertEquals(1f, ly, 1e-4f)
        // Nothing measured yet: no transform.
        assertEquals(1f to 1f, AskBackground.coverScale(0f, 1920f, 1080f, 1920f))
    }
}
