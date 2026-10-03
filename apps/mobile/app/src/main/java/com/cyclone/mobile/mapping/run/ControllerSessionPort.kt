package com.cyclone.mobile.mapping.run

import com.cyclone.mobile.mapping.crawl.MappingAuthority
import com.cyclone.mobile.mapping.crawl.MappingBudget
import com.cyclone.mobile.mapping.crawl.MappingDanger
import com.cyclone.mobile.mapping.crawl.MappingSessionPort
import com.cyclone.mobile.mapping.crawl.MappingSessionSnapshot
import com.cyclone.mobile.mapping.session.MappingAtlasStatus
import com.cyclone.mobile.mapping.session.MappingBoundary
import com.cyclone.mobile.mapping.session.MappingJob
import com.cyclone.mobile.mapping.session.MappingSessionControl
import com.cyclone.mobile.mapping.session.MappingSessionException
import com.cyclone.mobile.mapping.session.MappingSessionState
import com.cyclone.mobile.mapping.session.MappingVerifiedProgress

/** Thrown out of a walker step when the job stopped running (paused/stopped elsewhere). */
class MappingInterrupted(val state: MappingSessionState?) : RuntimeException("Mapping job is not running")

/**
 * The walker's session seam over the one phone-owned [MappingSessionControl]. There is no second
 * state machine: every snapshot re-reads the job and revalidates its plane lease.
 *
 * A dangerous door is structural information for the Atlas, not a reason to pause the whole job,
 * so [markDanger] only counts it here.
 */
class ControllerSessionPort(
    private val controller: MappingSessionControl,
    private val jobId: String,
) : MappingSessionPort {
    @Volatile var blockedDoors: Int = 0
        private set
    @Volatile var noProgressDoors: Int = 0
        private set

    override fun snapshot(): MappingSessionSnapshot {
        val job = controller.status(jobId) ?: throw MappingInterrupted(null)
        if (job.state != MappingSessionState.RUNNING) throw MappingInterrupted(job.state)
        val authority = try {
            controller.requireMutation(jobId)
            MappingAuthority.OWNED
        } catch (error: MappingSessionException) {
            authorityFor(error.code) ?: throw MappingInterrupted(controller.status(jobId)?.state)
        }
        val current = controller.status(jobId) ?: throw MappingInterrupted(null)
        return snapshotOf(current, authority)
    }

    override fun reportCurrentNode(nodeKey: String) {
        controller.reportCurrentNode(jobId, nodeKey)
    }

    override fun pauseNeedsSecret(reason: String) {
        controller.pauseForSecret(jobId)
    }

    override fun pauseHumanControl(reason: String) {
        val job = controller.status(jobId) ?: return
        if (!job.state.terminal) controller.pauseForHumanControl(jobId)
    }

    override fun markDanger(doorKey: String, danger: MappingDanger) {
        if (danger != MappingDanger.NONE) blockedDoors += 1
    }

    override fun recordVerifiedProgress(newScreen: Boolean) {
        val job = controller.status(jobId)
        controller.recordVerifiedProgress(
            jobId,
            MappingVerifiedProgress(
                currentAtlasNodeId = job?.currentAtlasNodeId,
                discoveredNewScreen = newScreen,
                progressMade = true,
                attemptedDoor = true,
            ),
        )
    }

    override fun recordNoProgress(doorKey: String) {
        noProgressDoors += 1
        controller.recordVerifiedProgress(
            jobId,
            MappingVerifiedProgress(progressMade = false, attemptedDoor = true),
        )
    }

    override fun completePartial(reason: String) {
        val job = controller.status(jobId) ?: return
        if (job.state.terminal) return
        // A look-only pass that met a sign-in wall says so, so Glass can tell the owner why it ended.
        if (reason == "sign_in_needed") runCatching { controller.markBoundary(jobId, MappingBoundary.AUTHENTICATION) }
        controller.complete(jobId, MappingAtlasStatus.PARTIAL)
    }

    override fun fail(reason: String) {
        val job = controller.status(jobId) ?: return
        if (!job.state.terminal) controller.fail(jobId, failureCode(reason))
    }

    companion object {
        fun failureCode(reason: String): String =
            reason.uppercase().replace(Regex("[^A-Z0-9_]+"), "_").trim('_').take(80).ifBlank { "MAPPING_FAILED" }

        /**
         * Foreground control changes (a human touch, Take control) pause the walk; they are not a
         * failure. Identity problems still fail closed through the walker.
         */
        internal fun authorityFor(code: String): MappingAuthority? = when (code) {
            "HUMAN_HAS_CONTROL", "STALE_CONTROL_REVISION" -> MappingAuthority.HUMAN_CONTROL
            "SESSION_REQUIRED" -> MappingAuthority.SESSION_MISSING
            "SESSION_DISPLAY_MISMATCH" -> MappingAuthority.DISPLAY_MISMATCH
            "WORKSPACE_GENERATION_REQUIRED" -> MappingAuthority.PLANE_CHANGED
            else -> null
        }

        internal fun snapshotOf(job: MappingJob, authority: MappingAuthority) = MappingSessionSnapshot(
            jobId = job.mappingJobId,
            placeId = job.placeId,
            sessionId = job.planeRequest.sessionId,
            displayId = job.planeRequest.displayId,
            plane = job.lease.plane.kind.name,
            controlRevision = job.lease.controlRevision,
            workspaceId = job.planeRequest.workspaceId,
            workspaceGeneration = job.planeRequest.workspaceGeneration,
            executionGeneration = job.lease.executionGeneration,
            startedAtMs = job.startedAtEpochMs,
            budget = MappingBudget(
                maxNewScreens = job.budget.maxNewScreens,
                maxElapsedMs = job.budget.maxElapsedMs,
                maxConsecutiveNonProgress = job.budget.maxConsecutiveNonProgress,
                maxAttemptsPerDoor = job.budget.maxAttemptsPerDoor,
            ),
            authority = authority,
            newScreens = job.progress.newScreens,
            consecutiveNonProgress = job.progress.consecutiveNonProgress,
            identity = job.identity,
        )
    }
}
