package com.cyclone.mobile.gateway

import android.content.ContextWrapper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GatewayActionPolicyV294Test {
    // The compatibility authority under test never dereferences Context. ContextWrapper is part of
    // the normal Android API surface and keeps this a plain local JVM unit test without android.test.
    private val context = object : ContextWrapper(null) {}

    @After
    fun resetAuthority() {
        GatewayActionAuthorityRegistry.resetForTests()
    }

    @Test
    fun compatibilityAuthorityIsFailClosedForMutations() {
        val decision = GatewayCompatibilityActionAuthority.authorize(
            context,
            GatewayActionAuthorityRequest(
                requestId = "request-1",
                capability = "phone.click",
                parameters = JSONObject().put("selector", JSONObject().put("text", "Apps")),
                currentObservationId = "obs-current",
                source = "PC_CODEX",
                goal = "Open Apps",
                missionMetadata = JSONObject(),
            ),
        )
        assertEquals(GatewayActionAuthorityOutcome.CAPABILITY_UNAVAILABLE, decision.outcome)
        assertEquals("V31_ACTION_AUTHORITY_NOT_BOUND", decision.reasonCode)
    }

    @Test
    fun compatibilityAuthorityRequiresFreshObservationBeforeMutation() {
        val decision = GatewayCompatibilityActionAuthority.authorize(
            context,
            GatewayActionAuthorityRequest(
                requestId = "request-2",
                capability = "phone.type",
                parameters = JSONObject().put("value", "must-not-be-logged"),
                currentObservationId = null,
                source = "PC_CODEX",
                goal = "Type a value",
                missionMetadata = JSONObject(),
            ),
        )
        assertEquals(GatewayActionAuthorityOutcome.STALE_OBSERVATION, decision.outcome)
        assertFalse(decision.safeMessage.contains("must-not-be-logged"))
    }

    @Test
    fun compatibilityAuthorityAllowsOnlyReadOnlyExecutorHandoff() {
        val decision = GatewayCompatibilityActionAuthority.authorize(
            context,
            GatewayActionAuthorityRequest(
                requestId = "request-3",
                capability = "phone.find",
                parameters = JSONObject().put("query", "Apps"),
                currentObservationId = null,
                source = "PC_CODEX",
                goal = "Find Apps",
                missionMetadata = JSONObject(),
            ),
        )
        assertEquals(GatewayActionAuthorityOutcome.AUTHORIZED_HANDOFF, decision.outcome)
    }

    @Test
    fun policyDeniedDecisionMapsToCanonicalGatewayError() {
        val decision = GatewayActionAuthorityDecision(
            GatewayActionAuthorityOutcome.POLICY_DENIED,
            "POLICY_TEST_DENY",
            "Action denied by Cyclone policy.",
        )
        try {
            decision.requireAuthorized("request-4")
            fail("Denied authority decision must stop before PhoneToolExecutor")
        } catch (error: GatewayProtocolException) {
            assertEquals("POLICY_DENIED", error.code)
            assertEquals("request-4", error.requestId)
        }
    }

    @Test
    fun productionBindingIsExplicitAndResettable() {
        assertFalse(GatewayActionAuthorityRegistry.isProductionAuthorityBound())
        GatewayActionAuthorityRegistry.bind("V31_POLICY_TEST") { _, _ ->
            GatewayActionAuthorityDecision(
                GatewayActionAuthorityOutcome.AUTHORIZED_HANDOFF,
                "TEST",
                "Authorized by test adapter.",
            )
        }
        assertTrue(GatewayActionAuthorityRegistry.isProductionAuthorityBound())
        assertEquals("V31_POLICY_TEST", GatewayActionAuthorityRegistry.bindingName())
    }
}
