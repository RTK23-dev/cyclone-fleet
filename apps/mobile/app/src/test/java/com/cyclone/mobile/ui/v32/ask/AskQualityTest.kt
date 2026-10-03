package com.cyclone.mobile.ui.v32.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AskQualityTest {
    private val flagship = DeviceClass(lowRam = false, totalRamGb = 11.5, cores = 8, performanceClass = 34)
    private val midNoClass = DeviceClass(lowRam = false, totalRamGb = 7.6, cores = 8, performanceClass = 0)
    private val budget = DeviceClass(lowRam = false, totalRamGb = 3.7, cores = 8, performanceClass = 0)
    private val oldMid = DeviceClass(lowRam = false, totalRamGb = 5.6, cores = 8, performanceClass = 0)
    private val goRam = DeviceClass(lowRam = true, totalRamGb = 2.0, cores = 4, performanceClass = 0)

    @Test
    fun autoPicksLiteOnlyForClearlyOlderPhones() {
        assertEquals(GlassQuality.FULL, QualityPolicy.autoTier(flagship))
        assertEquals(GlassQuality.FULL, QualityPolicy.autoTier(midNoClass))
        assertEquals(GlassQuality.LITE, QualityPolicy.autoTier(budget))
        assertEquals(GlassQuality.LITE, QualityPolicy.autoTier(oldMid))
        assertEquals(GlassQuality.LITE, QualityPolicy.autoTier(goRam))
        assertEquals(GlassQuality.LITE, QualityPolicy.autoTier(flagship.copy(cores = 4)))
    }

    @Test
    fun theOwnersChoiceWinsAndAutoRemembersAStepDown() {
        assertEquals(GlassQuality.FULL, QualityPolicy.resolve(QualityMode.FULL, budget, steppedDown = true))
        assertEquals(GlassQuality.LITE, QualityPolicy.resolve(QualityMode.LITE, flagship, steppedDown = false))
        assertEquals(GlassQuality.FULL, QualityPolicy.resolve(QualityMode.AUTO, flagship, steppedDown = false))
        assertEquals(GlassQuality.LITE, QualityPolicy.resolve(QualityMode.AUTO, flagship, steppedDown = true))
    }

    @Test
    fun explanationsSayWhatAutoDid() {
        assertTrue(QualityPolicy.explain(QualityMode.AUTO, GlassQuality.LITE, budget, false).startsWith("Auto chose Lite"))
        assertEquals("Auto switched to Lite after some slow frames on this phone.",
            QualityPolicy.explain(QualityMode.AUTO, GlassQuality.LITE, flagship, true))
        assertEquals("Auto chose Full for this phone.", QualityPolicy.explain(QualityMode.AUTO, GlassQuality.FULL, flagship, false))
        assertEquals("Lite: the same look with less work.", QualityPolicy.explain(QualityMode.LITE, GlassQuality.LITE, flagship, false))
    }

    @Test
    fun jankWindowNeedsEnoughFramesAndEnoughLateOnes() {
        val w = JankWindow(windowMs = 3_000, minFrames = 90, lateShare = 0.12)
        var t = 0L
        // 120 smooth frames at 60 Hz: never slow.
        repeat(120) { assertFalse(w.record(t, 15.0, 16.7)); t += 16 }
        // 10% late: still fine.
        w.reset(); t = 0
        var tripped = false
        repeat(150) { i -> tripped = tripped || w.record(t, if (i % 10 == 0) 40.0 else 15.0, 16.7); t += 16 }
        assertFalse(tripped)
        // 20% late over a full window: slow.
        w.reset(); t = 0
        repeat(150) { i -> tripped = tripped || w.record(t, if (i % 5 == 0) 40.0 else 15.0, 16.7); t += 16 }
        assertTrue(tripped)
        // Too few frames to judge (a short burst of jank while starting) never trips.
        w.reset(); t = 0; tripped = false
        repeat(40) { tripped = tripped || w.record(t, 60.0, 16.7); t += 16 }
        assertFalse(tripped)
    }

    @Test
    fun oldFramesLeaveTheWindow() {
        val w = JankWindow(windowMs = 1_000, minFrames = 10, lateShare = 0.5)
        var t = 0L
        repeat(30) { w.record(t, 50.0, 16.7); t += 16 }       // a slow start…
        t += 2_000                                               // …then a quiet gap
        var slow = false
        repeat(60) { slow = w.record(t, 15.0, 16.7); t += 16 }  // smooth again
        assertFalse(slow)
    }

    @Test
    fun rainHoldsWhileScrolling() {
        AskMotion.scrolled(10_000)
        assertTrue(AskMotion.holding(10_200))
        assertFalse(AskMotion.holding(10_000 + AskMotion.RESUME_MS + 1))
    }
}
