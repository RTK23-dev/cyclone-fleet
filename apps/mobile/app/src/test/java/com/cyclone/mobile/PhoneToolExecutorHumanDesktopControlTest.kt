package com.cyclone.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolExecutorHumanDesktopControlTest {
    @Test
    fun `desktop manual scope is narrow and restored after nested calls`() {
        assertFalse(PhoneToolExecutor.humanDesktopControlActive())
        PhoneToolExecutor.withHumanDesktopControl {
            assertTrue(PhoneToolExecutor.humanDesktopControlActive())
            PhoneToolExecutor.withHumanDesktopControl {
                assertTrue(PhoneToolExecutor.humanDesktopControlActive())
            }
            assertTrue(PhoneToolExecutor.humanDesktopControlActive())
        }
        assertFalse(PhoneToolExecutor.humanDesktopControlActive())
    }

    @Test
    fun `desktop manual scope is cleared after a failed command`() {
        try {
            PhoneToolExecutor.withHumanDesktopControl { error("failed") }
        } catch (_: IllegalStateException) {
            // The request failed, but no later AI request inherits manual authority.
        }
        assertFalse(PhoneToolExecutor.humanDesktopControlActive())
    }
}
