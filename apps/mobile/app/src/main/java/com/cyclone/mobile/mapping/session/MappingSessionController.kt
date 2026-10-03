package com.cyclone.mobile.mapping.session

import java.util.UUID

class MappingSessionController(
    private val authority: MappingAuthority,
    private val journal: AtlasDiffJournal,
    private val clock: MappingClock = MappingClock.SYSTEM,
    private val idFactory: () -> String = { "map-" + UUID.randomUUID().toString().replace("-", "") },
) : MappingSessionControl {
    private val jobs = linkedMapOf<String, MappingJob>()
    private val activeByPlane = linkedMapOf<String, String>()

    @Synchronized
    override fun start(request: MappingStartRequest): MappingJob {
        val lease = authority.acquire(request.plane)
        val planeKey = planeKey(lease)
        activeByPlane[planeKey]?.let { existingId ->
            val existing = jobs[existingId]
            if (existing != null && existing.ownsPlane) {
                throw MappingSessionException(
                    "MAPPING_PLANE_BUSY",
                    "Another nonterminal mapping job already owns this phone plane.",
                )
            }
            activeByPlane.remove(planeKey)
        }
        val now = clock.nowEpochMs()
        val job = MappingJob(
            mappingJobId = idFactory(),
            placeId = request.placeId,
            persona = request.persona,
            planeRequest = request.plane,
            lease = lease,
            budget = request.budget,
            state = MappingSessionState.RUNNING,
            startedAtEpochMs = now,
            updatedAtEpochMs = now,
            identity = request.identity,
        )
        jobs[job.mappingJobId] = job
        activeByPlane[planeKey] = job.mappingJobId
        return job
    }

    @Synchronized
    override fun resume(mappingJobId: String, plane: MappingPlaneRequest): MappingJob {
        val current = requireJob(mappingJobId)
        if (current.state !in setOf(
                MappingSessionState.PAUSED,
                MappingSessionState.NEEDS_SECRET,
                MappingSessionState.HUMAN_CONTROL,
            )
        ) {
            throw MappingSessionException("MAPPING_INVALID_STATE", "Only a paused mapping job can resume.")
        }
        val refreshed = authority.acquire(plane)
        if (!samePlane(current.lease, refreshed)) {
            throw MappingSessionException(
                "SESSION_DISPLAY_MISMATCH",
                "Mapping resume must remain on the original phone plane.",
            )
        }
        val now = clock.nowEpochMs()
        val updated = current.copy(
            planeRequest = plane,
            lease = refreshed,
            state = MappingSessionState.RUNNING,
            boundary = null,
            updatedAtEpochMs = now,
        )
        jobs[mappingJobId] = updated
        activeByPlane[planeKey(refreshed)] = mappingJobId
        return updated
    }

    @Synchronized
    override fun requireMutation(mappingJobId: String): MappingJob {
        val current = requireJob(mappingJobId)
        if (current.state != MappingSessionState.RUNNING) {
            throw MappingSessionException("MAPPING_INVALID_STATE", "Mapping mutation requires a running job.")
        }
        try {
            authority.revalidate(current.lease)
        } catch (error: MappingSessionException) {
            val nextState = if (error.code == "HUMAN_HAS_CONTROL") {
                MappingSessionState.HUMAN_CONTROL
            } else {
                MappingSessionState.PAUSED
            }
            jobs[mappingJobId] = current.copy(
                state = nextState,
                boundary = if (nextState == MappingSessionState.HUMAN_CONTROL) {
                    MappingBoundary.HUMAN_CONTROL
                } else {
                    current.boundary
                },
                updatedAtEpochMs = clock.nowEpochMs(),
            )
            throw error
        }
        return current
    }

    @Synchronized
    override fun reportCurrentNode(mappingJobId: String, currentAtlasNodeId: String?): MappingJob {
        val current = requireMutation(mappingJobId)
        currentAtlasNodeId?.let { AtlasStructuralIds.requireScreen(it, "currentAtlasNodeId") }
        return replace(current.copy(
            currentAtlasNodeId = currentAtlasNodeId,
            updatedAtEpochMs = clock.nowEpochMs(),
        ))
    }

    @Synchronized
    override fun pause(mappingJobId: String): MappingJob =
        transitionNonterminal(mappingJobId, MappingSessionState.PAUSED)

    @Synchronized
    override fun pauseForSecret(mappingJobId: String): MappingJob =
        transitionNonterminal(
            mappingJobId,
            MappingSessionState.NEEDS_SECRET,
            MappingBoundary.AUTHENTICATION,
        )

    @Synchronized
    override fun pauseForHumanControl(mappingJobId: String): MappingJob =
        transitionNonterminal(
            mappingJobId,
            MappingSessionState.HUMAN_CONTROL,
            MappingBoundary.HUMAN_CONTROL,
        )

    @Synchronized
    override fun markDanger(mappingJobId: String, danger: MappingDanger): MappingJob {
        val current = requireJob(mappingJobId)
        requireNonterminal(current)
        return replace(current.copy(
            state = MappingSessionState.PAUSED,
            danger = danger,
            boundary = MappingBoundary.DANGER,
            updatedAtEpochMs = clock.nowEpochMs(),
        ))
    }

    @Synchronized
    override fun markBoundary(mappingJobId: String, boundary: MappingBoundary): MappingJob {
        val current = requireJob(mappingJobId)
        requireNonterminal(current)
        return replace(current.copy(
            state = MappingSessionState.PAUSED,
            boundary = boundary,
            updatedAtEpochMs = clock.nowEpochMs(),
        ))
    }

    @Synchronized
    override fun recordVerifiedProgress(
        mappingJobId: String,
        progress: MappingVerifiedProgress,
    ): MappingJob {
        val current = requireMutation(mappingJobId)
        val nextProgress = current.progress.copy(
            newScreens = current.progress.newScreens + if (progress.discoveredNewScreen) 1 else 0,
            verifiedMutations = current.progress.verifiedMutations + 1,
            consecutiveNonProgress = if (progress.progressMade) {
                0
            } else {
                current.progress.consecutiveNonProgress + 1
            },
            attemptedDoors = current.progress.attemptedDoors + if (progress.attemptedDoor) 1 else 0,
            remainingDarkRegions = progress.remainingDarkRegions ?: current.progress.remainingDarkRegions,
        )
        if (progress.atlasChanges.isNotEmpty()) {
            journal.append(current.placeId, current.persona, progress.atlasChanges)
        }
        return replace(current.copy(
            currentAtlasNodeId = progress.currentAtlasNodeId ?: current.currentAtlasNodeId,
            progress = nextProgress,
            updatedAtEpochMs = clock.nowEpochMs(),
        ))
    }

    @Synchronized
    override fun complete(mappingJobId: String, atlasStatus: MappingAtlasStatus): MappingJob {
        val current = requireJob(mappingJobId)
        requireNonterminal(current)
        return terminal(current.copy(
            state = MappingSessionState.COMPLETED,
            atlasStatus = atlasStatus,
            updatedAtEpochMs = clock.nowEpochMs(),
        ))
    }

    @Synchronized
    override fun stop(mappingJobId: String): MappingJob {
        val current = requireJob(mappingJobId)
        if (current.state.terminal) return current
        return terminal(current.copy(
            state = MappingSessionState.STOPPED,
            updatedAtEpochMs = clock.nowEpochMs(),
        ))
    }

    @Synchronized
    override fun fail(mappingJobId: String, failureCode: String): MappingJob {
        require(failureCode.matches(Regex("[A-Z0-9_]{1,80}"))) {
            "failureCode must be a bounded code"
        }
        val current = requireJob(mappingJobId)
        if (current.state.terminal) return current
        return terminal(current.copy(
            state = MappingSessionState.FAILED,
            failureCode = failureCode,
            updatedAtEpochMs = clock.nowEpochMs(),
        ))
    }

    @Synchronized
    override fun status(mappingJobId: String): MappingJob? = jobs[mappingJobId]

    @Synchronized
    override fun statusForPlane(plane: MappingPlaneRequest): MappingJob? {
        val id = activeByPlane[requestPlaneKey(plane)] ?: return null
        return jobs[id]
    }

    /**
     * Publishes structural Atlas changes the phone already committed (room discovered, place status)
     * without claiming a new mutation. Terminal jobs may still publish their final place status.
     */
    @Synchronized
    fun appendAtlasChanges(mappingJobId: String, changes: List<AtlasStructuralChange>) {
        if (changes.isEmpty()) return
        val current = requireJob(mappingJobId)
        journal.append(current.placeId, current.persona, changes)
    }

    fun atlasDiff(placeId: String, persona: String, since: String?): AtlasDiffResult =
        journal.diff(placeId, persona, since)

    @Synchronized
    private fun transitionNonterminal(
        mappingJobId: String,
        state: MappingSessionState,
        boundary: MappingBoundary? = null,
    ): MappingJob {
        val current = requireJob(mappingJobId)
        requireNonterminal(current)
        return replace(current.copy(
            state = state,
            boundary = boundary ?: current.boundary,
            updatedAtEpochMs = clock.nowEpochMs(),
        ))
    }

    private fun requireJob(mappingJobId: String): MappingJob =
        jobs[mappingJobId]
            ?: throw MappingSessionException("MAPPING_JOB_NOT_FOUND", "Unknown mapping job.")

    private fun requireNonterminal(job: MappingJob) {
        if (job.state.terminal) {
            throw MappingSessionException("MAPPING_INVALID_STATE", "Mapping job is already terminal.")
        }
    }

    private fun replace(job: MappingJob): MappingJob {
        jobs[job.mappingJobId] = job
        return job
    }

    private fun terminal(job: MappingJob): MappingJob {
        jobs[job.mappingJobId] = job
        activeByPlane.remove(planeKey(job.lease), job.mappingJobId)
        return job
    }

    private fun samePlane(left: MappingControlLease, right: MappingControlLease): Boolean =
        planeKey(left) == planeKey(right)

    private fun planeKey(lease: MappingControlLease): String = with(lease.plane) {
        kind.name + "|" + sessionId + "|" + displayId + "|" + workspaceId.orEmpty()
    }

    private fun requestPlaneKey(request: MappingPlaneRequest): String {
        val kind = when {
            request.workspaceId != null -> "LAYER2_WORKSPACE"
            request.sessionId == "default-foreground" -> "FOREGROUND"
            else -> "SESSION_KERNEL_VD"
        }
        return kind + "|" + request.sessionId + "|" + request.displayId + "|" + request.workspaceId.orEmpty()
    }
}
