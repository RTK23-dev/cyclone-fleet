package com.cyclone.mobile.mapping.session

import com.cyclone.mobile.runtime.session.SessionPlane

class MappingSessionException(
    val code: String,
    override val message: String,
) : IllegalStateException(message)

enum class MappingSessionState(val wireValue: String) {
    RUNNING("running"),
    PAUSED("paused"),
    NEEDS_SECRET("needs-secret"),
    HUMAN_CONTROL("human-control"),
    COMPLETED("completed"),
    STOPPED("stopped"),
    FAILED("failed");

    val terminal: Boolean
        get() = this in setOf(COMPLETED, STOPPED, FAILED)
}

enum class MappingAtlasStatus(val wireValue: String) {
    PARTIAL("partial"),
    MAPPED("mapped"),
}

enum class MappingDanger(val wireValue: String) {
    PAYMENT("payment"),
    SEND_PUBLIC("send-public"),
    DELETE_ACCOUNT("delete-account"),
    LOGOUT_ALL("logout-all"),
    PERMISSION("permission"),
    AUTHENTICATION("authentication"),
    UNKNOWN("unknown"),
}

enum class MappingBoundary(val wireValue: String) {
    DANGER("danger"),
    AUTHENTICATION("authentication"),
    HUMAN_CONTROL("human-control"),
    BUDGET("budget"),
    UNKNOWN("unknown"),
}

data class MappingBudget(
    val maxNewScreens: Int,
    val maxElapsedMs: Long,
    val maxConsecutiveNonProgress: Int,
    val maxAttemptsPerDoor: Int,
) {
    init {
        require(maxNewScreens in 1..500) { "maxNewScreens must be 1..500" }
        require(maxElapsedMs in 1_000L..7_200_000L) { "maxElapsedMs must be 1s..2h" }
        require(maxConsecutiveNonProgress in 1..100) { "maxConsecutiveNonProgress must be 1..100" }
        require(maxAttemptsPerDoor in 1..20) { "maxAttemptsPerDoor must be 1..20" }
    }

    companion object {
        val DEFAULT = MappingBudget(
            maxNewScreens = 40,
            maxElapsedMs = 10 * 60_000L,
            maxConsecutiveNonProgress = 6,
            maxAttemptsPerDoor = 3,
        )
    }
}

data class MappingProgress(
    val newScreens: Int = 0,
    val verifiedMutations: Int = 0,
    val consecutiveNonProgress: Int = 0,
    val attemptedDoors: Int = 0,
    val remainingDarkRegions: Int = 0,
) {
    init {
        require(newScreens >= 0)
        require(verifiedMutations >= 0)
        require(consecutiveNonProgress >= 0)
        require(attemptedDoors >= 0)
        require(remainingDarkRegions >= 0)
    }
}

data class MappingPlaneRequest(
    val sessionId: String,
    val displayId: Int,
    val workspaceId: String? = null,
    val workspaceGeneration: Long? = null,
    val executionGeneration: Long? = null,
) {
    init {
        if (sessionId.isBlank()) {
            throw MappingSessionException("SESSION_REQUIRED", "sessionId is required for mapping.")
        }
        if (displayId < 0) {
            throw MappingSessionException("SESSION_DISPLAY_MISMATCH", "displayId must be non-negative.")
        }
        if ((workspaceId == null) != (workspaceGeneration == null)) {
            throw MappingSessionException(
                "WORKSPACE_GENERATION_REQUIRED",
                "workspaceId and workspaceGeneration must be supplied together.",
            )
        }
        if (workspaceGeneration != null && workspaceGeneration < 0L) {
            throw MappingSessionException("WORKSPACE_GENERATION_REQUIRED", "workspaceGeneration must be non-negative.")
        }
        if (executionGeneration != null && executionGeneration < 0L) {
            throw MappingSessionException("STALE_CONTROL_REVISION", "executionGeneration must be non-negative.")
        }
        when {
            workspaceId != null &&
                (sessionId != "default-foreground" || displayId != 0) -> {
                throw MappingSessionException(
                    "SESSION_DISPLAY_MISMATCH",
                    "Layer-2 mapping requires default-foreground/display 0.",
                )
            }
            workspaceId == null && sessionId == "default-foreground" && displayId != 0 -> {
                throw MappingSessionException(
                    "SESSION_DISPLAY_MISMATCH",
                    "default-foreground mapping must use display 0.",
                )
            }
            workspaceId == null && sessionId != "default-foreground" && displayId <= 0 -> {
                throw MappingSessionException(
                    "SESSION_DISPLAY_MISMATCH",
                    "Named mapping sessions require a nonzero displayId.",
                )
            }
        }
    }
}

data class MappingControlLease(
    val plane: SessionPlane,
    val controlRevision: Long,
    val executionGeneration: Long? = null,
) {
    init {
        require(controlRevision >= 0L)
        require(executionGeneration == null || executionGeneration >= 0L)
    }
}

data class MappingStartRequest(
    val placeId: String,
    val persona: String,
    val plane: MappingPlaneRequest,
    val budget: MappingBudget = MappingBudget.DEFAULT,
    val identity: com.cyclone.mobile.mapping.crawl.MappingIdentity = com.cyclone.mobile.mapping.crawl.MappingIdentity.OWN,
) {
    init {
        requirePlaceId(placeId)
        requirePersona(persona)
    }
}

data class MappingJob(
    val mappingJobId: String,
    val placeId: String,
    val persona: String,
    val planeRequest: MappingPlaneRequest,
    val lease: MappingControlLease,
    val budget: MappingBudget,
    val state: MappingSessionState,
    val currentAtlasNodeId: String? = null,
    val progress: MappingProgress = MappingProgress(),
    val atlasStatus: MappingAtlasStatus? = null,
    val danger: MappingDanger? = null,
    val boundary: MappingBoundary? = null,
    val startedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val failureCode: String? = null,
    val identity: com.cyclone.mobile.mapping.crawl.MappingIdentity = com.cyclone.mobile.mapping.crawl.MappingIdentity.OWN,
) {
    init {
        require(mappingJobId.matches(Regex("[A-Za-z0-9_-]{8,120}")))
        requirePlaceId(placeId)
        requirePersona(persona)
        require(startedAtEpochMs >= 0L)
        require(updatedAtEpochMs >= startedAtEpochMs)
        require(currentAtlasNodeId == null || AtlasStructuralIds.screen(currentAtlasNodeId))
        require(failureCode == null || failureCode.matches(Regex("[A-Z0-9_]{1,80}")))
    }

    val ownsPlane: Boolean
        get() = !state.terminal
}

data class MappingVerifiedProgress(
    val currentAtlasNodeId: String? = null,
    val discoveredNewScreen: Boolean = false,
    val progressMade: Boolean = true,
    val attemptedDoor: Boolean = false,
    val remainingDarkRegions: Int? = null,
    val atlasChanges: List<AtlasStructuralChange> = emptyList(),
) {
    init {
        require(currentAtlasNodeId == null || AtlasStructuralIds.screen(currentAtlasNodeId))
        require(remainingDarkRegions == null || remainingDarkRegions >= 0)
    }
}

interface MappingAuthority {
    fun acquire(request: MappingPlaneRequest): MappingControlLease
    fun revalidate(lease: MappingControlLease)
}

interface MappingClock {
    fun nowEpochMs(): Long

    companion object {
        val SYSTEM = object : MappingClock {
            override fun nowEpochMs(): Long = System.currentTimeMillis()
        }
    }
}

/**
 * Narrow control seam for Agent 005. The crawler never owns a second lock/state machine.
 */
interface MappingSessionControl {
    fun start(request: MappingStartRequest): MappingJob
    fun resume(mappingJobId: String, plane: MappingPlaneRequest): MappingJob
    fun requireMutation(mappingJobId: String): MappingJob
    fun reportCurrentNode(mappingJobId: String, currentAtlasNodeId: String?): MappingJob
    fun pause(mappingJobId: String): MappingJob
    fun pauseForSecret(mappingJobId: String): MappingJob
    fun pauseForHumanControl(mappingJobId: String): MappingJob
    fun markDanger(mappingJobId: String, danger: MappingDanger): MappingJob
    fun markBoundary(mappingJobId: String, boundary: MappingBoundary): MappingJob
    fun recordVerifiedProgress(mappingJobId: String, progress: MappingVerifiedProgress): MappingJob
    fun complete(mappingJobId: String, atlasStatus: MappingAtlasStatus): MappingJob
    fun stop(mappingJobId: String): MappingJob
    fun fail(mappingJobId: String, failureCode: String): MappingJob
    fun status(mappingJobId: String): MappingJob?
    fun statusForPlane(plane: MappingPlaneRequest): MappingJob?
}

internal fun requirePlaceId(placeId: String) {
    val packagePlace = Regex("^package:[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
    val chromePlace = Regex("^chrome:https?://[^\\s/]+(?::[0-9]{1,5})?$")
    if (!packagePlace.matches(placeId) && !chromePlace.matches(placeId)) {
        throw MappingSessionException("INVALID_REQUEST", "Invalid mapping placeId.")
    }
}

internal fun requirePersona(persona: String) {
    if (persona != "live" && persona != "mapping") {
        throw MappingSessionException("INVALID_REQUEST", "persona must be live or mapping.")
    }
}
