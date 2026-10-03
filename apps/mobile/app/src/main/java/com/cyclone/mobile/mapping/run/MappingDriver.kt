package com.cyclone.mobile.mapping.run

import com.cyclone.mobile.mapping.crawl.MappingAuthority
import com.cyclone.mobile.mapping.crawl.MappingNavigationPort
import com.cyclone.mobile.mapping.crawl.MappingStepResult
import com.cyclone.mobile.mapping.crawl.SafeMapperWalker
import com.cyclone.mobile.mapping.session.MappingAtlasStatus
import com.cyclone.mobile.mapping.session.MappingJob
import com.cyclone.mobile.mapping.session.MappingSessionControl
import com.cyclone.mobile.mapping.session.MappingSessionException
import com.cyclone.mobile.mapping.session.MappingSessionState

/** One structural line of the mapping report. Codes and counts only, never screen content. */
data class MappingDriverEvent(
    val atEpochMs: Long,
    val kind: String,
    val detail: String,
    /** Structural room key the step reached (progress) or finished (room exhausted); null otherwise. */
    val roomKey: String? = null,
)

/**
 * Runs one mapping job to a parked state: completed, stopped, failed, or paused (secret / human /
 * operator pause). It owns no state machine of its own; the controller stays the authority, and
 * every step re-reads it, so Pause/Stop from the phone, Glass or the notification win before the
 * next mutation.
 *
 * Exploration is depth-first: the walker opens one unexplored safe door per step. When a room is
 * exhausted the driver goes back; after [maxBacksPerClimb] backs without finding a new door, or if
 * the phone left the place, it relaunches the place at its entry room. An exhausted entry room right
 * after a relaunch means the reachable house is walked.
 */
class MappingDriver(
    private val controller: MappingSessionControl,
    private val jobId: String,
    private val walker: SafeMapperWalker,
    private val session: ControllerSessionPort,
    private val navigation: MappingNavigationPort,
    private val atlas: AtlasStoreMappingPort,
    private val publishChanges: (List<com.cyclone.mobile.mapping.session.AtlasStructuralChange>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val maxBacksPerClimb: Int = 6,
    private val maxResets: Int = 6,
    private val stepDelayMs: Long = 250,
    /** Every structural event, as it happens (the mapping run's trace for Glass Runs). */
    private val onEvent: (MappingDriverEvent) -> Unit = {},
) {
    private val eventLog = mutableListOf<MappingDriverEvent>()

    val events: List<MappingDriverEvent> get() = synchronized(eventLog) { eventLog.toList() }

    fun run(freshStart: Boolean): MappingJob? {
        publish()
        if (freshStart && !enterPlace(resetToEntry = true, reason = "enter")) return finalState()

        var backsThisClimb = 0
        var resets = 0
        var justReset = freshStart
        val maxIterations = stepCap()
        var iterations = 0

        while (iterations++ < maxIterations) {
            val job = controller.status(jobId) ?: return null
            if (job.state != MappingSessionState.RUNNING) return parked(job)

            val result = try {
                walker.step(clock())
            } catch (interrupted: MappingInterrupted) {
                return parked(controller.status(jobId))
            } catch (error: MappingSessionException) {
                log("error", error.code)
                return parked(controller.status(jobId))
            } finally {
                publish()
            }
            log(kindOf(result), detailOf(result), roomOf(result))

            when (result) {
                is MappingStepResult.Progress -> {
                    backsThisClimb = 0
                    justReset = false
                }
                is MappingStepResult.NoProgress -> justReset = false
                is MappingStepResult.Paused,
                is MappingStepResult.CompletedPartial,
                is MappingStepResult.Failed -> return parked(controller.status(jobId))

                is MappingStepResult.RoomExhausted -> {
                    if (justReset) return complete(mapped = true, reason = "entry_room_exhausted")
                    if (backsThisClimb < maxBacksPerClimb) {
                        backsThisClimb += 1
                        if (!navigate("back") { navigation.back(it) }) {
                            if (!resetOrFinish(++resets)) return finalState()
                            backsThisClimb = 0
                            justReset = true
                        }
                    } else {
                        if (!resetOrFinish(++resets)) return finalState()
                        backsThisClimb = 0
                        justReset = true
                    }
                }
                is MappingStepResult.LeftPlace -> {
                    if (!resetOrFinish(++resets)) return finalState()
                    backsThisClimb = 0
                    justReset = true
                }
            }
            pause(stepDelayMs)
        }
        return complete(mapped = false, reason = "driver_step_cap")
    }

    private fun resetOrFinish(resets: Int): Boolean {
        if (resets > maxResets) {
            complete(mapped = false, reason = "reset_limit")
            return false
        }
        return enterPlace(resetToEntry = true, reason = "reset")
    }

    private fun enterPlace(resetToEntry: Boolean, reason: String): Boolean {
        if (navigate(reason) { navigation.openPlace(it, resetToEntry) }) return true
        val job = controller.status(jobId)
        if (job != null && job.state == MappingSessionState.RUNNING) {
            controller.fail(jobId, "PLACE_LAUNCH_FAILED")
        }
        return false
    }

    private fun navigate(
        label: String,
        action: (com.cyclone.mobile.mapping.crawl.MappingSessionSnapshot) -> com.cyclone.mobile.mapping.crawl.MappingMutationResult,
    ): Boolean {
        val snapshot = try {
            session.snapshot()
        } catch (interrupted: MappingInterrupted) {
            return false
        }
        if (snapshot.authority != MappingAuthority.OWNED) {
            session.pauseHumanControl("navigation_authority")
            log("paused", "human_control")
            return false
        }
        val result = action(snapshot)
        log("navigate", label + ":" + (result.errorCode ?: if (result.performed) "ok" else "not_performed"))
        if (result.errorCode == "HUMAN_HAS_CONTROL") session.pauseHumanControl("navigation_human_control")
        return result.performed
    }

    private fun complete(mapped: Boolean, reason: String): MappingJob? {
        val job = controller.status(jobId) ?: return null
        if (job.state.terminal) return job
        val status = atlas.finish(mapped)
        publish()
        log("complete", reason + ":" + status.wireValue)
        return controller.complete(
            jobId,
            if (status == com.cyclone.mobile.brain.graphv2.AtlasMapStatus.MAPPED) MappingAtlasStatus.MAPPED else MappingAtlasStatus.PARTIAL,
        )
    }

    private fun parked(job: MappingJob?): MappingJob? {
        publish()
        job?.let { log("parked", it.state.wireValue) }
        return job
    }

    private fun finalState(): MappingJob? = parked(controller.status(jobId))

    private fun publish() {
        val changes = atlas.drainChanges()
        if (changes.isNotEmpty()) runCatching { publishChanges(changes) }
    }

    private fun stepCap(): Int {
        val budget = controller.status(jobId)?.budget ?: return 0
        return (budget.maxNewScreens * 12 + 40).coerceAtMost(2_000)
    }

    private fun log(kind: String, detail: String, roomKey: String? = null) {
        val event = MappingDriverEvent(clock(), kind, detail.take(80), roomKey)
        synchronized(eventLog) {
            eventLog += event
            while (eventLog.size > 400) eventLog.removeAt(0)
        }
        runCatching { onEvent(event) }
    }

    private fun roomOf(result: MappingStepResult): String? = when (result) {
        is MappingStepResult.Progress -> result.toNodeKey
        is MappingStepResult.RoomExhausted -> result.nodeKey
        else -> null
    }

    private fun kindOf(result: MappingStepResult): String = when (result) {
        is MappingStepResult.Progress -> "progress"
        is MappingStepResult.NoProgress -> "no_progress"
        is MappingStepResult.Paused -> "paused"
        is MappingStepResult.CompletedPartial -> "partial"
        is MappingStepResult.Failed -> "failed"
        is MappingStepResult.RoomExhausted -> "room_exhausted"
        is MappingStepResult.LeftPlace -> "left_place"
    }

    /** Structural detail only: door kinds, reason codes, room purpose words. */
    private fun detailOf(result: MappingStepResult): String = when (result) {
        is MappingStepResult.Progress -> result.action.kind.name.lowercase() + ":" + purposeWord(result.toNodeKey)
        is MappingStepResult.NoProgress -> result.action.kind.name.lowercase() + ":" + result.reason
        is MappingStepResult.Paused -> result.reason.name.lowercase()
        is MappingStepResult.CompletedPartial -> result.reason
        is MappingStepResult.Failed -> result.reason
        is MappingStepResult.RoomExhausted -> purposeWord(result.nodeKey)
        is MappingStepResult.LeftPlace -> result.reason
    }

    private fun purposeWord(nodeKey: String): String = nodeKey.split(':').getOrNull(1) ?: "screen"
}
