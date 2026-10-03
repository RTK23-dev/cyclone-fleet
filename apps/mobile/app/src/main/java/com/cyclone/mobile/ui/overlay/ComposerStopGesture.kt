package com.cyclone.mobile.ui.overlay

/** Monotonic timing shared by pointer feedback and deterministic interaction tests. */
internal class ComposerStopGesture {
    enum class Release { FIRST_TAP, STOP, NONE }
    private var pressedAt: Long? = null
    private var armedAt: Long? = null
    private var stopped = false
    fun armed(now: Long) = armedAt?.let { now - it in 0 until TAP_RETRACT_MS } == true
    fun press(now: Long) { pressedAt = now; stopped = false }
    fun progress(now: Long): Float = pressedAt?.let {
        ((now - it).toFloat() / HOLD_MS).coerceIn(0f, 1f)
    } ?: armedAt?.let { (0.5f * (1f - (now - it).toFloat() / TAP_RETRACT_MS)).coerceIn(0f, .5f) } ?: 0f
    fun hold(now: Long): Boolean {
        if (stopped || pressedAt?.let { now - it >= HOLD_MS } != true) return false
        stopped = true
        return true
    }
    fun release(now: Long): Release {
        if (stopped || pressedAt == null) { pressedAt = null; return Release.NONE }
        val stop = hold(now) || armed(now)
        pressedAt = null
        if (stop) { stopped = true; armedAt = null; return Release.STOP }
        armedAt = now
        return Release.FIRST_TAP
    }
    fun cancel() { pressedAt = null }
    fun reset() { pressedAt = null; armedAt = null; stopped = false }
    companion object { const val HOLD_MS = 2_000L; const val TAP_RETRACT_MS = 3_000L }
}
