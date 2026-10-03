package com.cyclone.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayLockScreenPolicyTest {
    @Test fun onlyAnAwakeUnlockedPhoneCanShowChrome() {
        for (screenOff in listOf(false, true)) {
            for (locked in listOf(false, true)) {
                for (interactive in listOf(false, true)) {
                    assertEquals(!screenOff && !locked && interactive,
                        !OverlayLockScreenPolicy.blocked(screenOff, locked, interactive))
                }
            }
        }
    }
}
