package com.cyclone.mobile.ui.overlay.tracefield

/**
 * The pictures the Trace Field draws with its digits while Cyclone works ("Cyclone Tide").
 * [shaderIndex] is the id TraceFieldShader branches on; [word] is the mood the scene stands for.
 */
enum class TraceScene(val shaderIndex: Float, val word: String) {
    CYCLONE(0f, "Spinning up"),
    TIDE(1f, "Drifting"),
    CONTOUR(2f, "Charting"),
    WEAVE(3f, "Weaving"),
    LIGHT(4f, "Opening"),
    CLOCK(5f, "Waiting"),
    REACH(6f, "Your turn"),
}

/** What the shader needs to draw the scene layer this frame. */
data class TraceSceneFrame(
    val from: TraceScene,
    val to: TraceScene,
    /** Position of the tide line, -0.2..1.2; outside that range no transition is running. */
    val front: Float,
    /** True when the tide spreads from the centre (Cyclone scenes) instead of rising from the bottom. */
    val radial: Boolean,
)

/**
 * Pure scene director: maps what the agent is doing to one calm scene at a time. Scenes hold for a
 * minimum time so quick actions never make the picture jump, change through a slow tide, and rotate
 * on their own when nothing new happens. No Android types; time is passed in seconds.
 */
class TraceSceneDirector(var reduceMotion: Boolean = false) {
    var current: TraceScene = TraceScene.TIDE
        private set
    private var previous: TraceScene = TraceScene.TIDE
    private var changedAt = Double.NEGATIVE_INFINITY
    private var transitionStart = Double.NEGATIVE_INFINITY
    private var transitionSeconds = TIDE_S
    private var radial = false
    private var pending: TraceScene? = null
    private var lastSignalAt = 0.0
    private var ambientIndex = 0
    private var waitingEligible = false

    /** A task started: the Cyclone spiral forms, then the tide takes over. */
    fun wake(now: Double) {
        pending = null
        lastSignalAt = now
        waitingEligible = true
        force(TraceScene.CYCLONE, now)
    }

    /** The agent read the screen and is now thinking about it. */
    fun observed(now: Double) = signal(TraceScene.CONTOUR, now, waiting = true)

    /** A tap, swipe or typing step. */
    fun acted(now: Double) = signal(TraceScene.WEAVE, now, waiting = true)

    /** Opening an app or moving to another screen. */
    fun navigated(now: Double) = signal(TraceScene.LIGHT, now, waiting = true)

    /** Cyclone needs the user: shown at once, never held back by the minimum hold. */
    fun handoff(now: Double) {
        pending = null
        waitingEligible = false
        force(TraceScene.REACH, now)
    }

    /** Work continues after a handoff or approval. */
    fun resumed(now: Double) {
        lastSignalAt = now
        waitingEligible = true
        pending = null
        force(TraceScene.TIDE, now)
    }

    /** The task finished: the spiral blooms once more. */
    fun done(now: Double) {
        pending = null
        waitingEligible = false
        force(TraceScene.CYCLONE, now)
    }

    fun frame(now: Double): TraceSceneFrame {
        advance(now)
        val span = if (reduceMotion) 0.0 else transitionSeconds
        val p = if (span <= 0.0) 1.0 else ((now - transitionStart) / span).coerceIn(0.0, 1.0)
        return if (p >= 1.0) {
            TraceSceneFrame(current, current, IDLE_FRONT, radial = false)
        } else {
            TraceSceneFrame(previous, current, (-0.2 + 1.4 * p).toFloat(), radial)
        }
    }

    private fun signal(scene: TraceScene, now: Double, waiting: Boolean) {
        lastSignalAt = now
        waitingEligible = waiting
        if (current == TraceScene.REACH) return
        pending = scene
        advance(now)
    }

    private fun advance(now: Double) {
        if (current == TraceScene.REACH) return
        val held = now - changedAt
        val canChange = held >= minHold(current)
        val next = pending
        if (next != null) {
            if (next == current) { pending = null; return }
            if (canChange) {
                pending = null
                change(next, now)
            }
            return
        }
        // Long silence from the agent reads as waiting: a page loading or a slow model reply.
        if (waitingEligible && now - lastSignalAt >= WAIT_AFTER_S && current != TraceScene.CLOCK && canChange) {
            change(TraceScene.CLOCK, now)
            return
        }
        if (current == TraceScene.CYCLONE && held >= CYCLONE_HOLD_S) {
            change(TraceScene.TIDE, now)
            return
        }
        if (current == TraceScene.CLOCK) return
        if (held >= AMBIENT_HOLD_S) {
            ambientIndex = (AMBIENT.indexOf(current).takeIf { it >= 0 } ?: ambientIndex) + 1
            change(AMBIENT[ambientIndex % AMBIENT.size], now)
        }
    }

    private fun minHold(scene: TraceScene): Double = when (scene) {
        TraceScene.CYCLONE -> CYCLONE_HOLD_S
        TraceScene.CLOCK -> CLOCK_MIN_HOLD_S
        else -> MIN_HOLD_S
    }

    private fun force(scene: TraceScene, now: Double) {
        if (scene == current && now - changedAt < TIDE_S) return
        change(scene, now)
    }

    private fun change(scene: TraceScene, now: Double) {
        previous = current
        current = scene
        changedAt = now
        transitionStart = now
        radial = scene == TraceScene.CYCLONE || previous == TraceScene.CYCLONE
        transitionSeconds = when (scene) {
            TraceScene.LIGHT, TraceScene.CLOCK -> 4.0
            TraceScene.REACH -> 5.0
            TraceScene.CYCLONE -> 3.0
            else -> TIDE_S
        }
    }

    companion object {
        const val TIDE_S = 7.0
        const val MIN_HOLD_S = 12.0
        const val CYCLONE_HOLD_S = 6.0
        const val AMBIENT_HOLD_S = 35.0
        const val WAIT_AFTER_S = 8.0
        const val CLOCK_MIN_HOLD_S = 4.0
        const val IDLE_FRONT = 2f
        val AMBIENT = listOf(TraceScene.TIDE, TraceScene.CONTOUR, TraceScene.WEAVE, TraceScene.LIGHT)
    }
}
