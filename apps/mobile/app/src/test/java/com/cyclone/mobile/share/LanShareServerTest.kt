package com.cyclone.mobile.share

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyPair
import java.security.Signature

class LanShareServerTest {
    private fun ecKeyPair(): KeyPair = LanShareProtocol.newEphemeral()

    private fun sign(pair: KeyPair, text: String): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(text.toByteArray(Charsets.UTF_8))
        LanShareProtocol.b64(sign())
    }

    private fun verify(publicKey: String, text: String, sig: String): Boolean = Signature.getInstance("SHA256withECDSA").run {
        initVerify(LanShareProtocol.decodePublicKey(publicKey))
        update(text.toByteArray(Charsets.UTF_8))
        verify(LanShareProtocol.unb64(sig))
    }

    private val phoneKey = ecKeyPair()
    private val pcKey = ecKeyPair()
    private val phone = object : LanShareServer.PhoneSigner {
        override val phoneId = "phone-abc"
        override fun sign(transcript: String) = sign(phoneKey, transcript)
    }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun server(trusted: Boolean): LanShareServer = LanShareServer(
        trust = { id -> if (trusted && id == "trust-1") LanShareProtocol.publicKeyB64(pcKey.public) else null },
        identity = phone,
        frames = { after -> if (after < 1) LanShareServer.Frame(1, jpeg, 4, 8) else null },
        bindAddress = InetAddress.getLoopbackAddress(),
        preferredPort = 0,
        frameIntervalMs = 10,
    )

    private fun line(input: BufferedInputStream): JSONObject {
        val out = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            out.append(c.toChar())
        }
        return JSONObject(out.toString())
    }

    /** Plays the PC: verifies the phone, derives keys, returns the first frame's payload. */
    private fun connectAsPc(port: Int, trustId: String = "trust-1", tamper: Boolean = false): Pair<JSONObject, ByteArray?> {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val hello = line(input)
            val ephemeral = ecKeyPair()
            val pcNonce = LanShareProtocol.nonce()
            val pcEph = LanShareProtocol.publicKeyB64(ephemeral.public)
            val transcript = LanShareProtocol.transcript(hello.getString("phoneId"), trustId, hello.getString("phoneNonce"), pcNonce, hello.getString("phoneEph"), pcEph)
            val signature = sign(pcKey, if (tamper) transcript + "x" else transcript)
            socket.getOutputStream().write((JSONObject().put("trustId", trustId).put("pcNonce", pcNonce).put("pcEph", pcEph).put("pcSig", signature).toString() + "\n").toByteArray())
            val reply = line(input)
            if (!reply.optBoolean("ok")) return reply to null
            assertTrue("the phone proves who it is", verify(LanShareProtocol.publicKeyB64(phoneKey.public), transcript, reply.getString("phoneSig")))
            val keys = LanShareProtocol.deriveKeys(LanShareProtocol.ecdh(ephemeral.private, LanShareProtocol.decodePublicKey(hello.getString("phoneEph"))), transcript)
            val data = DataInputStream(input)
            val sealed = ByteArray(data.readInt()).also { data.readFully(it) }
            return reply to LanShareProtocol.Opener(keys.phoneToPc).open(sealed)
        }
    }

    @Test
    fun aTrustedPcGetsEncryptedFramesAndVerifiesThePhone() {
        val share = server(trusted = true)
        val port = share.start()
        try {
            val (reply, payload) = connectAsPc(port)
            assertTrue(reply.getBoolean("ok"))
            assertEquals(LanShareProtocol.TYPE_JPEG, payload!![0])
            assertArrayEquals(jpeg, payload.copyOfRange(1, payload.size))
        } finally {
            share.stop()
        }
        assertFalse(share.isRunning)
    }

    @Test
    fun unknownOrLoggedOutPcsAndForgedSignaturesAreRefused() {
        val share = server(trusted = false)
        val port = share.start()
        try {
            assertEquals("NOT_TRUSTED", connectAsPc(port).first.getString("code"))
        } finally {
            share.stop()
        }
        val trusted = server(trusted = true)
        val port2 = trusted.start()
        try {
            assertEquals("BAD_SIGNATURE", connectAsPc(port2, tamper = true).first.getString("code"))
            assertEquals("NOT_TRUSTED", connectAsPc(port2, trustId = "trust-other").first.getString("code"))
        } finally {
            trusted.stop()
        }
    }

    @Test
    fun sealedRecordsCannotBeReplayedOrReordered() {
        val key = LanShareProtocol.hkdf(ByteArray(32) { 1 }, ByteArray(32) { 2 }, "test")
        val sealer = LanShareProtocol.Sealer(key)
        val first = sealer.seal(byteArrayOf(1))
        val second = sealer.seal(byteArrayOf(2))
        val opener = LanShareProtocol.Opener(key)
        assertArrayEquals(byteArrayOf(1), opener.open(first))
        assertTrue(runCatching { opener.open(first) }.isFailure)
        assertArrayEquals(byteArrayOf(2), LanShareProtocol.Opener(key).let { fresh -> fresh.open(first); fresh.open(second) })
    }

    @Test
    fun hkdfMatchesTheSharedVector() {
        // Same vector is checked by the PC side (apps/device-gateway/tests/test_lan_share.py).
        val out = LanShareProtocol.hkdf("cyclone".toByteArray(), "salt".toByteArray(), "${LanShareProtocol.VERSION} phone->pc")
        assertEquals(HKDF_VECTOR, LanShareProtocol.b64(out))
    }

    companion object {
        const val HKDF_VECTOR = "m8as1vvqDP8l5-qa4kxJXIfbBQWBtkEUzw4OXxMYlbE"
    }
}
