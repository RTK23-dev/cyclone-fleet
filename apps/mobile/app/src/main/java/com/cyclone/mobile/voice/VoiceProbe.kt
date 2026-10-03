package com.cyclone.mobile.voice

import android.os.SystemClock
import java.io.ByteArrayOutputStream

/**
 * Test voice (plan 24 §5.7): measures, on this phone and network, each stage of a request against its target. Cyclone
 * says a sample request with the chosen voice, transcribes that audio back with the chosen model, and has the fast
 * model understand it; no microphone and nothing stored. The owner picks a fast voice from real numbers.
 */
object VoiceProbe {
    const val SAMPLE = "Set a timer for ten minutes."

    data class SttRun(val model: String, val ms: Long?, val transcript: String, val error: String? = null)

    /** Alpha 93: another listed voice saying the same sample; [ms] is the time to its first sound. */
    data class TtsRun(val model: String, val ms: Long?, val error: String? = null)

    data class Result(
        val firstSoundMs: Long? = null,
        val transcribeMs: Long? = null,
        val understandMs: Long? = null,
        val transcript: String = "",
        val kind: VoiceKind? = null,
        val goal: String = "",
        val error: String? = null,
        /** The same clip through the other listed speech-to-text models (alpha.52): model, time, what it heard. */
        val sttCompared: List<SttRun> = emptyList(),
        /** The same sample through up to two more listed voices (alpha.93: Grok against Gemini), first sound only. */
        val ttsCompared: List<TtsRun> = emptyList(),
    ) {
        /** What the owner would wait between stopping and hearing the confirmation start. */
        val confirmMs: Long? get() = if (transcribeMs != null && understandMs != null && firstSoundMs != null) transcribeMs + understandMs + firstSoundMs else null
        val understood: Boolean get() = kind == VoiceKind.TASK && goal.contains("timer", ignoreCase = true)
    }

    /** Network: call off the main thread. */
    fun run(key: String, choice: VoiceChoice, language: String): Result {
        if (key.isBlank()) return Result(error = VoiceCopy.NO_KEY)
        val tts = choice.tts ?: return Result(error = "OpenRouter lists no speech model right now.")
        val api = OpenRouterVoice(key)
        return try {
            val pcm = ByteArrayOutputStream()
            var rate = 24_000
            var first = -1L
            val started = SystemClock.elapsedRealtime()
            api.speak(tts, choice.voice, SAMPLE, onStart = { rate = it }, onPcm = { bytes, n ->
                if (first < 0) first = SystemClock.elapsedRealtime() - started
                pcm.write(bytes, 0, n)
            })
            var result = Result(firstSoundMs = first.takeIf { it >= 0 }, ttsCompared = compareVoices(api, tts))
            val stt = choice.stt ?: return result.copy(error = "Recognition is on the phone: only speech is measured.")
            val clip = Wav.resample(Wav.samples(pcm.toByteArray()), rate, VoiceActivity.SAMPLE_RATE)
            val t0 = SystemClock.elapsedRealtime()
            val text = api.transcribe(stt, Wav.encode(clip), language)
            result = result.copy(transcribeMs = SystemClock.elapsedRealtime() - t0, transcript = text)
            // The same clip through up to two more listed transcription models, newest first, to compare on this phone.
            val others = VoiceCatalog.lists.value.stt.map { it.id }
                .let { ids -> VoiceModels.PREFERRED_STT.mapNotNull { want -> ids.firstOrNull { it == want || it.startsWith("$want-") } } }
                .filter { it != stt }.distinct().take(2)
            val wav = Wav.encode(clip)
            result = result.copy(sttCompared = others.map { other ->
                val t = SystemClock.elapsedRealtime()
                try {
                    val heard = api.transcribe(other, wav, language)
                    SttRun(other, SystemClock.elapsedRealtime() - t, heard)
                } catch (error: VoiceCallException) {
                    SttRun(other, null, "", error.message)
                }
            })
            val fast = choice.fast ?: return result.copy(error = "OpenRouter lists no fast text model right now.")
            val t1 = SystemClock.elapsedRealtime()
            val u = api.understand(fast, text, VoiceContext(language = language))
            result.copy(understandMs = SystemClock.elapsedRealtime() - t1, kind = u.kind, goal = u.goal)
        } catch (error: VoiceCallException) {
            Result(error = error.message)
        }
    }

    /** Up to two other preferred voices from the live list, each timed to its first sound and then cut off. */
    private fun compareVoices(api: OpenRouterVoice, chosen: String): List<TtsRun> {
        val ids = VoiceCatalog.lists.value.tts.map { it.id }
        val others = VoiceModels.PREFERRED_TTS_BEST.mapNotNull { want -> ids.firstOrNull { it == want || it.startsWith("$want-") } }
            .filter { it != chosen }.distinct().take(2)
        return others.map { other ->
            val voice = VoiceModels.voice(VoiceCatalog.lists.value.tts.firstOrNull { it.id == other }, null)
            val started = SystemClock.elapsedRealtime()
            var first = -1L
            try {
                api.speak(other, voice, SAMPLE, onStart = {}, onPcm = { _, _ ->
                    if (first < 0) {
                        first = SystemClock.elapsedRealtime() - started
                        throw FirstSound()
                    }
                })
                TtsRun(other, first.takeIf { it >= 0 })
            } catch (stop: FirstSound) {
                TtsRun(other, first)
            } catch (error: VoiceCallException) {
                TtsRun(other, null, error.message)
            }
        }
    }

    /** Ends a compared voice's stream at its first sound: only the wait is measured, nothing is played. */
    private class FirstSound : RuntimeException()
}
