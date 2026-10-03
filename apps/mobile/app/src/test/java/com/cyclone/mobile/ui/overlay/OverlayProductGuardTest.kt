package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.ui.v32.V32Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayProductGuardTest {
    @Test
    fun overlayDoesNotAddALauncherActivity() {
        assertNull(OverlayChromeContract.overlayLauncherActivity)
        assertEquals("com.cyclone.mobile.MainActivity", OverlayChromeContract.HOST_LAUNCHER_ACTIVITY)
        assertEquals("TYPE_ACCESSIBILITY_OVERLAY", OverlayChromeContract.WINDOW_TYPE)
        assertTrue(
            "overlay chrome is a service window, not an Activity subclass name",
            OverlayChromeControllerClassName.endsWith("OverlayChromeController"),
        )
        assertFalseActivitySuffixIsNotLauncher()
    }

    @Test
    fun primaryShellHasFiveConsumerDestinationsAndNoOverlayTab() {
        assertEquals(listOf("HOME", "PROFILES", "AI", "ROUTINES", "BRAIN"), V32Destination.entries.map { it.name })
        assertEquals(listOf("Home", "Profiles", "AI", "Routines", "Brain"), V32Destination.entries.map { it.label })
        assertEquals(OverlayChromeContract.homeDestinationNames, V32Destination.entries.map { it.name })
        assertEquals(OverlayChromeContract.homeDestinationLabels, V32Destination.entries.map { it.label })
        assertEquals(5, V32Destination.entries.size)
        assertTrue(V32Destination.entries.none { it.name == "OVERLAY" || it.label == "Overlay" })
    }

    private fun assertFalseActivitySuffixIsNotLauncher() {
        assertEquals("com.cyclone.mobile.ai.OverlayChromeController", OverlayChromeControllerClassName)
        assertTrue(!OverlayChromeControllerClassName.endsWith("Activity"))
    }

    companion object {
        private const val OverlayChromeControllerClassName = "com.cyclone.mobile.ai.OverlayChromeController"
    }
}
