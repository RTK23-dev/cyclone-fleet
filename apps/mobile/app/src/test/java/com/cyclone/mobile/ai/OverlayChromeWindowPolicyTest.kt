package com.cyclone.mobile.ai

import android.view.Gravity
import android.view.WindowManager
import com.cyclone.mobile.ui.overlay.OverlayChromeContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayChromeWindowPolicyTest {
    @Test fun backgroundGlassIsTouchableButDoesNotTakeAppFocus() {
        val glass = OverlayChromeWindowPolicy.glass()
        assertTrue(glass.matchParentWidth)
        assertEquals(null, glass.heightDp)
        assertTrue(glass.notFocusable)
        assertTrue(glass.notTouchModal)
        assertFalse(glass.notTouchable)
    }

    @Test
    fun compactGravityIsBottomCenterAndWindowIsNarrow() {
        val compact = OverlayChromeWindowPolicy.main(compact = true)
        assertTrue(compact.bottomCenter)
        assertFalse(compact.matchParentWidth)
        assertEquals(OverlayChromeContract.IDLE_TOUCH_SIZE_DP, compact.widthDp)
        assertEquals(OverlayChromeContract.IDLE_TOUCH_SIZE_DP, compact.heightDp)
        assertEquals(
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            OverlayChromeWindowPolicy.gravity(compact),
        )
    }

    @Test
    fun compactWindowRemainsNotFocusableAndNotTouchModal() {
        val compact = OverlayChromeWindowPolicy.main(compact = true)
        val flags = OverlayChromeWindowPolicy.flags(compact)
        assertTrue(compact.notFocusable)
        assertTrue(compact.notTouchModal)
        assertFalse(compact.notTouchable)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
    }

    @Test
    fun visualHaloIsLargeButNeverTouchable() {
        val halo = OverlayChromeWindowPolicy.halo
        val flags = OverlayChromeWindowPolicy.flags(halo)
        assertFalse(halo.matchParentWidth)
        assertEquals(144, halo.widthDp)
        assertEquals(72, halo.heightDp)
        assertTrue(halo.notTouchable)
        assertTrue(halo.notFocusable)
        assertTrue(halo.notTouchModal)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertEquals(
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            OverlayChromeWindowPolicy.gravity(halo),
        )
    }

    @Test
    fun haloAndHotspotShareTheSameVisualCenterAboveGestureNavigation() {
        val compact = OverlayChromeWindowPolicy.main(compact = true)
        val halo = OverlayChromeWindowPolicy.halo
        val compactCenterFromBottom = compact.bottomMarginDp + requireNotNull(compact.heightDp) / 2
        val haloCenterFromBottom = halo.bottomMarginDp + requireNotNull(halo.heightDp) / 2
        assertEquals(52, compactCenterFromBottom)
        assertEquals(compactCenterFromBottom, haloCenterFromBottom)
        assertTrue(compact.bottomMarginDp >= 24)
    }

    @Test
    fun idleOverlayDoesNotCreateFullWidthBlockingSurface() {
        val compact = OverlayChromeWindowPolicy.main(compact = true)
        val halo = OverlayChromeWindowPolicy.halo
        assertFalse(compact.matchParentWidth)
        assertEquals(48, compact.widthDp)
        assertTrue("large visual window must be non-touchable", halo.notTouchable)
    }

    @Test
    fun expandedPanelRemainsTouchableFocusableAndFullWidth() {
        val expanded = OverlayChromeWindowPolicy.main(compact = false)
        val flags = OverlayChromeWindowPolicy.flags(expanded)
        assertTrue(expanded.matchParentWidth)
        assertFalse(expanded.notTouchable)
        assertFalse(expanded.notFocusable)
        assertTrue(expanded.notTouchModal)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0)
        assertTrue("ordinary Ask chrome must be visible in USB mirroring", flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
        assertTrue("Secrets Card remains capture-protected", OverlayChromeWindowPolicy.flags(expanded, secretVisible = true) and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test
    fun hostGesturePassthroughMakesExpandedPanelIgnoreTouchesWithoutChangingRestingPolicy() {
        val expanded = OverlayChromeWindowPolicy.main(compact = false)
        val resting = OverlayChromeWindowPolicy.flags(expanded)
        val passing = OverlayChromeWindowPolicy.withHostGesturePassthrough(resting)
        assertTrue(resting and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
        assertTrue(passing and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(passing and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(passing and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
    }

    @Test
    fun minimizedComposerIsContentHeightFocusableAndReadyForImmediateTyping() {
        val minimized = OverlayChromeWindowPolicy.minimizedComposer()
        val flags = OverlayChromeWindowPolicy.flags(minimized)
        assertTrue(minimized.bottomCenter)
        assertTrue(minimized.matchParentWidth)
        assertEquals(null, minimized.heightDp)
        assertFalse(minimized.notFocusable)
        assertTrue(minimized.notTouchModal)
        assertFalse(minimized.notTouchable)
        assertEquals(0, minimized.bottomMarginDp)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
    }
}
