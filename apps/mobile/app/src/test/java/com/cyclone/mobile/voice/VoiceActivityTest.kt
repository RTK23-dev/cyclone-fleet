package com.cyclone.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** The voice detector on synthetic signals: silence, road noise, hiss, a blip and real-length "speech". */
class VoiceActivityTest {
    private val rate = VoiceActivity.SAMPLE_RATE

    private fun silence(ms: Int, amplitude: Int = 40, seed: Int = 1): ShortArray {
        val r = Random(seed)
        return ShortArray(rate * ms / 1000) { (r.nextInt(-amplitude, amplitude + 1)).toShort() }
    }

    /** A voiced sound: a 180 Hz fundamental with harmonics and a syllable-rate envelope, like speech. */
    private fun speech(ms: Int, amplitude: Double = 6_000.0): ShortArray = ShortArray(rate * ms / 1000) { i ->
        val t = i.toDouble() / rate
        val envelope = 0.65 + 0.35 * sin(2 * PI * 4 * t)
        val v = sin(2 * PI * 180 * t) + 0.5 * sin(2 * PI * 360 * t) + 0.25 * sin(2 * PI * 720 * t)
        (amplitude * envelope * v / 1.75).toInt().toShort()
    }

    /** White hiss: loud, but it crosses zero far too often to be a voice. */
    private fun hiss(ms: Int, amplitude: Int = 8_000): ShortArray {
        val r = Random(7)
        return ShortArray(rate * ms / 1000) { r.nextInt(-amplitude, amplitude + 1).toShort() }
    }

    private fun run(vararg parts: ShortArray, tuning: VoiceActivity.Tuning = VoiceActivity.Tuning()): VoiceActivity.Result {
        val detector = VoiceActivity(tuning)
        var last: VoiceActivity.Result = VoiceActivity.Result.Listening(0f, false)
        for (part in parts) {
            // Feed in odd-sized chunks, as AudioRecord does.
            var i = 0
            while (i < part.size) {
                val n = minOf(437, part.size - i)
                last = detector.feed(part.copyOfRange(i, i + n))
                if (last !is VoiceActivity.Result.Listening) return last
                i += n
            }
        }
        return last
    }

    @Test fun `silence closes after four seconds with no speech`() {
        assertEquals(VoiceActivity.Result.NoSpeech, run(silence(5_000)))
    }

    @Test fun `hiss is not speech`() {
        assertEquals(VoiceActivity.Result.NoSpeech, run(silence(200), hiss(5_000)))
    }

    @Test fun `a short blip is an accidental tap`() {
        val result = run(silence(300), speech(200), silence(1_500))
        assertTrue("was $result", result is VoiceActivity.Result.Blip)
    }

    @Test fun `speech ends after trailing silence`() {
        val result = run(silence(300), speech(1_500), silence(1_000))
        assertTrue("was $result", result is VoiceActivity.Result.Heard)
        result as VoiceActivity.Result.Heard
        assertTrue("voiced ${result.voicedMs}", result.voicedMs in 1_300..1_600)
        assertTrue("start ${result.startMs}", result.startMs in 250..400)
    }

    @Test fun `the end waits for the configured silence`() {
        val detector = VoiceActivity()
        for (part in listOf(silence(300), speech(1_000), silence(500))) {
            assertTrue(detector.feed(part) is VoiceActivity.Result.Listening)
        }
        assertTrue(detector.feed(silence(300)) is VoiceActivity.Result.Heard)
    }

    @Test fun `speech over steady road noise is still heard`() {
        val road = { ms: Int -> ShortArray(rate * ms / 1000) { i -> (900 * sin(2 * PI * 60 * i / rate)).toInt().toShort() } }
        val mixed = speech(1_200).let { s -> val n = road(1_200); ShortArray(s.size) { (s[it] + n[it]).coerceIn(-32768, 32767).toShort() } }
        val result = run(road(400), mixed, road(1_000))
        assertTrue("was $result", result is VoiceActivity.Result.Heard)
    }

    @Test fun `road noise alone is not speech`() {
        val road = ShortArray(rate * 5) { i -> (900 * sin(2 * PI * 60 * i / rate)).toInt().toShort() }
        assertEquals(VoiceActivity.Result.NoSpeech, run(road))
    }

    @Test fun `a pause between words does not end the request`() {
        val result = run(silence(300), speech(600), silence(400), speech(600), silence(900))
        assertTrue("was $result", result is VoiceActivity.Result.Heard)
        assertTrue((result as VoiceActivity.Result.Heard).voicedMs >= 1_100)
    }

    @Test fun `a clip never runs past fifteen seconds`() {
        val result = run(silence(200), speech(20_000))
        assertTrue("was $result", result is VoiceActivity.Result.Heard)
        assertTrue((result as VoiceActivity.Result.Heard).endMs <= 15_000)
    }

    @Test fun `speech time grows only with real speech, so a blip never counts as talking`() {
        val detector = VoiceActivity()
        detector.feed(silence(300))
        val blip = detector.feed(speech(200)) as VoiceActivity.Result.Listening
        assertTrue("blip ${blip.speechMs}", blip.speechMs < VoiceActivity.Tuning().minSpeechMs)
        val talk = detector.feed(speech(600)) as VoiceActivity.Result.Listening
        assertTrue("talk ${talk.speechMs}", talk.speechMs >= VoiceActivity.Tuning().minSpeechMs)
    }

    @Test fun `the result is final`() {
        val detector = VoiceActivity()
        val first = detector.feed(silence(5_000))
        assertEquals(first, detector.feed(speech(2_000)))
    }

    @Test fun `talking right after the earcon still counts`() {
        // The session opens the microphone after the earcon; the detector needs 100 ms of room to learn the floor.
        val result = run(silence(100), speech(1_500), silence(1_000))
        assertTrue("was $result", result is VoiceActivity.Result.Heard)
    }

    @Test fun `quiet speech from a phone in a car mount is heard`() {
        // Alpha.72: about 200 RMS, what a mounted phone hears without gain control. The old 350 floor dropped it.
        val result = run(silence(300, amplitude = 8), speech(1_500, amplitude = 700.0), silence(1_000, amplitude = 8))
        assertTrue("was $result", result is VoiceActivity.Result.Heard)
    }

    @Test fun `the level follows the voice`() {
        val detector = VoiceActivity()
        detector.feed(silence(300))
        val quiet = detector.feed(silence(100)) as VoiceActivity.Result.Listening
        val loud = detector.feed(speech(300)) as VoiceActivity.Result.Listening
        assertTrue("quiet ${quiet.level} loud ${loud.level}", loud.level > quiet.level + 0.2f)
        assertTrue(loud.speaking)
    }
}
