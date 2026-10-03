package com.cyclone.mobile.voice

import kotlin.math.PI
import kotlin.math.sin

/**
 * The short film that plays when the owner turns Driver mode on (plan 32): three scenes and a close, about nine
 * seconds, on a night road.
 *  1. The Ask bubble grows into the big orb; a tap; the orb listens.
 *  2. The orb thinks, then answers in one line, then "Sent".
 *  3. A payment comes up: the orb turns warm and the step waits while the car slows to a stop; then it can be approved.
 *  Close: "Drive safe", and Got it.
 * Kept pure and tested, like [VoiceFace]: the Compose code (DriveIntro) only draws a [DriveIntroFrame]. With
 * animations off in Android settings the film holds each scene still ([still]) and moves on by itself more slowly.
 */
object DriveIntroScript {
    const val SCENE_MS = 3_000L
    const val SCENES = 3
    const val END_MS = SCENE_MS * SCENES
    /** The close fades in over this long after the last scene; the film's clock stops at [LAST_MS]. */
    const val CLOSE_FADE_MS = 500L
    const val LAST_MS = END_MS + CLOSE_FADE_MS
    /** With animations off, each scene is held this long before the next. */
    const val STILL_SCENE_MS = 4_000L

    val captions: List<Pair<String, String>> = listOf(
        "Tap the orb and talk" to "Driver mode turns Cyclone into one big voice button.",
        "It answers in one line" to "Cyclone says what it is doing, and tells you when it is done.",
        "Anything risky waits" to "Paying, deleting, passwords and permissions wait until you have stopped.",
    )
    val close: Pair<String, String> = "Drive safe" to "Stop is always one tap. Keep your eyes on the road."

    /** Where the film goes on a tap: the start of the next scene, or the close. */
    fun next(t: Long): Long = if (t >= END_MS) t.coerceAtMost(LAST_MS) else ((t / SCENE_MS) + 1) * SCENE_MS

    /** The moment each scene is shown at when animations are off: everything in place, nothing moving. */
    fun settled(scene: Int): Long = if (scene >= SCENES) END_MS else scene * SCENE_MS + 2_500L

    fun frame(time: Long, still: Boolean = false): DriveIntroFrame {
        val t = time.coerceIn(0L, LAST_MS)
        val scene = (t / SCENE_MS).toInt().coerceAtMost(SCENES)
        val local = t - scene.coerceAtMost(SCENES - 1) * SCENE_MS
        val (title, detail) = if (scene >= SCENES) close else captions[scene]
        val captionAlpha = when {
            still -> 1f
            scene >= SCENES -> ease(ramp(t, END_MS, END_MS + 400))
            else -> ease(ramp(local, 150, 550)) * (1f - ease(ramp(local, SCENE_MS - 250, SCENE_MS)))
        }
        return when (scene) {
            0 -> sceneOne(local, title, detail, captionAlpha, still, t)
            1 -> sceneTwo(local, title, detail, captionAlpha, still, t)
            2 -> sceneThree(local, title, detail, captionAlpha, still, t)
            else -> DriveIntroFrame(
                scene = SCENES, title = title, detail = detail, captionAlpha = captionAlpha,
                orbScale = 1f, motion = OrbMotion.CALM, level = 0f, warm = false, ripple = 0f,
                pill = "", pillIcon = PillIcon.NONE, pillAlpha = 0f, pillLift = 0f,
                roadTravel = travel(END_MS), roadSpeed = 0f, done = true,
            )
        }
    }

    // Scene 1: the bubble grows into the orb (0–700 ms), a tap lands (900–1500 ms), the orb listens to a voice.
    private fun sceneOne(local: Long, title: String, detail: String, captionAlpha: Float, still: Boolean, t: Long): DriveIntroFrame {
        val grow = if (still) 1f else overshoot(ramp(local, 0, 700))
        val listening = still || local >= 1_100
        val level = if (still || !listening) 0f else voice(local - 1_100)
        return DriveIntroFrame(
            scene = 0, title = title, detail = detail, captionAlpha = captionAlpha,
            orbScale = 0.34f + 0.66f * grow, motion = if (listening) OrbMotion.LISTEN else OrbMotion.CALM, level = level, warm = false,
            ripple = if (still) 0f else ramp(local, 900, 1_500).takeIf { it in 0.001f..0.999f } ?: 0f,
            pill = if (listening) "“Tell Louella I'm ten minutes late”" else "", pillIcon = PillIcon.YOU,
            pillAlpha = if (still) 1f else ease(ramp(local, 1_300, 1_700)) * (1f - ease(ramp(local, 2_700, 3_000))),
            pillLift = if (still) 0f else 1f - ease(ramp(local, 1_300, 1_700)),
            roadTravel = travel(t), roadSpeed = speed(t), done = false,
        )
    }

    // Scene 2: thinking (0–600 ms), then one line spoken, then "Sent" (from 1 900 ms).
    private fun sceneTwo(local: Long, title: String, detail: String, captionAlpha: Float, still: Boolean, t: Long): DriveIntroFrame {
        val thinking = !still && local < 600
        val sent = still || local >= 1_900
        return DriveIntroFrame(
            scene = 1, title = title, detail = detail, captionAlpha = captionAlpha,
            orbScale = 1f, motion = when { thinking -> OrbMotion.SWIRL; sent -> OrbMotion.CALM; else -> OrbMotion.SPEAK },
            level = if (thinking || sent) 0f else voice(local - 600) * 0.8f, warm = false, ripple = 0f,
            pill = if (sent) "Sent to Louella" else "Texting Louella: running ten minutes late",
            pillIcon = if (sent) PillIcon.DONE else PillIcon.CYCLONE,
            pillAlpha = if (still) 1f else ease(ramp(local, 500, 850)) * (1f - ease(ramp(local, 2_750, 3_000))),
            pillLift = if (still) 0f else 1f - ease(ramp(local, 500, 850)),
            roadTravel = travel(t), roadSpeed = speed(t), done = false,
        )
    }

    // Scene 3: a payment turns the orb warm and waits; the road slows to a stop (400–1 400 ms); then it may go ahead.
    private fun sceneThree(local: Long, title: String, detail: String, captionAlpha: Float, still: Boolean, t: Long): DriveIntroFrame {
        val stopped = still || local >= 1_700
        return DriveIntroFrame(
            scene = 2, title = title, detail = detail, captionAlpha = captionAlpha,
            orbScale = 1f, motion = OrbMotion.CALM, level = 0f, warm = true, ripple = 0f,
            pill = if (stopped) "Stopped: pay €24.90 when you are ready" else "Pay €24.90? Waiting until you stop",
            pillIcon = if (stopped) PillIcon.READY else PillIcon.WAIT,
            pillAlpha = if (still) 1f else ease(ramp(local, 200, 550)) * (1f - ease(ramp(local, 2_750, 3_000))),
            pillLift = if (still) 0f else 1f - ease(ramp(local, 200, 550)),
            roadTravel = travel(t), roadSpeed = speed(t), done = false,
        )
    }

    /** How fast the road moves, 0..1: driving, then easing to a stop in scene 3 and staying parked. */
    fun speed(t: Long): Float {
        val slowFrom = 2 * SCENE_MS + 400
        val stopAt = 2 * SCENE_MS + 1_400
        return when {
            t <= slowFrom -> 1f
            t >= stopAt -> 0f
            else -> 1f - ease(ramp(t, slowFrom, stopAt))
        }
    }

    /**
     * How far the road has moved (in dash lengths): the exact integral of [speed], so it never goes backwards. While
     * slowing, speed = 1 - smoothstep(u), whose integral over u is u - (u³ - u⁴ / 2).
     */
    fun travel(t: Long): Float {
        val slowFrom = 2 * SCENE_MS + 400
        val stopAt = 2 * SCENE_MS + 1_400
        val cruise = DASHES_PER_SECOND / 1000f
        if (t <= slowFrom) return t * cruise
        val u = ramp(minOf(t, stopAt), slowFrom, stopAt)
        val slowed = (stopAt - slowFrom) * (u - (u * u * u - u * u * u * u / 2f))
        return (slowFrom + slowed) * cruise
    }

    const val DASHES_PER_SECOND = 1.6f

    private fun ramp(t: Long, from: Long, to: Long): Float = ((t - from).toFloat() / (to - from).toFloat()).coerceIn(0f, 1f)
    private fun ease(x: Float): Float = x * x * (3f - 2f * x)
    private fun overshoot(x: Float): Float {
        val c = 1.4f
        val y = x - 1f
        return 1f + (c + 1f) * y * y * y + c * y * y
    }
    /** A speaking voice's loudness, 0..1: syllables on a slow phrase. */
    private fun voice(ms: Long): Float {
        val s = ms / 1000f
        val syllables = 0.5f + 0.5f * sin(2f * PI.toFloat() * 4.2f * s)
        val phrase = 0.55f + 0.45f * sin(2f * PI.toFloat() * 0.7f * s + 0.6f)
        return (syllables * phrase).coerceIn(0f, 1f)
    }
}

enum class PillIcon { NONE, YOU, CYCLONE, DONE, WAIT, READY }

data class DriveIntroFrame(
    /** 0..2 for the scenes, 3 for the close. */
    val scene: Int,
    val title: String,
    val detail: String,
    val captionAlpha: Float,
    /** The orb's size, 0.34 (the Ask bubble) to 1 (Drive's orb). */
    val orbScale: Float,
    val motion: OrbMotion,
    val level: Float,
    val warm: Boolean,
    /** A tap's ring, 0..1 while it spreads; 0 when there is none. */
    val ripple: Float,
    val pill: String,
    val pillIcon: PillIcon,
    val pillAlpha: Float,
    /** 1 = still below its place (rising in), 0 = in place. */
    val pillLift: Float,
    val roadTravel: Float,
    val roadSpeed: Float,
    /** The close: Got it shows. */
    val done: Boolean,
) {
    /** What TalkBack reads for the scene. */
    val spoken: String get() = "$title. $detail"
}
