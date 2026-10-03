package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OverlayGesturePassthroughTest {
    @Before
    fun reset() {
        OverlayGesturePassthrough.resetForTests()
    }

    @Test
    fun hostPassthroughIsOffUntilAStrokeRuns() {
        assertFalse(OverlayGesturePassthrough.active())
    }

    @Test
    fun acquireAppliesTrueAndReleaseAppliesFalse() {
        val events = mutableListOf<Boolean>()
        OverlayGesturePassthrough.bind { events += it }
        val result = OverlayGesturePassthrough.withHostPassthrough {
            assertTrue(OverlayGesturePassthrough.active())
            7
        }
        assertEquals(7, result)
        assertFalse(OverlayGesturePassthrough.active())
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun nestedStrokesDoNotRestoreUntilTheOuterStrokeFinishes() {
        val events = mutableListOf<Boolean>()
        OverlayGesturePassthrough.bind { events += it }
        OverlayGesturePassthrough.withHostPassthrough {
            OverlayGesturePassthrough.withHostPassthrough {
                assertTrue(OverlayGesturePassthrough.active())
            }
            assertTrue(OverlayGesturePassthrough.active())
        }
        assertFalse(OverlayGesturePassthrough.active())
        assertEquals(listOf(true, false), events)
    }

    @Test
    fun exceptionStillReleasesPassthrough() {
        OverlayGesturePassthrough.bind { }
        try {
            OverlayGesturePassthrough.withHostPassthrough<Unit> {
                error("stroke failed")
            }
        } catch (_: IllegalStateException) {
        }
        assertFalse(OverlayGesturePassthrough.active())
    }

    @Test
    fun aTouchDuringOrJustAfterCyclonesOwnGestureIsNeverTheOwners() {
        assertFalse("nothing injected yet", OverlayGesturePassthrough.injectedRecently())
        OverlayGesturePassthrough.bind { }
        OverlayGesturePassthrough.withHostPassthrough {
            assertTrue(OverlayGesturePassthrough.injectedRecently())
        }
        val now = System.nanoTime() / 1_000_000
        assertTrue("the echo of the gesture", OverlayGesturePassthrough.injectedRecently(nowMs = now))
        assertFalse("the owner, a moment later", OverlayGesturePassthrough.injectedRecently(nowMs = now + OverlayGesturePassthrough.INJECTED_ECHO_MS + 50))
    }
}
