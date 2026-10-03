package com.cyclone.mobile.secrets

import android.content.Context
import com.cyclone.mobile.gateway.GatewayV5ContractSources
import com.cyclone.mobile.gateway.GatewayV5SecretRequestMetadata
import com.cyclone.mobile.gateway.GatewayV5SecretsSource

/**
 * Agent-001 integration adapter. The gateway can observe boolean slot presence and request a
 * phone-owned card, but it cannot fetch, accept, or forward a secret value.
 */
internal class VaultGatewayV5SecretsSource(
    private val catalog: SecretSlotCatalog,
    private val requestCard: (SecretRequestMetadata) -> Unit,
) : GatewayV5SecretsSource {
    override fun slots(placeId: String, persona: String): Map<String, Boolean> {
        val parsedPersona = SecretPersona.parse(persona)
        return catalog.slots(placeId, parsedPersona)
            .associate { metadata -> metadata.key.slotName to metadata.present }
    }

    override fun request(metadata: GatewayV5SecretRequestMetadata) {
        requestCard(
            SecretRequestMetadata(
                placeId = metadata.placeId,
                persona = SecretPersona.parse(metadata.persona),
                slot = metadata.slot,
                reason = metadata.reason,
            ),
        )
    }
}

internal object VaultGatewayV5Integration {
    fun install(context: Context) {
        val app = context.applicationContext
        GatewayV5ContractSources.installSecrets(
            VaultGatewayV5SecretsSource(
                catalog = SecretsVaultRuntime.slotCatalog(app),
                requestCard = { request ->
                    SecretsCardRuntime.request(
                        context = app,
                        request = request,
                        target = null,
                    )
                },
            ),
        )
    }
}
