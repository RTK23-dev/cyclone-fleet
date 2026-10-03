package com.cyclone.mobile.mapping.crawl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapperDoorRiskTest {
    private fun risk(vararg labels: String, role: String = "button", checkable: Boolean = false, containsCheckable: Boolean = false,
                     identity: MappingIdentity? = MappingIdentity.OWN) =
        MapperDoorRisk.classify(MapperDoorRisk.Facts(labels.toList(), role, checkable, containsCheckable), identity)

    @Test fun togglesAndRowsHoldingOneAreNeverTapped() {
        assertEquals(MappingDanger.SETTING_CHANGE, risk("Private account", role = "switch"))
        assertEquals(MappingDanger.SETTING_CHANGE, risk("Dark mode", checkable = true))
        assertEquals(MappingDanger.SETTING_CHANGE, risk("Show activity status", role = "row", containsCheckable = true))
        assertEquals(MappingDanger.SETTING_CHANGE, risk("Volume", role = "SeekBar"))
    }

    @Test fun stateChangingActionsAreNeverTapped() {
        listOf("Follow", "Like", "Subscribe", "Join group", "Add friend", "Block", "Report post", "Install", "Turn on notifications", "Accept")
            .forEach { assertEquals(it, MappingDanger.STATE_CHANGE, risk(it)) }
    }

    @Test fun securityAreasAreNeverEntered() {
        listOf("Password and security", "Two-factor authentication", "Passkeys", "Where you're logged in", "Deactivate account", "Backup codes")
            .forEach { assertEquals(it, MappingDanger.SECURITY, risk(it)) }
    }

    @Test fun accountDoorsAreOffLimitsOnlyForTheOwnersOwnAccount() {
        assertEquals(MappingDanger.ACCOUNT, risk("Sign in", identity = MappingIdentity.OWN))
        assertEquals(MappingDanger.ACCOUNT, risk("Edit profile", identity = MappingIdentity.OWN))
        assertNull(risk("Sign in", identity = MappingIdentity.TEST))
        assertNull(risk("Sign in", identity = null))
    }

    @Test fun ordinaryNavigationStaysOpen() {
        listOf("Home", "Search", "Reels", "Messages", "Settings", "Notifications", "Display", "Connected devices", "About phone", "Help")
            .forEach { assertNull(it, risk(it)) }
        assertNull(risk())
    }
}
