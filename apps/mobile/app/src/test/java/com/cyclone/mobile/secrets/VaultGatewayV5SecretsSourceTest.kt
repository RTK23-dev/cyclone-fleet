package com.cyclone.mobile.secrets

import com.cyclone.mobile.gateway.GatewayV5ContractAdapter
import com.cyclone.mobile.gateway.GatewayV5ContractSources
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultGatewayV5SecretsSourceTest {
    private class FakeCatalog(
        private val records: List<SecretSlotMetadata>,
    ) : SecretSlotCatalog {
        override fun metadata(key: SecretSlotKey): SecretSlotMetadata? =
            records.firstOrNull { it.key == key }

        override fun slots(placeId: String, persona: SecretPersona): List<SecretSlotMetadata> =
            records.filter { it.key.placeId == placeId && it.key.persona == persona }

        override fun allSlots(): List<SecretSlotMetadata> = records
    }

    @Test
    fun agent001GatewayReadsPresenceOnlyAndForwardsSafeRequestMetadata() {
        val place = "package:com.example.app"
        val password = SecretSlotMetadata(
            SecretSlotKey.of(place, SecretPersona.LIVE, "password"),
            present = true,
            createdAtMs = 10L,
            updatedAtMs = 20L,
        )
        val otp = SecretSlotMetadata(
            SecretSlotKey.of(place, SecretPersona.LIVE, "otp"),
            present = false,
            createdAtMs = 10L,
            updatedAtMs = 20L,
        )
        var requested: SecretRequestMetadata? = null
        val source = VaultGatewayV5SecretsSource(FakeCatalog(listOf(password, otp))) {
            requested = it
        }

        GatewayV5ContractSources.installSecrets(source)
        try {
            val slots = GatewayV5ContractAdapter.dispatch(
                "secrets.slots",
                JSONObject().put("placeId", place).put("persona", "live"),
            )
            val values = slots.getJSONObject("slots")
            assertEquals(true, values.getBoolean("password"))
            assertEquals(false, values.getBoolean("otp"))
            assertEquals(2, values.length())

            val acknowledgement = GatewayV5ContractAdapter.dispatch(
                "secrets.request",
                JSONObject()
                    .put("placeId", place)
                    .put("persona", "live")
                    .put("slot", "password")
                    .put("reason", "Login required"),
            )
            assertEquals("needs-secret", acknowledgement.getString("state"))
            assertEquals(place, requested?.placeId)
            assertEquals(SecretPersona.LIVE, requested?.persona)
            assertEquals("password", requested?.slot)
            assertEquals("Login required", requested?.reason)
            assertFalse(acknowledgement.toString().contains("\"value\""))
            assertTrue(acknowledgement.getJSONObject("request").keys().asSequence().toSet() ==
                setOf("placeId", "persona", "slot", "reason"))
        } finally {
            GatewayV5ContractSources.resetForTests()
        }
    }

    @Test
    fun personasRemainSeparateThroughAgent001Source() {
        val place = "package:com.example.app"
        val source = VaultGatewayV5SecretsSource(
            FakeCatalog(
                listOf(
                    SecretSlotMetadata(
                        SecretSlotKey.of(place, SecretPersona.LIVE, "password"),
                        true, 1L, 1L,
                    ),
                    SecretSlotMetadata(
                        SecretSlotKey.of(place, SecretPersona.MAPPING, "password"),
                        false, 1L, 1L,
                    ),
                ),
            ),
        ) {}

        assertEquals(true, source.slots(place, "live").getValue("password"))
        assertEquals(false, source.slots(place, "mapping").getValue("password"))
    }
}
