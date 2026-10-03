package com.cyclone.mobile.ui.overlay.tracefield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceFieldStyleTest {
    private fun r(c: Int) = c shr 16 and 0xFF
    private fun g(c: Int) = c shr 8 and 0xFF
    private fun b(c: Int) = c and 0xFF

    @Test fun styleParsingDefaultsToObsidian() {
        assertEquals(TraceFieldStyle.OBSIDIAN, TraceFieldStyle.parse(null))
        assertEquals(TraceFieldStyle.FORGE, TraceFieldStyle.parse(" Forge "))
        assertEquals(TraceFieldStyle.CHAMELEON, TraceFieldStyle.parse("chameleon"))
        assertEquals(TraceFieldStyle.SIGNAL, TraceFieldStyle.parse("signal"))
        assertEquals(TraceFieldStyle.OBSIDIAN, TraceFieldStyle.parse("matrix"))
    }

    @Test fun shaderIndicesAreDistinctAndOrdered() {
        assertEquals(listOf(0f, 1f, 2f, 3f), TraceFieldStyle.entries.map { it.shaderIndex })
    }

    @Test fun greyAndBlackAppsFallBackToCycloneBlue() {
        assertEquals(TraceFieldColor.CYCLONE_BLUE, TraceFieldColor.accentFrom(0xFF808080.toInt()))
        assertEquals(TraceFieldColor.CYCLONE_BLUE, TraceFieldColor.accentFrom(0xFF050505.toInt()))
        assertEquals(TraceFieldColor.CYCLONE_BLUE, TraceFieldColor.accentFrom(0xFFFAFAFA.toInt()))
    }

    @Test fun dullAppColoursAreLiftedButKeepTheirHue() {
        val spotify = TraceFieldColor.accentFrom(0xFF1A6B3A.toInt())
        assertTrue("green stays dominant", g(spotify) > r(spotify) && g(spotify) > b(spotify))
        assertTrue("brightened", g(spotify) >= 220)
        val youtube = TraceFieldColor.accentFrom(0xFF8A1A1A.toInt())
        assertTrue("red stays dominant", r(youtube) > g(youtube) && r(youtube) > b(youtube))
        assertEquals(0xFF, youtube ushr 24)
    }

    @Test fun lerpGlidesChannelByChannel() {
        val from = 0xFF000000.toInt()
        val to = 0xFFFF8040.toInt()
        assertEquals(from, TraceFieldColor.lerp(from, to, 0f))
        assertEquals(to, TraceFieldColor.lerp(from, to, 1f))
        val mid = TraceFieldColor.lerp(from, to, 0.5f)
        assertEquals(127, r(mid))
        assertEquals(64, g(mid))
        assertEquals(32, b(mid))
    }
}
