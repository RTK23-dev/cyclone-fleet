package com.cyclone.mobile.secrets

import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HPKE (RFC 9180), base mode, single-shot open. Plan 33 C2: Glass seals a vault secret to this phone's device key; the
 * phone opens it here. Suite: DHKEM(P-256, HKDF-SHA256) + HKDF-SHA256 + AES-256-GCM (0x0010/0x0001/0x0002). The AEAD
 * is a parameter only so tests can check RFC 9180 appendix A.3 (AES-128-GCM).
 *
 * [decap] computes the ECDH shared x-coordinate with the device's private key (Android Keystore in production), so
 * the private key never leaves the key store.
 */
internal object Hpke {
    const val KEM_P256 = 0x0010
    const val KDF_SHA256 = 0x0001
    const val AEAD_AES128GCM = 0x0001
    const val AEAD_AES256GCM = 0x0002

    class KeySchedule(val sharedSecret: ByteArray, val key: ByteArray, val baseNonce: ByteArray)

    fun open(
        enc: ByteArray, ct: ByteArray, info: ByteArray, aad: ByteArray, recipientPublic: ByteArray,
        decap: (ECPublicKey) -> ByteArray, params: ECParameterSpec, aead: Int = AEAD_AES256GCM,
    ): ByteArray {
        require(enc.size == 65 && enc[0] == 4.toByte()) { "enc is not an uncompressed P-256 point" }
        require(recipientPublic.size == 65) { "recipient key is not an uncompressed P-256 point" }
        val dh = decap(publicKey(enc, params))
        val ks = schedule(dh, enc, recipientPublic, info, aead)
        dh.fill(0)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(ks.key, "AES"), GCMParameterSpec(128, ks.baseNonce))
            cipher.updateAAD(aad)
            cipher.doFinal(ct)
        } finally {
            ks.key.fill(0)
            ks.sharedSecret.fill(0)
        }
    }

    fun schedule(dh: ByteArray, enc: ByteArray, recipient: ByteArray, info: ByteArray, aead: Int): KeySchedule {
        val kemSuite = "KEM".toByteArray() + i2osp(KEM_P256, 2)
        val eaePrk = labeledExtract(kemSuite, ByteArray(0), "eae_prk", dh)
        val shared = labeledExpand(kemSuite, eaePrk, "shared_secret", enc + recipient, 32)
        val suite = "HPKE".toByteArray() + i2osp(KEM_P256, 2) + i2osp(KDF_SHA256, 2) + i2osp(aead, 2)
        val pskIdHash = labeledExtract(suite, ByteArray(0), "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(suite, ByteArray(0), "info_hash", info)
        val context = byteArrayOf(0) + pskIdHash + infoHash
        val secret = labeledExtract(suite, shared, "secret", ByteArray(0))
        val nk = if (aead == AEAD_AES128GCM) 16 else 32
        return KeySchedule(shared, labeledExpand(suite, secret, "key", context, nk), labeledExpand(suite, secret, "base_nonce", context, 12))
    }

    fun publicKey(raw: ByteArray, params: ECParameterSpec): ECPublicKey {
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params)) as ECPublicKey
    }

    fun raw(key: ECPublicKey): ByteArray =
        byteArrayOf(4) + fixed(key.w.affineX.toByteArray()) + fixed(key.w.affineY.toByteArray())

    private fun fixed(bytes: ByteArray): ByteArray = when {
        bytes.size == 32 -> bytes
        bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
        else -> ByteArray(32 - bytes.size) + bytes
    }

    private val HPKE_V1 = "HPKE-v1".toByteArray()

    private fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray): ByteArray =
        hmac(if (salt.isEmpty()) ByteArray(32) else salt, HPKE_V1 + suite + label.toByteArray() + ikm)

    private fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray {
        val full = i2osp(length, 2) + HPKE_V1 + suite + label.toByteArray() + info
        var t = ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        var i = 1
        while (out.size() < length) {
            t = hmac(prk, t + full + byteArrayOf(i.toByte()))
            out.write(t)
            i += 1
        }
        return out.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    private fun i2osp(value: Int, length: Int): ByteArray = ByteArray(length) { i -> (value shr (8 * (length - 1 - i))).toByte() }
}
