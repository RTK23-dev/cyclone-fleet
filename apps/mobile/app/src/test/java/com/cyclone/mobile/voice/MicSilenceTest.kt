package com.cyclone.mobile.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** A silenced microphone (exact zeros) against a quiet but real one. */
class MicSilenceTest {
    private val rate = VoiceActivity.SAMPLE_RATE
    private fun zeros(ms: Int) = ShortArray(rate * ms / 1000)
    private fun roomNoise(ms: Int, amplitude: Int = 6) = Random(3).let { r -> ShortArray(rate * ms / 1000) { r.nextInt(-amplitude, amplitude + 1).toShort() } }

    private fun feedAll(silence: MicSilence, samples: ShortArray): Boolean {
        var i = 0
        var silenced = false
        while (i < samples.size) {
            val n = minOf(320, samples.size - i)
            silenced = silence.feed(samples.copyOfRange(i, i + n)) || silenced
            i += n
        }
        return silenced
    }

    @Test fun `exact zeros for the window mean a silenced microphone`() {
        assertTrue(feedAll(MicSilence(), zeros(MicSilence.WINDOW_MS)))
    }

    @Test fun `it is decided well before the four second no-speech close`() {
        assertTrue(MicSilence.WINDOW_MS < VoiceActivity.Tuning().noSpeechMs / 4)
    }

    @Test fun `a quiet real room is never taken for silence`() {
        assertFalse(feedAll(MicSilence(), roomNoise(5_000)))
    }

    @Test fun `dither of one or two steps still counts as silenced`() {
        val dither = ShortArray(rate) { if (it % 7 == 0) 2 else if (it % 5 == 0) -1 else 0 }
        assertTrue(feedAll(MicSilence(), dither))
    }

    @Test fun `any real sound inside the window clears it for good`() {
        val silence = MicSilence()
        assertFalse(feedAll(silence, zeros(300)))
        assertFalse(silence.feed(shortArrayOf(0, 0, 40, 0)))
        assertFalse(feedAll(silence, zeros(2_000)))
    }
}
