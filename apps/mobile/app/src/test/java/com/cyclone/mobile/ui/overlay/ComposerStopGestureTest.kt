package com.cyclone.mobile.ui.overlay

import org.junit.Assert.*
import org.junit.Test

class ComposerStopGestureTest {
    @Test fun holdStopsAtExactlyTwoSecondsAndOnlyOnce() {
        val g = ComposerStopGesture(); g.press(100)
        assertFalse(g.hold(2099)); assertEquals(.5f, g.progress(1100), .0001f)
        assertTrue(g.hold(2100)); assertFalse(g.hold(2101))
        assertEquals(ComposerStopGesture.Release.NONE, g.release(2102))
    }
    @Test fun tapShowsHalfRingAndRetractsOverThreeSeconds() {
        val g = ComposerStopGesture(); g.press(0)
        assertEquals(ComposerStopGesture.Release.FIRST_TAP, g.release(50))
        assertEquals(.5f, g.progress(50), .0001f)
        assertEquals(.25f, g.progress(1550), .0001f)
        assertEquals(0f, g.progress(3050), .0001f)
        assertFalse(g.armed(3050))
    }
    @Test fun secondTapStopsDuringRetraction() {
        val g = ComposerStopGesture(); g.press(0); g.release(50)
        g.press(1000)
        assertEquals(ComposerStopGesture.Release.STOP, g.release(1050))
    }
    @Test fun expiredTapDoesNotStopAndCancelledPressDoesNotArm() {
        val g = ComposerStopGesture(); g.press(0); g.cancel()
        assertFalse(g.armed(10)); assertEquals(0f, g.progress(10), .0001f)
        g.press(20); g.release(30); g.press(3100)
        assertEquals(ComposerStopGesture.Release.FIRST_TAP, g.release(3120))
    }
    @Test fun newTaskCannotInheritStopConfirmation() {
        val g = ComposerStopGesture(); g.press(0); g.release(10); g.reset()
        assertFalse(g.armed(20)); assertEquals(0f, g.progress(20), .0001f)
    }
}
