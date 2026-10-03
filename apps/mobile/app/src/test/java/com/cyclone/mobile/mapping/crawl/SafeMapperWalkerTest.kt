package com.cyclone.mobile.mapping.crawl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class SafeMapperWalkerTest {
    private val baseBudget = MappingBudget(
        maxNewScreens = 8,
        maxElapsedMs = 60_000,
        maxConsecutiveNonProgress = 3,
        maxAttemptsPerDoor = 2,
    )

    @Test
    fun everyChosenMutationHasPrecedingFreshObservation() {
        val h = Harness(
            observations = listOf(
                observation("obs-1", "screen-a", doors = listOf(door("obs-1", "settings", MappingDoorKind.SETTINGS))),
                observation("obs-2", "screen-b", purpose = StructuralScreenPurpose.SETTINGS),
            ),
        )

        val result = h.walker.step(nowMs = 1_100)

        assertTrue(result is MappingStepResult.Progress)
        assertEquals(
            listOf("observe:obs-1", "mutate:settings", "observe:obs-2", "atlas:settings"),
            h.events,
        )
        assertTrue(h.observer.returned.all { it.fresh })
    }

    @Test
    fun exactlyOneScreenChangingMutationPerDecision() {
        val h = Harness(
            observations = listOf(
                observation(
                    "obs-1",
                    "screen-a",
                    doors = listOf(
                        door("obs-1", "tab", MappingDoorKind.TAB),
                        door("obs-1", "menu", MappingDoorKind.MENU),
                    ),
                ),
                observation("obs-2", "screen-b"),
            ),
        )

        h.walker.step(nowMs = 1_100)

        assertEquals(1, h.mutations.actions.size)
    }

    @Test
    fun nextDecisionWaitsForAfterObservation() {
        val h = Harness(
            observations = listOf(
                observation("obs-1", "a", doors = listOf(door("obs-1", "tab-a", MappingDoorKind.TAB))),
                observation("obs-2", "b"),
                observation("obs-3", "b", doors = listOf(door("obs-3", "menu-b", MappingDoorKind.MENU))),
                observation("obs-4", "c"),
            ),
        )

        h.walker.step(nowMs = 1_100)
        h.walker.step(nowMs = 1_200)

        assertEquals(
            listOf(
                "observe:obs-1", "mutate:tab-a", "observe:obs-2", "atlas:tab-a",
                "observe:obs-3", "mutate:menu-b", "observe:obs-4", "atlas:menu-b",
            ),
            h.events,
        )
    }

    @Test
    fun paymentCandidateIsNeverClicked() {
        val h = Harness(
            observations = listOf(
                observation(
                    "obs-1",
                    "checkout",
                    doors = listOf(
                        door("obs-1", "buy", MappingDoorKind.TAB),
                        door("obs-1", "settings", MappingDoorKind.SETTINGS),
                    ),
                ),
                observation("obs-2", "settings"),
            ),
            dangers = mapOf("buy" to MappingDanger.PAY),
        )

        h.walker.step(nowMs = 1_100)

        assertEquals(listOf("settings"), h.mutations.actions.map { it.doorKey })
        assertEquals(listOf(MappingDanger.PAY), h.session.dangers.map { it.second })
        assertEquals(listOf("buy"), h.atlas.dangers.map { it.first })
    }

    @Test
    fun sendPublicCandidateIsNeverClicked() {
        assertDangerNeverMutates(MappingDanger.SEND_PUBLIC)
    }

    @Test
    fun deleteLogoutAndGrantCandidatesAreNeverClicked() {
        listOf(MappingDanger.DELETE, MappingDanger.LOGOUT_ALL, MappingDanger.GRANT).forEach {
            assertDangerNeverMutates(it)
        }
    }

    @Test
    fun secretWallPausesBeforeAnotherMutation() {
        val h = Harness(
            observations = listOf(
                observation("obs-secret", "login", purpose = StructuralScreenPurpose.LOGIN,
                    doors = listOf(door("obs-secret", "sign-in", MappingDoorKind.ACCOUNT))),
            ),
            secretObservations = setOf("obs-secret"),
            identity = MappingIdentity.TEST,
        )

        val result = h.walker.step(nowMs = 1_100)

        assertEquals(MappingStepResult.Paused(PauseReason.NEEDS_SECRET), result)
        assertEquals(0, h.mutations.actions.size)
        assertEquals(1, h.secrets.requests.size)
        assertEquals("Login required", h.session.secretPause)
    }

    @Test
    fun lookOnlyPassEndsAtASignInWallInsteadOfAskingForCredentials() {
        val h = Harness(
            observations = listOf(
                observation("obs-secret", "login", purpose = StructuralScreenPurpose.LOGIN,
                    doors = listOf(door("obs-secret", "sign-in", MappingDoorKind.ACCOUNT))),
            ),
            secretObservations = setOf("obs-secret"),
        )

        val result = h.walker.step(nowMs = 1_100)

        assertEquals(MappingStepResult.CompletedPartial("sign_in_needed"), result)
        assertEquals(0, h.mutations.actions.size)
        assertEquals(0, h.secrets.requests.size)
        assertEquals(null, h.session.secretPause)
        assertEquals("sign_in_needed", h.session.partialReason)
    }

    @Test
    fun humanControlStopsMutationImmediately() {
        val h = Harness(
            observations = listOf(
                observation("obs-1", "screen-a", doors = listOf(door("obs-1", "tab", MappingDoorKind.TAB))),
            ),
        )
        h.session.current = h.session.current.copy(authority = MappingAuthority.HUMAN_CONTROL)

        val result = h.walker.step(nowMs = 1_100)

        assertEquals(MappingStepResult.Paused(PauseReason.HUMAN_CONTROL), result)
        assertEquals(0, h.mutations.actions.size)
        assertEquals("human_control", h.session.humanPause)
    }

    @Test
    fun authorityLossAfterObservationStopsBeforeMutation() {
        lateinit var h: Harness
        h = Harness(
            observations = listOf(
                observation("obs-1", "screen-a", doors = listOf(door("obs-1", "tab", MappingDoorKind.TAB))),
            ),
            afterObserve = {
                h.session.current = h.session.current.copy(authority = MappingAuthority.COMPANION_CONTROL)
            },
        )

        val result = h.walker.step(nowMs = 1_100)

        assertEquals(MappingStepResult.Paused(PauseReason.HUMAN_CONTROL), result)
        assertEquals(0, h.mutations.actions.size)
    }

    @Test
    fun budgetExhaustionPersistsProgressAndLeavesPartial() {
        val oneScreen = baseBudget.copy(maxNewScreens = 1)
        val h = Harness(
            observations = listOf(
                observation("obs-1", "screen-a", doors = listOf(door("obs-1", "settings", MappingDoorKind.SETTINGS))),
                observation("obs-2", "screen-b", purpose = StructuralScreenPurpose.SETTINGS),
            ),
            budget = oneScreen,
        )

        val result = h.walker.step(nowMs = 1_100)

        assertTrue(result is MappingStepResult.CompletedPartial)
        assertEquals(1, h.atlas.verified.size)
        assertEquals("budget_max_new_screens", h.atlas.partialReason)
        assertEquals("budget_max_new_screens", h.session.partialReason)
    }

    @Test
    fun repeatedUnchangedResultDoesNotCauseDoubleClick() {
        val h = Harness(
            observations = listOf(
                observation("obs-1", "same", doors = listOf(door("obs-1", "menu", MappingDoorKind.MENU))),
                observation("obs-2", "same"),
                observation("obs-3", "same", doors = listOf(door("obs-3", "menu", MappingDoorKind.MENU))),
            ),
        )

        val first = h.walker.step(nowMs = 1_100)
        val second = h.walker.step(nowMs = 1_200)

        assertTrue(first is MappingStepResult.NoProgress)
        // A dead-end room hands control back to the driver instead of ending the whole job.
        assertTrue(second is MappingStepResult.RoomExhausted)
        assertEquals(1, h.mutations.actions.size)
        assertEquals("menu", h.mutations.actions.single().doorKey)
    }

    @Test
    fun mappingWritesNeverAlterLivePersona() {
        val h = Harness(
            observations = listOf(
                observation("obs-1", "a", doors = listOf(door("obs-1", "search", MappingDoorKind.SEARCH))),
                observation("obs-2", "b", purpose = StructuralScreenPurpose.SEARCH),
            ),
        )

        h.walker.step(nowMs = 1_100)

        assertTrue(h.atlas.personas.isNotEmpty())
        assertTrue(h.atlas.personas.all { it == MAPPING_PERSONA })
        assertFalse(h.atlas.personas.any { it == "live" })
    }

    @Test
    fun contentPersonFixtureDoesNotBecomeAtlasRoomLabel() {
        val alice = "Alice Example"
        val contentDoor = MappingDoor(
            elementId = "semantic:obs-1:row",
            observationId = "obs-1",
            key = "content:$alice",
            kind = MappingDoorKind.CONTENT_ROW,
        )
        val obs = observation("obs-1", "thread-list", doors = listOf(contentDoor))

        val node = StructuralRoomClassifier.nodeKey(obs)

        assertTrue(node.startsWith("screen:list:"))
        assertFalse(node.contains(alice))
        assertFalse(node.contains("content:"))
    }

    @Test
    fun structuralSampleCanBeUsedOnceWithoutPersistingContentLabel() {
        val person = "Bob Person"
        val h = Harness(
            observations = listOf(
                observation(
                    "obs-1",
                    "list-a",
                    doors = listOf(
                        MappingDoor(
                            elementId = "semantic:obs-1:sample",
                            observationId = "obs-1",
                            key = "sample:thread-shape",
                            kind = MappingDoorKind.STRUCTURAL_SAMPLE,
                        ),
                        MappingDoor(
                            elementId = "semantic:obs-1:person",
                            observationId = "obs-1",
                            key = "content:$person",
                            kind = MappingDoorKind.CONTENT_ROW,
                        ),
                    ),
                ),
                observation("obs-2", "detail-shape", purpose = StructuralScreenPurpose.DETAIL),
            ),
        )

        h.walker.step(nowMs = 1_100)

        assertEquals(listOf("sample:thread-shape"), h.mutations.actions.map { it.doorKey })
        assertTrue(h.atlas.verified.none {
            it.fromNodeKey.contains(person) || it.toNodeKey.contains(person)
        })
    }

    @Test
    fun deterministicTraceIsObserveOneActionObserveVerifiedWrite() {
        val h = Harness(
            observations = listOf(
                observation("before", "home-v1", doors = listOf(door("before", "settings", MappingDoorKind.SETTINGS))),
                observation("after", "settings-v1", purpose = StructuralScreenPurpose.SETTINGS),
            ),
        )

        h.walker.step(nowMs = 1_100)

        assertEquals(
            "observe:before -> mutate:settings -> observe:after -> atlas:settings",
            h.events.joinToString(" -> "),
        )
    }

    @Test
    fun staleObservationFailsClosed() {
        val h = Harness(
            observations = listOf(
                observation("obs-1", "a", fresh = false,
                    doors = listOf(door("obs-1", "menu", MappingDoorKind.MENU))),
            ),
        )

        val result = h.walker.step(nowMs = 1_100)

        assertEquals(MappingStepResult.Failed("stale_observation"), result)
        assertEquals(0, h.mutations.actions.size)
    }

    @Test
    fun displayMismatchFailsClosed() {
        val h = Harness(
            observations = listOf(
                observation("obs-1", "a", displayId = 99,
                    doors = listOf(door("obs-1", "menu", MappingDoorKind.MENU))),
            ),
        )

        val result = h.walker.step(nowMs = 1_100)

        assertEquals(MappingStepResult.Failed("display_mismatch"), result)
        assertEquals(0, h.mutations.actions.size)
    }

    @Test
    fun outsidePlaceBeforeStepNeverTaps() {
        val h = Harness(
            observations = listOf(
                observation("obs-1", "foreign", doors = listOf(door("obs-1", "menu", MappingDoorKind.MENU)))
                    .copy(inPlace = false),
            ),
        )

        val result = h.walker.step(nowMs = 1_100)

        assertTrue(result is MappingStepResult.LeftPlace)
        assertEquals(0, h.mutations.actions.size)
        assertTrue(h.atlas.verified.isEmpty())
    }

    @Test
    fun doorThatLeavesPlaceIsNotRecordedOrRetried() {
        val h = Harness(
            observations = listOf(
                observation("obs-1", "a", doors = listOf(door("obs-1", "share", MappingDoorKind.MENU))),
                observation("obs-2", "browser").copy(inPlace = false),
                observation("obs-3", "a", doors = listOf(door("obs-3", "share", MappingDoorKind.MENU))),
            ),
        )

        val first = h.walker.step(nowMs = 1_100)
        val second = h.walker.step(nowMs = 1_200)

        assertEquals(MappingStepResult.LeftPlace("door_left_place"), first)
        assertTrue(h.atlas.verified.isEmpty())
        assertTrue(second is MappingStepResult.RoomExhausted)
        assertEquals(1, h.mutations.actions.size)
    }

    private fun assertDangerNeverMutates(danger: MappingDanger) {
        val h = Harness(
            observations = listOf(
                observation("obs-1", "danger",
                    doors = listOf(door("obs-1", "danger-door", MappingDoorKind.SETTINGS))),
            ),
            dangers = mapOf("danger-door" to danger),
        )

        val result = h.walker.step(nowMs = 1_100)

        assertTrue(result is MappingStepResult.RoomExhausted)
        assertEquals(0, h.mutations.actions.size)
        assertEquals(danger, h.session.dangers.single().second)
        assertEquals(null, h.session.partialReason)
    }

    private fun observation(
        id: String,
        structural: String,
        fresh: Boolean = true,
        displayId: Int = 7,
        purpose: StructuralScreenPurpose? = null,
        doors: List<MappingDoor> = emptyList(),
    ) = MappingObservation(
        observationId = id,
        sessionId = "workspace-map",
        displayId = displayId,
        fingerprint = "raw-$id",
        structuralFingerprint = structural,
        fresh = fresh,
        purpose = purpose,
        doors = doors,
    )

    private fun door(observationId: String, key: String, kind: MappingDoorKind) = MappingDoor(
        elementId = "semantic:$observationId:$key",
        observationId = observationId,
        key = key,
        kind = kind,
    )

    @Test
    fun anUnreadableScreenReportsWhy() {
        val h = Harness(observations = emptyList())
        h.observer.cause = "SCREEN_KEPT_CHANGING"
        val result = h.walker.step(nowMs = 1_100)
        assertTrue(result is MappingStepResult.Failed)
        assertEquals("observation_missing_SCREEN_KEPT_CHANGING", (result as MappingStepResult.Failed).reason)
        assertEquals("observation_missing_SCREEN_KEPT_CHANGING", h.session.failure)
        val plain = Harness(observations = emptyList())
        assertEquals("observation_missing", (plain.walker.step(nowMs = 1_100) as MappingStepResult.Failed).reason)
    }

    private inner class Harness(
        observations: List<MappingObservation>,
        dangers: Map<String, MappingDanger> = emptyMap(),
        secretObservations: Set<String> = emptySet(),
        budget: MappingBudget = baseBudget,
        afterObserve: (() -> Unit)? = null,
        identity: MappingIdentity = MappingIdentity.OWN,
    ) {
        val events = mutableListOf<String>()
        val session = FakeSession(budget).also { it.current = it.current.copy(identity = identity) }
        val observer = FakeObservations(observations, events, afterObserve)
        val atlas = FakeAtlas(events)
        val safety = FakeSafety(dangers)
        val mutations = FakeMutations(events)
        val secrets = FakeSecrets(secretObservations)
        val walker = SafeMapperWalker(session, observer, atlas, safety, mutations, secrets)
    }

    private class FakeSession(budget: MappingBudget) : MappingSessionPort {
        var current = MappingSessionSnapshot(
            jobId = "job-1",
            placeId = "package:com.example",
            sessionId = "workspace-map",
            displayId = 7,
            plane = "workspace",
            controlRevision = 11,
            startedAtMs = 1_000,
            budget = budget,
        )
        var currentNode: String? = null
        var secretPause: String? = null
        var humanPause: String? = null
        var partialReason: String? = null
        var failure: String? = null
        val dangers = mutableListOf<Pair<String, MappingDanger>>()

        override fun snapshot(): MappingSessionSnapshot = current
        override fun reportCurrentNode(nodeKey: String) { currentNode = nodeKey }
        override fun pauseNeedsSecret(reason: String) { secretPause = reason }
        override fun pauseHumanControl(reason: String) { humanPause = reason }
        override fun markDanger(doorKey: String, danger: MappingDanger) { dangers += doorKey to danger }
        override fun recordVerifiedProgress(newScreen: Boolean) {
            current = current.copy(
                newScreens = current.newScreens + if (newScreen) 1 else 0,
                consecutiveNonProgress = 0,
            )
        }
        override fun recordNoProgress(doorKey: String) {
            current = current.copy(consecutiveNonProgress = current.consecutiveNonProgress + 1)
        }
        override fun completePartial(reason: String) { partialReason = reason }
        override fun fail(reason: String) { failure = reason }
    }

    private class FakeObservations(
        values: List<MappingObservation>,
        private val events: MutableList<String>,
        private val afterObserve: (() -> Unit)?,
    ) : MappingObservationPort {
        private val queue = ArrayDeque(values)
        val returned = mutableListOf<MappingObservation>()
        var cause: String? = null
        override fun lastFailure(): String? = cause

        override fun freshObservation(session: MappingSessionSnapshot): MappingObservation? {
            if (queue.isEmpty()) return null
            val value = queue.removeFirst()
            returned += value
            events += "observe:${value.observationId}"
            afterObserve?.invoke()
            return value
        }
    }

    private class FakeAtlas(private val events: MutableList<String>) : MappingAtlasPort {
        val verified = mutableListOf<VerifiedStructure>()
        val dangers = mutableListOf<Pair<String, MappingDanger>>()
        val personas = mutableListOf<String>()
        var partialReason: String? = null

        override fun hint(
            placeId: String,
            persona: String,
            observation: MappingObservation,
        ): MappingAtlasHint {
            personas += persona
            return MappingAtlasHint()
        }

        override fun recordVerified(placeId: String, persona: String, structure: VerifiedStructure) {
            personas += persona
            verified += structure
            events += "atlas:${structure.doorKey}"
        }

        override fun markDanger(
            placeId: String,
            persona: String,
            nodeKey: String,
            doorKey: String,
            danger: MappingDanger,
        ) {
            personas += persona
            dangers += doorKey to danger
        }

        override fun markPartial(placeId: String, persona: String, reason: String) {
            personas += persona
            partialReason = reason
        }
    }

    private class FakeSafety(private val dangers: Map<String, MappingDanger>) : MappingSafetyPort {
        override fun classify(observation: MappingObservation, door: MappingDoor): MappingDanger =
            dangers[door.key] ?: MappingDanger.NONE
    }

    private class FakeMutations(private val events: MutableList<String>) : MappingMutationPort {
        val actions = mutableListOf<MappingAction>()
        var onExecute: (() -> Unit)? = null

        override fun execute(
            session: MappingSessionSnapshot,
            action: MappingAction,
        ): MappingMutationResult {
            actions += action
            events += "mutate:${action.doorKey}"
            onExecute?.invoke()
            return MappingMutationResult(performed = true, verifiedByExecutor = true)
        }
    }

    private class FakeSecrets(private val secretObservations: Set<String>) : MappingSecretsPort {
        val requests = mutableListOf<MappingSecretWall>()

        override fun detect(
            session: MappingSessionSnapshot,
            observation: MappingObservation,
        ): MappingSecretWall? =
            if (observation.observationId in secretObservations) {
                MappingSecretWall(
                    placeId = session.placeId,
                    slot = "password",
                    reason = "Login required",
                    elementId = "semantic:${observation.observationId}:password",
                    observationId = observation.observationId,
                    sessionId = observation.sessionId,
                    displayId = observation.displayId,
                )
            } else {
                null
            }

        override fun request(wall: MappingSecretWall) {
            requests += wall
        }
    }
}