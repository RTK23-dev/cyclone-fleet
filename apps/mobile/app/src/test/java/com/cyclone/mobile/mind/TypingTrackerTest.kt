package com.cyclone.mobile.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingTrackerTest {
    @Test fun twoIdenticalRefusalsHintAndFourFailuresHandOff() {
        val tracker = TypingTracker()
        assertEquals(TypingTracker.Advice.None, tracker.failure("e8", "Not done: ambiguous"))
        assertTrue(tracker.failure("e8", "Not done: ambiguous") is TypingTracker.Advice.Hint)
        assertEquals(TypingTracker.Advice.None, tracker.failure("focused", "No text box has focus"))
        assertEquals(TypingTracker.Advice.Handoff, tracker.failure("focused", "No text box has focus"))
    }

    @Test fun aSuccessStartsOver() {
        val tracker = TypingTracker()
        repeat(3) { tracker.failure("e8", "r$it") }
        tracker.success()
        assertEquals(0, tracker.failedInARow)
        assertEquals(TypingTracker.Advice.None, tracker.failure("e8", "r"))
    }
}
