package com.cyclone.mobile.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFallbackPolicyTest {
    @Test fun backupTakesOverOnlyWhenTheRouteIsBusyOrDown() {
        assertTrue("alpha.22 run: HTTP 429 upstream", ProviderFallbackPolicy.shouldSwitch(429, null, ProviderFailureClass.RATE_LIMITED))
        assertTrue(ProviderFallbackPolicy.shouldSwitch(503, null, null))
        assertTrue(ProviderFallbackPolicy.shouldSwitch(0, "provider.circuit_open", null))
        assertFalse("the task budget is the limit, not the route", ProviderFallbackPolicy.shouldSwitch(0, "provider.deadline", null))
        assertFalse("key/account problems are the owner's to fix", ProviderFallbackPolicy.shouldSwitch(401, null, null))
        assertFalse(ProviderFallbackPolicy.shouldSwitch(400, null, null))
    }
}
