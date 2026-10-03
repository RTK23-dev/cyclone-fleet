package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayIdleActivationTrackerTest {
    @Test
    fun singleTapDoesNotActivate() {
        val tracker = OverlayIdleActivationTracker()
        val result = tracker.onTap(1_000)
        assertFalse(result.activate)
        assertEquals(1, result.tapCount)
        assertEquals(1, result.pulseSerial)
        assertEquals(1, result.pulseLevel)
    }

    @Test
    fun doubleTapDoesNotActivate() {
        val tracker = OverlayIdleActivationTracker()
        tracker.onTap(1_000)
        val result = tracker.onTap(1_600)
        assertFalse(result.activate)
        assertEquals(2, result.tapCount)
        assertEquals(2, result.pulseSerial)
        assertEquals(2, result.pulseLevel)
    }

    @Test
    fun thirdValidTapActivatesExactlyOnce() {
        val tracker = OverlayIdleActivationTracker()
        assertFalse(tracker.onTap(1_000).activate)
        assertFalse(tracker.onTap(1_550).activate)
        val third = tracker.onTap(2_100)
        assertTrue(third.activate)
        assertEquals(3, third.pulseSerial)
        assertEquals(3, third.pulseLevel)

        val ignored = tracker.onTap(2_250)
        assertFalse(ignored.activate)
        assertTrue(ignored.ignored)
        assertEquals(3, ignored.pulseSerial)
    }

    @Test
    fun tripleTapTimeoutResetsSequence() {
        val tracker = OverlayIdleActivationTracker()
        tracker.onTap(1_000)
        tracker.onTap(1_500)
        val expiredGap = tracker.onTap(2_250)
        assertFalse(expiredGap.activate)
        assertEquals(1, expiredGap.tapCount)

        tracker.reset()
        tracker.onTap(10_000)
        tracker.onTap(10_700)
        val expiredTotal = tracker.onTap(11_401)
        assertFalse(expiredTotal.activate)
        assertEquals(1, expiredTotal.tapCount)
    }

    @Test
    fun tapAnimationSerialAndLevelAdvancePerRecognizedTap() {
        val tracker = OverlayIdleActivationTracker()
        val one = tracker.onTap(100)
        val two = tracker.onTap(500)
        val three = tracker.onTap(900)
        assertEquals(listOf(1, 2, 3), listOf(one.pulseSerial, two.pulseSerial, three.pulseSerial))
        assertEquals(listOf(1, 2, 3), listOf(one.pulseLevel, two.pulseLevel, three.pulseLevel))
    }

    @Test
    fun activationTransitionCannotDoubleFire() {
        val tracker = OverlayIdleActivationTracker()
        tracker.onTap(0)
        tracker.onTap(600)
        assertTrue(tracker.onTap(1_200).activate)
        repeat(4) { index ->
            val result = tracker.onTap(1_250L + index * 50L)
            assertTrue(result.ignored)
            assertFalse(result.activate)
        }
    }

    @Test
    fun longHoldHasNoNormalTouchActivationShortcut() {
        val tracker = OverlayIdleActivationTracker()
        // Normal touch code reports at most the release as one tap; there is no hold callback.
        val releaseAfterThreeSeconds = tracker.onTap(3_000)
        assertFalse(releaseAfterThreeSeconds.activate)
        assertEquals(1, releaseAfterThreeSeconds.tapCount)
    }

    @Test
    fun accessibilitySemanticActionCanOpenDirectly() {
        val tracker = OverlayIdleActivationTracker()
        val result = tracker.semanticActivate()
        assertTrue(result.activate)
        assertEquals(3, result.pulseLevel)
        assertFalse(result.ignored)
        assertTrue(tracker.semanticActivate().ignored)
    }

    @Test
    fun compactVisualContractUsesLargeHaloAndSmallHotspot() {
        assertEquals(144, OverlayChromeContract.IDLE_VISUAL_WIDTH_DP)
        assertEquals(72, OverlayChromeContract.IDLE_VISUAL_HEIGHT_DP)
        assertEquals(48, OverlayChromeContract.IDLE_TOUCH_SIZE_DP)
        assertTrue(OverlayChromeContract.IDLE_VISUAL_WIDTH_DP > OverlayChromeContract.IDLE_TOUCH_SIZE_DP)
        assertEquals(700L, OverlayChromeContract.IDLE_TAP_MAX_GAP_MS)
        assertEquals(1_400L, OverlayChromeContract.IDLE_TAP_MAX_SEQUENCE_MS)
    }

    @Test
    fun expandedAuroraIsDeliberatelyDarkerThan389MidLayer() {
        assertTrue(OverlayChromeContract.EXPANDED_AURORA_BASE_ALPHA in 0.55f..0.75f)
        assertTrue(OverlayChromeContract.EXPANDED_AURORA_BASE_ALPHA > 0.34f)
    }
}
