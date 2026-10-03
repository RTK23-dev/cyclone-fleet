package com.cyclone.mobile.runtime.plane

/** One look at a background plane's health (plan 25 §4.6). */
data class HealthFacts(
    val binderAlive: Boolean,
    val serviceAlive: Boolean,
    val displayValid: Boolean,
    /** Milliseconds since the last frame from the background screen; null when none has arrived yet. */
    val lastFrameAgeMs: Long?,
    val taskPresent: Boolean,
    val deviceLocked: Boolean,
)

enum class HealthProblem(val readable: String) {
    SHIZUKU_STOPPED("Shizuku stopped"),
    SERVICE_LOST("the background service disconnected"),
    DISPLAY_GONE("the background screen closed"),
    FRAMES_STALLED("the background screen stopped updating"),
    TASK_GONE("the app left the background screen"),
}

sealed class HealthVerdict {
    data object Healthy : HealthVerdict()
    /** Locked: pause and wait (alpha.27 mission survival resumes after unlock); not a failure. */
    data object Locked : HealthVerdict()
    data class Broken(val problem: HealthProblem) : HealthVerdict()
}

/** What to do about a broken background plane, one rung at a time. */
enum class RecoveryStep { REBIND, RECREATE_AND_RETURN, MOVE_TO_SCREEN, PAUSE }

object BackgroundHealth {
    const val FRAME_STALL_MS = 2_000L
    const val FIRST_FRAME_GRACE_MS = 1_500L

    fun judge(facts: HealthFacts, sinceStartMs: Long = Long.MAX_VALUE): HealthVerdict = when {
        !facts.binderAlive -> HealthVerdict.Broken(HealthProblem.SHIZUKU_STOPPED)
        !facts.serviceAlive -> HealthVerdict.Broken(HealthProblem.SERVICE_LOST)
        !facts.displayValid -> HealthVerdict.Broken(HealthProblem.DISPLAY_GONE)
        facts.deviceLocked -> HealthVerdict.Locked
        !facts.taskPresent -> HealthVerdict.Broken(HealthProblem.TASK_GONE)
        facts.lastFrameAgeMs == null -> if (sinceStartMs > FIRST_FRAME_GRACE_MS) HealthVerdict.Broken(HealthProblem.FRAMES_STALLED) else HealthVerdict.Healthy
        facts.lastFrameAgeMs > FRAME_STALL_MS -> HealthVerdict.Broken(HealthProblem.FRAMES_STALLED)
        else -> HealthVerdict.Healthy
    }
}

/**
 * The recovery ladder for one background task: rebind the service, then recreate the screen and walk back with the
 * map, then move to the main screen (or pause, when the owner chose background only). Each rung once; a healthy tick
 * resets nothing (a plane that broke twice is not trusted again this task).
 */
class RecoveryLadder(private val mode: PlaneMode) {
    private val tried = mutableSetOf<RecoveryStep>()

    fun next(problem: HealthProblem): RecoveryStep {
        val ladder = when (problem) {
            // Without Shizuku nothing on the background plane can be repaired from here.
            HealthProblem.SHIZUKU_STOPPED -> listOf(finalStep())
            HealthProblem.SERVICE_LOST -> listOf(RecoveryStep.REBIND, RecoveryStep.RECREATE_AND_RETURN, finalStep())
            HealthProblem.DISPLAY_GONE, HealthProblem.FRAMES_STALLED -> listOf(RecoveryStep.RECREATE_AND_RETURN, finalStep())
            HealthProblem.TASK_GONE -> listOf(RecoveryStep.RECREATE_AND_RETURN, finalStep())
        }
        val step = ladder.firstOrNull { it !in tried || it == finalStep() } ?: finalStep()
        tried += step
        return step
    }

    private fun finalStep() = if (mode == PlaneMode.BACKGROUND) RecoveryStep.PAUSE else RecoveryStep.MOVE_TO_SCREEN
}
