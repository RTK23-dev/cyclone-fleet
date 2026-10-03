package com.cyclone.mobile.secrets

import android.content.Context

/**
 * Narrow phone-owned seam for Agent-001 gateway/task-state integration.
 *
 * This facade exposes only metadata/presence plus a card request. There is intentionally no
 * plaintext getter and no method that accepts a secret from Gateway/PC/Glass.
 */
object SecretsPhoneFacade {
    fun slotPresence(
        context: Context,
        placeId: String,
        persona: SecretPersona,
    ): Map<String, Boolean> =
        SecretsVaultRuntime.slotCatalog(context)
            .slots(placeId, persona)
            .associate { it.key.slotName to it.present }

    fun requestCard(
        context: Context,
        request: SecretRequestMetadata,
        onResolution: (SecretUseResult) -> Unit = {},
    ) {
        SecretsCardRuntime.request(
            context = context,
            request = request,
            target = null,
            onResolution = onResolution,
        )
    }

    /**
     * Local run-only seam. [target] is observation-scoped and never crosses the V5 wire contract.
     * A successful one-shot fill resolves with taskMayResume=true; Skip/Cancel remain blocked.
     */
    fun requestForRun(
        context: Context,
        request: SecretRequestMetadata,
        target: SecretFillTarget,
        onResolution: (SecretUseResult) -> Unit,
    ) {
        SecretsCardRuntime.request(
            context = context,
            request = request,
            target = target,
            onResolution = onResolution,
        )
    }
}
