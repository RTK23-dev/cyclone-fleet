package com.cyclone.mobile.runtime.workspaces

import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Credentials are encrypted for the destination's non-exportable Android Keystore key. */
internal object ProfileTransferCipher {
    private val parameters = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)
    fun encrypt(publicKey: String, plaintext: ByteArray): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, parameters)
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext))
    }
    fun decrypt(privateKey: PrivateKey, ciphertext: String): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey, parameters)
        return cipher.doFinal(Base64.getDecoder().decode(ciphertext))
    }

    /**
     * Plan 40 P2: a carry bundle is too big for RSA alone. It is sealed with a fresh AES-256-GCM key, and only that
     * key is wrapped (RSA-OAEP) for the destination's Keystore key. [context] (the destination and the nonce) is bound
     * as associated data, so a bundle can't be replayed into another profile or another switch.
     */
    fun seal(publicKey: String, plaintext: ByteArray, context: String): JSONObject {
        val aes = ByteArray(32).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
            val sealed = cipher.doFinal(plaintext)
            return JSONObject().put("v", 1).put("key", encrypt(publicKey, aes))
                .put("iv", Base64.getEncoder().encodeToString(iv)).put("data", Base64.getEncoder().encodeToString(sealed))
        } finally { aes.fill(0) }
    }

    fun open(privateKey: PrivateKey, sealed: JSONObject, context: String): ByteArray {
        require(sealed.optInt("v") == 1) { "Unknown carry bundle." }
        val aes = decrypt(privateKey, sealed.getString("key"))
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, Base64.getDecoder().decode(sealed.getString("iv"))))
            cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(Base64.getDecoder().decode(sealed.getString("data")))
        } finally { aes.fill(0) }
    }
}
