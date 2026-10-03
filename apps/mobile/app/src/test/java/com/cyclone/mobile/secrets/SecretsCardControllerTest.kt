package com.cyclone.mobile.secrets

import com.cyclone.mobile.ui.overlay.OverlayGesturePassthrough
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsCardControllerTest {
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
                byteArrayOf(1),
                ByteArray(secret.size) { index -> (secret[index].code xor 0x33).toByte() },
            )

        override fun open(key: SecretSlotKey, sealed: SealedSecret): CharArray =
            CharArray(sealed.ciphertext.size) { index ->
                (sealed.ciphertext[index].toInt() xor 0x33).toChar()
            }
    }

    private fun generatedSecret(size: Int = 17): CharArray =
        CharArray(size) { index -> ('A'.code + (index * 5) % 26).toChar() }

    private fun request() = SecretRequestMetadata(
        placeId = "package:com.example",
        persona = SecretPersona.LIVE,
        slot = "password",
        reason = "Sign-in needs this saved slot",
    )

    private fun vault(
        store: MemoryStore = MemoryStore(),
        fill: SecretFillExecutor = SecretFillExecutor { _, _ ->
            SecretFillExecution(performed = true, verified = true)
        },
    ) = SecretsVault(store, TestSealer(), fill)

    @Test
    fun submitAcceptsCardInputAndNeverStoresItInUiState() {
        val controller = SecretsCardController(vault())
        var resolution: SecretUseResult? = null
        controller.request(
            request(),
            SecretFillTarget("semantic:obs:password", "obs"),
        ) { resolution = it }
        val input = generatedSecret()
        val probe = input.concatToString()

        controller.submit(input)

        assertEquals(SecretUseStatus.FILLED, resolution?.status)
        assertFalse(controller.state.value?.visible ?: true)
        assertTrue(input.all { it == '\u0000' })
        val rendered = SecretsCardCopy.allRenderedStrings(controller.state.value!!).joinToString("|")
        assertFalse(rendered.contains(probe))
    }

    @Test
    fun metadataOnlyRequestNeverOffersSavedFillAction() {
        val store = MemoryStore()
        val vault = vault(store)
        val key = request().key()
        vault.replace(key, generatedSecret())
        val controller = SecretsCardController(vault)

        controller.request(request(), null) {}

        assertTrue(controller.state.value!!.hasStoredSlot)
        assertFalse(controller.state.value!!.canUseStored)
    }

    @Test
    fun storeOnlyRequestClosesCardButCannotResumeTask() {
        val controller = SecretsCardController(vault())
        var resolution: SecretUseResult? = null
        controller.request(request(), null) { resolution = it }

        controller.submit(generatedSecret())

        assertEquals(SecretUseStatus.STORED, resolution?.status)
        assertFalse(resolution?.taskMayResume ?: true)
        assertFalse(controller.state.value?.visible ?: true)
    }

    @Test
    fun skipAndCancelAreNonTerminalAndDoNotPermitResume() {
        val controller = SecretsCardController(vault())
        val results = mutableListOf<SecretUseResult>()

        controller.request(request(), null, results::add)
        controller.skip()
        controller.request(request(), null, results::add)
        controller.cancel()

        assertEquals(listOf(SecretUseStatus.SKIPPED, SecretUseStatus.CANCELLED), results.map { it.status })
        assertTrue(results.all { !it.taskTerminal })
        assertTrue(results.all { !it.taskMayResume })
    }

    @Test
    fun skippedRunCardCanReopenAndLaterResumeAfterVerifiedFill() {
        val controller = SecretsCardController(vault())
        val results = mutableListOf<SecretUseResult>()
        controller.request(
            request(),
            SecretFillTarget("semantic:obs:password", "obs"),
            results::add,
        )

        controller.skip()
        assertFalse(controller.state.value!!.visible)
        assertEquals(SecretUseStatus.SKIPPED, results.single().status)
        assertTrue(controller.reopen())
        assertTrue(controller.state.value!!.visible)

        controller.submit(generatedSecret())
        assertEquals(
            listOf(SecretUseStatus.SKIPPED, SecretUseStatus.FILLED),
            results.map { it.status },
        )
        assertFalse(controller.state.value!!.visible)
        assertTrue(results.last().taskMayResume)
    }

    @Test
    fun storedValueNeverAppearsInCardOrSettingsPresentation() {
        val store = MemoryStore()
        val vault = vault(store)
        val key = request().key()
        val secret = generatedSecret(23)
        val probe = secret.concatToString()
        vault.replace(key, secret)
        val controller = SecretsCardController(vault)

        controller.request(request(), null) {}
        val cardText = SecretsCardCopy.allRenderedStrings(controller.state.value!!).joinToString("|")
        val settingsText = VaultSettingsPresenter.rows(vault.slots(key.placeId, key.persona))
            .flatMap(VaultSlotRowUi::renderedStrings)
            .joinToString("|")

        assertTrue(controller.state.value!!.hasStoredSlot)
        assertFalse(cardText.contains(probe))
        assertFalse(settingsText.contains(probe))
    }

    @Test
    fun activeCardSharesHostGestureYieldWithoutLosingCardState() {
        OverlayGesturePassthrough.resetForTests()
        val controller = SecretsCardController(vault())
        controller.request(request(), null) {}

        val transitions = mutableListOf<Boolean>()
        OverlayGesturePassthrough.bind(transitions::add)
        try {
            assertTrue(controller.state.value?.visible == true)
            assertFalse(OverlayGesturePassthrough.active())

            OverlayGesturePassthrough.withHostPassthrough {
                assertTrue(OverlayGesturePassthrough.active())
                assertTrue(controller.state.value?.visible == true)
            }

            assertFalse(OverlayGesturePassthrough.active())
            assertEquals(listOf(true, false), transitions)
            assertTrue(controller.state.value?.visible == true)
        } finally {
            OverlayGesturePassthrough.unbind()
            OverlayGesturePassthrough.resetForTests()
        }
    }
}
