package com.cyclone.mobile.secrets

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * Plan 33 C2: this phone's device key (DK), a P-256 key-agreement pair in Android Keystore (StrongBox when the phone
 * has it). The private key never leaves the key store; the PC gets the public key and both screens show its
 * fingerprint. Sealed vault secrets are addressed to this key and opened with [decap] inside the key store.
 */
internal object DeviceKey {
    private const val ALIAS = "cyclone-device-key-v1"
    private const val STORE = "AndroidKeyStore"

    data class Public(val raw: ByteArray, val fingerprint: String, val strongBox: Boolean)

    @Volatile private var strongBox: Boolean? = null

    @Synchronized
    fun ensure(): Public {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        if (!store.containsAlias(ALIAS)) generate()
        val certificate = store.getCertificate(ALIAS) ?: error("device key missing")
        val raw = Hpke.raw(certificate.publicKey as ECPublicKey)
        return Public(raw, fingerprint(raw), strongBox ?: runCatching { strongBoxBacked(store) }.getOrDefault(false))
    }

    private fun strongBoxBacked(store: KeyStore): Boolean {
        val key = store.getKey(ALIAS, null) as PrivateKey
        val info = java.security.KeyFactory.getInstance(key.algorithm, STORE)
            .getKeySpec(key, android.security.keystore.KeyInfo::class.java)
        return info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
    }

    private fun generate() {
        fun spec(strong: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setIsStrongBoxBacked(strong)
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, STORE)
        strongBox = try {
            generator.initialize(spec(true))
            generator.generateKeyPair()
            selfTest()
            true
        } catch (_: Exception) {
            // Some StrongBox chips make the key but cannot agree with it: fall back to the phone's TEE.
            runCatching { KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(ALIAS) }
            generator.initialize(spec(false))
            generator.generateKeyPair()
            false
        }
    }

    private fun selfTest() {
        val probe = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        check(decap(probe.public as ECPublicKey).size == 32)
    }

    /** The ECDH x-coordinate with [ephemeral], computed inside Android Keystore. */
    fun decap(ephemeral: ECPublicKey): ByteArray {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        val key = store.getKey(ALIAS, null) as PrivateKey
        return KeyAgreement.getInstance("ECDH", STORE).run {
            init(key)
            doPhase(ephemeral, true)
            generateSecret()
        }
    }

    fun params(): java.security.spec.ECParameterSpec {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        return (store.getCertificate(ALIAS).publicKey as ECPublicKey).params
    }

    /** SHA-256 of the raw key, first 16 bytes, grouped by four hex digits. Glass computes the same string. */
    fun fingerprint(raw: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(raw).copyOf(16)
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }
            .chunked(4).joinToString(" ")
}
