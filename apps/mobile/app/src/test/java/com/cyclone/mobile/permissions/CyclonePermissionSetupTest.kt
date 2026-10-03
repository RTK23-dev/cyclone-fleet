package com.cyclone.mobile.permissions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CyclonePermissionSetupTest {
    @Test
    fun `safe permission check preserves successful true result`() {
        assertTrue(safePermissionCheck { true })
    }

    @Test
    fun `safe permission check fails closed on platform exception`() {
        assertFalse(safePermissionCheck { throw SecurityException("system setting unavailable") })
    }

    @Test
    fun `safe permission check fails closed on runtime exception`() {
        assertFalse(safePermissionCheck { throw IllegalStateException("OEM service unavailable") })
    }
}
