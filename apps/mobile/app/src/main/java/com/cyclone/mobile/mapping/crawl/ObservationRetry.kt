package com.cyclone.mobile.mapping.crawl

import com.cyclone.mobile.agent.CaptureChanged
import com.cyclone.mobile.gateway.GatewayProtocolException

/**
 * How the mapper reads a screen that will not hold still. A capture is refused when the screen changes while it is
 * being read (apps with animations, streaming text or a busy status bar do this). The Mind already retries that; the
 * mapper used to give up on the first try and report only OBSERVATION_MISSING.
 *
 * Now each attempt waits longer for the display to go quiet, and the last attempt accepts a capture where only the
 * content moved (same windows, size, rotation and scope). A real failure (Accessibility off, the display gone, a
 * scope change) stops at once, and its cause is kept for the report.
 */
object ObservationRetry {
    data class Attempt(val index: Int, val settleQuietMs: Long, val settleMaxMs: Long, val tolerateContentChange: Boolean)

    val PLAN = listOf(
        Attempt(0, 0, 0, false),
        Attempt(1, 150, 1_000, false),
        Attempt(2, 250, 2_000, false),
        Attempt(3, 400, 3_000, false),
        Attempt(4, 400, 3_000, true),
    )
    private val TRANSIENT = setOf("SCREEN_KEPT_CHANGING", "TIMEOUT")

    /** A short, stable cause for the report (becomes OBSERVATION_MISSING_<CAUSE>). */
    fun cause(error: Throwable): String = when {
        error is CaptureChanged -> "SCREEN_KEPT_CHANGING"
        error is GatewayProtocolException -> when (error.code) {
            "OBSERVATION_CHANGED_DURING_CAPTURE" -> "SCREEN_KEPT_CHANGING"
            "ACCESSIBILITY_NOT_CONNECTED" -> "ACCESSIBILITY_OFF"
            else -> error.code
        }
        error.message == "DISPLAY_GONE" -> "DISPLAY_GONE"
        error is java.util.concurrent.TimeoutException -> "TIMEOUT"
        else -> (error.javaClass.simpleName.ifBlank { "ERROR" }).replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
    }

    /** Runs [capture] along [PLAN]. Returns the observation, or null and the last cause. */
    fun <T : Any> run(capture: (Attempt) -> T, pause: (Long) -> Unit = { Thread.sleep(it) }): Pair<T?, String?> {
        var last: String? = null
        for (attempt in PLAN) {
            try {
                return capture(attempt) to null
            } catch (error: Exception) {
                if (error is InterruptedException || error is java.util.concurrent.CancellationException) throw error
                last = cause(error)
                if (last !in TRANSIENT) return null to last
                if (attempt != PLAN.last()) pause(200L * (attempt.index + 1))
            }
        }
        return null to last
    }
}
