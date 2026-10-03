package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.brain.graphv2.AtlasGraphIds
import com.cyclone.mobile.gateway.GatewayObservation
import com.cyclone.mobile.mapping.crawl.ExistingGateMappingSafetyPort
import com.cyclone.mobile.mapping.crawl.MappingDanger
import com.cyclone.mobile.mapping.crawl.MappingStructuralProjection
import com.cyclone.mobile.mapping.crawl.fromGateway
import com.cyclone.mobile.mapping.run.AtlasStoreMappingPort

/** Recompute mapper door digests from this capture. Never turn a saved label/bounds into a tap. */
internal object LiveAtlasTargets {
    fun from(observation: GatewayObservation): List<AtlasNavigator.Target> {
        val projection = MappingStructuralProjection.fromGateway(observation)
        val safety = ExistingGateMappingSafetyPort()
        return projection.doors.map { door ->
            val keys = mutableSetOf(AtlasStoreMappingPort.doorDigest(door.key))
            observation.page.controls.filter { it.key == door.elementId ||
                it.selector.optString("elementId") == door.elementId }.forEach {
                keys += AtlasGraphIds.selectorDigest(it.selector.toString())
            }
            AtlasNavigator.Target(door.elementId, keys,
                safety.classify(projection, door) == MappingDanger.NONE)
        }
    }
}
