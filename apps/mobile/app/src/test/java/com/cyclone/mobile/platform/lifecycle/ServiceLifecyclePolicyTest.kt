package com.cyclone.mobile.platform.lifecycle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecyclePolicyTest {
    @Test
    fun supportsStartupDegradationRecoveryAndStop() {
        assertTrue(ServiceLifecyclePolicy.canTransition(ServiceLifecycleState.REGISTERED, ServiceLifecycleState.STARTING))
        assertTrue(ServiceLifecyclePolicy.canTransition(ServiceLifecycleState.STARTING, ServiceLifecycleState.READY))
        assertTrue(ServiceLifecyclePolicy.canTransition(ServiceLifecycleState.READY, ServiceLifecycleState.DEGRADED))
        assertTrue(ServiceLifecyclePolicy.canTransition(ServiceLifecycleState.DEGRADED, ServiceLifecycleState.READY))
        assertTrue(ServiceLifecyclePolicy.canTransition(ServiceLifecycleState.READY, ServiceLifecycleState.STOPPED))
        assertTrue(ServiceLifecyclePolicy.canTransition(ServiceLifecycleState.STOPPED, ServiceLifecycleState.STARTING))
    }

    @Test
    fun rejectsSkippingStartupAndResurrectingWithoutStart() {
        assertFalse(ServiceLifecyclePolicy.canTransition(ServiceLifecycleState.REGISTERED, ServiceLifecycleState.READY))
        assertFalse(ServiceLifecyclePolicy.canTransition(ServiceLifecycleState.FAILED, ServiceLifecycleState.READY))
        assertFalse(ServiceLifecyclePolicy.canTransition(ServiceLifecycleState.STOPPED, ServiceLifecycleState.READY))
    }
}
