package com.cyclone.mobile.ui.v32.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskSceneColorTest {
    private fun rgb(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    @Test
    fun limitedRangeBlackAndWhite() {
        assertEquals(Triple(0, 0, 0), rgb(AskSceneColor.bt709(16, 128, 128)))
        assertEquals(Triple(254, 254, 254), rgb(AskSceneColor.bt709(235, 128, 128)))
        assertEquals(0xFF, AskSceneColor.bt709(16, 128, 128) ushr 24)
    }

    @Test
    fun copperStaysWarmAndReadsAsLight() {
        // Copper (200, 110, 60) in BT.709 limited range: Y 118, Cb 101, Cr 174.
        val (r, g, b) = rgb(AskSceneColor.bt709(118, 101, 174))
        assertTrue("r=$r g=$g b=$b", r in 190..212 && g in 95..120 && b in 50..72)
        assertEquals(r / 255f, AskSceneColor.light(AskSceneColor.bt709(118, 101, 174)), 1e-6f)
    }

    @Test
    fun valuesClampInsteadOfWrapping() {
        val (r, g, b) = rgb(AskSceneColor.bt709(255, 255, 255))
        assertTrue(r in 0..255 && g in 0..255 && b == 255)
        assertEquals(0f, AskSceneColor.light(AskSceneColor.bt709(0, 128, 128)), 1e-6f)
    }
}
