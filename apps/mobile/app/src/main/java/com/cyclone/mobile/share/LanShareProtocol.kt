package com.cyclone.mobile.share

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wi-Fi screen share, AnyDesk-style: the phone streams its own screen to a PC it already trusts, with no USB/ADB.
 *
 * Handshake (JSON lines, each ≤ 4 KiB):
 *  1. phone → PC  {v, phoneId, phoneNonce, phoneEph}
 *  2. PC → phone  {trustId, pcNonce, pcEph, pcSig}        pcSig = PC identity key over the transcript
 *  3. phone → PC  {ok:true, phoneSig}                     phoneSig = phone identity key over the transcript
 * Both identity keys were exchanged when the owner tapped Allow ("Connect this PC?"); a PC the phone logged out is
 * refused. The ephemeral P-256 keys give a fresh ECDH secret per connection (forward secrecy); HKDF-SHA256 derives one
 * AES-256-GCM key per direction. Records after the handshake: 4-byte big-endian length + sealed payload; the payload's
 * first byte is its type ([TYPE_JPEG] frame or [TYPE_STATUS] JSON). Nothing here can control the phone: the channel
 * carries pixels out, never taps in.
 */
object LanShareProtocol {
    const val VERSION = "cyclone-lan-share-v1"
    const val MAX_LINE_BYTES = 4096
    const val MAX_RECORD_BYTES = 4 * 1024 * 1024
    const val TYPE_JPEG: Byte = 1
    const val TYPE_STATUS: Byte = 2
    private val random = SecureRandom()

    fun nonce(): String = b64(ByteArray(16).also(random::nextBytes))

    fun newEphemeral(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), random)
        generateKeyPair()
    }

    fun publicKeyB64(key: PublicKey): String = b64(key.encoded)

    fun decodePublicKey(value: String): PublicKey {
        val bytes = unb64(value)
        require(bytes.size in 64..512) { "public key size" }
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    fun transcript(phoneId: String, trustId: String, phoneNonce: String, pcNonce: String, phoneEph: String, pcEph: String): String =
        listOf(VERSION, phoneId, trustId, phoneNonce, pcNonce, phoneEph, pcEph).joinToString("\n")

    fun ecdh(privateKey: PrivateKey, peer: PublicKey): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(privateKey)
        doPhase(peer, true)
        generateSecret()
    }

    /** RFC 5869 HKDF with HMAC-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String, length: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            previous = hmac(prk, previous + info.toByteArray(Charsets.UTF_8) + byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - offset)
            System.arraycopy(previous, 0, out, offset, take)
            offset += take
            counter++
        }
        return out
    }

    data class Keys(val phoneToPc: ByteArray, val pcToPhone: ByteArray)

    fun deriveKeys(sharedSecret: ByteArray, transcript: String): Keys {
        val salt = MessageDigest.getInstance("SHA-256").digest(transcript.toByteArray(Charsets.UTF_8))
        return Keys(
            phoneToPc = hkdf(sharedSecret, salt, "$VERSION phone->pc"),
            pcToPhone = hkdf(sharedSecret, salt, "$VERSION pc->phone"),
        )
    }

    /** AES-256-GCM with a 96-bit counter nonce; the counter never repeats within one connection's key. */
    class Sealer(key: ByteArray) {
        private val spec = SecretKeySpec(key, "AES")
        private var counter = 0L
        fun seal(plain: ByteArray): ByteArray {
            val iv = ByteBuffer.allocate(12).putInt(0).putLong(counter++).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(128, iv))
            cipher.updateAAD(VERSION.toByteArray(Charsets.UTF_8))
            return iv + cipher.doFinal(plain)
        }
    }

    class Opener(key: ByteArray) {
        private val spec = SecretKeySpec(key, "AES")
        private var expected = 0L
        fun open(sealed: ByteArray): ByteArray {
            require(sealed.size > 28) { "record too short" }
            val iv = sealed.copyOfRange(0, 12)
            val counter = ByteBuffer.wrap(iv, 4, 8).long
            require(counter == expected) { "out-of-order record" }
            expected++
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(128, iv))
            cipher.updateAAD(VERSION.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }
    }

    fun b64(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    fun unb64(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        doFinal(data)
    }
}
