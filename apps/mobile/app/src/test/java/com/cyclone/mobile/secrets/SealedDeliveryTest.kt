package com.cyclone.mobile.secrets

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Plan 33 C2: HPKE against RFC 9180 and the Glass fixture, and the sealed-delivery rules on the phone. */
class SealedDeliveryTest {
    private val params = (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair().public as ECPublicKey).params

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun toHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun privateKey(scalar: String): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, hex(scalar)), params)) as ECPrivateKey

    private fun decapWith(scalar: String): (ECPublicKey) -> ByteArray = { ephemeral ->
        KeyAgreement.getInstance("ECDH").run { init(privateKey(scalar)); doPhase(ephemeral, true); generateSecret() }
    }

    private val fixture: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResource("cyclone-hpke-fixture.json")!!.readText())
    }

    // RFC 9180 appendix A.3.1 (AES-128-GCM variant of the same KEM and KDF).
    private val skRm = "f3ce7fdae57e1a310d87f1ebbde6f328be0a99cdbcadf4d6589cf29de4b8ffd2"
    private val pkRm = "04fe8c19ce0905191ebc298a9245792531f26f0cece2460639e8bc39cb7f706a826a779b4cf969b8a0e539c7f62fb3d30ad6aa8f80e30f1d128aafd68a2ce72ea0"
    private val pkEm = "04a92719c6195d5085104f469a8b9814d5838ff72b60501e2c4466e5e67b325ac98536d7b61a1af4b78e5b7f951c0900be863c403ce65c9bfcb9382657222d18c4"

    @Before fun seams() {
        SealedDelivery.resetForTests()
        SealedDelivery.ownFingerprint = { "FIXTURE" }
        SealedDelivery.ownPublic = { hex(pkRm) }
        SealedDelivery.decap = decapWith(skRm)
        SealedDelivery.params = { params }
        SealedDelivery.clock = { 1_800_000_000_000L }
    }

    @After fun clean() = SealedDelivery.resetForTests()

    @Test fun hpkeOpensRfc9180A31() {
        val info = hex("4f6465206f6e2061204772656369616e2055726e")
        val ct = hex("5ad590bb8baa577f8619db35a36311226a896e7342a6d836d8b7bcd2f20b6c7f9076ac232e3ab2523f39513434")
        val plain = Hpke.open(hex(pkEm), ct, info, hex("436f756e742d30"), hex(pkRm), decapWith(skRm), params, Hpke.AEAD_AES128GCM)
        assertEquals("4265617574792069732074727574682c20747275746820626561757479", toHex(plain))
        val ks = Hpke.schedule(decapWith(skRm)(Hpke.publicKey(hex(pkEm), params)), hex(pkEm), hex(pkRm), info, Hpke.AEAD_AES128GCM)
        assertEquals("c0d26aeab536609a572b07695d933b589dcf363ff9d93c93adea537aeabb8cb8", toHex(ks.sharedSecret))
        assertEquals("868c066ef58aae6dc589b6cfdd18f97e", toHex(ks.key))
        assertEquals("4e0bc5018beba4bf004cca59", toHex(ks.baseNonce))
    }

    @Test fun hpkeOpensWhatGlassSealed() {
        val plain = Hpke.open(hex(fixture.getString("enc")), hex(fixture.getString("ct")), fixture.getString("info").toByteArray(),
            fixture.getString("aad").toByteArray(), hex(fixture.getString("pkRm")), decapWith(skRm), params)
        assertEquals(fixture.getString("plaintext"), String(plain))
        // Change one byte of the bound data and it no longer opens.
        runCatching {
            Hpke.open(hex(fixture.getString("enc")), hex(fixture.getString("ct")), fixture.getString("info").toByteArray(),
                fixture.getString("aad").replace("password", "passwore").toByteArray(), hex(fixture.getString("pkRm")), decapWith(skRm), params)
        }.onSuccess { fail("tampered bound data must not open") }
    }

    private fun envelope(): SealedDelivery.Envelope = SealedDelivery.parse(JSONObject()
        .put("leaseId", "ls_fixture00000000").put("slot", "password")
        .put("enc", Base64.getEncoder().encodeToString(hex(fixture.getString("enc"))))
        .put("ct", Base64.getEncoder().encodeToString(hex(fixture.getString("ct"))))
        .put("aad", fixture.getString("aad")))

    private fun code(block: () -> Unit): String = (runCatching(block).exceptionOrNull() as SealedDelivery.Rejected).code

    @Test fun anEnvelopeOpensOnceForItsTaskAndFillsOnlyOnItsApp() {
        val opened = SealedDelivery.open("tsk_fixture0000", listOf(envelope()))
        SealedDelivery.hold("mabcdefgh", opened)
        assertTrue(SealedDelivery.has("mabcdefgh", "password"))
        assertNull("another app never gets it", SealedDelivery.take("mabcdefgh", "password", "package:com.evil.phish"))
        val taken = SealedDelivery.take("mabcdefgh", "password", "package:com.example.shop")!!
        var seen = ""
        taken.lease.consume { seen = String(it); SecretFillExecution(true, true) }
        assertEquals(fixture.getString("plaintext"), seen)
        assertNull("used once", SealedDelivery.take("mabcdefgh", "password", "package:com.example.shop"))
        SealedDelivery.report(taken.leaseId, "used")
        assertEquals(mapOf("ls_fixture00000000" to "used"), SealedDelivery.outcomes(listOf("ls_fixture00000000")))
        assertEquals("a replayed envelope is refused", "REPLAYED", code { SealedDelivery.open("tsk_fixture0000", listOf(envelope())) })
    }

    @Test fun boundDataIsChecked() {
        assertEquals("AAD_TASK", code { SealedDelivery.open("tsk_other000000", listOf(envelope())) })
        SealedDelivery.ownFingerprint = { "ANOTHER PHONE" }
        assertEquals("NOT_FOR_THIS_PHONE", code { SealedDelivery.open("tsk_fixture0000", listOf(envelope())) })
        SealedDelivery.ownFingerprint = { "FIXTURE" }
        SealedDelivery.clock = { 1_893_456_000_001L }
        assertEquals("EXPIRED", code { SealedDelivery.open("tsk_fixture0000", listOf(envelope())) })
        SealedDelivery.clock = { 1_800_000_000_000L }
        SealedDelivery.decap = decapWith("4995788ef4b9d6132b249ce59a77281493eb39af373d236a1fe415cb0c2d7beb")
        assertEquals("the wrong device key cannot open it", "OPEN_FAILED", code { SealedDelivery.open("tsk_fixture0000", listOf(envelope())) })
    }

    @Test fun unusedValuesAreWipedWhenTheMissionEnds() {
        SealedDelivery.hold("mabcdefgh", SealedDelivery.open("tsk_fixture0000", listOf(envelope())))
        SealedDelivery.finish("mabcdefgh")
        assertFalse(SealedDelivery.has("mabcdefgh", "password"))
        assertEquals("unused", SealedDelivery.outcomes(listOf("ls_fixture00000000")).values.single())
    }

    @Test fun malformedEnvelopesAreRefused() {
        assertEquals("SLOT", code { SealedDelivery.parse(JSONObject().put("leaseId", "ls_fixture00000000").put("slot", "card.number")) })
        assertEquals("ENVELOPE_FIELDS", code { SealedDelivery.parse(JSONObject().put("leaseId", "ls_fixture00000000").put("value", "x")) })
        assertEquals("LEASE_ID", code { SealedDelivery.parse(JSONObject().put("leaseId", "../x")) })
    }

    @Test fun sitesMatchTheirHostAndSubdomainsOverHttpsOnly() {
        assertTrue(SealedDelivery.placeMatches("chrome:https://shop.example.com", "chrome:https://example.com"))
        assertTrue(SealedDelivery.placeMatches("chrome:https://example.com", "chrome:https://example.com"))
        assertFalse(SealedDelivery.placeMatches("chrome:https://example.com.evil.net", "chrome:https://example.com"))
        assertFalse(SealedDelivery.placeMatches("chrome:http://example.com", "chrome:https://example.com"))
        assertFalse(SealedDelivery.placeMatches("chrome:https://notexample.com", "chrome:https://example.com"))
        assertFalse(SealedDelivery.validPlace("chrome:http://example.com"))
    }

    @Test fun ccStartOpensEnvelopesBeforeTheMissionAndRefusesBadOnes() {
        val adapter = com.cyclone.mobile.gateway.GatewayV5CommandAdapter
        adapter.overlayReady = { true }
        adapter.busyFor = { false }
        adapter.humanHasControl = { false }
        adapter.start = { "mabcdefgh" }
        adapter.running = { null }
        adapter.load = { null }
        fun sealed() = org.json.JSONArray().put(JSONObject()
            .put("leaseId", "ls_fixture00000000").put("slot", "password")
            .put("enc", Base64.getEncoder().encodeToString(hex(fixture.getString("enc"))))
            .put("ct", Base64.getEncoder().encodeToString(hex(fixture.getString("ct"))))
            .put("aad", fixture.getString("aad")))
        val wrongTask = runCatching {
            adapter.start(JSONObject().put("goal", "Sign in to the shop").put("taskId", "tsk_other000000").put("sealed", sealed()))
        }.exceptionOrNull() as com.cyclone.mobile.gateway.GatewayProtocolException
        assertEquals("SEALED_REJECTED", wrongTask.code)
        assertFalse(SealedDelivery.has("mabcdefgh", "password"))
        SealedDelivery.resetForTests()
        seams()
        val ack = adapter.start(JSONObject().put("goal", "Sign in to the shop").put("taskId", "tsk_fixture0000").put("sealed", sealed()))
        assertEquals("mabcdefgh", ack.getString("missionId"))
        assertTrue(SealedDelivery.has("mabcdefgh", "password"))
        SealedDelivery.finish("mabcdefgh")
    }

    @Test fun totpMatchesRfc6238() {
        // RFC 6238 SHA-1 seed "12345678901234567890"; the 8-digit reference 94287082 ends in 287082.
        assertArrayEquals("287082".toCharArray(), SealedDelivery.totp("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ".toCharArray(), 59_000))
    }

    // Plan 48 run 4: the PC's Port Hub (Python, ports/seal.py) sealed this code to the RFC key; the phone opens it.
    private val portCode: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResource("cyclone-port-code-fixture.json")!!.readText())
    }

    @Test fun aPortCodeSealedByThePcOpensForItsRunAndPlaceAndFillsBeforeTheAuthenticator() {
        val length = SealedDelivery.openCode("mabcdefgh", "mabcdefgh", "package:com.example.shop", portCode)
        assertEquals(6, length)
        assertTrue(SealedDelivery.has("mabcdefgh", SealedDelivery.CODE_SLOT))
        assertNull("another app never gets it", SealedDelivery.take("mabcdefgh", SealedDelivery.CODE_SLOT, "package:com.evil.phish"))
        val taken = SealedDelivery.take("mabcdefgh", SealedDelivery.CODE_SLOT, "package:com.example.shop")!!
        var seen = ""
        taken.lease.consume { seen = String(it); SecretFillExecution(true, true) }
        assertEquals("482913", seen)
        assertNull("used once", SealedDelivery.take("mabcdefgh", SealedDelivery.CODE_SLOT, "package:com.example.shop"))
        assertEquals("REPLAYED", code { SealedDelivery.openCode("mabcdefgh", "mabcdefgh", "package:com.example.shop", portCode) })
    }

    @Test fun aPortCodeIsBoundToItsRunPlaceKeyAndTime() {
        assertEquals("AAD_RUN", code { SealedDelivery.openCode("mabcdefgh", "mother0000", "package:com.example.shop", portCode) })
        assertEquals("AAD_PLACE", code { SealedDelivery.openCode("mabcdefgh", "mabcdefgh", "package:com.example.other", portCode) })
        SealedDelivery.ownFingerprint = { "ANOTHER PHONE" }
        assertEquals("NOT_FOR_THIS_PHONE", code { SealedDelivery.openCode("mabcdefgh", "mabcdefgh", "package:com.example.shop", portCode) })
        SealedDelivery.ownFingerprint = { "FIXTURE" }
        SealedDelivery.clock = { 1_800_000_300_001L }
        assertEquals("EXPIRED", code { SealedDelivery.openCode("mabcdefgh", "mabcdefgh", "package:com.example.shop", portCode) })
        SealedDelivery.clock = { 1_800_000_000_000L }
        val tampered = JSONObject(portCode.toString()).put("aad", portCode.getString("aad").replace("\"slot\":\"code\"", "\"slot\":\"otp\""))
        assertEquals("AAD_SLOT", code { SealedDelivery.openCode("mabcdefgh", "mabcdefgh", "package:com.example.shop", tampered) })
        SealedDelivery.decap = decapWith("4995788ef4b9d6132b249ce59a77281493eb39af373d236a1fe415cb0c2d7beb")
        assertEquals("OPEN_FAILED", code { SealedDelivery.openCode("mabcdefgh", "mabcdefgh", "package:com.example.shop", portCode) })
        assertFalse(SealedDelivery.has("mabcdefgh", SealedDelivery.CODE_SLOT))
    }
}
