package com.cyclone.mobile.applearner.graphv2

import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.brain.graphv2.AtlasReadProvider
import com.cyclone.mobile.gateway.GatewayV5AtlasSource
import com.cyclone.mobile.gateway.GatewayV5ContractSources
import org.json.JSONObject

/**
 * Production bridge from the durable phone-owned Atlas to Agent 001's V5 gateway seam.
 *
 * This adapter exposes only the schema-shaped, already-redacted AtlasReadProvider output.
 * PC/Glass never become Atlas authority and no raw graph/database object crosses the seam.
 */
internal class AtlasGatewayV5Source(
    private val provider: AtlasReadProvider,
) : GatewayV5AtlasSource {
    override fun places(): List<JSONObject> {
        val array = provider.places().getJSONArray("places")
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(JSONObject(it.toString())) }
            }
        }
    }

    override fun get(placeId: String, persona: String): JSONObject? =
        runCatching { AtlasPersona.fromWire(persona) }
            .getOrNull()
            ?.let { provider.get(placeId, it) }
            ?.let { JSONObject(it.toString()) }
}

internal object AtlasGatewayV5Integration {
    fun install(provider: AtlasReadProvider) {
        GatewayV5ContractSources.installAtlas(AtlasGatewayV5Source(provider))
    }
}
