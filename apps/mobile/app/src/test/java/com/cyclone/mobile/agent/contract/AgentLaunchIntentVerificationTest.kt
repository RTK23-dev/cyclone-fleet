package com.cyclone.mobile.agent.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLaunchIntentVerificationTest {
    private fun state(pkg: String, page: String, haystack: String) = SemanticObservationState(
        packageName = pkg,
        pageKey = page,
        accessibilityFingerprint = page,
        haystack = haystack,
        elements = emptyList(),
    )

    @Test
    fun webLaunchDoesNotPassFromPackageChangeAlone() {
        val result = AgentSemanticVerifier.verify(
            tool = "phone.launch_intent",
            androidExecutionOk = true,
            executorAssertionFailed = false,
            explicitExpectation = false,
            expectedPackage = "",
            goalLabel = "Facebook",
            before = state("com.android.settings", "settings", "Settings"),
            after = state("com.android.chrome", "new-tab", "Chrome New tab"),
            expectedUri = "https://facebook.com",
        )

        assertFalse(result.passed)
        assertEquals("EXPECTED_URI_HOST_NOT_OBSERVED", result.basis)
    }

    @Test
    fun webLaunchPassesOnlyWhenRequestedHostIsVisible() {
        val before = state("com.android.settings", "settings", "Settings")
        val good = AgentSemanticVerifier.verify(
            tool = "phone.launch_intent",
            androidExecutionOk = true,
            executorAssertionFailed = false,
            explicitExpectation = false,
            expectedPackage = "",
            goalLabel = "Facebook",
            before = before,
            after = state("com.android.chrome", "facebook", "https://m.facebook.com/ Create new account"),
            expectedUri = "https://facebook.com",
        )
        val lookalike = AgentSemanticVerifier.verify(
            tool = "phone.launch_intent",
            androidExecutionOk = true,
            executorAssertionFailed = false,
            explicitExpectation = false,
            expectedPackage = "",
            goalLabel = "Facebook",
            before = before,
            after = state("com.android.chrome", "evil", "https://facebook.com.evil.example/"),
            expectedUri = "https://facebook.com",
        )

        assertTrue(good.passed)
        assertEquals("EXPECTED_URI_HOST", good.basis)
        assertFalse(lookalike.passed)
    }
}
