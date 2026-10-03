package com.cyclone.mobile.runtime.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NamedWorkspaceControlPolicyTest {
    @Test
    fun namedVdMayMutateWhenNotPausedAndNotGated() {
        val decision = NamedWorkspaceControlPolicy.mayMutate(
            sessionId = "named-vd",
            displayId = 7,
            userPaused = false,
            gateRequired = false,
        )
        assertTrue(decision.allowMutate)
        assertNull(decision.errorClass)
        assertNull(decision.detail)
    }

    @Test
    fun userPausedTakeControlBlocksMutate() {
        val decision = NamedWorkspaceControlPolicy.mayMutate(
            sessionId = "named-vd",
            displayId = 7,
            userPaused = true,
            gateRequired = false,
        )
        assertFalse(decision.allowMutate)
        assertEquals(NamedWorkspaceControlPolicy.USER_PAUSED, decision.errorClass)
        assertEquals("Take control paused Cyclone; do not act", decision.detail)
    }

    @Test
    fun gateRequiredBlocksMutateEvenOnNamedVd() {
        val decision = NamedWorkspaceControlPolicy.mayMutate(
            sessionId = "named-vd",
            displayId = 7,
            userPaused = false,
            gateRequired = true,
        )
        assertFalse(decision.allowMutate)
        assertEquals(NamedWorkspaceControlPolicy.GATE_REQUIRED, decision.errorClass)
        assertEquals("GATE_CONFIRM is required; never synthesize approval / pcAutoApprove", decision.detail)
        val pausedAndGated = NamedWorkspaceControlPolicy.mayMutate(
            sessionId = "named-vd",
            displayId = 7,
            userPaused = true,
            gateRequired = true,
        )
        assertFalse(pausedAndGated.allowMutate)
        assertEquals(NamedWorkspaceControlPolicy.USER_PAUSED, pausedAndGated.errorClass)
    }

    @Test
    fun namedDisplayZeroCannotMutateOrContinue() {
        val namedZero = NamedWorkspaceControlPolicy.mayMutate(
            sessionId = "named-vd",
            displayId = 0,
            userPaused = false,
            gateRequired = false,
        )
        assertFalse(namedZero.allowMutate)
        assertEquals(SessionContract.SESSION_DISPLAY_MISMATCH, namedZero.errorClass)

        val foreground = NamedWorkspaceControlPolicy.mayMutate(
            sessionId = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
            displayId = 0,
            userPaused = false,
            gateRequired = false,
        )
        assertFalse(foreground.allowMutate)
        assertEquals(SessionContract.SESSION_DISPLAY_MISMATCH, foreground.errorClass)

        val pausedForeground = NamedWorkspaceControlPolicy.mayMutate(
            sessionId = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
            displayId = 0,
            userPaused = true,
            gateRequired = true,
        )
        assertFalse(pausedForeground.allowMutate)
        assertEquals(SessionContract.SESSION_DISPLAY_MISMATCH, pausedForeground.errorClass)

        val zeroContinue = assertThrows(SessionIdentityException::class.java) {
            NamedWorkspaceControlPolicy.continueIdentity("named-vd", 0)
        }
        assertEquals(SessionContract.SESSION_DISPLAY_MISMATCH, zeroContinue.errorClass)
    }

    @Test
    fun continueKeepsSameSessionAndDisplay() {
        val continued = NamedWorkspaceControlPolicy.continueIdentity("named-vd", 7)
        assertEquals("named-vd", continued.sessionId)
        assertEquals(7, continued.displayId)
        assertEquals(ExecutionContext("named-vd", 7), continued)
        assertFalse(continued.sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID)
        assertFalse(continued.displayId == ExecutionSession.DEFAULT_DISPLAY_ID)

        val foreground = assertThrows(SessionIdentityException::class.java) {
            NamedWorkspaceControlPolicy.continueIdentity(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, 0)
        }
        assertEquals(SessionContract.SESSION_DISPLAY_MISMATCH, foreground.errorClass)
    }

    @Test
    fun takeControlRelinquishInjectIsTrueForNamedVd() {
        assertTrue(NamedWorkspaceControlPolicy.takeControlRelinquishInject("named-vd", 7))
        assertTrue(NamedWorkspaceControlPolicy.takeControlRelinquishInject("named-vd", 0))
        assertFalse(
            NamedWorkspaceControlPolicy.takeControlRelinquishInject(
                ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID,
                0,
            ),
        )
        assertEquals(1, SessionKernel.PRODUCT_HOT_BACKGROUND_LIMIT)
        assertEquals(3, SessionPlaneKind.entries.size)
    }
}
