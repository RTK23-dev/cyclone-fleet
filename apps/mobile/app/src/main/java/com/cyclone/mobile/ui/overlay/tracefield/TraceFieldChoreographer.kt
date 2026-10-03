package com.cyclone.mobile.ui.overlay.tracefield

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** User-selectable working indicator. FIELD is the full Trace Field; EDGE is the perimeter filament only. */
enum class TraceFieldMode(val wire: String) {
    FIELD("field"),
    EDGE("edge"),
    OFF("off"),
    ;

    companion object {
        fun parse(raw: String?): TraceFieldMode =
            entries.firstOrNull { it.wire == raw?.trim()?.lowercase() } ?: FIELD
    }
}

enum class TraceActKind { TAP, LONG_PRESS, SCROLL, TYPE }

/** Read-only attention signals. They describe what the agent already did; they never drive the agent. */
sealed interface TraceEvent {
    data class Wake(val x: Float, val y: Float) : TraceEvent
    data class Observe(val fingerprint: String) : TraceEvent
    data class Target(val left: Float, val top: Float, val right: Float, val bottom: Float, val key: String) : TraceEvent
    data class Act(val kind: TraceActKind, val x: Float, val y: Float, val dx: Float = 0f, val dy: Float = 0f) : TraceEvent
    data object Recover : TraceEvent
    /** The agent opened an app or went back/home: a new screen is coming. */
    data object Navigate : TraceEvent
    data object Gate : TraceEvent
    data object Resume : TraceEvent
    data object Handoff : TraceEvent
    data object Done : TraceEvent
    data object Stop : TraceEvent
    data class Background(val active: Boolean) : TraceEvent
}

enum class TracePhase { OFF, WAKE, OBSERVE, THINK, TARGET, ACT, VERIFY, RECOVER, GATE, HANDOFF, DONE, FADE }

/** Everything the shader needs for one frame. Pixel units, screen space. */
data class TraceFrame(
    val visible: Boolean,
    val animating: Boolean,
    val phase: TracePhase,
    val intensity: Float,
    val lensX: Float,
    val lensY: Float,
    val lensHalfW: Float,
    val lensHalfH: Float,
    val lensCorner: Float,
    val rippleX: Float,
    val rippleY: Float,
    val rippleRadius: Float,
    val rippleStrength: Float,
    val scanY: Float,
    val scanStrength: Float,
    val flow: Float,
    val rain: Float,
    val scramble: Float,
    val glyphClock: Float,
    val warmth: Float,
    val edge: Float,
    val edgeHead: Float,
    val seed: Float,
    /** 0 = diffuse ambient aurora (thinking, opening apps), 1 = sharp attention on one target. */
    val focus: Float = 0f,
    /** Seconds of ambient flow; frozen with the field (GATE, reduce motion). Also the scene clock. */
    val flowTime: Float = 0f,
    /** Cyclone Tide scene layer: scene ids, tide line (-0.2..1.2, 2 = no transition), radial tide, visibility. */
    val sceneFrom: Float = TraceScene.TIDE.shaderIndex,
    val sceneTo: Float = TraceScene.TIDE.shaderIndex,
    val sceneFront: Float = TraceSceneDirector.IDLE_FRONT,
    val sceneRadial: Float = 0f,
    val sceneLevel: Float = 0f,
)

/**
 * Pure state machine behind the Trace Field. No Android types: time is passed in seconds so every
 * choreography is deterministic under JVM tests.
 */
class TraceFieldChoreographer(
    private val width: Float,
    private val height: Float,
    private val density: Float,
    var mode: TraceFieldMode = TraceFieldMode.FIELD,
    var reduceMotion: Boolean = false,
) {
    var phase: TracePhase = TracePhase.OFF
        private set

    private var phaseStart = 0.0
    private var lastNow = Double.NaN
    private var glyphClock = 0.0
    private var seed = 0f

    // Lens: current and target.
    private var lx = width / 2f
    private var ly = height / 2f
    private var lhw = dp(LENS_RADIUS_DP)
    private var lhh = dp(LENS_RADIUS_DP)
    private var lcorner = dp(LENS_RADIUS_DP)
    private var tx = lx
    private var ty = ly
    private var thw = lhw
    private var thh = lhh
    private var tcorner = lcorner
    private var anchorX = lx
    private var anchorY = ly

    private var intensity = 0f
    private var sceneLevel = 0f
    private val director = TraceSceneDirector(reduceMotion)
    private var focus = 0f
    private var flowTime = 0.0
    private var edge = 0f
    private var warmth = 0f
    private var backgroundActive = false
    private var taskActive = false

    private var rippleX = 0f
    private var rippleY = 0f
    private var rippleStart = Double.NEGATIVE_INFINITY
    private var rippleScale = 1f
    private var flow = 0f
    private var flowVelocity = 0f
    private var lastActAt = Double.NEGATIVE_INFINITY
    private var lastObserveAt = Double.NEGATIVE_INFINITY
    private var scrambleUntil = Double.NEGATIVE_INFINITY

    fun onEvent(event: TraceEvent, now: Double) {
        if (event is TraceEvent.Background) {
            backgroundActive = event.active
            return
        }
        if (mode == TraceFieldMode.OFF) return
        when (event) {
            is TraceEvent.Wake -> {
                taskActive = true
                seed = seedOf("wake:$now")
                lx = event.x; ly = event.y
                lhw = dp(10f); lhh = dp(10f); lcorner = dp(10f)
                setLens(width / 2f, height * 0.42f, dp(LENS_RADIUS_DP))
                anchorX = tx; anchorY = ty
                warmth = 0f
                director.wake(flowTime)
                enter(TracePhase.WAKE, now)
            }
            is TraceEvent.Observe -> {
                if (!lensActive()) return
                if (now - lastObserveAt < OBSERVE_DEBOUNCE_S) return
                lastObserveAt = now
                director.observed(flowTime)
                seed = seedOf(event.fingerprint)
                if (now - lastActAt < VERIFY_WINDOW_S) {
                    // The observation that follows an action is its verification: pull in, freeze.
                    thw *= VERIFY_PULL_SCALE; thh *= VERIFY_PULL_SCALE
                    enter(TracePhase.VERIFY, now)
                } else {
                    enter(TracePhase.OBSERVE, now)
                }
            }
            is TraceEvent.Target -> {
                if (!lensActive()) return
                val pad = dp(10f)
                val cx = (event.left + event.right) / 2f
                val cy = (event.top + event.bottom) / 2f
                val hw = max(dp(24f), abs(event.right - event.left) / 2f + pad)
                val hh = max(dp(24f), abs(event.bottom - event.top) / 2f + pad)
                tx = cx; ty = cy; thw = min(hw, width * 0.48f); thh = min(hh, height * 0.3f)
                tcorner = min(dp(18f), min(thw, thh))
                anchorX = cx; anchorY = cy
                seed = seedOf(event.key)
                enter(TracePhase.TARGET, now)
            }
            is TraceEvent.Act -> {
                if (!lensActive()) return
                lastActAt = now
                director.acted(flowTime)
                rippleX = event.x; rippleY = event.y
                when (event.kind) {
                    TraceActKind.SCROLL -> {
                        // Content moves with the finger: an upward swipe (dy < 0) scrolls glyphs up.
                        flowVelocity = if (event.dy < 0f) -dp(260f) else dp(260f)
                        rippleStart = Double.NEGATIVE_INFINITY
                    }
                    TraceActKind.TYPE -> { rippleStart = now; rippleScale = 0.45f }
                    TraceActKind.LONG_PRESS -> { rippleStart = now; rippleScale = 1.35f }
                    TraceActKind.TAP -> { rippleStart = now; rippleScale = 1f }
                }
                if (phase != TracePhase.TARGET) {
                    // Coordinate actions arrive without a Target; bring the lens to the touch point.
                    setLens(event.x, event.y, dp(56f))
                    anchorX = event.x; anchorY = event.y
                }
                enter(TracePhase.ACT, now)
            }
            TraceEvent.Navigate -> {
                if (!lensActive()) return
                director.navigated(flowTime)
            }
            TraceEvent.Recover -> {
                if (!lensActive()) return
                scrambleUntil = now + 0.15
                thw *= 1.4f; thh *= 1.4f
                enter(TracePhase.RECOVER, now)
            }
            TraceEvent.Gate -> {
                if (!taskActive) return
                enter(TracePhase.GATE, now)
            }
            TraceEvent.Resume -> {
                taskActive = true
                warmth = 0f
                director.resumed(flowTime)
                enter(TracePhase.THINK, now)
            }
            TraceEvent.Handoff -> {
                if (!taskActive) return
                director.handoff(flowTime)
                enter(TracePhase.HANDOFF, now)
            }
            TraceEvent.Done -> {
                if (!taskActive && phase == TracePhase.OFF) return
                taskActive = false
                director.done(flowTime)
                if (reduceMotion) enter(TracePhase.FADE, now) else enter(TracePhase.DONE, now)
            }
            TraceEvent.Stop -> {
                if (phase == TracePhase.OFF) { taskActive = false; return }
                taskActive = false
                if (phase != TracePhase.DONE) enter(TracePhase.FADE, now)
            }
            is TraceEvent.Background -> Unit
        }
    }

    fun frame(now: Double): TraceFrame {
        val dt = if (lastNow.isNaN()) 0.0 else (now - lastNow).coerceIn(0.0, 0.1)
        lastNow = now
        val t = now - phaseStart
        autoAdvance(now, t)
        val t2 = now - phaseStart

        val frozen = reduceMotion || phase == TracePhase.GATE ||
            (phase == TracePhase.VERIFY && t2 >= VERIFY_PULL_S)
        val scrambling = now < scrambleUntil
        if (!frozen) glyphClock += dt * if (scrambling) 4.0 else 1.0
        if (!frozen) flowTime += dt

        // Think: no single spot to look at, so the glow wanders wide on incommensurate periods
        // (never visibly repeating) while the ambient aurora carries the motion.
        var breathe = 1f
        if (phase == TracePhase.THINK && !reduceMotion) {
            breathe = 1f + 0.08f * sin(2.0 * PI * t2 / 2.4).toFloat()
            val amp = smoothstep01((t2 / THINK_DRIFT_RAMP_S).toFloat())
            val cx = anchorX + (width / 2f - anchorX) * 0.5f * amp
            val cy = anchorY + (height * 0.45f - anchorY) * 0.5f * amp
            tx = cx + amp * width * (0.2f * sin(2.0 * PI * t2 / 19.0).toFloat() + 0.07f * sin(2.0 * PI * t2 / 7.3 + 1.1).toFloat())
            ty = cy + amp * height * (0.12f * sin(2.0 * PI * t2 / 23.0 + 0.6).toFloat() + 0.04f * sin(2.0 * PI * t2 / 8.9).toFloat())
        }

        val lensTarget = when (phase) {
            TracePhase.OFF, TracePhase.HANDOFF, TracePhase.FADE -> 0f
            else -> if (mode == TraceFieldMode.FIELD) 1f else 0f
        }
        val edgeTarget = when {
            mode == TraceFieldMode.OFF -> 0f
            backgroundActive && !taskActive -> 1f
            mode == TraceFieldMode.EDGE && taskActive && phase != TracePhase.HANDOFF -> 1f
            else -> 0f
        }
        val warmTarget = if (phase == TracePhase.GATE) 1f else 0f
        val fadeTau = when (phase) {
            TracePhase.HANDOFF, TracePhase.FADE -> 0.06
            else -> 0.14
        }
        val snap = reduceMotion
        val focusTarget = focusFor(phase)
        // Focus arrives quickly and lets go slowly: attention snaps in, then relaxes back into the aurora.
        focus = approach(focus, focusTarget, dt, if (focusTarget > focus) 0.12 else 0.6, snap)
        // The scene layer stays through handoff (Reach) and the finale; it leaves with the task.
        val sceneTarget = when (phase) {
            TracePhase.OFF, TracePhase.FADE -> 0f
            else -> if (mode == TraceFieldMode.FIELD && (taskActive || phase == TracePhase.DONE)) 1f else 0f
        }
        sceneLevel = approach(sceneLevel, sceneTarget, dt, if (sceneTarget < sceneLevel) 0.12 else 0.3, snap)
        director.reduceMotion = reduceMotion
        val scene = director.frame(flowTime)
        intensity = approach(intensity, lensTarget, dt, fadeTau, snap)
        edge = approach(edge, edgeTarget, dt, 0.3, snap)
        warmth = approach(warmth, warmTarget, dt, 0.18, snap)

        val lensTau = if (phase == TracePhase.WAKE) 0.16 else LENS_TAU_S
        lx = approach(lx, tx, dt, lensTau, snap)
        ly = approach(ly, ty, dt, lensTau, snap)
        lhw = approach(lhw, thw, dt, lensTau, snap)
        lhh = approach(lhh, thh, dt, lensTau, snap)
        lcorner = approach(lcorner, tcorner, dt, lensTau, snap)

        if (flowVelocity != 0f && !frozen) {
            flow += flowVelocity * dt.toFloat()
            flowVelocity *= exp(-dt / 0.35).toFloat()
            if (abs(flowVelocity) < dp(4f)) flowVelocity = 0f
        }

        val rippleAge = now - rippleStart
        val rippleOn = !reduceMotion && rippleAge in 0.0..RIPPLE_S
        val rippleP = (rippleAge / RIPPLE_S).toFloat().coerceIn(0f, 1f)

        val scanOn = phase == TracePhase.OBSERVE && !reduceMotion
        val scanP = if (scanOn) easeInOut((t2 / OBSERVE_S).toFloat().coerceIn(0f, 1f)) else 0f

        // Finale: the Cyclone spiral blooms, then the digits rain away in the last DONE_RAIN_S.
        val rain = if (phase == TracePhase.DONE && !reduceMotion && t2 >= DONE_S - DONE_RAIN_S) {
            ((t2 - (DONE_S - DONE_RAIN_S)) / DONE_RAIN_S).toFloat().coerceIn(0f, 1f)
        } else -1f
        val edgeHead = ((now / EDGE_LAP_S) % 1.0).toFloat()

        val visible = intensity > VISIBLE_EPS || edge > VISIBLE_EPS || lensTarget > 0f || edgeTarget > 0f ||
            sceneLevel > VISIBLE_EPS || sceneTarget > 0f
        val lensSettled = abs(lx - tx) < 0.5f && abs(ly - ty) < 0.5f && abs(lhw - thw) < 0.5f &&
            abs(intensity - lensTarget) < 0.002f && abs(warmth - warmTarget) < 0.002f &&
            abs(focus - focusTarget) < 0.002f && abs(sceneLevel - sceneTarget) < 0.002f
        val animating = visible && !(phase == TracePhase.GATE && lensSettled && edge < VISIBLE_EPS) &&
            !(reduceMotion && lensSettled && edge < VISIBLE_EPS)

        return TraceFrame(
            visible = visible,
            animating = animating,
            phase = phase,
            intensity = intensity,
            lensX = lx,
            lensY = ly,
            lensHalfW = lhw * breathe,
            lensHalfH = lhh * breathe,
            lensCorner = lcorner * breathe,
            rippleX = rippleX,
            rippleY = rippleY,
            rippleRadius = dp(150f) * rippleScale * easeOut(rippleP),
            rippleStrength = if (rippleOn && mode == TraceFieldMode.FIELD) (1f - rippleP) * intensity else 0f,
            scanY = scanP * height,
            scanStrength = if (scanOn && mode == TraceFieldMode.FIELD) 0.85f * intensity * sin(PI * scanP).toFloat().coerceAtLeast(0.25f) else 0f,
            flow = flow,
            rain = rain,
            scramble = if (scrambling) 1f else 0f,
            glyphClock = glyphClock.toFloat(),
            warmth = warmth,
            focus = focus,
            flowTime = flowTime.toFloat(),
            edge = edge,
            edgeHead = edgeHead,
            seed = seed,
            sceneFrom = scene.from.shaderIndex,
            sceneTo = scene.to.shaderIndex,
            sceneFront = scene.front,
            sceneRadial = if (scene.radial) 1f else 0f,
            sceneLevel = sceneLevel,
        )
    }

    private fun autoAdvance(now: Double, t: Double) {
        when (phase) {
            TracePhase.WAKE -> if (t >= WAKE_S) enter(TracePhase.THINK, now)
            TracePhase.OBSERVE -> if (t >= OBSERVE_S) enter(TracePhase.THINK, now)
            TracePhase.VERIFY -> if (t >= VERIFY_PULL_S + VERIFY_HOLD_S) {
                thw /= VERIFY_PULL_SCALE; thh /= VERIFY_PULL_SCALE
                enter(TracePhase.THINK, now)
            }
            TracePhase.ACT -> if (t >= ACT_S) enter(TracePhase.THINK, now)
            TracePhase.RECOVER -> if (t >= 0.35) {
                thw /= 1.4f; thh /= 1.4f
                enter(TracePhase.THINK, now)
            }
            TracePhase.TARGET -> if (t >= TARGET_HOLD_S) enter(TracePhase.THINK, now)
            TracePhase.DONE -> if (t >= DONE_S) enter(TracePhase.OFF, now)
            TracePhase.FADE -> if (intensity <= VISIBLE_EPS && sceneLevel <= VISIBLE_EPS && t > 0.05) enter(TracePhase.OFF, now)
            else -> Unit
        }
    }

    private fun enter(next: TracePhase, now: Double) {
        if (next == TracePhase.THINK && phase != TracePhase.THINK) {
            anchorX = tx; anchorY = ty
        }
        phase = next
        phaseStart = now
    }

    private fun lensActive(): Boolean = taskActive && phase !in setOf(
        TracePhase.OFF, TracePhase.GATE, TracePhase.HANDOFF, TracePhase.DONE, TracePhase.FADE,
    )

    private fun setLens(x: Float, y: Float, radius: Float) {
        tx = x; ty = y; thw = radius; thh = radius; tcorner = radius
    }

    private fun dp(value: Float): Float = value * density

    private fun focusFor(phase: TracePhase): Float = when (phase) {
        TracePhase.TARGET, TracePhase.ACT, TracePhase.VERIFY -> 1f
        TracePhase.RECOVER -> 0.8f
        TracePhase.OBSERVE -> 0.6f
        TracePhase.GATE -> 0.5f
        else -> 0.12f
    }

    companion object {
        const val LENS_RADIUS_DP = 96f
        const val WAKE_S = 0.45
        const val OBSERVE_S = 0.7
        const val VERIFY_PULL_S = 0.35
        const val VERIFY_HOLD_S = 0.2
        const val VERIFY_PULL_SCALE = 0.72f
        const val VERIFY_WINDOW_S = 2.5
        const val ACT_S = 0.6
        const val RIPPLE_S = 0.32
        const val TARGET_HOLD_S = 3.0
        const val DONE_S = 2.6
        const val DONE_RAIN_S = 0.6
        const val EDGE_LAP_S = 40.0
        const val OBSERVE_DEBOUNCE_S = 0.25
        const val LENS_TAU_S = 0.11
        const val VISIBLE_EPS = 0.003f
        const val THINK_DRIFT_RAMP_S = 3.0

        private fun smoothstep01(x: Float): Float {
            val c = x.coerceIn(0f, 1f)
            return c * c * (3f - 2f * c)
        }

        fun seedOf(text: String): Float = ((text.hashCode().toLong() and 0x7fffffff) % 997L).toFloat()

        internal fun approach(current: Float, target: Float, dt: Double, tau: Double, snap: Boolean): Float {
            if (snap) return target
            if (dt <= 0.0) return current
            val k = (1.0 - exp(-dt / tau)).toFloat()
            return current + (target - current) * k
        }

        private fun easeInOut(p: Float): Float = (0.5 - 0.5 * cos(PI * p)).toFloat()
        private fun easeOut(p: Float): Float = 1f - (1f - p) * (1f - p)
    }
}
