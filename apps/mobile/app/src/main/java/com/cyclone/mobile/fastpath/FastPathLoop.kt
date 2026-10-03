package com.cyclone.mobile.fastpath

import org.json.JSONArray
import org.json.JSONObject

/** ClosePaw-shaped post-action timings. Observation retries only — never a second click. */
object FastPathTimings {
    const val SETTLE_MS = 300L
    val LADDER_MS = longArrayOf(500L, 1_000L)
    const val GESTURE_COMPLETION_SLACK_MS = 1_500L
    const val UNCHANGED_WARNING =
        "UNCHANGED: UI fingerprint did not change after settle+ladder. Do not retry via a second click channel."

    /**
     * How long Human Gesture may wait for [android.accessibilityservice.GestureResultCallback]
     * after queueing a stroke. Fast Path settle starts only after onCompleted. A timeout is
     * incomplete, not a performed action.
     */
    fun gestureAwaitBudgetMs(durationMs: Long): Long =
        durationMs.coerceAtLeast(0L) + GESTURE_COMPLETION_SLACK_MS

    /** Stroke duration is already awaited; Fast Path then uses the ordinary 300ms settle. */
    fun settleAfterCompletedGestureMs(): Long = SETTLE_MS
}

data class FastPathSettleResult(
    val changed: Boolean?,
    val verified: Boolean,
    val observations: Int,
    val elapsedMs: Long,
    val warning: String?,
    val afterFingerprint: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("changed", changed ?: JSONObject.NULL)
        .put("verified", verified)
        .put("observations", observations)
        .put("elapsedMs", elapsedMs)
        .put("warning", warning ?: JSONObject.NULL)
        .put("afterFingerprint", afterFingerprint ?: JSONObject.NULL)
        .put("settleMs", FastPathTimings.SETTLE_MS)
        .put("ladderMs", JSONArray(FastPathTimings.LADDER_MS.toList()))
        .put("doubleClickSuppressed", true)
}

/**
 * Perceive → one act → settle 300ms → fingerprint → optional +500/+1000.
 * Unchanged returns verified=false with a warning. Callers must not dispatch another click channel.
 * Human Gesture waits for stroke completion before this settle starts, so SETTLE_MS is not
 * increased by gesture duration.
 */
object FastPathLoop {
    fun settle(
        beforeFingerprint: String?,
        sleepMs: (Long) -> Unit,
        observeFingerprint: () -> String?,
        nowMs: () -> Long = { System.currentTimeMillis() },
        initialSettleMs: Long = FastPathTimings.SETTLE_MS,
    ): FastPathSettleResult {
        val started = nowMs()
        var observations = 0
        fun observe(): String? {
            observations += 1
            return observeFingerprint()
        }

        sleepMs(initialSettleMs.coerceAtLeast(FastPathTimings.SETTLE_MS))
        var after = observe()
        if (fingerprintChanged(beforeFingerprint, after)) {
            return result(true, true, observations, started, nowMs(), warning = null, after)
        }
        for (wait in FastPathTimings.LADDER_MS) {
            sleepMs(wait)
            after = observe()
            if (fingerprintChanged(beforeFingerprint, after)) {
                return result(true, true, observations, started, nowMs(), warning = null, after)
            }
        }
        val changed = when {
            beforeFingerprint.isNullOrBlank() || after.isNullOrBlank() -> null
            else -> false
        }
        return result(
            changed,
            verified = false,
            observations,
            started,
            nowMs(),
            warning = FastPathTimings.UNCHANGED_WARNING,
            after,
        )
    }

    fun fingerprintChanged(before: String?, after: String?): Boolean {
        if (before.isNullOrBlank() || after.isNullOrBlank()) return false
        return before != after
    }

    /** Unchanged after a performed action is a soft success: do not treat it as a reason to click again. */
    fun allowSecondClickChannel(actionPerformed: Boolean, settle: FastPathSettleResult): Boolean {
        if (!actionPerformed) return false
        if (settle.changed == false || settle.verified.not() && settle.warning != null) return false
        return false
    }

    private fun result(
        changed: Boolean?,
        verified: Boolean,
        observations: Int,
        started: Long,
        finished: Long,
        warning: String?,
        afterFingerprint: String?,
    ) = FastPathSettleResult(
        changed = changed,
        verified = verified,
        observations = observations,
        elapsedMs = (finished - started).coerceAtLeast(0L),
        warning = warning,
        afterFingerprint = afterFingerprint,
    )
}
