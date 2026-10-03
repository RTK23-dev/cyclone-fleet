package com.cyclone.mobile

import org.junit.Assert.assertFalse
import org.junit.Test

class BridgeClientSafetyTest {
    @Test
    fun retiredIntegrationCannotClaimDeliveryOfStoredRoutineEvents() {
        assertFalse(BridgeClient.sendAutomationEvent("automation.request", mapOf("goal" to "open Settings")))
    }
}
