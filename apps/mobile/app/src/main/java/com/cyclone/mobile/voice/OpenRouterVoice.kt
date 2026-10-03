package com.cyclone.mobile.voice

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A voice call that failed, said to the owner as [failure]. The message is for diagnostics and never holds audio. */
class VoiceCallException(val failure: VoiceFailure, message: String) : IOException(message)

/**
 * Drive's three OpenRouter calls on the owner's key (plan 24 §3): speech to text, understanding, text to speech. Fixed
 * OpenRouter origin, no redirects (they would carry the bearer), no retries (a slow answer is worse than a spoken
 * "I can't reach the internet"). Nothing here is written to disk; the clip lives only in the request body.
 */
class OpenRouterVoice(private val key: String, private val http: OkHttpClient = CLIENT) {

    /** The recorded clip, as WAV bytes in memory, to text. */
    fun transcribe(model: String, wav: ByteArray, language: String = "auto", track: (Call) -> Unit = {}): String {
        val reply = post("audio/transcriptions", transcriptionBody(model, wav, language), track)
        val json = runCatching { JSONObject(reply) }.getOrNull() ?: throw VoiceCallException(VoiceFailure.NOT_HEARD, "transcription was not JSON")
        return (json.optString("text").ifBlank { json.optString("transcript") }).trim()
    }

    /** One fast chat call with a strict output shape. A model that refuses the schema gets plain JSON mode once. */
    fun understand(model: String, transcript: String, context: VoiceContext, track: (Call) -> Unit = {}): Understanding {
        fun body(format: JSONObject) = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("max_tokens", 220)
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", VoiceUnderstanding.systemPrompt()))
                .put(JSONObject().put("role", "user").put("content", VoiceUnderstanding.userPrompt(transcript, context))))
            .put("response_format", format)
            .put("provider", JSONObject().put("sort", "latency").put("require_parameters", false))
        val reply = try {
            post("chat/completions", body(VoiceUnderstanding.responseFormat()), track)
        } catch (error: SchemaRefused) {
            post("chat/completions", body(JSONObject().put("type", "json_object")), track)
        }
        val content = runCatching {
            JSONObject(reply).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        }.getOrNull()
        return VoiceUnderstanding.parse(content)
    }

    /**
     * Speaks [text] with [model] and [voice] as raw 16-bit PCM, handing each chunk to [onPcm] as it arrives so playback
     * starts on the first bytes. Returns the stream's sample rate before the first chunk via [onStart].
     */
    fun speak(model: String, voice: String?, text: String, onStart: (Int) -> Unit, onPcm: (ByteArray, Int) -> Unit,
              track: (Call) -> Unit = {}) {
        val body = JSONObject().put("model", model).put("input", text).put("response_format", "pcm")
        voice?.let { body.put("voice", it) }
        val call = http.newCall(request("audio/speech", body))
        track(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw failure(response.code, response.body?.string().orEmpty())
                val source = response.body?.byteStream() ?: throw VoiceCallException(VoiceFailure.OFFLINE, "speech had no body")
                onStart(VoiceModels.pcmRate(response.header("Content-Type")))
                val buffer = ByteArray(4_096)
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    if (n > 0) onPcm(buffer, n)
                }
            }
        } catch (error: VoiceCallException) {
            throw error
        } catch (error: IOException) {
            throw VoiceCallException(VoiceFailure.OFFLINE, "speech: ${error.javaClass.simpleName}")
        }
    }

    /**
     * Opens the connection to OpenRouter while the owner is still talking (a tiny authenticated GET of the key's own
     * status), so the transcription call that follows skips the TLS handshake. Failures are ignored.
     */
    fun warm() {
        val request = Request.Builder().url("$BASE/key").header("Authorization", "Bearer $key").build()
        runCatching { http.newCall(request).execute().use { it.body?.close() } }
    }

    /**
     * JEV, watching: one call to OpenRouter's Decisions API (alpha). Returns the raw answer for [JevShadow.parse]; any
     * failure is a [VoiceCallException] and changes nothing in Drive.
     */
    fun decide(body: JSONObject, track: (Call) -> Unit = {}): String {
        val request = Request.Builder().url(DECISIONS)
            .header("Authorization", "Bearer $key")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Drive")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val call = JEV_CLIENT.newCall(request)
        track(call)
        return try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw failure(response.code, text)
                text
            }
        } catch (error: VoiceCallException) {
            throw error
        } catch (error: IOException) {
            throw VoiceCallException(VoiceFailure.OFFLINE, "decisions: ${error.javaClass.simpleName}")
        }
    }

    /** `GET /models?output_modalities=[modality]`: the live list Drive picks from. */
    fun models(modality: String): List<VoiceModel> {
        val request = Request.Builder().url("$BASE/models?output_modalities=$modality")
            .header("Authorization", "Bearer $key").header("X-Title", "Cyclone Drive").build()
        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw failure(response.code, text)
                VoiceModels.parse(text)
            }
        } catch (error: VoiceCallException) {
            throw error
        } catch (error: IOException) {
            throw VoiceCallException(VoiceFailure.OFFLINE, "models: ${error.javaClass.simpleName}")
        }
    }

    private class SchemaRefused : IOException("structured outputs refused")

    private fun post(path: String, body: JSONObject, track: (Call) -> Unit): String {
        val call = http.newCall(request(path, body))
        track(call)
        return try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 400 && path == "chat/completions" && text.contains("response_format", ignoreCase = true)) throw SchemaRefused()
                    throw failure(response.code, text)
                }
                text
            }
        } catch (error: VoiceCallException) {
            throw error
        } catch (error: SchemaRefused) {
            throw error
        } catch (error: IOException) {
            throw VoiceCallException(VoiceFailure.OFFLINE, "$path: ${error.javaClass.simpleName}")
        }
    }

    private fun request(path: String, body: JSONObject): Request = Request.Builder()
        .url("$BASE/$path")
        .header("Authorization", "Bearer $key")
        .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
        .header("X-Title", "Cyclone Drive")
        .post(body.toString().toRequestBody(JSON))
        .build()

    private fun failure(code: Int, body: String): VoiceCallException = when (code) {
        401, 403 -> VoiceCallException(VoiceFailure.NO_KEY, "OpenRouter HTTP $code")
        else -> VoiceCallException(VoiceFailure.OFFLINE, "OpenRouter HTTP $code: ${body.take(160).replace(Regex("\\s+"), " ")}")
    }

    companion object {
        /** `POST audio/transcriptions`: the clip inline as base64 WAV (Grok STT and the others take the same shape). */
        fun transcriptionBody(model: String, wav: ByteArray, language: String = "auto"): JSONObject {
            val body = JSONObject()
                .put("model", model)
                .put("input_audio", JSONObject().put("data", Base64.getEncoder().encodeToString(wav)).put("format", "wav"))
            languageCode(language)?.let { body.put("language", it) }
            return body
        }

        private const val BASE = "https://openrouter.ai/api/v1"
        private const val DECISIONS = "https://openrouter.ai/api/alpha/decisions"
        private val JSON = "application/json".toMediaType()

        val CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .build()

        /** JEV only watches: it gets a short leash so it never holds anything up. */
        private val JEV_CLIENT: OkHttpClient by lazy { CLIENT.newBuilder().callTimeout(3, TimeUnit.SECONDS).build() }

        /** ISO code for a language name, for transcription; null means auto-detect. */
        fun languageCode(language: String): String? = when (language.lowercase()) {
            "english" -> "en"
            "dutch" -> "nl"
            "german" -> "de"
            "french" -> "fr"
            "spanish" -> "es"
            else -> null
        }
    }
}
