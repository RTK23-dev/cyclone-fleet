package com.cyclone.mobile.ai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenRouter438ContractTest {
    private val reasoning = CatalogReasoning(
        supportedEfforts = listOf("max", "xhigh", "high", "medium", "low", "minimal", "none"),
        defaultEffort = "high",
        mandatory = false,
        defaultEnabled = true,
        supportsMaxTokens = false,
    )

    @Test fun accessPolicyFailsClosedWhenKeyScopeIsUnknownOrChanged() {
        val ids = setOf("a/one")
        assertEquals(OpenRouterModelAvailability.UNKNOWN, OpenRouterAccessPolicy.availability(null, "new", ids, "a/one"))
        assertEquals(OpenRouterModelAvailability.UNKNOWN, OpenRouterAccessPolicy.availability("old", "new", ids, "a/one"))
        assertEquals(OpenRouterModelAvailability.UNKNOWN, OpenRouterAccessPolicy.availability("same", "same", null, "a/one"))
        assertEquals(OpenRouterModelAvailability.AVAILABLE, OpenRouterAccessPolicy.availability("same", "same", ids, "a/one"))
        assertEquals(OpenRouterModelAvailability.UNAVAILABLE, OpenRouterAccessPolicy.availability("same", "same", ids, "b/two"))
    }

    @Test fun reasoningUsesOnlyExactAdvertisedEffortWithoutChangingRouting() {
        val body = JSONObject()
            .put("model", "vendor/model")
            .put("provider", JSONObject().put("sort", "latency").put("allow_fallbacks", false).put("only", org.json.JSONArray(listOf("provider-tag"))))
        OpenRouterReasoningContract.apply(body, reasoning, "xhigh")
        assertEquals("vendor/model", body.getString("model"))
        assertEquals("latency", body.getJSONObject("provider").getString("sort"))
        assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        assertEquals("provider-tag", body.getJSONObject("provider").getJSONArray("only").getString(0))
        assertEquals("xhigh", body.getJSONObject("reasoning").getString("effort"))
    }

    @Test fun unsupportedOrAbsentReasoningNeverInventsFallback() {
        assertNull(OpenRouterReasoningContract.exactEffort(reasoning, "extreme"))
        assertNull(OpenRouterReasoningContract.exactEffort(null, "high"))
        val body = JSONObject().put("model", "vendor/model")
        OpenRouterReasoningContract.apply(body, reasoning, "extreme")
        assertFalse(body.has("reasoning"))
    }
}
