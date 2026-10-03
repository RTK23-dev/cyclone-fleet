package com.cyclone.mobile.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayTrustV33FixtureTest {
    private fun fixture(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("gateway/v33/android_trust_protocol_fixture.json")
            ?: error("V3.3 trust fixture is missing")
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    @Test
    fun frozenTrustAndSessionSignaturesVerifyAcrossImplementations() {
        val fixture = fixture()
        val pc = fixture.getJSONObject("pc")
        val phone = fixture.getJSONObject("phone")
        val trustChallenge = fixture.getJSONObject("trustChallenge")
        val trustReceipt = fixture.getJSONObject("trustReceipt")
        val sessionChallenge = fixture.getJSONObject("sessionChallenge")
        val sessionReceipt = fixture.getJSONObject("sessionReceipt")

        assertEquals(GatewayTrustProtocolV33.VERSION, fixture.getString("protocolVersion"))
        assertEquals(
            pc.getString("pcId"),
            GatewayTrustCrypto.publicKeyId(pc.getString("publicKey")),
        )
        assertEquals(
            phone.getString("phoneId"),
            GatewayTrustCrypto.publicKeyId(phone.getString("publicKey")),
        )
        assertTrue(
            GatewayTrustCrypto.verifyPcSignature(
                pc.getString("publicKey"),
                trustChallenge.getString("transcript"),
                trustChallenge.getString("pcSignature"),
            ),
        )
        assertTrue(
            GatewayTrustCrypto.verifyPcSignature(
                phone.getString("publicKey"),
                trustReceipt.getString("transcript"),
                trustReceipt.getString("phoneSignature"),
            ),
        )
        assertTrue(
            GatewayTrustCrypto.verifyPcSignature(
                pc.getString("publicKey"),
                sessionChallenge.getString("transcript"),
                sessionChallenge.getString("pcSignature"),
            ),
        )
        assertTrue(
            GatewayTrustCrypto.verifyPcSignature(
                phone.getString("publicKey"),
                sessionReceipt.getString("transcript"),
                sessionReceipt.getString("phoneSignature"),
            ),
        )
    }

    @Test
    fun fixtureContainsOnlyTestCredentialMaterial() {
        val text = fixture().toString()
        assertTrue(text.contains("TEST_ONLY_V33_SESSION_TOKEN"))
        assertFalse(text.contains("PRIVATE KEY"))
        assertFalse(text.contains("BEGIN EC PRIVATE"))
        assertFalse(text.contains("apiKey"))
    }
}
