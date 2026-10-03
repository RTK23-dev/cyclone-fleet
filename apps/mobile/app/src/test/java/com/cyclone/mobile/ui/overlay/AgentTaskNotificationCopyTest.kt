package com.cyclone.mobile.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentTaskNotificationCopyTest {
    @Test fun titlesMatchTaskLifecycle() {
        assertEquals("Cyclone is working", AgentTaskNotificationCopy.title(AgentTaskNotificationState.WORKING))
        assertEquals("Cyclone needs you", AgentTaskNotificationCopy.title(AgentTaskNotificationState.WAITING))
        assertEquals("Task completed", AgentTaskNotificationCopy.title(AgentTaskNotificationState.COMPLETED))
        assertEquals("Task stopped", AgentTaskNotificationCopy.title(AgentTaskNotificationState.STOPPED))
    }

    @Test fun notificationTextIsBoundedAndWhitespaceNormalized() {
        val compact = AgentTaskNotificationCopy.text("  one\n\n two   three  ", "fallback")
        assertEquals("one two three", compact)
        val bounded = AgentTaskNotificationCopy.text("x".repeat(400), "fallback")
        assertEquals(240, bounded.length)
        assertFalse(bounded.contains("\n"))
    }

    @Test fun blankResultUsesSafeFallback() {
        assertEquals("Cyclone finished.", AgentTaskNotificationCopy.text("   ", "Cyclone finished."))
    }
}
