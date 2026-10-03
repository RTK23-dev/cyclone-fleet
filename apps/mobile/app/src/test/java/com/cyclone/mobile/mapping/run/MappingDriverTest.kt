package com.cyclone.mobile.mapping.run

import com.cyclone.mobile.brain.graphv2.AtlasMapStatus
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.brain.graphv2.AtlasPlace
import com.cyclone.mobile.brain.graphv2.AtlasPlaceKey
import com.cyclone.mobile.brain.graphv2.AtlasStore
import com.cyclone.mobile.brain.graphv2.StoreBackedAtlasReadProvider
import com.cyclone.mobile.mapping.crawl.MappingDanger
import com.cyclone.mobile.mapping.crawl.MappingDoor
import com.cyclone.mobile.mapping.crawl.MappingDoorKind
import com.cyclone.mobile.mapping.crawl.MappingMutationPort
import com.cyclone.mobile.mapping.crawl.MappingMutationResult
import com.cyclone.mobile.mapping.crawl.MappingNavigationPort
import com.cyclone.mobile.mapping.crawl.MappingObservation
import com.cyclone.mobile.mapping.crawl.MappingObservationPort
import com.cyclone.mobile.mapping.crawl.MappingAction
import com.cyclone.mobile.mapping.crawl.MappingSafetyPort
import com.cyclone.mobile.mapping.crawl.MappingSecretWall
import com.cyclone.mobile.mapping.crawl.MappingSecretsPort
import com.cyclone.mobile.mapping.crawl.MappingSessionSnapshot
import com.cyclone.mobile.mapping.crawl.SafeMapperWalker
import com.cyclone.mobile.mapping.crawl.StructuralScreenPurpose
import com.cyclone.mobile.mapping.session.AtlasDiffJournal
import com.cyclone.mobile.mapping.session.AtlasStructuralEntity
import com.cyclone.mobile.mapping.session.MappingAuthority
import com.cyclone.mobile.mapping.session.MappingBudget
import com.cyclone.mobile.mapping.session.MappingClock
import com.cyclone.mobile.mapping.session.MappingControlLease
import com.cyclone.mobile.mapping.session.MappingPlaneRequest
import com.cyclone.mobile.mapping.session.MappingSessionController
import com.cyclone.mobile.mapping.session.MappingSessionException
import com.cyclone.mobile.mapping.session.MappingSessionState
import com.cyclone.mobile.mapping.session.MappingStartRequest
import com.cyclone.mobile.runtime.session.SessionPlane
import com.cyclone.mobile.runtime.session.SessionPlaneKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Drives the real walker, real controller, real diff journal and real AtlasStore against a fake
 * app so the whole phone mapping loop is exercised without a device.
 */
class MappingDriverTest {
    private val dir: File = Files.createTempDirectory("mapping-driver").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun walksWholeHouseWithBacktrackingAndPublishesDiffs() {
        val h = Harness(FakeApp.house())
        // Glass bootstraps a phone-issued cursor before it asks for live changes.
        val cursor = h.controller.atlasDiff(PLACE, "mapping", null).cursor

        val finished = h.driver.run(freshStart = true)

        assertEquals(MappingSessionState.COMPLETED, finished!!.state)
        // home, tab A, a room under A, tab B, menu under B
        assertEquals(setOf("home", "a", "Louella", "b", "menu"), h.app.visited)
        val snapshot = h.store.snapshot(AtlasPlaceKey(PLACE, AtlasPersona.MAPPING))!!
        assertEquals(5, snapshot.screens.size)
        assertEquals(4, snapshot.edgeMetadata.size)
        assertTrue("back was used to reach sibling doors", h.nav.backs > 0)

        val diff = h.controller.atlasDiff(PLACE, "mapping", cursor)
        val screens = diff.changes.map { it.second }.filter { it.entity == AtlasStructuralEntity.SCREEN }
        val edges = diff.changes.map { it.second }.filter { it.entity == AtlasStructuralEntity.EDGE }
        assertEquals(5, screens.size)
        assertEquals(4, edges.size)

        // The diff ids are exactly the ids Glass reads from atlas.get.
        val atlasJson = StoreBackedAtlasReadProvider(h.store).get(PLACE, AtlasPersona.MAPPING)!!
        val boardScreens = (0 until atlasJson.getJSONArray("screens").length())
            .map { atlasJson.getJSONArray("screens").getJSONObject(it).getString("screenId") }.toSet()
        val boardEdges = (0 until atlasJson.getJSONArray("edges").length())
            .map { atlasJson.getJSONArray("edges").getJSONObject(it).getString("edgeId") }.toSet()
        assertEquals(boardScreens, screens.map { it.id }.toSet())
        assertEquals(boardEdges, edges.map { it.id }.toSet())
        assertTrue(finished.currentAtlasNodeId in boardScreens)
    }

    @Test
    fun dangerousDoorsAreNeverTappedAndLeaveThePlacePartial() {
        val h = Harness(FakeApp.house(withDanger = true))

        val finished = h.driver.run(freshStart = true)

        assertEquals(MappingSessionState.COMPLETED, finished!!.state)
        assertFalse("pay" in h.app.tapped)
        assertFalse("delete" in h.app.tapped)
        assertEquals(AtlasMapStatus.PARTIAL, h.store.place(AtlasPlaceKey(PLACE, AtlasPersona.MAPPING))!!.mapStatus)
        assertTrue(h.atlas.darkDoorCount() >= 2)
    }

    @Test
    fun loginWallPausesForSecretThenResumesAndFinishes() {
        val h = Harness(FakeApp.house(withLogin = true))

        val paused = h.driver.run(freshStart = true)

        assertEquals(MappingSessionState.NEEDS_SECRET, paused!!.state)
        assertEquals(1, h.secrets.requests.size)
        val tapsBefore = h.app.tapped.size

        // The phone card fills; the login screen turns into the signed-in room.
        h.app.completeLogin()
        h.controller.resume(paused.mappingJobId, h.plane)
        val finished = h.driver.run(freshStart = false)

        assertEquals(MappingSessionState.COMPLETED, finished!!.state)
        assertTrue(h.app.tapped.size > tapsBefore)
        // The signed-in room's own door was walked after the fill.
        assertTrue("compose" in h.app.visited)
    }

    @Test
    fun humanControlParksTheJobWithoutAnotherTap() {
        val h = Harness(FakeApp.house())
        h.app.onTap = { count -> if (count == 2) h.authority.revalidateError = MappingSessionException("HUMAN_HAS_CONTROL", "human") }

        val parked = h.driver.run(freshStart = true)

        assertEquals(MappingSessionState.HUMAN_CONTROL, parked!!.state)
        assertEquals(2, h.app.tapped.size)
    }

    @Test
    fun stopFromElsewhereWinsBeforeTheNextTap() {
        val h = Harness(FakeApp.house())
        h.app.onTap = { count -> if (count == 1) h.controller.stop(h.jobId) }

        val parked = h.driver.run(freshStart = true)

        assertEquals(MappingSessionState.STOPPED, parked!!.state)
        assertEquals(1, h.app.tapped.size)
    }

    @Test
    fun leavingTheAppRelaunchesInsteadOfMappingForeignScreens() {
        val h = Harness(FakeApp.house(withExit = true))

        val finished = h.driver.run(freshStart = true)

        assertEquals(MappingSessionState.COMPLETED, finished!!.state)
        assertTrue(h.nav.opens >= 2)
        assertFalse("outside" in h.app.visitedInPlace)
        val snapshot = h.store.snapshot(AtlasPlaceKey(PLACE, AtlasPersona.MAPPING))!!
        assertEquals(5, snapshot.screens.size)
    }

    @Test
    fun budgetStopsWithPartialStatus() {
        val h = Harness(
            FakeApp.house(),
            budget = MappingBudget(maxNewScreens = 2, maxElapsedMs = 60_000, maxConsecutiveNonProgress = 6, maxAttemptsPerDoor = 2),
        )

        val finished = h.driver.run(freshStart = true)

        assertEquals(MappingSessionState.COMPLETED, finished!!.state)
        assertEquals(AtlasMapStatus.PARTIAL, h.store.place(AtlasPlaceKey(PLACE, AtlasPersona.MAPPING))!!.mapStatus)
    }

    @Test
    fun liveMapIsUntouchedAndNoLabelsAreStored() {
        val h = Harness(FakeApp.house())
        h.store.registerPlace(AtlasPlace.packagePlace("com.example.app", "Example", AtlasPersona.LIVE))

        h.driver.run(freshStart = true)

        val live = h.store.snapshot(AtlasPlaceKey(PLACE, AtlasPersona.LIVE))!!
        assertTrue(live.screens.isEmpty())
        val disk = File(dir, "atlas.json").also { h.store.close() }.readText()
        FakeApp.PRIVATE_LABELS.forEach { assertFalse("label leaked: $it", disk.contains(it)) }
    }

    @Test
    fun reportIsStructuralOnly() {
        val h = Harness(FakeApp.house(withDanger = true))
        val finished = h.driver.run(freshStart = true)!!

        val report = MappingReport.build(finished, h.atlas, h.session, h.driver.events).toString()

        assertTrue(report.contains(MappingReport.SCHEMA))
        assertTrue(report.contains("\"rooms\":5"))
        FakeApp.PRIVATE_LABELS.forEach { assertFalse("label leaked: $it", report.contains(it)) }
    }

    private inner class Harness(
        val app: FakeApp,
        budget: MappingBudget = MappingBudget(maxNewScreens = 20, maxElapsedMs = 60_000, maxConsecutiveNonProgress = 6, maxAttemptsPerDoor = 2),
    ) {
        val authority = FakeAuthority()
        val controller = MappingSessionController(
            authority = authority,
            journal = AtlasDiffJournal(File(dir, "diff.json")),
            clock = object : MappingClock { override fun nowEpochMs() = 1_000L },
        )
        val plane = MappingPlaneRequest("default-foreground", 0)
        val jobId = controller.start(MappingStartRequest(PLACE, "mapping", plane, budget)).mappingJobId
        val store = AtlasStore(File(dir, "atlas.json"))
        val atlas = AtlasStoreMappingPort(store, PLACE, "Example", clock = { 1_000L })
        val session = ControllerSessionPort(controller, jobId)
        val nav = FakeNavigation(app)
        val secrets = FakeSecrets(app)
        val walker = SafeMapperWalker(session, app, atlas, FakeSafety(), app, secrets)
        val driver = MappingDriver(
            controller = controller,
            jobId = jobId,
            walker = walker,
            session = session,
            navigation = nav,
            atlas = atlas,
            publishChanges = { controller.appendAtlasChanges(jobId, it) },
            clock = { 1_000L },
            pause = {},
        )
    }

    /** A tiny app: rooms with doors. Door targets can be rooms, "outside", or dangerous. */
    class FakeApp(
        private val rooms: Map<String, List<Pair<String, String>>>,
        private val loginRoom: String? = null,
    ) : MappingObservationPort, MappingMutationPort {
        private var current = "launcher"
        private val stack = ArrayDeque<String>()
        private var sequence = 0
        private var loggedIn = false
        val tapped = mutableListOf<String>()
        val visited = linkedSetOf<String>()
        val visitedInPlace = linkedSetOf<String>()
        var onTap: (Int) -> Unit = {}

        fun room(): String = if (current == loginRoom && loggedIn) "inbox" else current
        fun isLoginWall(): Boolean = current == loginRoom && !loggedIn
        fun completeLogin() { loggedIn = true }

        fun open() { stack.clear(); current = "home"; visited += "home" }
        fun back(): Boolean {
            if (stack.isEmpty()) return false
            current = stack.removeLast()
            return true
        }

        override fun freshObservation(session: MappingSessionSnapshot): MappingObservation {
            val id = "obs-${++sequence}"
            val room = room()
            val inPlace = room != "outside" && room != "launcher"
            if (inPlace) visitedInPlace += room
            val doors = rooms[room].orEmpty().map { (key, _) ->
                MappingDoor(elementId = "el:$id:$key", observationId = id, key = "door:menu:$key", kind = MappingDoorKind.MENU)
            }
            return MappingObservation(
                observationId = id,
                sessionId = session.sessionId,
                displayId = session.displayId,
                fingerprint = "fp:$id",
                structuralFingerprint = "structure:$room",
                fresh = true,
                purpose = if (isLoginWall()) StructuralScreenPurpose.LOGIN else StructuralScreenPurpose.UNKNOWN,
                doors = doors,
                inPlace = inPlace,
            )
        }

        override fun execute(session: MappingSessionSnapshot, action: MappingAction): MappingMutationResult {
            val key = action.doorKey.removePrefix("door:menu:")
            tapped += key
            val target = rooms[room()].orEmpty().firstOrNull { it.first == key }?.second
                ?: return MappingMutationResult(false, false, "TARGET_NOT_FOUND")
            stack.addLast(current)
            current = target
            visited += room()
            onTap(tapped.size)
            return MappingMutationResult(performed = true, verifiedByExecutor = true)
        }

        companion object {
            /** Never allowed on disk or in reports: these stand in for real screen text. */
            val PRIVATE_LABELS = listOf("Louella", "j.doe@example.com")

            fun house(
                withDanger: Boolean = false,
                withLogin: Boolean = false,
                withExit: Boolean = false,
            ): FakeApp {
                val home = mutableListOf("tab_a" to "a", "tab_b" to "b")
                if (withDanger) home += "pay" to "checkout"
                if (withExit) home.add(0, "share" to "outside")
                val b = mutableListOf("menu" to "menu")
                if (withDanger) b += "delete" to "gone"
                val rooms = mutableMapOf(
                    "home" to home.toList(),
                    // Real screens carry private text; it may only ever reach the Atlas as a digest.
                    "a" to listOf("j.doe@example.com" to "Louella"),
                    "Louella" to emptyList(),
                    "b" to b.toList(),
                    "menu" to emptyList(),
                    "outside" to listOf("x" to "home"),
                )
                if (withLogin) {
                    rooms["menu"] = listOf("account" to "login")
                    rooms["login"] = emptyList()
                    rooms["inbox"] = listOf("compose" to "compose")
                    rooms["compose"] = emptyList()
                }
                return FakeApp(rooms, loginRoom = if (withLogin) "login" else null)
            }
        }
    }

    class FakeNavigation(private val app: FakeApp) : MappingNavigationPort {
        var backs = 0
        var opens = 0
        override fun back(session: MappingSessionSnapshot): MappingMutationResult {
            backs += 1
            return MappingMutationResult(app.back(), true)
        }

        override fun openPlace(session: MappingSessionSnapshot, resetToEntry: Boolean): MappingMutationResult {
            opens += 1
            app.open()
            return MappingMutationResult(performed = true, verifiedByExecutor = true)
        }
    }

    class FakeSafety : MappingSafetyPort {
        override fun classify(observation: MappingObservation, door: MappingDoor): MappingDanger = when {
            door.key.endsWith(":pay") -> MappingDanger.PAY
            door.key.endsWith(":delete") -> MappingDanger.DELETE
            else -> MappingDanger.NONE
        }
    }

    class FakeSecrets(private val app: FakeApp) : MappingSecretsPort {
        val requests = mutableListOf<MappingSecretWall>()
        override fun detect(session: MappingSessionSnapshot, observation: MappingObservation): MappingSecretWall? =
            if (app.isLoginWall()) {
                MappingSecretWall(session.placeId, "password", "Login required", "el:pw", observation.observationId, observation.sessionId, observation.displayId)
            } else {
                null
            }

        override fun request(wall: MappingSecretWall) {
            requests += wall
        }
    }

    class FakeAuthority : MappingAuthority {
        var revalidateError: MappingSessionException? = null
        override fun acquire(request: MappingPlaneRequest) = MappingControlLease(
            plane = SessionPlane(
                kind = SessionPlaneKind.FOREGROUND,
                sessionId = request.sessionId,
                displayId = request.displayId,
                workspaceId = null,
                workspaceGeneration = null,
            ),
            controlRevision = 1,
        )

        override fun revalidate(lease: MappingControlLease) {
            revalidateError?.let { throw it }
        }
    }

    private companion object {
        const val PLACE = "package:com.example.app"
    }
}
