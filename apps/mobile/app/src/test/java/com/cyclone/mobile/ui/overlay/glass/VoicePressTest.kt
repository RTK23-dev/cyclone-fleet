package com.cyclone.mobile.ui.overlay.glass

import com.cyclone.mobile.ui.overlay.glass.VoicePress.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePressTest {
    @Test fun aPressStartsVoiceAsTheFingerGoesDown() {
        assertEquals(Action.START, VoicePress.down(now = 10_000, listening = false, listeningSince = 0, lastPressAt = 0))
    }

    @Test fun aQuickSecondTouchRightAfterStartingNeverStopsIt() {
        // A bounce or nervous double tap lands within the debounce.
        assertEquals(Action.NONE, VoicePress.down(now = 10_250, listening = true, listeningSince = 10_000, lastPressAt = 10_000))
        // Past the debounce but still in the first moments of listening.
        assertEquals(Action.NONE, VoicePress.down(now = 10_600, listening = true, listeningSince = 10_000, lastPressAt = 10_000))
    }

    @Test fun aTapAfterListeningForAWhileStopsIt() {
        assertEquals(Action.STOP, VoicePress.down(now = 13_000, listening = true, listeningSince = 10_000, lastPressAt = 10_000))
    }

    @Test fun aTapStartIsTapToTalkAndAHeldStartIsPushToTalk() {
        assertEquals(Action.NONE, VoicePress.upAfterStart(heldMs = 180))
        assertEquals(Action.STOP, VoicePress.upAfterStart(heldMs = VoicePress.HOLD_TO_TALK_MS))
        assertEquals(Action.STOP, VoicePress.upAfterStart(heldMs = 3_000))
    }

    @Test fun startingAgainRightAfterAStopIsDebounced() {
        assertEquals(Action.NONE, VoicePress.down(now = 13_200, listening = false, listeningSince = 0, lastPressAt = 13_000))
        assertEquals(Action.START, VoicePress.down(now = 13_500, listening = false, listeningSince = 0, lastPressAt = 13_000))
    }
}
