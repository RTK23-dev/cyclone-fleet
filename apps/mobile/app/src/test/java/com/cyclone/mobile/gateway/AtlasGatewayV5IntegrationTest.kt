package com.cyclone.mobile.gateway

import com.cyclone.mobile.applearner.graphv2.AtlasGatewayV5Integration
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.brain.graphv2.AtlasScreenMetadata
import com.cyclone.mobile.brain.graphv2.AtlasStore
import com.cyclone.mobile.brain.graphv2.GraphNodeId
import com.cyclone.mobile.brain.graphv2.PageNode
import com.cyclone.mobile.brain.graphv2.StoreBackedAtlasReadProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class AtlasGatewayV5IntegrationTest {
    @Test
    fun productionAtlasInstallSeamServesDurablePartialGraph() {
        val dir = Files.createTempDirectory("atlas-gateway").toFile()
        val store = AtlasStore(dir.resolve("atlas.json"))
        try {
            val place = store.ensurePackagePlace(
                packageName = "com.example.shop",
                label = "Shop",
                persona = AtlasPersona.LIVE,
            )
            val page = PageNode(
                id = GraphNodeId("page:home"),
                packageName = "com.example.shop",
                identity = "home",
                displayName = "Home",
            )
            store.mutateGraph(place.key) { graph -> graph.registerNode(page) }
            store.upsertScreen(
                place.key,
                AtlasScreenMetadata(
                    screenId = page.id,
                    purpose = "Home",
                    confidence = 0.8,
                    lastObservedAtEpochMillis = 100,
                    layoutX = 0.0,
                    layoutY = 0.0,
                ),
            )

            AtlasGatewayV5Integration.install(StoreBackedAtlasReadProvider(store))

            val atlas = GatewayV5ContractAdapter.dispatch(
                "atlas.get",
                JSONObject()
                    .put("placeId", place.id)
                    .put("persona", "live"),
            )
            assertEquals("partial", atlas.getString("mapStatus"))
            assertEquals(1, atlas.getJSONArray("screens").length())

            val places = GatewayV5ContractAdapter.dispatch("atlas.places", JSONObject())
                .getJSONArray("places")
            assertEquals(1, places.length())
            assertEquals("partial", places.getJSONObject(0).getString("mapStatus"))
            assertEquals(place.id, places.getJSONObject(0).getJSONObject("place").getString("placeId"))
        } finally {
            GatewayV5ContractSources.resetForTests()
            store.close()
            dir.deleteRecursively()
        }
    }
}
