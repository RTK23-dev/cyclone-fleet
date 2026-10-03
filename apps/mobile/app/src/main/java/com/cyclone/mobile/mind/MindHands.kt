package com.cyclone.mobile.mind

/**
 * Plan 21 (Hands): the loop breaker for putting text into a field. Two identical failures on the same box add one
 * line with the next thing to try; after [handoffAfter] failed attempts in a row the draft goes to the owner.
 * A success resets it.
 */
class TypingTracker(private val handoffAfter: Int = HANDOFF_AFTER) {
    sealed class Advice {
        data object None : Advice()
        data class Hint(val text: String) : Advice()
        data object Handoff : Advice()
    }

    private var failures = 0
    private var lastKey: String? = null
    private var lastReason: String? = null
    private var same = 0

    val failedInARow: Int get() = failures

    fun success() {
        failures = 0
        same = 0
        lastKey = null
        lastReason = null
    }

    fun failure(key: String, reason: String): Advice {
        failures++
        if (key == lastKey && reason == lastReason) same++ else {
            same = 1
            lastKey = key
            lastReason = reason
        }
        if (failures >= handoffAfter) return Advice.Handoff
        if (same == 2) return Advice.Hint(
            "This text box refused twice in the same way ($reason). Try something different: tap_point on the box, " +
                "then type_text with focused=true. Reuse your draft; do not rewrite it.")
        return Advice.None
    }

    companion object {
        const val HANDOFF_AFTER = 4
    }
}
