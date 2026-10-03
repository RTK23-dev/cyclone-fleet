package com.cyclone.mobile.ai

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OpenRouterCatalogClientTest {
    private fun client(reply: (Request) -> Pair<Int, String>) = OpenRouterCatalogClient(
        OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = reply(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("fixture").body(body.toResponseBody()).build()
        }.build(),
    )

    @Test fun fullCatalogAndAccountAvailabilityUseAuthenticatedUnpaginatedGets() {
        val requests = mutableListOf<Request>()
        val result = client { request ->
            requests += request
            200 to if (request.url.encodedPath.endsWith("/user")) """{"data":[{"id":"a/one"}]}"""
            else """{"data":[{"id":"a/one"},{"id":"b/two"}]}"""
        }.fetch("catalog-test-key")
        assertEquals(listOf("/api/v1/models", "/api/v1/models/user"), requests.map { it.url.encodedPath })
        requests.forEach {
            assertEquals("GET", it.method)
            assertEquals("openrouter.ai", it.url.host)
            assertEquals("Bearer catalog-test-key", it.header("Authorization"))
            assertEquals("all", it.url.queryParameter("output_modalities"))
            assertNull(it.url.queryParameter("limit"))
            assertNull(it.url.queryParameter("offset"))
            assertNull(it.body)
        }
        assertEquals(2, result.models.size)
        assertEquals(setOf("a/one"), result.availableIds)
    }

    @Test fun reasoningMetadataRetainsExactOpenRouterEffortsAndRoundTrips() {
        val json = """{"data":[{"id":"meta/test","name":"Test","architecture":{"input_modalities":["text"],"output_modalities":["text"]},"reasoning":{"mandatory":true,"default_enabled":true,"supported_efforts":["xhigh","high","medium","low","minimal"],"default_effort":"medium"}}]}"""
        val model = OpenRouterCatalog.parse(json).single()
        assertEquals(listOf("xhigh", "high", "medium", "low", "minimal"), model.reasoning?.supportedEfforts)
        assertEquals("medium", model.reasoning?.defaultEffort)
        assertTrue(model.reasoning?.mandatory == true)
        val roundTrip = OpenRouterCatalog.parse(org.json.JSONObject().put("data", org.json.JSONArray().put(model.toJson())).toString()).single()
        assertEquals(model.reasoning, roundTrip.reasoning)
    }

    @Test fun failedAccountLookupRetainsCatalogWithoutClaimingAccess() {
        val result = client { request ->
            if (request.url.encodedPath.endsWith("/user")) 403 to """{"error":{"message":"Key permissions denied"}}"""
            else 200 to """{"data":[{"id":"a/one"}]}"""
        }.fetch("catalog-test-key")
        assertEquals(1, result.models.size)
        assertNull(result.availableIds)
        assertTrue(result.warning.orEmpty().contains("HTTP 403"))
    }

    @Test fun unauthorizedAndEmbeddedErrorsAreActionableAndSanitized() {
        for (status in listOf(200, 401)) {
            try {
                client { status to """{"error":{"code":401,"message":"Invalid key sk-or-secretfixture123","metadata":{"error_type":"authentication"}}}""" }
                    .fetch("catalog-test-key")
                fail("Expected an authentication error")
            } catch (error: IOException) {
                assertTrue(error.message.orEmpty().contains("HTTP 401"))
                assertFalse(error.message.orEmpty().contains("secretfixture123"))
            }
        }
    }
}
