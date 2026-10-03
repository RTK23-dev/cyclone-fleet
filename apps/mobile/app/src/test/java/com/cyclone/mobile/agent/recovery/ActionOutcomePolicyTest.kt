package com.cyclone.mobile.agent.recovery

import com.cyclone.mobile.PhoneToolErrorCode
import com.cyclone.mobile.agent.CycloneTaskClassification
import com.cyclone.mobile.agent.contract.AgentFailureClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionOutcomePolicyTest {
    @Test
    fun leftoverLeaseCapabilityMissIsARecoveryIncident() {
        assertFalse(
            ActionOutcomePolicy.hardBlocker(
                AgentFailureClass.CAPABILITY_UNAVAILABLE,
                "Execution scope unavailable (MUTATE_LOCK); observe the current session again.",
            ),
        )
        assertFalse(ActionOutcomePolicy.hardBlocker(AgentFailureClass.EXECUTION_FAILED, "Workspace lease blocked open_app"))
        assertFalse(ActionOutcomePolicy.hardBlocker(AgentFailureClass.TARGET_NOT_FOUND, "App is not installed"))
    }

    @Test
    fun onlyPolicyAndUnexposedToolsAreTerminal() {
        assertTrue(ActionOutcomePolicy.hardBlocker(AgentFailureClass.POLICY_DENIED, "Payments require confirmation"))
        assertTrue(ActionOutcomePolicy.hardBlocker(AgentFailureClass.CAPABILITY_UNAVAILABLE, "Tool phone.shell is not exposed"))
        assertFalse(ActionOutcomePolicy.hardBlocker(AgentFailureClass.CAPABILITY_UNAVAILABLE, "Execution scope unavailable"))
        assertFalse(ActionOutcomePolicy.hardBlocker(AgentFailureClass.STALE_OBSERVATION, "stale"))
        assertFalse(ActionOutcomePolicy.hardBlocker(AgentFailureClass.ACCESSIBILITY_UNAVAILABLE, "disconnected"))
    }

    @Test
    fun captureAfterFailedMutationsUnlessObservationIsImpossible() {
        assertTrue(ActionOutcomePolicy.shouldCaptureAfter(null))
        assertTrue(ActionOutcomePolicy.shouldCaptureAfter(PhoneToolErrorCode.CAPABILITY_UNAVAILABLE))
        assertTrue(ActionOutcomePolicy.shouldCaptureAfter(PhoneToolErrorCode.WORKSPACE_SCOPE_CONFLICT))
        assertTrue(ActionOutcomePolicy.shouldCaptureAfter(PhoneToolErrorCode.APP_NOT_FOUND))
        assertTrue(ActionOutcomePolicy.shouldCaptureAfter(PhoneToolErrorCode.ACTION_FAILED))
        assertFalse(ActionOutcomePolicy.shouldCaptureAfter(PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED))
        assertFalse(ActionOutcomePolicy.shouldCaptureAfter(PhoneToolErrorCode.HUMAN_HAS_CONTROL))
    }

    @Test
    fun providerMissesAreNotHardBlockers() {
        assertEquals(
            CycloneTaskClassification.HUMAN_OR_GATE,
            ActionOutcomePolicy.providerBoundary("NO_PROVIDER_AVAILABLE"),
        )
        assertEquals(
            CycloneTaskClassification.PROVIDER_RETRY_LATER,
            ActionOutcomePolicy.providerBoundary("provider.deadline"),
        )
        assertEquals(
            CycloneTaskClassification.PROVIDER_RETRY_LATER,
            ActionOutcomePolicy.providerBoundary("NETWORK_FAILURE"),
        )
        assertEquals(
            CycloneTaskClassification.PROVIDER_RETRY_LATER,
            ActionOutcomePolicy.providerBoundary("RATE_LIMITED"),
        )
        assertEquals(
            CycloneTaskClassification.PROVIDER_RETRY_LATER,
            ActionOutcomePolicy.providerBoundary("provider.circuit_open"),
        )
        assertEquals(
            CycloneTaskClassification.CANCELLED,
            ActionOutcomePolicy.providerBoundary("provider.cancelled"),
        )
        assertEquals(
            CycloneTaskClassification.HUMAN_OR_GATE,
            ActionOutcomePolicy.providerBoundary("PROVIDER_AUTH_FAILED"),
        )
    }
}
