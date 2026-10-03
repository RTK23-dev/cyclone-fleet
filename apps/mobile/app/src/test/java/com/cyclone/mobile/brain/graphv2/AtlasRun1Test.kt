package com.cyclone.mobile.brain.graphv2

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.AppGraphSnapshot
import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.LearnedApp
import com.cyclone.mobile.applearner.LearnedScreen
import com.cyclone.mobile.applearner.LearnedTransition
import com.cyclone.mobile.applearner.ScreenRecognition
import com.cyclone.mobile.applearner.graphv2.AtlasLegacyImporter
import com.cyclone.mobile.applearner.graphv2.FollowMeAtlasPromoter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class AtlasRun1Test {
    @Test
    fun followMeObservationCreatesAndUpdatesAtlasScreen() {
        withStore { store ->
            val promoter = FollowMeAtlasPromoter(store)
            promoter.observeScreen(app(), screen("home", 100), listOf(action("home")))

            val snapshot = store.snapshot(key(AtlasPersona.LIVE))
            assertEquals(1, snapshot?.screens?.size)
            assertEquals(100L, snapshot?.screens?.single()?.lastObservedAtEpochMillis)
        }
    }

    @Test
    fun demonstratedTransitionBecomesAtlasEdge() {
        withStore { store ->
            val promoter = FollowMeAtlasPromoter(store)
            val from = screen("home", 100)
            val to = screen("orders", 120)
            val tap = action(from.id)
            promoter.observeScreen(app(), from, listOf(tap))
            promoter.observeScreen(app(), to, emptyList())
            promoter.demonstrateTransition(app(), from, tap, to, 120)

            val edges = store.snapshot(key(AtlasPersona.LIVE))!!.edges
            assertTrue(edges.any {
                it.key.type == GraphEdgeType.NAVIGATES_TO &&
                    it.key.from == AtlasGraphIds.encoded("page", from.id) &&
                    it.key.to == AtlasGraphIds.encoded("page", to.id)
            })
        }
    }

    @Test
    fun closeAndReopenRetainsPlaceScreensEdgesAndLayout() {
        val dir = Files.createTempDirectory("atlas-reopen").toFile()
        val file = dir.resolve("atlas.json")
        val first = AtlasStore(file)
        val promoter = FollowMeAtlasPromoter(first)
        val from = screen("home", 100)
        val to = screen("orders", 120)
        val tap = action(from.id)
        promoter.observeScreen(app(), from, listOf(tap))
        promoter.observeScreen(app(), to, emptyList())
        promoter.demonstrateTransition(app(), from, tap, to, 120)
        val before = first.snapshot(key(AtlasPersona.LIVE))!!
        first.close()

        val reopened = AtlasStore(file)
        val after = reopened.snapshot(key(AtlasPersona.LIVE))!!
        assertEquals(before.place, after.place)
        assertEquals(before.screens, after.screens)
        assertEquals(before.edgeMetadata, after.edgeMetadata)
        assertEquals(
            before.edges.map { it.key },
            after.edges.map { it.key },
        )
        reopened.close()
        dir.deleteRecursively()
    }

    @Test
    fun legacyGraphProjectsWithoutScreenshotDynamicDataOrPhysicalVerification() {
        withStore { store ->
            val home = screen("home", 100).copy(
                screenshotPath = "/private/raw-frame.png",
                sampleDynamicData = mapOf("dynamic_json" to "captured@example.com"),
                knowledgeState = KnowledgeState.VERIFIED,
                lastVerifiedAt = 100,
            )
            val orders = screen("orders", 120).copy(knowledgeState = KnowledgeState.VERIFIED)
            val tap = action(home.id).copy(
                selectorJson = "{\"resourceId\":\"orders\",\"typed_value\":\"NeverSerializeMe\"}",
                knowledgeState = KnowledgeState.VERIFIED,
            )
            val legacy = AppGraphSnapshot(
                app = app().copy(knowledgeState = KnowledgeState.VERIFIED, lastVerifiedAt = 120),
                screens = listOf(home, orders),
                actions = listOf(tap),
                transitions = listOf(
                    LearnedTransition(
                        id = "route",
                        packageName = APP_PACKAGE,
                        fromScreenId = home.id,
                        actionId = tap.id,
                        toScreenId = orders.id,
                        knowledgeState = KnowledgeState.VERIFIED,
                        confidence = 0.9,
                        lastObservedAt = 120,
                    ),
                ),
            )

            AtlasLegacyImporter(store).import(legacy)
            val snapshot = store.snapshot(key(AtlasPersona.LIVE))!!
            assertTrue(snapshot.edges.all {
                it.evidence.verificationScope != GraphVerificationScope.PHYSICAL_DEVICE
            })
            val wire = StoreBackedAtlasReadProvider(store).get(key(AtlasPersona.LIVE).placeId, AtlasPersona.LIVE).toString()
            assertFalse(wire.contains("captured@example.com"))
            assertFalse(wire.contains("NeverSerializeMe"))
            assertFalse(wire.contains("raw-frame.png"))
            assertFalse(wire.contains("dynamic_json"))
        }
    }

    @Test
    fun ordinaryContentLabelsNeverBecomeDurableAtlasStructure() {
        val dir = Files.createTempDirectory("atlas-content-privacy").toFile()
        val file = dir.resolve("atlas.json")
        val store = AtlasStore(file)
        try {
            val promoter = FollowMeAtlasPromoter(store)
            val thread = screen("thread-uuid", 100).copy(
                identity = "Louella",
                title = "Louella",
                purpose = "Chat with Louella about Dinner plans",
            )
            val contentRow = action(thread.id).copy(
                id = "content-row",
                semanticName = "louella",
                label = "Dinner plans",
                selectorJson = "{\"text\":\"Dinner plans\"}",
            )

            promoter.observeScreen(app(), thread, listOf(contentRow), AtlasPersona.LIVE)

            val snapshot = store.snapshot(key(AtlasPersona.LIVE))!!
            val page = snapshot.nodes.filterIsInstance<PageNode>().single()
            val element = snapshot.nodes.filterIsInstance<ElementNode>().single()
            assertEquals("page", page.identity)
            assertEquals("Screen", page.displayName)
            assertEquals("control", element.semanticName)
            assertEquals("Control", element.displayName)
            assertEquals("Messages", snapshot.screens.single().purpose)

            val wire = StoreBackedAtlasReadProvider(store)
                .get(key(AtlasPersona.LIVE).placeId, AtlasPersona.LIVE)
                .toString()
            val disk = file.readText()
            listOf("Louella", "Dinner plans", "Chat with Louella").forEach { content ->
                assertFalse(wire.contains(content))
                assertFalse(disk.contains(content))
            }
        } finally {
            store.close()
            dir.deleteRecursively()
        }
    }

    @Test
    fun mappingPersonaNeverMutatesLivePersona() {
        withStore { store ->
            val promoter = FollowMeAtlasPromoter(store)
            promoter.observeScreen(app(), screen("live-home", 100), emptyList(), AtlasPersona.LIVE)
            val liveBefore = store.snapshot(key(AtlasPersona.LIVE))

            promoter.observeScreen(app(), screen("dummy-login", 200), emptyList(), AtlasPersona.MAPPING)

            assertEquals(liveBefore, store.snapshot(key(AtlasPersona.LIVE)))
            assertEquals(1, store.snapshot(key(AtlasPersona.MAPPING))?.screens?.size)
            assertEquals("Login", (store.snapshot(key(AtlasPersona.MAPPING))!!.nodes.filterIsInstance<PageNode>().single()).identity)
        }
    }

    @Test
    fun factSlotDefinitionHasNoCapturedFactValueAndSecretShapedSlotsStayOffWire() {
        withStore { store ->
            val place = store.ensurePackagePlace(APP_PACKAGE, "Shop", AtlasPersona.LIVE)
            val page = PageNode(GraphNodeId("page:account"), APP_PACKAGE, "account", "Account")
            store.mutateGraph(place.key) { graph -> graph.registerNode(page) }
            store.upsertScreen(
                place.key,
                AtlasScreenMetadata(
                    screenId = page.id,
                    purpose = "Account identity",
                    factSlots = listOf(
                        AtlasFactSlot("signed_in_email", page.id, "sha256:abc", "Read the signed in email label", 0.9),
                        AtlasFactSlot("password", page.id, "sha256:def", "Credential field", 0.5),
                    ),
                    confidence = 0.9,
                    lastObservedAtEpochMillis = 100,
                    layoutX = 10.0,
                    layoutY = 20.0,
                ),
            )

            val wire = StoreBackedAtlasReadProvider(store).get(place.id, AtlasPersona.LIVE)!!.toString()
            assertTrue(wire.contains("signed_in_email"))
            assertFalse(wire.contains("\"password\""))
            assertFalse(wire.contains("captured@example.com"))
            assertFalse(AtlasFactSlot::class.java.declaredFields.any { it.name == "value" })
        }
    }

    @Test
    fun providerOutputMatchesAgent001Run1ShapeAndUsesIsoTimestamps() {
        withStore { store ->
            FollowMeAtlasPromoter(store).observeScreen(app(), screen("home", 100), listOf(action("home")))
            val provider = StoreBackedAtlasReadProvider(store)
            val doc = provider.get(key(AtlasPersona.LIVE).placeId, AtlasPersona.LIVE)!!

            assertEquals(
                setOf("place", "persona", "mapStatus", "screens", "edges", "capabilities", "confidence", "lastObservedAt", "lastVerifiedAt"),
                doc.keys().asSequence().toSet(),
            )
            assertEquals("live", doc.getString("persona"))
            assertEquals("partial", doc.getString("mapStatus"))
            val place = doc.getJSONObject("place")
            assertEquals(setOf("placeId", "kind", "label", "packageName"), place.keys().asSequence().toSet())
            Instant.parse(doc.getString("lastObservedAt"))
            val first = doc.getJSONArray("screens").getJSONObject(0)
            assertEquals(
                setOf("screenId", "label", "purpose", "factSlots", "risk", "confidence", "lastObservedAt", "lastVerifiedAt", "layout"),
                first.keys().asSequence().toSet(),
            )
            assertTrue(first.getJSONObject("risk").has("danger"))
            assertTrue(first.getJSONObject("risk").has("classes"))
        }
    }

    @Test
    fun unmappedPlaceProducesValidEmptyGraph() {
        withStore { store ->
            val place = store.ensurePackagePlace(APP_PACKAGE, "Shop", AtlasPersona.LIVE)
            val doc = StoreBackedAtlasReadProvider(store).get(place.id, AtlasPersona.LIVE)!!

            assertEquals("unmapped", doc.getString("mapStatus"))
            assertEquals(0, doc.getJSONArray("screens").length())
            assertEquals(0, doc.getJSONArray("edges").length())
            assertEquals(0, doc.getJSONArray("capabilities").length())
            assertEquals(0.0, doc.getDouble("confidence"), 0.0)
        }
    }

    @Test
    fun retrievalReturnsHintsWithoutMutatingOrExecutingAtlas() {
        withStore { store ->
            val promoter = FollowMeAtlasPromoter(store)
            val from = screen("home", 100)
            val to = screen("orders", 120).copy(purpose = "Order history")
            val tap = action(from.id).copy(semanticName = "open_orders", label = "Orders")
            promoter.observeScreen(app(), from, listOf(tap))
            promoter.observeScreen(app(), to, emptyList())
            promoter.demonstrateTransition(app(), from, tap, to, 120)
            val before = store.snapshot(key(AtlasPersona.LIVE))

            val hint = AtlasRetriever(store).findHint(
                key(AtlasPersona.LIVE),
                "open order history",
                AtlasGraphIds.encoded("page", from.id),
            )

            assertNotNull(hint)
            assertEquals(AtlasGraphIds.encoded("page", to.id), hint!!.destinationScreen)
            assertEquals(before, store.snapshot(key(AtlasPersona.LIVE)))
            assertFalse(AtlasRetriever::class.java.declaredFields.any {
                it.type.name.contains("PhoneToolExecutor") || it.type.name.contains("AppGraphExecutor")
            })
        }
    }

    private fun withStore(block: (AtlasStore) -> Unit) {
        val dir = Files.createTempDirectory("atlas-test").toFile()
        val store = AtlasStore(dir.resolve("atlas.json"))
        try {
            block(store)
        } finally {
            store.close()
            dir.deleteRecursively()
        }
    }

    private fun key(persona: AtlasPersona) = AtlasPlaceKey("package:$APP_PACKAGE", persona)

    private fun app() = LearnedApp(
        packageName = APP_PACKAGE,
        label = "Shop",
        versionName = "1.0",
        versionCode = 1,
        confidence = 0.9,
        lastLearnedAt = 100,
    )

    private fun screen(id: String, seen: Long) = LearnedScreen(
        id = id,
        packageName = APP_PACKAGE,
        identity = id,
        title = id.replace('-', ' '),
        purpose = "Screen $id",
        recognition = ScreenRecognition(
            semanticFingerprint = "semantic-$id",
            structuralFingerprint = "structural-$id",
            stableAnchors = listOf(id),
            className = "com.example.MainActivity",
            titleHints = listOf(id),
        ),
        knowledgeState = KnowledgeState.UNDERSTOOD,
        confidence = 0.9,
        lastSeenAt = seen,
    )

    private fun action(screenId: String) = LearnedAction(
        id = "action-$screenId",
        packageName = APP_PACKAGE,
        screenId = screenId,
        semanticName = "open_orders",
        label = "Orders",
        androidActions = listOf("ACTION_CLICK"),
        selectorJson = "{\"resourceId\":\"orders\"}",
        risk = ActionRisk.SAFE,
        knowledgeState = KnowledgeState.UNDERSTOOD,
        confidence = 0.9,
    )

    companion object {
        private const val APP_PACKAGE = "com.example.shop"
    }
}
