package com.cyclone.mobile.runtime.workspaces

import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ProfileTransferCipherTest {
    @Test fun credentialRoundTripRequiresDestinationPrivateKey() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val destination = generator.generateKeyPair()
        val wrong = generator.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(destination.public.encoded)
        val secret = "test-credential".toByteArray()
        val encrypted = ProfileTransferCipher.encrypt(publicKey, secret)
        assertArrayEquals(secret, ProfileTransferCipher.decrypt(destination.private, encrypted))
        assertTrue(runCatching { ProfileTransferCipher.decrypt(wrong.private, encrypted) }.isFailure)
        assertNotEquals(encrypted, ProfileTransferCipher.encrypt(publicKey, secret))
    }
    @Test fun corruptCredentialIsRejected() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val bytes = Base64.getDecoder().decode(ProfileTransferCipher.encrypt(publicKey, "test".toByteArray()))
        bytes[12] = (bytes[12].toInt() xor 1).toByte()
        assertTrue(runCatching { ProfileTransferCipher.decrypt(pair.private, Base64.getEncoder().encodeToString(bytes)) }.isFailure)
    }
}
