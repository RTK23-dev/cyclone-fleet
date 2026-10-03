package com.cyclone.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbKeeperTest {
    private val healthy = OrbKeeper.Look(enabled = true, serviceCurrent = true, windowsPresent = true, buttonAttached = true,
        buttonVisible = true, panelWanted = false, panelAttached = false, buttonOnScreen = true)

    @Test fun `a healthy orb is left alone and nothing happens with Driver mode off`() {
        assertEquals(OrbKeeper.Action.NONE, OrbKeeper.decide(healthy))
        assertEquals(OrbKeeper.Action.NONE, OrbKeeper.decide(healthy.copy(enabled = false, windowsPresent = false)))
    }

    @Test fun `every way the orb goes missing has a repair`() {
        assertEquals(OrbKeeper.Action.REATTACH, OrbKeeper.decide(healthy.copy(serviceCurrent = false)))
        assertEquals(OrbKeeper.Action.REBUILD, OrbKeeper.decide(healthy.copy(windowsPresent = false)))
        assertEquals(OrbKeeper.Action.REBUILD, OrbKeeper.decide(healthy.copy(buttonAttached = false)))
        assertEquals(OrbKeeper.Action.SHOW_BUTTON, OrbKeeper.decide(healthy.copy(buttonVisible = false)))
        assertEquals(OrbKeeper.Action.PLACE, OrbKeeper.decide(healthy.copy(buttonOnScreen = false)))
    }

    @Test fun `while AI mode is open the orb lives in the panel`() {
        val open = healthy.copy(panelWanted = true, buttonVisible = false)
        assertEquals(OrbKeeper.Action.NONE, OrbKeeper.decide(open.copy(panelAttached = true)))
        assertEquals(OrbKeeper.Action.REBUILD, OrbKeeper.decide(open.copy(panelAttached = false)))
    }

    @Test fun `failing repairs back off but showing and placing never wait`() {
        assertTrue(OrbKeeper.allowed(OrbKeeper.Action.REBUILD, failures = 0, lastRepairMs = 0, nowMs = 1))
        assertFalse(OrbKeeper.allowed(OrbKeeper.Action.REBUILD, failures = 2, lastRepairMs = 1_000, nowMs = 4_000))
        assertTrue(OrbKeeper.allowed(OrbKeeper.Action.REBUILD, failures = 2, lastRepairMs = 1_000, nowMs = 5_000))
        assertEquals(30_000L, OrbKeeper.backoffMs(12))
        assertTrue(OrbKeeper.allowed(OrbKeeper.Action.SHOW_BUTTON, failures = 9, lastRepairMs = 0, nowMs = 1))
    }

    @Test fun `a button pushed off the screen is noticed`() {
        assertTrue(OrbKeeper.onScreen(900, 1800, 200, 1080, 2400))
        assertFalse(OrbKeeper.onScreen(2000, 1800, 200, 1080, 2400))   // portrait spot after turning to landscape and back
        assertFalse(OrbKeeper.onScreen(100, -190, 200, 1080, 2400))
    }
}
