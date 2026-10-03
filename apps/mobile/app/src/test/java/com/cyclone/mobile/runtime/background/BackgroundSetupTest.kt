package com.cyclone.mobile.runtime.background
import org.junit.Assert.*
import org.junit.Test
class BackgroundSetupTest {
    private val ready = BackgroundReadiness(true, true, true, true, true, true, true)
    @Test fun everyRequiredRowBlocksWithItsOwnMessage() {
        assertTrue(ready.ready)
        for ((state, label) in listOf(
            ready.copy(android = false) to "Android version",
            ready.copy(installed = false) to "Shizuku",
            ready.copy(running = false) to "Shizuku",
            ready.copy(authorized = false) to "Shizuku access",
            ready.copy(accessibility = false) to "Accessibility",
            ready.copy(notifications = false) to "Task notifications",
            ready.copy(humanScreen = false) to "Main screen")) {
            assertFalse(state.ready)
            assertTrue(state.failure!!.startsWith(label))
        }
    }
    @Test fun configuredPhoneStillRequiresActualHumanDisplayCheck() {
        assertNull(ready.copy(humanScreen = false).setupFailure)
        assertFalse(ready.copy(humanScreen = false).ready)
    }
}
