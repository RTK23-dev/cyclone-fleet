package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCopyTest {
    @Test
    fun copyStringsMatchTheV4Bible() {
        assertEquals("Analysis", OverlayCopy.ANALYSIS_TITLE)
        assertEquals("Task automation", OverlayCopy.WORKING_TITLE)
        assertEquals(
            "I'm on it. I'll let you know when this is ready to complete. You can leave this screen.",
            OverlayCopy.WORKING_BODY,
        )
        assertEquals("Working on this task", OverlayCopy.STATUS)
        assertEquals("View progress", OverlayCopy.PRIMARY)
        assertEquals("Do this", OverlayCopy.CONFIRM)
        assertEquals("Order this from", OverlayCopy.COMMERCE)
        assertEquals("Stop task", OverlayCopy.LIVE_LEFT)
        assertEquals("Take control", OverlayCopy.LIVE_RIGHT)
        assertEquals("Ask Cyclone", OverlayCopy.COMPOSER)
        assertEquals("Cyclone needs you to confirm before finishing this.", OverlayCopy.GATE)
        assertEquals(
            "Saved as a draft skill. Review it in Automations before it can run alone.",
            OverlayCopy.DONE,
        )
        assertEquals(
            "Supervise closely. Interrupt when needed. Select apps only. Compatibility varies.",
            OverlayCopy.LEGAL,
        )
    }

    @Test
    fun visibleCopyNeverUsesForbiddenPhrases() {
        val joined = OverlayCopy.visibleStrings().joinToString("\n")
        OverlayCopy.NEVER_SAY.forEach { forbidden ->
            assertFalse("visible copy must not contain: $forbidden", joined.contains(forbidden))
        }
        assertTrue(OverlayCopy.NEVER_SAY.size == 4)
    }
}
