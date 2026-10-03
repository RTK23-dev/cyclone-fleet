package com.cyclone.mobile.runtime.workspaces

import org.junit.Assert.*
import org.junit.Test

class ProfileBootstrapContractTest {
    @Test fun identityMustMatchReceivingAndroidUser() {
        ProfileBootstrapContract.validateTransfer(0, 12, 12)
        assertTrue(runCatching { ProfileBootstrapContract.validateTransfer(0, 12, 13) }.isFailure)
        assertTrue(runCatching { ProfileBootstrapContract.validateTransfer(0, 0, 0) }.isFailure)
        assertTrue(runCatching { ProfileBootstrapContract.validateTransfer(12, 12, 12) }.isFailure)
    }
    @Test fun onlyPortablePreferencesAreTransferred() {
        val input = mapOf("openrouter_model" to "model", "safe_mode" to true,
            "api_key" to "secret", "sessionId" to "owned", "gate_approved" to true, "pairing_token" to "private")
        assertEquals(mapOf("openrouter_model" to "model", "safe_mode" to true), ProfileBootstrapContract.portablePreferences(input))
    }
    @Test fun serviceMergePreservesUnrelatedAccessibilityAndIsIdempotent() {
        val own = ProfileBootstrapContract.ACCESSIBILITY
        val other = "reader.app/.Service"
        val once = ProfileBootstrapContract.mergeServiceList(other, own)
        assertEquals("$other:$own", once)
        assertEquals(once, ProfileBootstrapContract.mergeServiceList(once, own))
        assertEquals(own, ProfileBootstrapContract.mergeServiceList("null", own))
    }
    @Test fun arbitraryServicesCannotBeGrantedByTransfer() {
        assertTrue(runCatching { ProfileBootstrapContract.mergeServiceList("", "other.app/.Service") }.isFailure)
    }
    @Test fun uidArithmeticRetainsExactUser() {
        assertEquals(1210234, ProfileBootstrapContract.targetUid(10234, 12))
        assertEquals(12, ProfileBootstrapContract.userId(1210234))
        assertTrue(runCatching { ProfileBootstrapContract.targetUid(0, 12) }.isFailure)
    }
}
