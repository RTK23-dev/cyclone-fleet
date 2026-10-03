package com.cyclone.mobile.applearner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowMeGestureIntelligenceV292Test {
    @Test
    fun leftSwipeMovesFromRightToLeft() {
        val p = FollowMeGestureIntelligenceV292.swipeParamsForTest("left", 1000, 2000)
        assertTrue(p.getInt("x1") > p.getInt("x2"))
        assertEquals(1000, p.getInt("y1"))
        assertEquals(1000, p.getInt("y2"))
    }

    @Test
    fun rightSwipeMovesFromLeftToRight() {
        val p = FollowMeGestureIntelligenceV292.swipeParamsForTest("right", 1000, 2000)
        assertTrue(p.getInt("x1") < p.getInt("x2"))
    }

    @Test
    fun verticalSwipeUsesScreenCenter() {
        val up = FollowMeGestureIntelligenceV292.swipeParamsForTest("up", 1000, 2000)
        val down = FollowMeGestureIntelligenceV292.swipeParamsForTest("down", 1000, 2000)
        assertEquals(500, up.getInt("x1"))
        assertEquals(500, up.getInt("x2"))
        assertTrue(up.getInt("y1") > up.getInt("y2"))
        assertTrue(down.getInt("y1") < down.getInt("y2"))
    }
}
