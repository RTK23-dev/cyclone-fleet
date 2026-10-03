package com.cyclone.mobile.secrets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsVaultTest {
    private class MemoryStore : SecretRecordStore {
        val records = linkedMapOf<String, SecretRecord>()

        override fun load(key: SecretSlotKey): SecretRecord? = records[key.storageId()]

        override fun save(record: SecretRecord) {
            records[record.metadata.key.storageId()] = record
        }

        override fun list(placeId: String, persona: SecretPersona): List<SecretRecord> =
            records.values.filter {
                it.metadata.key.placeId == placeId && it.metadata.key.persona == persona
            }
        override fun listAll(): List<SecretRecord> = records.values.toList()
    }

    private class TestSealer : SecretSealer {
        override fun seal(key: SecretSlotKey, secret: CharArray): SealedSecret =
            SealedSecret(
                iv = byteArrayOf(7, 3, 1),
                ciphertext = ByteArray(secret.size) { index ->
                    (secret[index].code xor 0x5a).toByte()
                },
            )

        override fun open(key: SecretSlotKey, sealed: SealedSecret): CharArray =
            CharArray(sealed.ciphertext.size) { index ->
                (sealed.ciphertext[index].toInt() xor 0x5a).toChar()
            }
    }

    private fun generatedSecret(size: Int = 14): CharArray =
        CharArray(size) { index -> ('a'.code + (index * 7) % 26).toChar() }

    private fun target() = SecretFillTarget("semantic:obs:field", "obs")

    @Test
    fun saveThenDeleteChangesPresenceWithoutReturningValue() {
        val store = MemoryStore()
        val vault = SecretsVault(
            store,
            TestSealer(),
            SecretFillExecutor { _, _ -> SecretFillExecution(performed = true, verified = true) },
            clockMs = { 10L },
        )
        val key = SecretSlotKey.of("package:com.example", SecretPersona.LIVE, "password")
        val input = generatedSecret()

        vault.replace(key, input)

        assertTrue(vault.hasSlot(key))
        assertTrue(input.all { it == '\u0000' })
        assertTrue(vault.delete(key))
        assertFalse(vault.hasSlot(key))
        assertEquals(false, vault.metadata(key)?.present)
    }

    @Test
    fun liveAndMappingPersonasNeverCollide() {
        val vault = SecretsVault(
            MemoryStore(),
            TestSealer(),
            SecretFillExecutor { _, _ -> SecretFillExecution(performed = true, verified = true) },
        )
        val live = SecretSlotKey.of(
            "chrome:https://example.test",
            SecretPersona.LIVE,
            "password",
        )
        val mapping = SecretSlotKey.of(
            "chrome:https://example.test",
            SecretPersona.MAPPING,
            "password",
        )

        vault.replace(live, generatedSecret())
        assertTrue(vault.hasSlot(live))
        assertFalse(vault.hasSlot(mapping))

        vault.replace(mapping, generatedSecret(18))
        assertTrue(vault.hasSlot(live))
        assertTrue(vault.hasSlot(mapping))
        assertEquals(1, vault.slots(live.placeId, SecretPersona.LIVE).size)
        assertEquals(1, vault.slots(mapping.placeId, SecretPersona.MAPPING).size)
    }

    @Test
    fun strongBoxUnavailableFallsBackWithoutDisablingVault() {
        var fallbackCalls = 0
        val selected = StrongBoxKeySelector(
            strongBox = { throw VaultStrongBoxUnavailable() },
            fallback = {
                fallbackCalls += 1
                "keystore-fallback"
            },
        ).select()

        assertEquals("keystore-fallback", selected.key)
        assertFalse(selected.strongBoxBacked)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun leaseIsOneShotAndZerosReferenceAfterSuccess() {
        val source = generatedSecret()
        var boundaryReference: CharArray? = null
        val lease = OneShotSecretLease(source)

        val first = lease.consume {
            boundaryReference = it
            SecretFillExecution(performed = true, verified = true)
        }

        assertNotNull(first)
        assertTrue(lease.revokedForTest())
        assertTrue(boundaryReference!!.all { it == '\u0000' })
        assertNull(lease.consume { SecretFillExecution(true, true) })
    }

    @Test
    fun leaseRevokesAndZerosAfterFailure() {
        var boundaryReference: CharArray? = null
        val lease = OneShotSecretLease(generatedSecret())

        runCatching {
            lease.consume {
                boundaryReference = it
                error("synthetic fill failure")
            }
        }

        assertTrue(lease.revokedForTest())
        assertTrue(boundaryReference!!.all { it == '\u0000' })
        assertNull(lease.consume { SecretFillExecution(true, true) })
    }

    @Test
    fun diagnosticProjectionCannotContainPlaintext() {
        val store = MemoryStore()
        val vault = SecretsVault(
            store,
            TestSealer(),
            SecretFillExecutor { _, _ -> SecretFillExecution(performed = true, verified = true) },
        )
        val key = SecretSlotKey.of("package:com.example", SecretPersona.LIVE, "password")
        val input = generatedSecret(19)
        val probe = input.concatToString()
        vault.replace(key, input)

        val result = vault.useOnce(key, target())
        val diagnostic = SecretDiagnostics.line(key, result)

        assertEquals(SecretUseStatus.FILLED, result.status)
        assertFalse(diagnostic.contains(probe))
        assertFalse(store.records.values.joinToString().contains(probe))
    }
}
