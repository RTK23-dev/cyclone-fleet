package com.cyclone.mobile.ui.v32

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneChatDrawerGesturePolicyTest {
    @Test
    fun deliberateDownwardDragCollapsesButShortMovementSettlesOpen() {
        assertFalse(CycloneChatDrawerGesturePolicy.shouldCollapse(0f))
        assertFalse(CycloneChatDrawerGesturePolicy.shouldCollapse(40f))
        assertTrue(CycloneChatDrawerGesturePolicy.shouldCollapse(72f))
        assertTrue(CycloneChatDrawerGesturePolicy.shouldCollapse(120f))
    }

    @Test
    fun upwardDragReopensCollapsedAskCyclonePill() {
        assertFalse(CycloneChatDrawerGesturePolicy.shouldExpand(0f))
        assertFalse(CycloneChatDrawerGesturePolicy.shouldExpand(-20f))
        assertTrue(CycloneChatDrawerGesturePolicy.shouldExpand(-34f))
        assertTrue(CycloneChatDrawerGesturePolicy.shouldExpand(-80f))
    }
}
