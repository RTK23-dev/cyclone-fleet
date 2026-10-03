package com.cyclone.mobile.mapping.session

import com.cyclone.mobile.runtime.session.SessionPlane
import com.cyclone.mobile.runtime.session.SessionPlaneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MappingSessionRun2Test {
    @Test
    fun missingSessionFailsClosed() {
        val error = runCatching {
            MappingPlaneRequest(sessionId = "", displayId = 0)
        }.exceptionOrNull()
        assertTrue(error is MappingSessionException)
        assertEquals("SESSION_REQUIRED", (error as MappingSessionException).code)
    }

    @Test
    fun displayMismatchFailsClosed() {
        val error = runCatching {
            MappingPlaneRequest(sessionId = "workspace-1", displayId = 0)
        }.exceptionOrNull()
        assertTrue(error is MappingSessionException)
        assertEquals("SESSION_DISPLAY_MISMATCH", (error as MappingSessionException).code)
    }

    @Test
    fun humanControlIsRejectedAndTransitionsJobNonterminal() {
        withController { controller, authority, plane ->
            val job = controller.start(startRequest(plane))
            authority.revalidateError = MappingSessionException(
                "HUMAN_HAS_CONTROL",
                "Human owns input.",
            )

            val error = runCatching {
                controller.requireMutation(job.mappingJobId)
            }.exceptionOrNull()

            assertTrue(error is MappingSessionException)
            assertEquals("HUMAN_HAS_CONTROL", (error as MappingSessionException).code)
            val status = controller.status(job.mappingJobId)!!
            assertEquals(MappingSessionState.HUMAN_CONTROL, status.state)
            assertFalse(status.state.terminal)
        }
    }

    @Test
    fun startPauseResumeStopTransitionsAreExplicit() {
        withController { controller, authority, plane ->
            val started = controller.start(startRequest(plane))
            assertEquals(MappingSessionState.RUNNING, started.state)

            val paused = controller.pause(started.mappingJobId)
            assertEquals(MappingSessionState.PAUSED, paused.state)

            authority.nextRevision += 1
            val resumed = controller.resume(started.mappingJobId, plane)
            assertEquals(MappingSessionState.RUNNING, resumed.state)
            assertEquals(authority.nextRevision, resumed.lease.controlRevision)

            val stopped = controller.stop(started.mappingJobId)
            assertEquals(MappingSessionState.STOPPED, stopped.state)
            assertTrue(stopped.state.terminal)
            assertNull(controller.statusForPlane(plane))
        }
    }

    @Test
    fun needsSecretIsNonterminalAndRetainsPlaneOwnership() {
        withController { controller, _, plane ->
            val first = controller.start(startRequest(plane))
            val waiting = controller.pauseForSecret(first.mappingJobId)

            assertEquals(MappingSessionState.NEEDS_SECRET, waiting.state)
            assertFalse(waiting.state.terminal)

            val second = runCatching {
                controller.start(startRequest(plane))
            }.exceptionOrNull()
            assertTrue(second is MappingSessionException)
            assertEquals("MAPPING_PLANE_BUSY", (second as MappingSessionException).code)
        }
    }

    @Test
    fun verifiedProgressUpdatesSafeCountersAndCurrentNode() {
        withController { controller, _, plane ->
            val job = controller.start(startRequest(plane))
            val updated = controller.recordVerifiedProgress(
                job.mappingJobId,
                MappingVerifiedProgress(
                    currentAtlasNodeId = "screen:home",
                    discoveredNewScreen = true,
                    progressMade = true,
                    attemptedDoor = true,
                    remainingDarkRegions = 3,
                ),
            )

            assertEquals("screen:home", updated.currentAtlasNodeId)
            assertEquals(1, updated.progress.newScreens)
            assertEquals(1, updated.progress.verifiedMutations)
            assertEquals(1, updated.progress.attemptedDoors)
            assertEquals(3, updated.progress.remainingDarkRegions)
        }
    }

    @Test
    fun diffCursorAdvancesAndPersistsStructuralChangesOnly() {
        val dir = Files.createTempDirectory("atlas-diff-run2").toFile()
        try {
            val file = dir.resolve("atlas-diff.json")
            val journal = AtlasDiffJournal(file, maxEntriesPerStream = 4)
            val baseline = journal.diff(PLACE, "mapping", null)
            val firstCursor = baseline.cursor

            journal.append(
                PLACE,
                "mapping",
                listOf(
                    AtlasStructuralChange(
                        entity = AtlasStructuralEntity.SCREEN,
                        change = AtlasStructuralChangeKind.UPSERT,
                        id = "screen:home",
                        layoutX = 10.0,
                        layoutY = 20.0,
                    ),
                ),
            )
            val diff = journal.diff(PLACE, "mapping", firstCursor)

            assertFalse(diff.resyncRequired)
            assertNotEquals(firstCursor, diff.cursor)
            assertEquals(1, diff.changes.size)
            val json = diff.toJson().toString()
            assertTrue(json.contains("screen:home"))
            assertFalse(json.contains("label"))
            assertFalse(json.contains("content"))
            assertFalse(json.contains("text"))
            assertFalse(json.contains("value"))

            val reopened = AtlasDiffJournal(file, maxEntriesPerStream = 4)
            val afterRestart = reopened.diff(PLACE, "mapping", firstCursor)
            assertEquals(diff.cursor, afterRestart.cursor)
            assertEquals(1, afterRestart.changes.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun staleOrUnknownDiffCursorRequiresResyncWithoutFabricatedChanges() {
        val dir = Files.createTempDirectory("atlas-diff-stale").toFile()
        try {
            val journal = AtlasDiffJournal(dir.resolve("journal.json"), maxEntriesPerStream = 4)
            val baseline = journal.diff(PLACE, "mapping", null).cursor
            repeat(5) { index ->
                journal.append(
                    PLACE,
                    "mapping",
                    listOf(
                        AtlasStructuralChange(
                            entity = AtlasStructuralEntity.SCREEN,
                            change = AtlasStructuralChangeKind.UPSERT,
                            id = "screen:n" + index,
                            layoutX = index.toDouble(),
                            layoutY = 0.0,
                        ),
                    ),
                )
            }

            val stale = journal.diff(PLACE, "mapping", baseline)
            assertTrue(stale.resyncRequired)
            assertTrue(stale.changes.isEmpty())

            val unknown = journal.diff(PLACE, "mapping", "c1:aaaaaaaaaaaaaaaaaaaa:1")
            assertTrue(unknown.resyncRequired)
            assertTrue(unknown.changes.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun completePartialIsSuccessfulTerminalStateNotFailure() {
        withController { controller, _, plane ->
            val job = controller.start(startRequest(plane))
            val completed = controller.complete(job.mappingJobId, MappingAtlasStatus.PARTIAL)
            assertEquals(MappingSessionState.COMPLETED, completed.state)
            assertEquals(MappingAtlasStatus.PARTIAL, completed.atlasStatus)
            assertTrue(completed.state.terminal)
            assertNull(completed.failureCode)
        }
    }

    private fun withController(
        block: (MappingSessionController, FakeAuthority, MappingPlaneRequest) -> Unit,
    ) {
        val dir = Files.createTempDirectory("mapping-session-run2").toFile()
        try {
            val authority = FakeAuthority()
            val clock = FakeClock()
            val controller = MappingSessionController(
                authority = authority,
                journal = AtlasDiffJournal(dir.resolve("journal.json")),
                clock = clock,
                idFactory = { "map-test-0001" },
            )
            val plane = MappingPlaneRequest(
                sessionId = "default-foreground",
                displayId = 0,
            )
            block(controller, authority, plane)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun startRequest(plane: MappingPlaneRequest) = MappingStartRequest(
        placeId = PLACE,
        persona = "mapping",
        plane = plane,
        budget = MappingBudget(
            maxNewScreens = 2,
            maxElapsedMs = 5_000,
            maxConsecutiveNonProgress = 2,
            maxAttemptsPerDoor = 1,
        ),
    )

    private class FakeClock : MappingClock {
        private var now = 1_000L
        override fun nowEpochMs(): Long = now++
    }

    private class FakeAuthority : MappingAuthority {
        var nextRevision = 1L
        var revalidateError: MappingSessionException? = null

        override fun acquire(request: MappingPlaneRequest): MappingControlLease =
            MappingControlLease(
                plane = SessionPlane(
                    kind = if (request.workspaceId == null) {
                        SessionPlaneKind.FOREGROUND
                    } else {
                        SessionPlaneKind.LAYER2_WORKSPACE
                    },
                    sessionId = request.sessionId,
                    displayId = request.displayId,
                    workspaceId = request.workspaceId,
                    workspaceGeneration = request.workspaceGeneration,
                ),
                controlRevision = nextRevision,
            )

        override fun revalidate(lease: MappingControlLease) {
            revalidateError?.let { throw it }
        }
    }

    companion object {
        private const val PLACE = "package:com.example.app"
    }
}
