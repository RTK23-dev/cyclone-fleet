package com.cyclone.mobile.agent.tools

/** At-most-once effect, independent of planner history and the currently visible page. */
class PhotoEffectLedger {
    enum class State { NOT_ATTEMPTED, AWAITING_PROOF, VERIFIED }
    var state = State.NOT_ATTEMPTED
        private set
    private var baseline: Set<Long>? = null
    private var requestedAtMs = 0L
    fun request(existing: Set<Long>?, atMs: Long): Boolean {
        if (state != State.NOT_ATTEMPTED) return false
        baseline = existing
        requestedAtMs = atMs
        state = State.AWAITING_PROOF
        return true
    }
    fun observe(images: Map<Long, Long>?) {
        val before = baseline ?: return
        if (state != State.AWAITING_PROOF || images == null) return
        if (images.any { (id, takenAt) -> id !in before && takenAt >= requestedAtMs }) state = State.VERIFIED
    }
    companion object {
        fun isSinglePhotoGoal(goal: String?): Boolean = goal != null &&
            Regex("(?i)\\b(take|capture|shoot)\\b").containsMatchIn(goal) &&
            Regex("(?i)\\b(a|one|single) (photo|picture|photograph)\\b").containsMatchIn(goal) &&
            !Regex("(?i)\\b(two|three|multiple|burst|video)\\b").containsMatchIn(goal)
        fun isShutter(packageName: String, label: String): Boolean = packageName.contains("camera", true) &&
            Regex("(?i)(shutter|capture.?button|take (photo|picture)|capture (photo|picture))").containsMatchIn(label)
    }
}
