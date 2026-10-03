package com.cyclone.mobile.secrets

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SecretsCardUiState(
    val request: SecretRequestMetadata,
    val hasStoredSlot: Boolean,
    val canUseStored: Boolean,
    val visible: Boolean = true,
    val busy: Boolean = false,
    val errorCode: String? = null,
    val lastStatus: SecretUseStatus? = null,
)

internal class SecretsCardController(
    private val vault: SecretsVault,
    private val stateBacking: MutableStateFlow<SecretsCardUiState?> = MutableStateFlow(null),
) {
    private data class Session(
        val request: SecretRequestMetadata,
        val target: SecretFillTarget?,
        val onResolution: (SecretUseResult) -> Unit,
    )

    private var session: Session? = null
    val state: StateFlow<SecretsCardUiState?> = stateBacking.asStateFlow()

    @Synchronized
    fun request(
        request: SecretRequestMetadata,
        target: SecretFillTarget?,
        onResolution: (SecretUseResult) -> Unit,
    ) {
        val key = request.key()
        vault.declare(key)
        session = Session(request, target, onResolution)
        stateBacking.value = SecretsCardUiState(
            request = request,
            hasStoredSlot = vault.hasSlot(key),
            canUseStored = target != null,
        )
    }

    @Synchronized
    fun submit(secret: CharArray) {
        val active = session
        if (active == null) {
            secret.fill('\u0000')
            return
        }
        stateBacking.value = stateBacking.value?.copy(busy = true, errorCode = null)
        val result = try {
            vault.replace(active.request.key(), secret)
            active.target?.let { vault.useOnce(active.request.key(), it) }
                ?: SecretUseResult(SecretUseStatus.STORED, verified = true)
        } catch (_: Exception) {
            secret.fill('\u0000')
            SecretUseResult(SecretUseStatus.FAILED, errorCode = "VAULT_WRITE_FAILED")
        }
        applyResult(active, result)
    }

    @Synchronized
    fun useStored() {
        val active = session ?: return
        stateBacking.value = stateBacking.value?.copy(busy = true, errorCode = null)
        val result = active.target?.let { vault.useOnce(active.request.key(), it) }
            ?: if (vault.hasSlot(active.request.key())) {
                SecretUseResult(SecretUseStatus.STORED, verified = true)
            } else {
                SecretUseResult(SecretUseStatus.MISSING, errorCode = "SLOT_MISSING")
            }
        applyResult(active, result)
    }

    @Synchronized
    fun skip() {
        completeHumanDismissal(SecretUseStatus.SKIPPED)
    }

    @Synchronized
    fun cancel() {
        completeHumanDismissal(SecretUseStatus.CANCELLED)
    }

    private fun completeHumanDismissal(status: SecretUseStatus) {
        val active = session ?: return
        val result = SecretUseResult(status)
        stateBacking.value = stateBacking.value?.copy(
            visible = false,
            busy = false,
            errorCode = null,
            lastStatus = status,
        )
        // Keep only safe request/target metadata so a still-waiting task can reopen the card.
        // No plaintext value is retained here.
        active.onResolution(result)
    }

    @Synchronized
    fun reopen(): Boolean {
        if (session == null) return false
        val current = stateBacking.value ?: return false
        if (current.visible) return true
        stateBacking.value = current.copy(
            visible = true,
            busy = false,
            errorCode = null,
        )
        return true
    }

    private fun applyResult(active: Session, result: SecretUseResult) {
        val current = stateBacking.value ?: return
        if (result.taskMayResume || result.status == SecretUseStatus.STORED) {
            stateBacking.value = current.copy(
                visible = false,
                busy = false,
                errorCode = null,
                lastStatus = result.status,
                hasStoredSlot = vault.hasSlot(active.request.key()),
            )
            session = null
            active.onResolution(result)
        } else {
            stateBacking.value = current.copy(
                visible = true,
                busy = false,
                errorCode = result.errorCode ?: "SECRET_NOT_READY",
                lastStatus = result.status,
                hasStoredSlot = vault.hasSlot(active.request.key()),
            )
        }
    }
}

object SecretsCardRuntime {
    private val stateBacking = MutableStateFlow<SecretsCardUiState?>(null)
    val state: StateFlow<SecretsCardUiState?> = stateBacking.asStateFlow()

    @Volatile private var controller: SecretsCardController? = null

    fun request(
        context: Context,
        request: SecretRequestMetadata,
        target: SecretFillTarget? = null,
        onResolution: (SecretUseResult) -> Unit = {},
    ) {
        controller(context).request(request, target, onResolution)
        com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.refreshExternalSurface()
    }

    internal fun submit(secret: CharArray) {
        val current = controller
        if (current == null) {
            secret.fill('\u0000')
            return
        }
        current.submit(secret)
    }

    internal fun useStored() {
        controller?.useStored()
    }

    fun reopenWaiting(): Boolean {
        val reopened = controller?.reopen() == true
        if (reopened) com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.refreshExternalSurface()
        return reopened
    }

    internal fun skip() {
        controller?.skip()
    }

    internal fun cancel() {
        controller?.cancel()
    }

    private fun controller(context: Context): SecretsCardController {
        controller?.let { return it }
        synchronized(this) {
            controller?.let { return it }
            return SecretsCardController(
                vault = SecretsVaultRuntime.vault(context.applicationContext),
                stateBacking = stateBacking,
            ).also { controller = it }
        }
    }

    internal fun resetForTests() {
        controller = null
        stateBacking.value = null
    }
}

internal object SecretsCardCopy {
    fun title(state: SecretsCardUiState): String = "Secret needed"

    fun slotLine(state: SecretsCardUiState): String =
        state.request.slot + " for " + state.request.placeId

    fun personaLine(state: SecretsCardUiState): String =
        "Profile: " + state.request.persona.wireValue

    fun reasonLine(state: SecretsCardUiState): String = state.request.reason

    fun errorLine(state: SecretsCardUiState): String? = when (state.errorCode) {
        null -> null
        "SLOT_MISSING" -> "No saved value is available for this slot."
        "STALE_OBSERVATION", "STALE_ELEMENT" -> "The field changed. Cyclone needs a fresh target."
        "FILL_NOT_VERIFIED", "ASSERTION_FAILED" -> "Cyclone could not verify that the field was filled."
        "VAULT_OPEN_FAILED" -> "The saved slot could not be opened."
        "VAULT_WRITE_FAILED" -> "The value could not be saved."
        else -> "The secret is still needed."
    }

    fun allRenderedStrings(state: SecretsCardUiState): List<String> =
        listOfNotNull(title(state), slotLine(state), personaLine(state), reasonLine(state), errorLine(state))
}
