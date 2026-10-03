package com.cyclone.mobile.mapping.crawl

/**
 * Deterministic one-mutation-at-a-time mapping walker.
 *
 * This class intentionally has no Android UI, gateway or session implementation of its own.
 * Authority, observation, GATE, PhoneTool execution, Vault prompting and Atlas persistence are all
 * injected phone-owned ports so Agent 004 / Agent 006 can provide their final implementations.
 */
class SafeMapperWalker(
    private val session: MappingSessionPort,
    private val observations: MappingObservationPort,
    private val atlas: MappingAtlasPort,
    private val safety: MappingSafetyPort,
    private val mutations: MappingMutationPort,
    private val secrets: MappingSecretsPort,
) {
    private val attemptsByDoor = linkedMapOf<String, Int>()
    private val unchangedOnFingerprint = linkedSetOf<String>()

    fun step(nowMs: Long): MappingStepResult {
        val beforeSession = session.snapshot()
        authorityResult(beforeSession)?.let { return it }
        budgetResult(beforeSession, nowMs)?.let { return it }

        val before = observations.freshObservation(beforeSession)
            ?: return fail(missing("observation_missing"))
        validateObservation(beforeSession, before)?.let { return it }
        // Never classify or tap a foreign app/site as part of this place. The driver recovers.
        if (!before.inPlace) return MappingStepResult.LeftPlace("before_observation_outside_place")

        secrets.detect(beforeSession, before)?.let { wall ->
            // Look only: the owner's own account is never signed in or out by a mapping pass.
            if (beforeSession.identity == MappingIdentity.OWN) return completePartial(beforeSession, "sign_in_needed")
            session.pauseNeedsSecret(wall.reason)
            secrets.request(wall)
            return MappingStepResult.Paused(PauseReason.NEEDS_SECRET)
        }

        val fromNode = StructuralRoomClassifier.nodeKey(before)
        session.reportCurrentNode(fromNode)
        val hint = atlas.hint(beforeSession.placeId, MAPPING_PERSONA, before)

        val selection = chooseSafeDoor(beforeSession, before, hint, fromNode)
        selection.blocked.forEach { (blockedDoor, danger) ->
            session.markDanger(blockedDoor.key, danger)
        }
        // A dead-end room is not the end of the job: the driver decides whether to backtrack,
        // reset to the place entry, or finish.
        val door = selection.door
            ?: return MappingStepResult.RoomExhausted(fromNode)

        // Human/companion/control-revision changes that happen while deciding must win before input.
        val preMutationSession = session.snapshot()
        authorityResult(preMutationSession)?.let { return it }
        if (!sameBinding(beforeSession, preMutationSession)) {
            session.pauseHumanControl("mapping_authority_changed")
            return MappingStepResult.Paused(PauseReason.HUMAN_CONTROL)
        }

        val action = MappingAction(
            observationId = before.observationId,
            elementId = door.elementId,
            doorKey = door.key,
            kind = door.kind,
        )
        attemptsByDoor[door.key] = (attemptsByDoor[door.key] ?: 0) + 1

        // Exactly one screen-changing mutation occurs in a step.
        val mutation = mutations.execute(preMutationSession, action)

        // Authority is checked again before any crawler continuation. If the human/companion took
        // over while the action was settling, the crawler stops immediately.
        if (mutation.errorCode == "HUMAN_HAS_CONTROL") {
            session.pauseHumanControl("executor_human_has_control")
            return MappingStepResult.Paused(PauseReason.HUMAN_CONTROL)
        }
        if (mutation.errorCode in setOf(
                "FRESH_OBSERVATION_REQUIRED",
                "WORKSPACE_SCOPE_CONFLICT",
                "TARGET_SCOPE_MISMATCH",
            )
        ) {
            return fail(mutation.errorCode?.lowercase() ?: "mutation_error")
        }

        val afterMutationSession = session.snapshot()
        authorityResult(afterMutationSession)?.let { return it }
        if (!sameBinding(preMutationSession, afterMutationSession)) {
            session.pauseHumanControl("mapping_authority_changed_after_mutation")
            return MappingStepResult.Paused(PauseReason.HUMAN_CONTROL)
        }

        // Eyes beat the map: every screen-changing attempt is followed by a new observation before
        // another decision can ever be made.
        val after = observations.freshObservation(afterMutationSession)
            ?: return fail(missing("after_observation_missing"))
        validateObservation(afterMutationSession, after)?.let { return it }
        if (after.observationId == before.observationId) {
            return fail("after_observation_not_fresh")
        }
        if (!after.inPlace) {
            // The door led out of the place (browser, share sheet, another app). Not a room, and
            // never a door worth retrying in this job.
            unchangedOnFingerprint += unchangedKey(before, door)
            session.recordNoProgress(door.key)
            return MappingStepResult.LeftPlace("door_left_place")
        }

        val toNode = StructuralRoomClassifier.nodeKey(after)
        val progressed = mutation.performed &&
            mutation.verifiedByExecutor &&
            StructuralProgressVerifier.changed(before, after)

        if (progressed) {
            atlas.recordVerified(
                beforeSession.placeId,
                MAPPING_PERSONA,
                VerifiedStructure(
                    fromNodeKey = fromNode,
                    toNodeKey = toNode,
                    doorKey = door.key,
                    doorKind = door.kind,
                    beforeStructuralFingerprint = before.structuralFingerprint,
                    afterStructuralFingerprint = after.structuralFingerprint,
                ),
            )
            session.reportCurrentNode(toNode)
            session.recordVerifiedProgress(newScreen = fromNode != toNode ||
                before.structuralFingerprint != after.structuralFingerprint)

            // If the verified landing is a credential wall, pause immediately before any further
            // mutation. The walker only passes metadata to the Run-1 Secrets Card seam.
            secrets.detect(afterMutationSession, after)?.let { wall ->
                session.pauseNeedsSecret(wall.reason)
                secrets.request(wall)
                return MappingStepResult.Paused(PauseReason.NEEDS_SECRET)
            }

            val postProgress = session.snapshot()
            budgetResult(postProgress, nowMs)?.let { return it }
            return MappingStepResult.Progress(action, fromNode, toNode)
        }

        unchangedOnFingerprint += unchangedKey(before, door)
        session.recordNoProgress(door.key)

        val postNoProgress = session.snapshot()
        budgetResult(postNoProgress, nowMs)?.let { return it }

        val reason = mutation.errorCode ?: "unchanged_or_unverified"
        return MappingStepResult.NoProgress(action, reason)
    }

    private data class DoorSelection(
        val door: MappingDoor?,
        val blocked: List<Pair<MappingDoor, MappingDanger>>,
    )

    private fun chooseSafeDoor(
        snapshot: MappingSessionSnapshot,
        observation: MappingObservation,
        hint: MappingAtlasHint,
        nodeKey: String,
    ): DoorSelection {
        val budget = snapshot.budget
        val candidates = StructuralDoorPolicy.rank(observation.doors)
            .filter { it.enabled && it.visible }
            .filter { it.observationId == observation.observationId }
            .filter { it.kind != MappingDoorKind.CONTENT_ROW && it.kind != MappingDoorKind.UNKNOWN }
            .filterNot { it.key in hint.knownDoorKeys || it.key in hint.darkDoorKeys }
            .filter { (attemptsByDoor[it.key] ?: 0) < budget.maxAttemptsPerDoor }
            .filterNot { unchangedKey(observation, it) in unchangedOnFingerprint }

        val blocked = mutableListOf<Pair<MappingDoor, MappingDanger>>()
        for (door in candidates) {
            val danger = safety.classify(observation, door)
            if (danger == MappingDanger.NONE) return DoorSelection(door, blocked)

            // Safety boundaries are structural information, not permission to cross them.
            // Record the dark door in Atlas, but do not pause the whole job while a safe
            // unexplored alternative still exists.
            blocked += door to danger
            atlas.markDanger(snapshot.placeId, MAPPING_PERSONA, nodeKey, door.key, danger)
        }
        return DoorSelection(null, blocked)
    }

    private fun validateObservation(
        snapshot: MappingSessionSnapshot,
        observation: MappingObservation,
    ): MappingStepResult? {
        if (!observation.fresh) return fail("stale_observation")
        if (observation.sessionId != snapshot.sessionId) return fail("session_mismatch")
        if (observation.displayId != snapshot.displayId) return fail("display_mismatch")
        if (observation.observationId.isBlank()) return fail("observation_id_missing")
        if (observation.fingerprint.isBlank() || observation.structuralFingerprint.isBlank()) {
            return fail("observation_fingerprint_missing")
        }
        return null
    }

    private fun authorityResult(snapshot: MappingSessionSnapshot): MappingStepResult? = when (snapshot.authority) {
        MappingAuthority.OWNED -> null
        MappingAuthority.HUMAN_CONTROL,
        MappingAuthority.COMPANION_CONTROL -> {
            session.pauseHumanControl(snapshot.authority.name.lowercase())
            MappingStepResult.Paused(PauseReason.HUMAN_CONTROL)
        }
        MappingAuthority.SESSION_MISSING -> fail("session_required")
        MappingAuthority.DISPLAY_MISMATCH -> fail("display_mismatch")
        MappingAuthority.PLANE_CHANGED -> fail("plane_changed")
        MappingAuthority.STALE_CONTROL_REVISION -> fail("stale_control_revision")
    }

    private fun budgetResult(snapshot: MappingSessionSnapshot, nowMs: Long): MappingStepResult? {
        val budget = snapshot.budget
        val reason = when {
            snapshot.newScreens >= budget.maxNewScreens -> "budget_max_new_screens"
            nowMs - snapshot.startedAtMs >= budget.maxElapsedMs -> "budget_max_elapsed"
            snapshot.consecutiveNonProgress >= budget.maxConsecutiveNonProgress ->
                "budget_max_consecutive_non_progress"
            else -> null
        } ?: return null
        return completePartial(snapshot, reason)
    }

    private fun completePartial(snapshot: MappingSessionSnapshot, reason: String): MappingStepResult {
        atlas.markPartial(snapshot.placeId, MAPPING_PERSONA, reason)
        session.completePartial(reason)
        return MappingStepResult.CompletedPartial(reason)
    }

    /** The report says why the screen could not be read, e.g. OBSERVATION_MISSING_SCREEN_KEPT_CHANGING. */
    private fun missing(base: String): String = observations.lastFailure()?.let { "${base}_$it" } ?: base

    private fun fail(reason: String): MappingStepResult.Failed {
        session.fail(reason)
        return MappingStepResult.Failed(reason)
    }

    private fun sameBinding(a: MappingSessionSnapshot, b: MappingSessionSnapshot): Boolean =
        a.jobId == b.jobId &&
            a.placeId == b.placeId &&
            a.sessionId == b.sessionId &&
            a.displayId == b.displayId &&
            a.plane == b.plane &&
            a.controlRevision == b.controlRevision

    private fun unchangedKey(observation: MappingObservation, door: MappingDoor): String =
        "${observation.structuralFingerprint}|${door.key}"
}

internal object StructuralProgressVerifier {
    fun changed(before: MappingObservation, after: MappingObservation): Boolean =
        before.structuralFingerprint != after.structuralFingerprint ||
            StructuralRoomClassifier.nodeKey(before) != StructuralRoomClassifier.nodeKey(after)
}

/**
 * Produces durable structural node identities without copying ordinary control labels/content.
 */
internal object StructuralRoomClassifier {
    fun nodeKey(observation: MappingObservation): String {
        val purpose = observation.purpose ?: infer(observation.doors)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(observation.structuralFingerprint.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
        return "screen:${purpose.name.lowercase()}:$digest"
    }

    private fun infer(doors: List<MappingDoor>): StructuralScreenPurpose {
        val kinds = doors.mapTo(linkedSetOf()) { it.kind }
        // A Settings/Search/Menu *door* describes a destination, not the room we are currently in.
        // Inferring the current room from destination labels caused unchanged screens to look like
        // progress when the candidate list changed. Only list-shape evidence is safe enough here;
        // richer purpose classification belongs in the fresh-observation projection.
        return if (
            MappingDoorKind.STRUCTURAL_SAMPLE in kinds ||
            MappingDoorKind.CONTENT_ROW in kinds
        ) {
            StructuralScreenPurpose.LIST
        } else {
            StructuralScreenPurpose.UNKNOWN
        }
    }
}

internal object StructuralDoorPolicy {
    private val priority = mapOf(
        MappingDoorKind.TAB to 0,
        MappingDoorKind.MENU to 1,
        MappingDoorKind.REVEAL to 2,
        MappingDoorKind.NAV_DRAWER to 3,
        MappingDoorKind.SETTINGS to 4,
        MappingDoorKind.ACCOUNT to 5,
        MappingDoorKind.SEARCH to 6,
        MappingDoorKind.STRUCTURAL_SAMPLE to 7,
        MappingDoorKind.BACK to 8,
        MappingDoorKind.HOME to 9,
        MappingDoorKind.CONTENT_ROW to 90,
        MappingDoorKind.UNKNOWN to 100,
    )

    fun rank(doors: List<MappingDoor>): List<MappingDoor> =
        doors.sortedWith(compareBy<MappingDoor> { !it.focus }.thenBy { priority[it.kind] ?: 100 }.thenBy { it.key })
}
