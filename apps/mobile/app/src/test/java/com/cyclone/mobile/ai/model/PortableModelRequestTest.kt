package com.cyclone.mobile.ai.model
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class PortableModelRequestTest {
    @Test fun defaultRoutingDoesNotPinPublicEndpointsOrOverridePrivacy() {
        val body = PortableModelRequest.bodyForVerifiedModel("provider/new-model", JSONArray())
        assertFalse(body.getJSONObject("provider").has("only"))
        assertFalse(body.getJSONObject("provider").has("data_collection"))
        assertFalse(body.has("models"))
        assertEquals("provider/new-model", body.getString("model"))
    }

    @Test fun everyProfileUsesTheSamePortableContractWithoutInventedThinkingModes() {
        ModelRegistry.all.forEach { profile ->
            val body = PortableModelRequest.bodyForVerifiedModel(profile.openRouterSlug, JSONArray(), listOf("verified"), 300)
            assertEquals(profile.openRouterSlug, body.getString("model"))
            assertFalse(body.has("reasoning")); assertFalse(body.has("temperature")); assertFalse(body.has("response_format"))
            assertEquals(300, body.getInt("max_tokens"))
            assertEquals("verified", body.getJSONObject("provider").getJSONArray("only").getString(0))
        }
    }
    @Test fun contributorIdentityAndAccountPrivacyCannotBeSubstituted() {
        val body = PortableModelRequest.bodyForVerifiedModel(ModelRegistry.MUSE_SPARK_1_3_CONTRIBUTOR.openRouterSlug, JSONArray(), listOf("meta"))
        assertFalse(body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        assertFalse(body.getJSONObject("provider").has("data_collection"))
    }
    @Test fun unhealthyAndIncompatibleEndpointsAreExcluded() {
        fun endpoint(tag: String, status: Int, parameter: String) = JSONObject().put("tag", tag).put("status", status).put("supported_parameters", JSONArray().put(parameter))
        val rows = JSONArray().put(endpoint("meta", 0, "max_tokens")).put(endpoint("down", -2, "max_tokens")).put(endpoint("incompatible", 0, "temperature"))
        assertEquals(listOf("meta"), ModelEndpointCatalog.eligibleTags(rows))
    }
    @Test fun changingAccountInvalidatesSuccessfulQualification() {
        val cache = InMemoryModelQualificationCache()
        cache.bindAccount("first"); cache.markQualified(ModelRegistry.MUSE_SPARK_1_3_CONTRIBUTOR)
        cache.bindAccount("second")
        assertFalse(cache.isQualified(ModelRegistry.MUSE_SPARK_1_3_CONTRIBUTOR))
    }
}
