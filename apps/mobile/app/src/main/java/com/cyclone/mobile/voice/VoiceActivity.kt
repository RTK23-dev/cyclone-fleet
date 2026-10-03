package com.cyclone.mobile.voice

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The voice detector (plan 24 §4.2): decides, 20 ms at a time, whether the owner is speaking, and when they stopped.
 * Pure Kotlin on 16 kHz mono PCM, so every rule is tested on synthetic signals without a phone.
 *
 * A frame is voice when its energy is well above the room's noise floor (learned from the quiet frames, so a car's
 * road noise does not count as speech) and its zero-crossing rate is in the range of speech (hiss and clicks are not).
 * Speech starts after [Tuning.startMs] of voice; it ends after [Tuning.endSilenceMs] of trailing silence.
 */
class VoiceActivity(private val tuning: Tuning = Tuning()) {

    data class Tuning(
        val sampleRate: Int = SAMPLE_RATE,
        /** Trailing silence that closes the mic (plan: 0.7 s, adjustable 0.5–1.2 s). */
        val endSilenceMs: Int = 700,
        /** No speech this long after opening: close without a single call. */
        val noSpeechMs: Int = 4_000,
        /** A clip is never longer than this. */
        val maxClipMs: Int = 15_000,
        /** Voice this long in a row counts as the start of speech. */
        val startMs: Int = 120,
        /** Speech shorter than this is an accidental tap or a cough. */
        val minSpeechMs: Int = 400,
        /** Voice is this many times louder than the noise floor (RMS). */
        val floorRatio: Double = 2.5,
        /**
         * Below this RMS (of 32767) nothing is voice, however quiet the room. Alpha.72 lowered it from 350: the
         * VOICE_RECOGNITION source has no gain control, and a phone in a car mount hears normal speech at 150–400.
         * The noise floor ratio and the speech zero-crossing range still keep noise out.
         */
        val absoluteMin: Double = 120.0,
    ) {
        init {
            require(endSilenceMs in 300..2_000) { "endSilenceMs out of range" }
        }
    }

    sealed interface Result {
        /** Still listening. [level] is 0..1 for the orb; [speechMs] is how long the owner has been talking so far. */
        data class Listening(val level: Float, val speaking: Boolean, val speechMs: Int = 0) : Result
        /** The owner spoke and stopped: [voicedMs] of speech (pauses between words included) from [startMs] to [endMs]. */
        data class Heard(val voicedMs: Int, val startMs: Int, val endMs: Int) : Result
        /** Nothing but silence or noise: close, no network. */
        data object NoSpeech : Result
        /** A blip too short to be a request: close, no network. */
        data class Blip(val voicedMs: Int) : Result
    }

    private val frameSamples = tuning.sampleRate / 50
    private var noiseFloor = 0.0
    private var calibrated = 0
    private var elapsedMs = 0
    private var runMs = 0
    private var silenceMs = 0
    private var voicedMs = 0
    private var speechStartMs = -1
    private var lastVoiceEndMs = 0
    private var finished: Result? = null
    private val pending = ShortArray(frameSamples)
    private var pendingCount = 0
    private var level = 0f

    /** Feeds [count] samples; returns the latest result. Once finished, the result never changes. */
    fun feed(samples: ShortArray, count: Int = samples.size): Result {
        finished?.let { return it }
        var i = 0
        while (i < count) {
            val take = min(frameSamples - pendingCount, count - i)
            System.arraycopy(samples, i, pending, pendingCount, take)
            pendingCount += take
            i += take
            if (pendingCount == frameSamples) {
                pendingCount = 0
                frame(pending)
                finished?.let { return it }
            }
        }
        return Result.Listening(level, speechStartMs >= 0 && silenceMs == 0, if (speechStartMs >= 0) lastVoiceEndMs - speechStartMs else 0)
    }

    private fun frame(samples: ShortArray) {
        elapsedMs += FRAME_MS
        var sum = 0.0
        var crossings = 0
        for (k in samples.indices) {
            val v = samples[k].toDouble()
            sum += v * v
            if (k > 0 && (samples[k] >= 0) != (samples[k - 1] >= 0)) crossings++
        }
        val rms = sqrt(sum / samples.size)
        val zcr = crossings.toDouble() / samples.size
        // Learn the floor from the quietest of the first frames (the owner may already be talking), then from every
        // quiet frame after; it rises slowly and falls fast.
        if (calibrated < CALIBRATION_FRAMES) {
            noiseFloor = if (calibrated == 0) rms else min(noiseFloor, rms)
            calibrated++
        }
        val threshold = max(tuning.absoluteMin, noiseFloor * tuning.floorRatio)
        val voice = rms >= threshold && zcr in SPEECH_ZCR
        if (!voice && calibrated >= CALIBRATION_FRAMES) noiseFloor = if (rms < noiseFloor) rms * 0.5 + noiseFloor * 0.5 else noiseFloor * 0.98 + rms * 0.02
        level = (((rms - noiseFloor) / (threshold * 4 - noiseFloor).coerceAtLeast(1.0)).coerceIn(0.0, 1.0)).toFloat()

        if (voice) {
            runMs += FRAME_MS
            silenceMs = 0
            if (speechStartMs < 0 && runMs >= tuning.startMs) speechStartMs = elapsedMs - runMs
            if (speechStartMs >= 0) { voicedMs += FRAME_MS; lastVoiceEndMs = elapsedMs }
        } else {
            if (speechStartMs >= 0) silenceMs += FRAME_MS
            runMs = 0
        }
        // Voice frames counted before the start was confirmed belong to the speech too.
        if (speechStartMs >= 0 && voicedMs < runMs) voicedMs = runMs

        finished = when {
            speechStartMs < 0 && elapsedMs >= tuning.noSpeechMs -> Result.NoSpeech
            speechStartMs >= 0 && silenceMs >= tuning.endSilenceMs -> end()
            elapsedMs >= tuning.maxClipMs -> if (speechStartMs < 0) Result.NoSpeech else end()
            else -> null
        }
    }

    /** Speech is measured from its start to its last voiced frame: syllables with short gaps are one request. */
    private fun end(): Result {
        val span = lastVoiceEndMs - speechStartMs
        return if (span < tuning.minSpeechMs || voicedMs < tuning.startMs) Result.Blip(span) else Result.Heard(span, speechStartMs, lastVoiceEndMs)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        private const val CALIBRATION_FRAMES = 5
        /** Zero crossings per sample: voiced speech sits well below hiss (≈0.5) and above hum (≈0). */
        private val SPEECH_ZCR = 0.01..0.35
    }
}
