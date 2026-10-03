package com.cyclone.mobile

import com.cyclone.mobile.ai.OpenRouterCustomModelStore
import com.cyclone.mobile.automation.RecoveryPolicy
import com.cyclone.mobile.automation.Selector
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.ui.v32.v32SafeSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V291TeachUxTest {
    @Test
    fun customModelSlugValidationAcceptsOpenRouterShape() {
        assertTrue(OpenRouterCustomModelStore.isValidSlug("provider/model-name"))
        assertTrue(OpenRouterCustomModelStore.isValidSlug("qwen/qwen3.8-27b"))
        assertFalse(OpenRouterCustomModelStore.isValidSlug("model-only"))
        assertFalse(OpenRouterCustomModelStore.isValidSlug("provider/model with spaces"))
        assertFalse(OpenRouterCustomModelStore.isValidSlug("/model"))
    }

    @Test
    fun automationDetailExplainsTargetWithoutLeakingSensitiveParameters() {
        val step = StepDefinition(
            name = "Open orders",
            type = StepType.PHONE_TOOL,
            parameters = mapOf(
                "tool" to "phone.click",
                "timeout_ms" to "1200",
                "password" to "never-show-this",
                "api_key" to "also-never-show-this",
            ),
            selector = Selector(resourceId = "com.shop:id/orders", text = "Orders", requireClickable = true),
            recovery = RecoveryPolicy(maxRetries = 1),
        )
        val summary = step.v32SafeSummary()
        assertTrue(summary.contains("Click"))
        assertTrue(summary.contains("Orders"))
        assertTrue(summary.contains("timeout ms=1200"))
        assertFalse(summary.contains("never-show-this"))
        assertFalse(summary.contains("also-never-show-this"))
    }
}
