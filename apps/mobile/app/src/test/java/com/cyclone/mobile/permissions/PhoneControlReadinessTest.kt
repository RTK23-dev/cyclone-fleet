package com.cyclone.mobile.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneControlReadinessTest {
    @Test
    fun settingAndBoundIsReady() {
        val snapshot = PhoneControlSnapshot(settingEnabled = true, serviceBound = true)
        assertEquals(PhoneControlState.READY, snapshot.state)
        assertEquals(PhoneControlState.READY, PhoneControlReadiness.resolve(true, true))
        assertTrue(snapshot.ready)
        assertFalse(snapshot.needsRepair)
        assertEquals("Manage", snapshot.actionLabel)
        assertEquals("Ready", snapshot.statusLabel)
        assertEquals("Phone control matches Android Accessibility.", snapshot.detail)
    }

    @Test
    fun settingOnUnboundIsRepairAfterForceStop() {
        val snapshot = PhoneControlSnapshot(settingEnabled = true, serviceBound = false)
        assertEquals(PhoneControlState.REPAIR, snapshot.state)
        assertEquals(PhoneControlState.REPAIR, PhoneControlReadiness.resolve(true, false))
        assertFalse(snapshot.ready)
        assertTrue(snapshot.needsRepair)
        assertEquals("Repair", snapshot.actionLabel)
        assertEquals("Needs repair", snapshot.statusLabel)
        assertEquals(
            "Android still lists Cyclone, but phone control is not running. Re-enable Cyclone in Accessibility after force-stop.",
            snapshot.detail,
        )
    }

    @Test
    fun settingOffBoundIsEnableNotReadyFromStaleBoundFlag() {
        val snapshot = PhoneControlSnapshot(settingEnabled = false, serviceBound = true)
        assertEquals(PhoneControlState.ENABLE, snapshot.state)
        assertEquals(PhoneControlState.ENABLE, PhoneControlReadiness.resolve(false, true))
        assertFalse(snapshot.ready)
        assertFalse(snapshot.needsRepair)
        assertEquals("Enable", snapshot.actionLabel)
        assertEquals("Accessibility off", snapshot.statusLabel)
        assertEquals("Turn on Cyclone in Android Accessibility, then return.", snapshot.detail)
    }

    @Test
    fun settingOffUnboundIsEnable() {
        val snapshot = PhoneControlSnapshot(settingEnabled = false, serviceBound = false)
        assertEquals(PhoneControlState.ENABLE, snapshot.state)
        assertEquals(PhoneControlState.ENABLE, PhoneControlReadiness.resolve(false, false))
        assertFalse(snapshot.ready)
        assertFalse(snapshot.needsRepair)
        assertEquals("Enable", snapshot.actionLabel)
        assertEquals("Accessibility off", snapshot.statusLabel)
        assertEquals("Turn on Cyclone in Android Accessibility, then return.", snapshot.detail)
    }
}
