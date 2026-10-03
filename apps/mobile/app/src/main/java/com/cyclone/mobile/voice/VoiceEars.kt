package com.cyclone.mobile.voice

/**
 * Which way Drive listens, and what happens when that way fails (alpha.78). Two ears:
 * - **the recorder:** Cyclone's own recording, transcribed by the owner's model;
 * - **the recognizer:** Android's speech recognizer, which records in its own process.
 *
 * Before alpha.78 one failure switched the whole session to the recognizer for good. A recognizer that couldn't start
 * in that state then failed on every tap at once ("I didn't catch that", again and again), with no way back. Now:
 * - an ear that fails hands over to the other ear **in the same turn**, once;
 * - a recognizer that fails hands the session back to the recorder;
 * - a network error in transcription never switches ears (the next tap simply tries again).
 *
 * Pure; the session asks it and does what it says.
 */
class VoiceEars {
    enum class Ear(val label: String) { RECORDER("Cyclone's recording"), RECOGNIZER("Android's recognizer") }

    /** The session's ear after a switch; null follows the owner's setting. */
    private var sessionEar: Ear? = null
    private val triedThisTurn = mutableSetOf<Ear>()

    /** A turn starts: the ear to listen with. [onDeviceSetting] is Settings → Voice → on-device recognition. */
    fun start(onDeviceSetting: Boolean): Ear {
        triedThisTurn.clear()
        val ear = sessionEar ?: if (onDeviceSetting) Ear.RECOGNIZER else Ear.RECORDER
        triedThisTurn += ear
        return ear
    }

    /** The recording ran but Android gave it silence, or another app holds the microphone. */
    fun recorderDeaf(): Ear? {
        sessionEar = Ear.RECOGNIZER
        return next(Ear.RECOGNIZER)
    }

    /** The recognizer could not listen at all (not "nothing was said"): back to the recorder, now and for the session. */
    fun recognizerBroken(): Ear? {
        sessionEar = Ear.RECORDER
        return next(Ear.RECORDER)
    }

    private fun next(ear: Ear): Ear? = if (ear in triedThisTurn) null else ear.also { triedThisTurn += it }
}
