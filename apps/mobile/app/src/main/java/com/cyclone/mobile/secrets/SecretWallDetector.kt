package com.cyclone.mobile.secrets

import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.ai.LoginAutofillPolicy
import com.cyclone.mobile.places.PlaceResolver

/**
 * Pure metadata-only detector that converts an already-classified login wall into a run-scoped
 * Vault request. Chrome requires current address-bar origin evidence.
 */
internal object SecretWallDetector {
    fun passwordForLogin(
        page: AgentPageCard,
        persona: SecretPersona = SecretPersona.LIVE,
    ): SecretWallRequest? {
        if (!LoginAutofillPolicy.isLoginWall(page)) return null
        val place = PlaceResolver.resolveCurrent(page) ?: return null

        val password = LoginAutofillPolicy.form(page).password ?: return null
        if (password.observationId != page.observationId || password.elementId.isBlank()) return null

        val request = runCatching {
            SecretRequestMetadata(
                placeId = place.id,
                persona = persona,
                slot = "password",
                reason = "Login required",
            )
        }.getOrNull() ?: return null

        return SecretWallRequest(
            request = request,
            target = SecretFillTarget(
                elementId = password.elementId,
                observationId = page.observationId,
                sessionId = page.sessionId,
                displayId = page.displayId,
            ),
        )
    }
}
