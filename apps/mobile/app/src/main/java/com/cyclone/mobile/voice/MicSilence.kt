package com.cyclone.mobile.voice

import kotlin.math.abs

/**
 * Tells a silenced microphone from a quiet room (alpha.72). When Android does not let an app in the background use
 * the microphone, the recording still "works" but every sample is zero. A real microphone, even in a silent car with
 * noise suppression on, always shows a few steps of noise. So [windowMs] of samples that never leave
 * [DIGITAL_ZERO] means nobody can be heard, and Drive switches to Android's own speech recognizer instead of waiting
 * four seconds and closing as if the owner had said nothing. Pure, so it is tested without a phone.
 */
class MicSilence(private val windowMs: Int = WINDOW_MS, private val sampleRate: Int = VoiceActivity.SAMPLE_RATE) {
    private var seen = 0
    private var live = false

    /** Feeds [count] samples; true once the whole window has been digital silence. */
    fun feed(samples: ShortArray, count: Int = samples.size): Boolean {
        if (live) return false
        for (i in 0 until count) {
            if (abs(samples[i].toInt()) > DIGITAL_ZERO) { live = true; return false }
        }
        seen += count
        return seen >= sampleRate / 1000 * windowMs
    }

    companion object {
        const val WINDOW_MS = 600
        /** Dither and rounding can leave ±1 or ±2 on a silenced stream; a live microphone goes past this. */
        const val DIGITAL_ZERO = 2
    }
}
