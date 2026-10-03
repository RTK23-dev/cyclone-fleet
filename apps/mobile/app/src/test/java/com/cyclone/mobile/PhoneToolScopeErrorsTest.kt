package com.cyclone.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneToolScopeErrorsTest {
    @Test
    fun leftoverWorkspaceLeaseIsNotACapabilityMiss() {
        val error = IllegalStateException("MUTATE_LOCK: switch and use the current workspaceId/workspaceGeneration")
        assertEquals(PhoneToolErrorCode.WORKSPACE_SCOPE_CONFLICT, PhoneToolScopeErrors.code(error))
        assertTrue(PhoneToolScopeErrors.message(error).contains("MUTATE_LOCK"))
        assertFalse(PhoneToolScopeErrors.code(error) == PhoneToolErrorCode.CAPABILITY_UNAVAILABLE)
    }

    @Test
    fun targetMismatchStaysTyped() {
        val error = IllegalStateException("TARGET_MISMATCH: package/user/display could not be verified")
        assertEquals(PhoneToolErrorCode.TARGET_SCOPE_MISMATCH, PhoneToolScopeErrors.code(error))
    }

    @Test
    fun unknownScopeFailureIsRetryableActionFailed() {
        val error = IllegalStateException("workspace registry not initialized")
        assertEquals(PhoneToolErrorCode.ACTION_FAILED, PhoneToolScopeErrors.code(error))
        assertTrue(PhoneToolScopeErrors.message(error).contains("workspace registry not initialized"))
    }

    @Test
    fun gateAndStaleKeepTheirExistingCodes() {
        assertEquals(
            PhoneToolErrorCode.POLICY_DENIED,
            PhoneToolScopeErrors.code(IllegalStateException("GATE: human review required")),
        )
        assertEquals(
            PhoneToolErrorCode.FRESH_OBSERVATION_REQUIRED,
            PhoneToolScopeErrors.code(IllegalStateException("STALE_OBSERVATION: observe this workspace again")),
        )
        assertEquals(
            PhoneToolErrorCode.INVALID_REQUEST,
            PhoneToolScopeErrors.code(IllegalArgumentException("Display/session mismatch")),
        )
        assertEquals(
            PhoneToolErrorCode.HUMAN_HAS_CONTROL,
            PhoneToolScopeErrors.code(IllegalStateException("HUMAN_HAS_CONTROL: stale input authority")),
        )
    }
}
