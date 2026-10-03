package com.cyclone.mobile.ui.overlay.tracefield

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceFieldChoreographerTest {
    private val w = 1080f
    private val h = 2400f
    private val d = 2.625f

    private fun machine(mode: TraceFieldMode = TraceFieldMode.FIELD, reduceMotion: Boolean = false) =
        TraceFieldChoreographer(w, h, d, mode, reduceMotion)

    /** Steps at 120 Hz from [from] to [to] and returns the last frame. */
    private fun TraceFieldChoreographer.run(from: Double, to: Double): TraceFrame {
        var t = from
        var last = frame(t)
        while (t < to) {
            t += 1.0 / 120.0
            last = frame(t)
        }
        return last
    }

    @Test fun idleFieldDrawsNothing() {
        val frame = machine().frame(0.0)
        assertFalse(frame.visible)
        assertFalse(frame.animating)
        assertEquals(TracePhase.OFF, frame.phase)
    }

    @Test fun attentionSignalsAreIgnoredWithoutATask() {
        val m = machine()
        m.onEvent(TraceEvent.Observe("fp"), 0.0)
        m.onEvent(TraceEvent.Act(TraceActKind.TAP, 10f, 10f), 0.1)
        assertEquals(TracePhase.OFF, m.phase)
        assertFalse(m.run(0.0, 1.0).visible)
    }

    @Test fun wakeRisesThenBreathesInThink() {
        val m = machine()
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        assertEquals(TracePhase.WAKE, m.phase)
        val frame = m.run(0.0, 1.0)
        assertEquals(TracePhase.THINK, frame.phase)
        assertTrue(frame.visible)
        assertTrue(frame.animating)
        assertTrue(frame.intensity > 0.95f)
        assertTrue("lens left the Aurora pill", frame.lensY < h * 0.6f)
    }

    @Test fun observeSweepsAScanlineThenReturnsToThink() {
        val m = machine()
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        m.run(0.0, 1.0)
        m.onEvent(TraceEvent.Observe("page-a"), 1.0)
        val mid = m.run(1.0, 1.35)
        assertEquals(TracePhase.OBSERVE, mid.phase)
        assertTrue(mid.scanStrength > 0f)
        assertTrue(mid.scanY in (h * 0.3f)..(h * 0.7f))
        val after = m.run(1.35, 2.2)
        assertEquals(TracePhase.THINK, after.phase)
        assertEquals(0f, after.scanStrength)
    }

    @Test fun targetMorphsLensOntoTheNodeAndTapRipples() {
        val m = machine()
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        m.run(0.0, 1.0)
        m.onEvent(TraceEvent.Target(100f, 400f, 980f, 540f, "0.1.2"), 1.0)
        val locked = m.run(1.0, 2.0)
        assertEquals(TracePhase.TARGET, locked.phase)
        assertEquals(540f, locked.lensX, 2f)
        assertEquals(470f, locked.lensY, 2f)
        assertTrue("lens is a wide rounded rect", locked.lensHalfW > locked.lensHalfH * 2)
        m.onEvent(TraceEvent.Act(TraceActKind.TAP, 540f, 470f), 2.0)
        val ripple = m.run(2.0, 2.1)
        assertEquals(TracePhase.ACT, ripple.phase)
        assertTrue(ripple.rippleStrength > 0f)
        assertEquals(540f, ripple.lensX, 3f)
        val settled = m.run(2.1, 2.9)
        assertEquals(0f, settled.rippleStrength)
        assertEquals(TracePhase.THINK, settled.phase)
    }

    @Test fun observationAfterAnActionIsItsVerification() {
        val m = machine()
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        m.run(0.0, 1.0)
        m.onEvent(TraceEvent.Act(TraceActKind.TAP, 300f, 300f), 1.0)
        m.run(1.0, 1.3)
        m.onEvent(TraceEvent.Observe("after-tap"), 1.3)
        assertEquals(TracePhase.VERIFY, m.phase)
        val frozenA = m.run(1.3, 1.7)
        val frozenB = m.run(1.7, 1.8)
        assertEquals(TracePhase.VERIFY, frozenB.phase)
        assertEquals("digits hold still while verifying", frozenA.glyphClock, frozenB.glyphClock, 0.0001f)
        assertEquals(TracePhase.THINK, m.run(1.8, 2.2).phase)
    }

    @Test fun scrollDrivesColumnFlowInTheSwipeDirection() {
        val m = machine()
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        m.run(0.0, 1.0)
        val before = m.frame(1.0).flow
        m.onEvent(TraceEvent.Act(TraceActKind.SCROLL, 540f, 1200f, 0f, -600f), 1.0)
        val after = m.run(1.0, 1.4).flow
        assertTrue("upward swipe moves glyphs up", after < before)
    }

    @Test fun gateFreezesAndStopsRedrawing() {
        val m = machine()
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        m.run(0.0, 1.0)
        m.onEvent(TraceEvent.Gate, 1.0)
        val a = m.run(1.0, 3.0)
        assertEquals(TracePhase.GATE, a.phase)
        assertTrue(a.visible)
        assertTrue(a.warmth > 0.99f)
        assertFalse("a still field costs no frames", a.animating)
        val b = m.run(3.0, 3.5)
        assertEquals(a.glyphClock, b.glyphClock, 0.0001f)
        m.onEvent(TraceEvent.Resume, 3.5)
        val resumed = m.run(3.5, 4.5)
        assertEquals(TracePhase.THINK, resumed.phase)
        assertTrue(resumed.warmth < 0.01f)
    }

    @Test fun handoffFadesOutQuicklyAndIgnoresAgentSignals() {
        val m = machine()
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        m.run(0.0, 1.0)
        m.onEvent(TraceEvent.Handoff, 1.0)
        val faded = m.run(1.0, 1.25)
        assertTrue(faded.intensity < 0.05f)
        // The lens leaves; the Reach scene stays so the user sees it is their turn.
        assertTrue(faded.visible)
        assertEquals(TraceScene.REACH.shaderIndex, faded.sceneTo)
        m.onEvent(TraceEvent.Act(TraceActKind.TAP, 1f, 1f), 1.25)
        assertEquals(TracePhase.HANDOFF, m.phase)
    }

    @Test fun doneBloomsTheSpiralThenRainsThenTurnsOff() {
        val m = machine()
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        m.run(0.0, 1.0)
        m.onEvent(TraceEvent.Done, 1.0)
        val bloom = m.run(1.0, 2.0)
        assertEquals(TracePhase.DONE, bloom.phase)
        assertEquals(-1f, bloom.rain)
        assertEquals(TraceScene.CYCLONE.shaderIndex, bloom.sceneTo)
        assertTrue(bloom.visible)
        val mid = m.run(2.0, 3.3)
        assertEquals(TracePhase.DONE, mid.phase)
        assertTrue(mid.rain in 0.3f..0.7f)
        val end = m.run(3.3, 4.5)
        assertEquals(TracePhase.OFF, end.phase)
        assertFalse(end.visible)
        assertFalse(end.animating)
    }

    @Test fun stopFadesWithoutAFinale() {
        val m = machine()
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        m.run(0.0, 1.0)
        m.onEvent(TraceEvent.Stop, 1.0)
        val frame = m.run(1.0, 2.0)
        assertEquals(TracePhase.OFF, frame.phase)
        assertEquals(-1f, frame.rain)
        assertFalse(frame.visible)
    }

    @Test fun offModeNeverShowsAnything() {
        val m = machine(TraceFieldMode.OFF)
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        m.onEvent(TraceEvent.Background(true), 0.0)
        assertFalse(m.run(0.0, 2.0).visible)
    }

    @Test fun edgeModeShowsOnlyThePerimeterFilament() {
        val m = machine(TraceFieldMode.EDGE)
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        val frame = m.run(0.0, 2.0)
        assertTrue(frame.visible)
        assertTrue(frame.edge > 0.9f)
        assertTrue(frame.intensity < 0.01f)
    }

    @Test fun backgroundWorkShowsTheEdgeFilamentOnly() {
        val m = machine()
        m.onEvent(TraceEvent.Background(true), 0.0)
        val on = m.run(0.0, 2.0)
        assertTrue(on.visible)
        assertTrue(on.edge > 0.9f)
        assertTrue(on.intensity < 0.01f)
        m.onEvent(TraceEvent.Background(false), 2.0)
        assertFalse(m.run(2.0, 5.0).visible)
    }

    @Test fun reduceMotionShowsAStillLensWithoutEffects() {
        val m = machine(reduceMotion = true)
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        m.run(0.0, 1.0)
        m.onEvent(TraceEvent.Act(TraceActKind.TAP, 300f, 300f), 1.0)
        val a = m.run(1.0, 1.1)
        assertEquals(0f, a.rippleStrength)
        assertEquals(0f, a.scanStrength)
        assertEquals(300f, a.lensX, 0.01f)
        assertFalse(a.animating)
        assertEquals(0f, a.glyphClock, 0.0001f)
        m.onEvent(TraceEvent.Done, 1.1)
        assertEquals(-1f, m.run(1.1, 1.5).rain)
    }

    @Test fun modeParsingDefaultsToField() {
        assertEquals(TraceFieldMode.FIELD, TraceFieldMode.parse(null))
        assertEquals(TraceFieldMode.EDGE, TraceFieldMode.parse(" Edge "))
        assertEquals(TraceFieldMode.OFF, TraceFieldMode.parse("off"))
        assertEquals(TraceFieldMode.FIELD, TraceFieldMode.parse("matrix"))
    }
}

class TraceFieldFocusTest {
    private val w = 1080f
    private val h = 2400f

    private fun TraceFieldChoreographer.run(from: Double, to: Double): TraceFrame {
        var t = from
        var last = frame(t)
        while (t < to) { t += 1.0 / 120.0; last = frame(t) }
        return last
    }

    @Test fun thinkingIsDiffuseAndFocusSharpensOnTarget() {
        val m = TraceFieldChoreographer(w, h, 2.625f)
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        val thinking = m.run(0.0, 2.0)
        assertTrue("diffuse while thinking", thinking.focus < 0.2f)
        m.onEvent(TraceEvent.Target(100f, 400f, 980f, 540f, "n"), 2.0)
        val focused = m.run(2.0, 2.6)
        assertTrue("focus arrives quickly", focused.focus > 0.95f)
        val relaxing = m.run(2.6, 5.4) // TARGET holds 3 s, then THINK
        assertEquals(TracePhase.THINK, relaxing.phase)
        val relaxed = m.run(5.4, 8.0)
        assertTrue("focus lets go back into the aurora", relaxed.focus < 0.2f)
    }

    @Test fun thinkingWandersWideInsteadOfSittingStill() {
        val m = TraceFieldChoreographer(w, h, 2.625f)
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var t = 0.0
        while (t < 40.0) {
            t += 1.0 / 30.0
            val f = m.frame(t)
            if (t > 5.0) { minX = minOf(minX, f.lensX); maxX = maxOf(maxX, f.lensX) }
        }
        assertTrue("glow travels across the screen (${maxX - minX}px)", maxX - minX > w * 0.3f)
    }

    @Test fun ambientFlowRunsWhileWorkingAndFreezesAtGate() {
        val m = TraceFieldChoreographer(w, h, 2.625f)
        m.onEvent(TraceEvent.Wake(w / 2, h), 0.0)
        val a = m.run(0.0, 2.0)
        assertTrue(a.flowTime > 1.9f)
        m.onEvent(TraceEvent.Gate, 2.0)
        val b = m.run(2.0, 3.0)
        val c = m.run(3.0, 4.0)
        assertEquals(b.flowTime, c.flowTime, 0.0001f)
    }
}
