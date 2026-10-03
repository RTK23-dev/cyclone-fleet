package com.cyclone.mobile.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * Which models Drive uses, chosen from OpenRouter's live list (plan 32: never hard-code a model the list has not
 * shown). The preferences below are only an order of taste; a model is used only when the live list has it, and
 * the owner's own pick always wins while it is still listed.
 */
data class VoiceModel(
    val id: String,
    val name: String,
    /** USD per million input tokens (or per unit the provider bills), when the list says; for the cost estimate only. */
    val promptPrice: Double? = null,
    val completionPrice: Double? = null,
    /** Voices the list advertises for a speech model, if any. */
    val voices: List<String> = emptyList(),
)

data class VoiceChoice(val stt: String?, val tts: String?, val fast: String?, val voice: String?)

object VoiceModels {
    /**
     * Newest first where it is better (alpha.52, researched 2026-09-27); the live list decides what exists, and a dated
     * or numbered variant ("meta/muse-voice-transcribe-1.0") counts as the model it names.
     * - Speech to text: Meta Muse Voice Transcribe (built for push-to-talk, ≈3% errors, final text ≈0.16 s after you
     *   stop), then Microsoft MAI-Transcribe, Google Gemini 3.5 Transcribe, then the older, cheaper Whisper line.
     * - Voice: Gemini 3.8 Flash-Lite TTS (fast); [PREFERRED_TTS_BEST] puts Gemini 3.8 Flash TTS first.
     * - Understanding: Gemini Flash-Lite.
     * Alpha 93 (owner request): xAI's Grok STT and Grok Voice TTS come first when listed (P50 ≈0.35 s and ≈0.07 s to
     * first audio on OpenRouter, against ≈3 s for Gemini 3.8 Flash TTS). The live list still decides; without them the
     * old order holds. Test voice compares them on the phone.
     */
    val PREFERRED_STT = listOf("x-ai/grok-stt", "meta/muse-voice-transcribe", "microsoft/mai-transcribe", "google/gemini-3.5-transcribe",
        "openai/gpt-4o-mini-transcribe", "openai/whisper-large-v3-turbo", "qwen/qwen3-asr-flash", "deepgram/nova-3", "openai/whisper-large-v3")
    val PREFERRED_TTS = listOf("x-ai/grok-voice-tts", "google/gemini-3.8-flash-lite-tts", "google/gemini-3.8-flash-tts", "openai/gpt-4o-mini-tts",
        "hexgrad/kokoro-82m", "deepgram/aura-2", "mistralai/voxtral-mini-tts", "minimax/speech-2.8-turbo")
    val PREFERRED_TTS_BEST = (listOf("x-ai/grok-voice-tts", "google/gemini-3.8-flash-tts") + PREFERRED_TTS).distinct()
    val PREFERRED_FAST = listOf("google/gemini-3.1-flash-lite", "google/gemini-3.5-flash-lite", "google/gemini-2.5-flash-lite",
        "google/gemini-3-flash", "openai/gpt-5-nano", "openai/gpt-4.1-nano", "mistralai/ministral-8b")

    /** Voices known to work per model family, used when the list names none. The first is the default. */
    private val FAMILY_VOICES = listOf(
        "x-ai/" to listOf("eve", "ara", "rex", "sal", "leo"),
        "google/" to listOf("Kore", "Puck", "Zephyr", "Charon", "Aoede", "Fenrir", "Leda", "Orus"),
        "openai/" to listOf("coral", "alloy", "nova", "sage", "shimmer", "verse", "ash", "echo"),
        "hexgrad/" to listOf("af_heart", "af_bella", "am_michael", "bf_emma", "bm_george"),
    )

    /** Rows of `GET /models?output_modalities=…`: ids, names, prices and any advertised voices. */
    fun parse(body: String): List<VoiceModel> {
        val rows = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return emptyList()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("id").trim()
            if (!SLUG.matches(id)) return@mapNotNull null
            val pricing = row.optJSONObject("pricing")
            VoiceModel(id, row.optString("name").ifBlank { id }.take(120),
                pricing?.optString("prompt")?.toDoubleOrNull(), pricing?.optString("completion")?.toDoubleOrNull(),
                strings(row.optJSONArray("voices")) + strings(row.optJSONObject("architecture")?.optJSONArray("voices")))
        }.distinctBy { it.id }
    }

    /**
     * The model to use: the owner's [chosen] while listed, otherwise the first preference whose id (or dated
     * variant, "openai/gpt-4o-mini-tts-2025-12-15") is listed, otherwise the first listed model. Null when the list
     * is empty: Drive then says so instead of guessing.
     */
    fun pick(live: List<VoiceModel>, preferred: List<String>, chosen: String? = null): String? {
        if (live.isEmpty()) return null
        chosen?.takeIf { c -> live.any { it.id == c } }?.let { return it }
        for (want in preferred) {
            live.firstOrNull { it.id == want }?.let { return it.id }
            live.filter { it.id.startsWith("$want-") }.maxByOrNull { it.id }?.let { return it.id }
        }
        return live.first().id
    }

    fun voices(model: VoiceModel?): List<String> = when {
        model == null -> emptyList()
        model.voices.isNotEmpty() -> model.voices
        else -> FAMILY_VOICES.firstOrNull { model.id.startsWith(it.first) }?.second.orEmpty()
    }

    /** The owner's voice while the model offers it; otherwise the model's first; null lets the provider choose. */
    fun voice(model: VoiceModel?, chosen: String?): String? {
        val all = voices(model)
        return chosen?.takeIf { it in all } ?: all.firstOrNull()
    }

    fun choose(stt: List<VoiceModel>, tts: List<VoiceModel>, text: List<VoiceModel>, settings: DriverSettings): VoiceChoice {
        val ttsId = pick(tts, if (settings.bestVoice) PREFERRED_TTS_BEST else PREFERRED_TTS, settings.ttsModel)
        return VoiceChoice(
            stt = if (settings.onDeviceStt) null else pick(stt, PREFERRED_STT, settings.sttModel),
            tts = ttsId,
            fast = pick(text, PREFERRED_FAST, settings.fastModel),
            voice = voice(tts.firstOrNull { it.id == ttsId }, settings.voice),
        )
    }

    /** The sample rate of a `pcm` speech stream from its Content-Type ("audio/pcm;rate=24000"), 24 kHz otherwise. */
    fun pcmRate(contentType: String?): Int {
        val rate = contentType?.let { Regex("(?i)rate\\s*=\\s*(\\d{4,6})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return rate?.takeIf { it in 8_000..48_000 } ?: 24_000
    }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }

    private val SLUG = Regex("^[A-Za-z0-9._~-]+/[A-Za-z0-9._~:-]+$")
}
