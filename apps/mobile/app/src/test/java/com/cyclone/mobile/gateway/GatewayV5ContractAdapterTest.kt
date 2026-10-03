package com.cyclone.mobile.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayV5ContractAdapterTest {
    @Test
    fun run2OperationsAreAdvertisedAndMappingMutationIsNotLegacyReadonly() {
        val expected = setOf(
            "atlas.places",
            "atlas.get",
            "atlas.diff",
            "mapping.start",
            "mapping.pause",
            "mapping.stop",
            "mapping.status",
            "secrets.slots",
            "secrets.request",
        )
        assertTrue(GatewayProtocol.operations.containsAll(expected))
        assertTrue("atlas.diff" in GatewayProtocol.legacyReadOnlyOperations)
        assertTrue("mapping.status" in GatewayProtocol.legacyReadOnlyOperations)
        assertFalse("mapping.start" in GatewayProtocol.legacyReadOnlyOperations)
        assertFalse("mapping.pause" in GatewayProtocol.legacyReadOnlyOperations)
        assertFalse("mapping.stop" in GatewayProtocol.legacyReadOnlyOperations)
    }

    @Test
    fun run1OperationsDispatchEmptyButValidPhoneOwnedShapes() {
        val places = GatewayV5ContractAdapter.dispatch("atlas.places", JSONObject())
        assertEquals(0, places.getJSONArray("places").length())

        val atlas = GatewayV5ContractAdapter.dispatch(
            "atlas.get",
            JSONObject().put("placeId", "package:com.example.app").put("persona", "live"),
        )
        assertEquals("unmapped", atlas.getString("mapStatus"))
        assertEquals(0, atlas.getJSONArray("screens").length())
        assertEquals(0, atlas.getJSONArray("edges").length())

        val slots = GatewayV5ContractAdapter.dispatch(
            "secrets.slots",
            JSONObject().put("placeId", "package:com.example.app").put("persona", "mapping"),
        )
        assertEquals(0, slots.getJSONObject("slots").length())

        val request = GatewayV5ContractAdapter.dispatch(
            "secrets.request",
            JSONObject()
                .put("placeId", "package:com.example.app")
                .put("persona", "live")
                .put("slot", "password")
                .put("reason", "Login required"),
        )
        assertEquals("needs-secret", request.getString("state"))
        assertFalse(request.getJSONObject("request").has("value"))
    }

    @Test
    fun phoneOwnedSourcesCanWireAtlasAndVaultWithoutChangingTheAdapterContract() {
        var requested: GatewayV5SecretRequestMetadata? = null
        GatewayV5ContractSources.installSecrets(object : GatewayV5SecretsSource {
            override fun slots(placeId: String, persona: String): Map<String, Boolean> =
                mapOf("password" to true, "otp" to false)

            override fun request(metadata: GatewayV5SecretRequestMetadata) {
                requested = metadata
            }
        })
        GatewayV5ContractSources.installAtlas(object : GatewayV5AtlasSource {
            override fun places(): List<JSONObject> = listOf(
                JSONObject()
                    .put("place", JSONObject()
                        .put("placeId", "package:com.example.app")
                        .put("kind", "package")
                        .put("label", "Example")
                        .put("packageName", "com.example.app"))
                    .put("persona", "live")
                    .put("mapStatus", "mapped")
                    .put("confidence", 0.8)
                    .put("lastObservedAt", JSONObject.NULL)
                    .put("lastVerifiedAt", JSONObject.NULL),
            )

            override fun get(placeId: String, persona: String): JSONObject = JSONObject()
                .put("place", JSONObject()
                    .put("placeId", placeId)
                    .put("kind", "package")
                    .put("label", "Example")
                    .put("packageName", "com.example.app"))
                .put("persona", persona)
                .put("mapStatus", "mapped")
                .put("screens", org.json.JSONArray())
                .put("edges", org.json.JSONArray())
                .put("capabilities", org.json.JSONArray())
                .put("confidence", 0.8)
                .put("lastObservedAt", JSONObject.NULL)
                .put("lastVerifiedAt", JSONObject.NULL)
        })

        try {
            val places = GatewayV5ContractAdapter.dispatch("atlas.places", JSONObject())
            assertEquals(1, places.getJSONArray("places").length())
            val atlas = GatewayV5ContractAdapter.dispatch(
                "atlas.get",
                JSONObject().put("placeId", "package:com.example.app").put("persona", "live"),
            )
            assertEquals("mapped", atlas.getString("mapStatus"))

            GatewayV5ContractSources.installAtlas(object : GatewayV5AtlasSource {
                override fun places(): List<JSONObject> = emptyList()
                override fun get(placeId: String, persona: String): JSONObject = JSONObject()
                    .put("place", JSONObject()
                        .put("placeId", placeId)
                        .put("kind", "package")
                        .put("label", "Example")
                        .put("packageName", "com.example.app"))
                    .put("persona", persona)
                    .put("mapStatus", "partial")
                    .put("screens", org.json.JSONArray())
                    .put("edges", org.json.JSONArray())
                    .put("capabilities", org.json.JSONArray())
                    .put("confidence", 0.5)
                    .put("lastObservedAt", JSONObject.NULL)
                    .put("lastVerifiedAt", JSONObject.NULL)
            })
            val partial = GatewayV5ContractAdapter.dispatch(
                "atlas.get",
                JSONObject().put("placeId", "package:com.example.app").put("persona", "live"),
            )
            assertEquals("partial", partial.getString("mapStatus"))

            val slots = GatewayV5ContractAdapter.dispatch(
                "secrets.slots",
                JSONObject().put("placeId", "package:com.example.app").put("persona", "live"),
            )
            assertTrue(slots.getJSONObject("slots").getBoolean("password"))
            assertFalse(slots.getJSONObject("slots").getBoolean("otp"))

            GatewayV5ContractAdapter.dispatch(
                "secrets.request",
                JSONObject()
                    .put("placeId", "package:com.example.app")
                    .put("persona", "live")
                    .put("slot", "password")
                    .put("reason", "Login required"),
            )
            assertEquals("password", requested?.slot)
            assertEquals("Login required", requested?.reason)
        } finally {
            GatewayV5ContractSources.resetForTests()
        }
    }

    @Test
    fun atlasSourceCannotExportASecretFactSlot() {
        GatewayV5ContractSources.installAtlas(object : GatewayV5AtlasSource {
            override fun places(): List<JSONObject> = emptyList()
            override fun get(placeId: String, persona: String): JSONObject = JSONObject()
                .put("place", JSONObject()
                    .put("placeId", placeId)
                    .put("kind", "package")
                    .put("label", "Example")
                    .put("packageName", "com.example.app"))
                .put("persona", persona)
                .put("mapStatus", "mapped")
                .put("screens", org.json.JSONArray().put(JSONObject()
                    .put("screenId", "login")
                    .put("purpose", "Sign in")
                    .put("factSlots", org.json.JSONArray().put(JSONObject().put("name", "password")))
                    .put("risk", JSONObject().put("danger", false).put("classes", org.json.JSONArray()))
                    .put("confidence", 0.8)
                    .put("lastObservedAt", JSONObject.NULL)
                    .put("lastVerifiedAt", JSONObject.NULL)
                    .put("layout", JSONObject().put("x", 0).put("y", 0))))
                .put("edges", org.json.JSONArray())
                .put("capabilities", org.json.JSONArray())
                .put("confidence", 0.8)
                .put("lastObservedAt", JSONObject.NULL)
                .put("lastVerifiedAt", JSONObject.NULL)
        })
        try {
            val error = runCatching {
                GatewayV5ContractAdapter.dispatch(
                    "atlas.get",
                    JSONObject().put("placeId", "package:com.example.app").put("persona", "live"),
                )
            }.exceptionOrNull()
            assertTrue(error is GatewayProtocolException)
            assertEquals("SECRET_PAYLOAD_REJECTED", (error as GatewayProtocolException).code)
        } finally {
            GatewayV5ContractSources.resetForTests()
        }
    }

    @Test
    fun secretBearingFieldIsRejectedInsteadOfSanitized() {
        val error = runCatching {
            GatewayV5ContractAdapter.dispatch(
                "secrets.request",
                JSONObject()
                    .put("placeId", "package:com.example.app")
                    .put("persona", "live")
                    .put("slot", "password")
                    .put("reason", "Login required")
                    .put("password", true),
            )
        }.exceptionOrNull()
        assertTrue(error is GatewayProtocolException)
        assertEquals("SECRET_PAYLOAD_REJECTED", (error as GatewayProtocolException).code)
    }
}
