package com.cyclone.mobile.ai

import org.junit.Assert.*
import org.junit.Test

class OpenRouterCatalogTest {
    @Test fun embeddedErrorsWorkAtBothChatAndAgentBoundaries() {
        val error = """{"code":403,"message":"This key cannot access the selected model"}"""
        for (body in listOf(error, "{\"error\":$error}")) {
            val failure = ProviderFailure.classify(200, body, "provider/model")
            assertEquals(403, failure.httpStatus)
            assertEquals(ProviderFailureClass.MODEL_ACCESS_DENIED, failure.failureClass)
            assertFalse(failure.retryable)
        }
    }

    @Test fun catalogPreservesExactIdsAndCapabilitiesAndSearchesBothNameAndProvider() {
        val models = OpenRouterCatalog.parse("""{"data":[
          {"id":"new/model:free","name":"New model","architecture":{"input_modalities":["text","image"],"output_modalities":["text"]},"top_provider":{"max_completion_tokens":2048}},
          {"id":"other/embedding","name":"Embedding","architecture":{"output_modalities":["embeddings"]}},
          {"id":"invalid"}, {"id":"new/model:free","name":"Duplicate"}
        ]}""")
        assertEquals(2, models.size)
        val selected = OpenRouterCatalog.search(models, "NEW free").single()
        assertEquals("new/model:free", selected.id)
        assertTrue(selected.preset().vision)
        assertEquals(2048, selected.maxOutputTokens)
        assertFalse(models.first { it.id == "other/embedding" }.textOutput)
        assertEquals(models, OpenRouterCatalog.parse(org.json.JSONObject().put("data", org.json.JSONArray().also { array -> models.forEach { array.put(it.toJson()) } }).toString()))
    }
    @Test fun typedPolicyBlockIsNotMisreportedAsModelAccess() {
        val failure = ProviderFailure.classify(403, """{"error":{"message":"Request blocked by guardrail","metadata":{"error_type":"permission_denied"}}}""")
        assertEquals(ProviderFailureClass.PROVIDER_REQUEST_BLOCKED, failure.failureClass)
        assertFalse(failure.retryable)
        assertTrue(failure.userMessage.contains("guardrail"))
        val refusal = ProviderFailure.classify(200, """{"error":{"metadata":{"error_type":"refusal"}}}""")
        assertEquals(ProviderFailureClass.PROVIDER_REQUEST_BLOCKED, refusal.failureClass)
    }
}
