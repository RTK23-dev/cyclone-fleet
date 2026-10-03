package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safe Pixel fixtures for overlay / task-entry acceptance.
 * No private phone data. Do not pay, send, call, delete, or grant on device.
 */
object OverlayPhysicalFixtures {
    const val REDACTED = "[REDACTED]"

    const val MESSAGES_PACKAGE = "com.google.android.apps.messaging"
    const val MESSAGES_GOAL = "Open Messages compose and type overlay fixture text. Do not send."
    const val MESSAGES_TYPED = "Cyclone overlay fixture"

    const val PHONE_PACKAGE = "com.google.android.dialer"
    const val PHONE_GOAL = "Open Phone keypad and type the fixture digits. Do not place a call."
    const val PHONE_KEYPAD_DIGITS = "5550100"

    const val CHROME_PACKAGE = "com.android.chrome"
    const val CHROME_GOAL = "Open Chrome and search a public query. Stop on search results. Do not tap ads or checkout."
    const val CHROME_QUERY = "Pixel 8 user guide site:support.google.com"

    const val CART_GOAL =
        "Reach a test cart or pay confirmation only if no charge, no saved card submission, and no order is placed."
    const val T6_UNVERIFIED_REASON =
        "No charge-free cart/pay confirmation is available. Leave T6 UNVERIFIED rather than completing a transaction."
}

class OverlayPhysicalFixturesTest {
    @Test
    fun fixturesNeverContainPrivatePhoneData() {
        val joined = listOf(
            OverlayPhysicalFixtures.MESSAGES_GOAL,
            OverlayPhysicalFixtures.MESSAGES_TYPED,
            OverlayPhysicalFixtures.PHONE_GOAL,
            OverlayPhysicalFixtures.PHONE_KEYPAD_DIGITS,
            OverlayPhysicalFixtures.CHROME_GOAL,
            OverlayPhysicalFixtures.CHROME_QUERY,
            OverlayPhysicalFixtures.CART_GOAL,
            OverlayPhysicalFixtures.T6_UNVERIFIED_REASON,
        ).joinToString("\n")
        assertFalse(joined.contains("@"))
        assertFalse(joined.contains("password", ignoreCase = true))
        assertFalse(joined.contains("token", ignoreCase = true))
        assertFalse(joined.contains("iban", ignoreCase = true))
        assertEquals("5550100", OverlayPhysicalFixtures.PHONE_KEYPAD_DIGITS)
        assertTrue(OverlayPhysicalFixtures.PHONE_KEYPAD_DIGITS.startsWith("555"))
        assertEquals("[REDACTED]", OverlayPhysicalFixtures.REDACTED)
    }

    @Test
    fun fixtureGoalsAssumeTaskEntryThenOverlayStates() {
        val machine = OverlayChromeMachine()
        machine.startAnalysis("t2-messages", bullets = listOf(OverlayPhysicalFixtures.MESSAGES_GOAL))
        assertEquals(OverlayChromeState.ANALYSIS, machine.state())
        assertEquals(OverlayCopy.AI_MODE, OverlayCopy.visibleFor(machine.snapshot()).first())
        machine.dispatch(OverlayUserAction.CONFIRM)
        assertEquals(OverlayChromeState.WORKING, machine.state())
        machine.dispatch(OverlayUserAction.VIEW_PROGRESS)
        assertEquals(OverlayChromeState.LIVE, machine.state())
        machine.enterGate(OverlayGateClass.SEND)
        assertEquals(OverlayChromeState.GATE, machine.state())
        assertTrue(
            OverlayCopy.visibleFor(machine.snapshot())
                .contains("Cyclone needs you to confirm before finishing this."),
        )
    }

    @Test
    fun cartFixtureStaysUnverifiedWhenUnsafe() {
        assertTrue(OverlayPhysicalFixtures.T6_UNVERIFIED_REASON.contains("UNVERIFIED"))
        assertTrue(OverlayPhysicalFixtures.CART_GOAL.contains("no order is placed"))
        assertFalse(OverlayPhysicalFixtures.CART_GOAL.contains("Pay now", ignoreCase = true))
    }
}
