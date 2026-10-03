package com.cyclone.mobile.ai

import com.cyclone.mobile.agent.CycloneTaskClassification
import com.cyclone.mobile.agent.recovery.ActionOutcomePolicy
import com.cyclone.mobile.fastpath.FastPathLanding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the 2026-09-18 failed run:
 * Settings -> explicit Chrome/Facebook navigation -> provider 429 -> signup surface.
 *
 * This keeps the cross-policy contract visible in one place so a later optimization cannot
 * independently regress browser intent, provider recovery, signup handling, or diagnostics.
 */
class AgentReliability468RegressionTest {
    private val goal = "open chrome and go to Facebook and try to make an account using my email"

    @Test
    fun failedFacebookSignupRunKeepsBrowserSignupAndProviderBoundariesSeparate() {
        val landing = FastPathLanding.resolve(goal)
        assertEquals("phone.launch_intent", landing?.tool)
        assertEquals("https://facebook.com", landing?.uri)
        assertTrue(LoginAutofillPolicy.isSignupGoal(goal))

        val failure = ProviderFailure.classify(
            429,
            """{"error":{"code":429,"message":"rate limited"}}""",
            selectedModelId = "deepseek/deepseek-flash-latest",
        )
        val breaker = ProviderTaskCircuitBreaker()
        val route = ProviderTaskCircuitBreaker.routeKey("deepseek/deepseek-flash-latest", "price")
        assertTrue(failure.retryable)
        assertTrue(breaker.record(route, failure))
        assertTrue(breaker.isOpen(route))
        assertEquals(
            CycloneTaskClassification.PROVIDER_RETRY_LATER,
            ActionOutcomePolicy.providerBoundary(failure.code),
        )
    }

    @Test
    fun navigationProgressAndMutationVerificationRemainOrthogonalInRunExport() {
        val events = listOf(
            AiTraceEvent("1", "run", 1, "ACTION_REQUESTED", "Open Facebook in Chrome", "phone.launch_intent", null, null),
            AiTraceEvent("2", "run", 2, "ANDROID_EXECUTION", "Android accepted the action", "NONE", true, "executorInvoked=true"),
            AiTraceEvent("3", "run", 3, "AFTER_OBSERVATION", "Fresh after-state unavailable", "AFTER_OBSERVATION_FAILED", false, null),
            AiTraceEvent("4", "run", 4, "ACTION_REQUESTED", "Wait for Facebook", "phone.wait_for", null, null),
            AiTraceEvent("5", "run", 5, "ANDROID_EXECUTION", "Android accepted the action", "NONE", true, "executorInvoked=true"),
            AiTraceEvent("6", "run", 6, "AFTER_OBSERVATION", "Fresh after-state: facebook", "READ_ONLY_TOOL", true, null),
            AiTraceEvent("7", "run", 7, "VERIFICATION", "Execution did not prove semantic success", "READ_ONLY_TOOL", false, null),
            AiTraceEvent("8", "run", 8, "PROGRESS_CLASSIFIED", "verified progress", "package_or_activity_changed", true, null),
        )
        val metrics = AgentRunDiagnosticV39.metrics(events)

        assertEquals(2, metrics.executorInvocations)
        assertEquals(2, metrics.androidAcceptedExecutions)
        assertEquals(1, metrics.freshAfterStates)
        assertEquals(1, metrics.taskProgressObservations)
        assertEquals(0, metrics.semanticallyVerifiedMutations)
        assertFalse(metrics.taskProgressObservations == 0)
    }
}
