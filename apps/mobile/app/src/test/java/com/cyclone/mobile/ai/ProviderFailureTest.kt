package com.cyclone.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFailureTest {
    @Test fun privacyRoutingAndMissingModelAreNotConflated() {
        val blocked = ProviderFailure.classify(404, "{\"error\":{\"message\":\"No endpoints match your data policy\"}}", selectedModelId = "meta/muse-spark-1.3-contributor")
        assertEquals(ProviderFailureClass.ROUTING_CONSTRAINT_UNSATISFIED, blocked.failureClass)
        assertTrue(blocked.userMessage.contains("privacy settings"))
        assertEquals(ProviderFailureClass.MODEL_NOT_FOUND, ProviderFailure.classify(404).failureClass)
        assertEquals(ProviderFailureClass.MODEL_ACCESS_DENIED, ProviderFailure.classify(403).failureClass)
    }

    @Test fun providerFailuresKeepDistinctActionableClasses() {
        assertEquals(ProviderFailureClass.PROVIDER_AUTH_FAILED, ProviderFailure.classify(401).failureClass)
        assertEquals(ProviderFailureClass.MODEL_ACCESS_DENIED, ProviderFailure.classify(403).failureClass)
        assertEquals(ProviderFailureClass.MODEL_NOT_FOUND, ProviderFailure.classify(404).failureClass)
        assertEquals(ProviderFailureClass.PROVIDER_CREDIT_EXHAUSTED, ProviderFailure.classify(402).failureClass)
        assertEquals(ProviderFailureClass.RATE_LIMITED, ProviderFailure.classify(429).failureClass)
        assertEquals(ProviderFailureClass.NETWORK_FAILURE, ProviderFailure.classify(0).failureClass)
        assertEquals(ProviderFailureClass.NO_PROVIDER_AVAILABLE, ProviderFailure.classify(500).failureClass)
        listOf(0, 401, 402, 403, 404, 408, 429, 500, 504).forEach {
            assertNotNull(ProviderFailure.message(ProviderFailure.code(it)))
        }
        assertNull(ProviderFailure.message("untrusted provider content"))
    }

    @Test fun bodySignalsRefineParameterContextAndRoutingFailures() {
        assertEquals(
            ProviderFailureClass.PARAMETER_UNSUPPORTED,
            ProviderFailure.classify(400, "{\"error\":{\"message\":\"unsupported parameter response_format\"}}").failureClass,
        )
        assertEquals(
            ProviderFailureClass.CONTEXT_LIMIT,
            ProviderFailure.classify(400, "{\"error\":{\"message\":\"context length limit exceeded\"}}").failureClass,
        )
        assertEquals(
            ProviderFailureClass.ROUTING_CONSTRAINT_UNSATISFIED,
            ProviderFailure.classify(400, "{\"error\":{\"message\":\"routing constraint require_parameters cannot be satisfied\"}}").failureClass,
        )
        assertEquals(
            ProviderFailureClass.NO_PROVIDER_AVAILABLE,
            ProviderFailure.classify(503, "{\"error\":{\"message\":\"no provider endpoints available\"}}").failureClass,
        )
    }

    @Test fun providerDiagnosticsAreSanitizedAndRetryabilityIsExplicit() {
        val failure = ProviderFailure.classify(
            429,
            "{\"error\":{\"code\":\"rate_limit\",\"message\":\"Bearer abcdefghijklmnop sk-secret12345678\"}}",
            selectedModelId = "muse-spark-1-3",
            providerName = "example-provider",
            requestId = "req-123",
        )
        assertTrue(failure.retryable)
        assertEquals("rate_limit", failure.providerCode)
        assertFalse(failure.providerMessage.orEmpty().contains("abcdefghijklmnop"))
        assertFalse(failure.providerMessage.orEmpty().contains("sk-secret"))
        assertEquals("req-123", failure.requestId)
        assertEquals("muse-spark-1-3", failure.selectedModelId)
    }
}
