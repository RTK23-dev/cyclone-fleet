package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The working status shows the app Cyclone works in, never Cyclone's own logo when a real app is known. */
class WorkingAppTest {
    @Test fun `cyclone, the system bars and the home screen are never the app`() {
        assertFalse(WorkingApp.shows("com.cyclone.mobile"))
        assertFalse(WorkingApp.shows("com.android.systemui"))
        assertFalse(WorkingApp.shows(null))
        assertFalse(WorkingApp.shows("com.google.android.apps.nexuslauncher", setOf("com.google.android.apps.nexuslauncher")))
        assertTrue(WorkingApp.shows("com.whatsapp"))
    }

    @Test fun `the on-screen app is the last real one`() {
        WorkingApp.seen("com.whatsapp")
        WorkingApp.seen("com.cyclone.mobile")
        WorkingApp.seen("com.android.systemui")
        WorkingApp.seen("launcher", setOf("launcher"))
        assertEquals("com.whatsapp", WorkingApp.foreground.value)
        WorkingApp.seen("com.spotify.music")
        assertEquals("com.spotify.music", WorkingApp.foreground.value)
    }

    @Test fun `a task shows the app it works in, not cyclone`() {
        assertNull(WorkingApp.forTask("t-new", "com.cyclone.mobile"))
        assertNull(WorkingApp.forTask("t-none", null))
        assertEquals("com.google.android.apps.maps", WorkingApp.forTask("t1", "com.google.android.apps.maps"))
        // Back in Cyclone's own screen, the task still shows the app it worked in last.
        assertEquals("com.google.android.apps.maps", WorkingApp.forTask("t1", "com.cyclone.mobile"))
    }
}
