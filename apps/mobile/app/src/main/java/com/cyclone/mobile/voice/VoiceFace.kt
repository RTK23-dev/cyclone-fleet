package com.cyclone.mobile.voice

/**
 * What Drive shows for a turn (plan 32 design), kept pure and tested: the orb's motion, the status word, the
 * captions, which buttons appear, and what TalkBack says. The Compose code only draws this.
 */
enum class OrbMotion {
    /** Idle: a slow, calm breath. */
    CALM,
    /** Listening: breathes with the owner's voice. */
    LISTEN,
    /** Thinking: swirls. */
    SWIRL,
    /** Speaking: pulses with Cyclone's voice. */
    SPEAK,
    /** Working: the button with a progress ring. */
    WORK,
}

data class VoiceFace(
    val motion: OrbMotion,
    /** The small status word over the captions. */
    val status: String,
    /** The owner's words (GlassMuted), at most two lines; blank hides the pill. */
    val you: String,
    /** Cyclone's line (GlassInk), at most two lines; blank hides the pill. */
    val cyclone: String,
    val panel: Boolean,
    val dim: Boolean,
    /** Warm glow: Cyclone needs the owner. */
    val warm: Boolean,
    val stop: Boolean,
    val notNow: Boolean,
    /** The button's TalkBack description and state. */
    val description: String,
    val stateDescription: String,
) {
    /** The small status word shows only when neither caption already says it: one word, said once. */
    val showStatus: Boolean get() = status.isNotBlank() && listOf(you, cyclone).none { repeats(it, status) }

    companion object {
        private fun words(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

        /** True when [caption] opens with the same words as [status] ("Listening…" repeats "Listening"). */
        fun repeats(caption: String, status: String): Boolean {
            val said = words(status)
            return said.isNotEmpty() && words(caption).take(said.size) == said
        }

        fun of(turn: VoiceTurn): VoiceFace {
            val needsYou = turn.moment != null
            // An announced message waits for a tap: the orb glows until it is answered or a minute passes.
            val message = turn.offer != null && !needsYou
            val motion = when {
                turn.speaking -> OrbMotion.SPEAK
                turn.phase == VoicePhase.LISTENING -> OrbMotion.LISTEN
                turn.phase in setOf(VoicePhase.TRANSCRIBING, VoicePhase.UNDERSTANDING) -> OrbMotion.SWIRL
                turn.phase == VoicePhase.WORKING || turn.taskLive -> OrbMotion.WORK
                else -> OrbMotion.CALM
            }
            val status = when {
                turn.phase == VoicePhase.LISTENING -> "Listening"
                turn.phase in setOf(VoicePhase.TRANSCRIBING, VoicePhase.UNDERSTANDING) -> "Thinking"
                turn.phase == VoicePhase.READBACK -> "Check this"
                needsYou -> "Needs you"
                turn.speaking -> "Cyclone"
                message -> "Message"
                turn.taskLive -> "Working"
                else -> "Cyclone"
            }
            val cyclone = when {
                turn.phase == VoicePhase.LISTENING && turn.said.isBlank() -> VoiceCopy.CAPTION_LISTENING
                turn.phase in setOf(VoicePhase.TRANSCRIBING, VoicePhase.UNDERSTANDING) && turn.said.isBlank() -> VoiceCopy.CAPTION_THINKING
                else -> turn.said
            }
            val asking = turn.phase in setOf(VoicePhase.ASKING, VoicePhase.READBACK) ||
                (turn.phase == VoicePhase.LISTENING && turn.answering)
            val stateDescription = when (motion) {
                OrbMotion.LISTEN -> "Listening"
                OrbMotion.SWIRL -> "Thinking"
                OrbMotion.SPEAK -> "Speaking"
                OrbMotion.WORK -> if (needsYou) "Needs you" else "Working"
                OrbMotion.CALM -> if (needsYou) "Needs you" else if (message) "Message: tap to reply" else "Ready"
            }
            return VoiceFace(
                motion = motion,
                status = status,
                you = turn.heard.takeIf { turn.panelOpen }.orEmpty(),
                cyclone = cyclone.takeIf { turn.panelOpen }.orEmpty(),
                panel = turn.panelOpen,
                dim = turn.dimmed,
                warm = needsYou || message,
                stop = turn.panelOpen || turn.taskLive,
                notNow = asking && turn.moment != null,
                description = "Cyclone voice. Tap to talk. Hold one second to move.",
                stateDescription = stateDescription,
            )
        }
    }
}
