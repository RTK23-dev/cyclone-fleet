package com.cyclone.mobile.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import kotlin.coroutines.resume

/**
 * Speech to text (plan 24 §5.3), two ways:
 * - [OpenRouterStt]: the clip Drive recorded, as WAV in memory, to the owner's transcription model;
 * - [OnDeviceStt]: Android's own recognizer (offline, private). It owns the microphone, so it listens and
 *   transcribes in one go instead of taking Drive's clip.
 */
class OpenRouterStt(private val api: OpenRouterVoice, private val model: String) {
    suspend fun transcribe(clip: ShortArray, language: String, track: (Call) -> Unit): String = withContext(Dispatchers.IO) {
        val wav = Wav.encode(clip)
        try {
            api.transcribe(model, wav, language, track)
        } finally {
            // The clip is not needed any more; drop the only copy of the audio.
            wav.fill(0)
        }
    }
}

class OnDeviceStt(private val context: Context) {
    sealed interface Result {
        data class Text(val text: String) : Result
        data object NothingHeard : Result
        /** [codes] are Android's recognizer error numbers, for the voice run log (alpha.78). */
        data class Failed(val failure: VoiceFailure, val codes: String = "") : Result
    }

    @Volatile private var active: SpeechRecognizer? = null

    /** The owner tapped while listening: the recognizer stops and delivers what it heard (alpha.78). */
    fun finish() { runCatching { active?.stopListening() } }

    /**
     * Listens with Android's recognizer until the owner stops; [onLevel] follows the voice. The on-device recognizer
     * goes first (offline, private); if it cannot serve this language or its model is missing, the phone's standard
     * recognizer takes over in the same turn (alpha.72).
     */
    suspend fun listen(language: String, onLevel: (Float) -> Unit): Result = withContext<Result>(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext Result.Failed(VoiceFailure.NOT_HEARD, "no recognizer on this phone")
        var onDeviceCode = ""
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            val first = attempt(SpeechRecognizer.createOnDeviceSpeechRecognizer(context), language, onLevel)
            if (!first.tryStandard) return@withContext first.result
            onDeviceCode = "on-device ${(first.result as? Result.Failed)?.codes.orEmpty()}; "
        }
        when (val second = attempt(SpeechRecognizer.createSpeechRecognizer(context), language, onLevel).result) {
            is Result.Failed -> second.copy(codes = onDeviceCode + "standard ${second.codes}")
            else -> second
        }
    }

    private class Attempt(val result: Result, val tryStandard: Boolean = false)

    private suspend fun attempt(recognizer: SpeechRecognizer, language: String, onLevel: (Float) -> Unit): Attempt = try {
        suspendCancellableCoroutine<Attempt> { continuation ->
            fun finish(attempt: Attempt) { if (continuation.isActive) continuation.resume(attempt) }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = onLevel(0f)
                override fun onError(error: Int) = finish(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Attempt(Result.NothingHeard)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Attempt(Result.Failed(VoiceFailure.NO_MIC, "error $error"))
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO -> Attempt(Result.Failed(VoiceFailure.MIC_BUSY, "error $error"))
                    // The recognizer itself could not serve this request: the standard one may.
                    in STANDARD_CAN_SERVE -> Attempt(Result.Failed(VoiceFailure.NOT_HEARD, "error $error"), tryStandard = true)
                    else -> Attempt(Result.Failed(VoiceFailure.NOT_HEARD, "error $error"))
                })
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    finish(Attempt(if (text.isBlank()) Result.NothingHeard else Result.Text(text)))
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            OpenRouterVoice.languageCode(language)?.let { intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            continuation.invokeOnCancellation { runCatching { recognizer.cancel() } }
            active = recognizer
            recognizer.startListening(intent)
        }
    } finally {
        if (active === recognizer) active = null
        runCatching { recognizer.destroy() }
        onLevel(0f)
    }

    private companion object {
        val STANDARD_CAN_SERVE = setOf(
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
        )
    }
}
