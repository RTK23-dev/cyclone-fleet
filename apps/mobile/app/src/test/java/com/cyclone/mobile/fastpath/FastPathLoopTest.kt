package com.cyclone.mobile.fastpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPathLoopTest {
    @Test
    fun settleDetectsChangeAfterInitial300msAndDoesNotClimbTheLadder() {
        val sleeps = mutableListOf<Long>()
        val fingerprints = ArrayDeque(listOf("after"))
        var now = 1_000L
        val result = FastPathLoop.settle(
            beforeFingerprint = "before",
            sleepMs = { sleeps += it },
            observeFingerprint = { fingerprints.removeFirst() },
            nowMs = { now.also { now += 10 } },
        )
        assertEquals(listOf(300L), sleeps)
        assertEquals(1, result.observations)
        assertTrue(result.verified)
        assertEquals(true, result.changed)
        assertNull(result.warning)
        assertEquals("after", result.afterFingerprint)
        assertTrue(result.toJson().optBoolean("doubleClickSuppressed"))
    }

    @Test
    fun unchangedClimbs500Then1000AndReturnsWarningWithoutAuthorizingASecondClick() {
        val sleeps = mutableListOf<Long>()
        val result = FastPathLoop.settle(
            beforeFingerprint = "same",
            sleepMs = { sleeps += it },
            observeFingerprint = { "same" },
            nowMs = { 0L },
        )
        assertEquals(listOf(300L, 500L, 1_000L), sleeps)
        assertEquals(3, result.observations)
        assertFalse(result.verified)
        assertEquals(false, result.changed)
        assertEquals(FastPathTimings.UNCHANGED_WARNING, result.warning)
        assertFalse(FastPathLoop.allowSecondClickChannel(actionPerformed = true, settle = result))
        assertTrue(result.warning!!.contains("Do not retry via a second click channel"))
    }

    @Test
    fun ladderStopsAtFirstChangedFingerprintAndStillForbidsDoubleClick() {
        val sleeps = mutableListOf<Long>()
        val fingerprints = ArrayDeque(listOf("same", "next"))
        val result = FastPathLoop.settle(
            beforeFingerprint = "same",
            sleepMs = { sleeps += it },
            observeFingerprint = { fingerprints.removeFirst() },
            nowMs = { 0L },
        )
        assertEquals(listOf(300L, 500L), sleeps)
        assertEquals(2, result.observations)
        assertTrue(result.verified)
        assertEquals("next", result.afterFingerprint)
        assertFalse(FastPathLoop.allowSecondClickChannel(true, result))
    }

    @Test
    fun missingBeforeFingerprintCannotClaimVerifiedChange() {
        val result = FastPathLoop.settle(
            beforeFingerprint = null,
            sleepMs = {},
            observeFingerprint = { "fp" },
            nowMs = { 0L },
        )
        assertFalse(result.verified)
        assertNull(result.changed)
        assertEquals(FastPathTimings.UNCHANGED_WARNING, result.warning)
        assertFalse(FastPathLoop.fingerprintChanged(null, "fp"))
        assertFalse(FastPathLoop.fingerprintChanged("a", ""))
        assertTrue(FastPathLoop.fingerprintChanged("a", "b"))
    }

    @Test
    fun completedGestureKeepsTheOrdinary300msSettleAndDoesNotAddStrokeDurationAgain() {
        assertEquals(300L, FastPathTimings.SETTLE_MS)
        assertEquals(300L, FastPathTimings.settleAfterCompletedGestureMs())
        assertEquals(1_580L, FastPathTimings.gestureAwaitBudgetMs(80L))
        assertEquals(1_850L, FastPathTimings.gestureAwaitBudgetMs(350L))
        val sleeps = mutableListOf<Long>()
        FastPathLoop.settle(
            beforeFingerprint = "before",
            sleepMs = { sleeps += it },
            observeFingerprint = { "after" },
            nowMs = { 0L },
            initialSettleMs = FastPathTimings.settleAfterCompletedGestureMs(),
        )
        assertEquals(listOf(300L), sleeps)
        assertFalse(FastPathLoop.allowSecondClickChannel(true, FastPathSettleResult(
            changed = true, verified = true, observations = 1, elapsedMs = 300L, warning = null, afterFingerprint = "after",
        )))
    }
}
