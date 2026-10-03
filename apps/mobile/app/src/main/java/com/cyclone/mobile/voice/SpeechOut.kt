package com.cyclone.mobile.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.sqrt

/**
 * Cyclone's voice (plan 24 §4.5): OpenRouter speech streamed as `pcm` into an [AudioTrack], so the first word plays
 * while the rest is still arriving. If the first bytes take longer than [FIRST_BYTE_MS], or there is no network, the
 * same line is said by Android's own [TextToSpeech]: the confirmation always happens.
 *
 * While Cyclone speaks it holds transient audio focus with ducking, so navigation and music dip and come back.
 * A few fixed lines ("Okay.", "Done.") are kept in memory per voice once synthesised, so they play at once; nothing
 * is written to disk.
 */
class SpeechOut(context: Context) {
    data class Spoken(val firstSoundMs: Long, val engine: String)

    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { }.build()
    private val _level = MutableStateFlow(0f)
    /** The voice's loudness (0..1), for the orb's pulse. */
    val level: StateFlow<Float> = _level
    private val local = LocalTts(app)
    private val cache = object : LinkedHashMap<String, Pair<Int, ByteArray>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, ByteArray>>?) = size > 12
    }
    @Volatile private var track: AudioTrack? = null
    @Volatile private var call: Call? = null

    /** Warms the local engine so a fallback line is not slow the first time. */
    fun prepare(language: String) = local.prepare(language)

    suspend fun earcon(earcon: Earcon) = withContext(Dispatchers.IO) {
        val pcm = Earcons.pcm(earcon)
        val bytes = ByteArray(pcm.size * 2)
        for (i in pcm.indices) { bytes[2 * i] = (pcm[i].toInt() and 0xff).toByte(); bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
        play(Earcons.RATE, bytes)
    }

    /**
     * Says [line]. [openRouter] is null without a key or a speech model: then the local engine speaks. Cancelling the
     * coroutine (barge-in, Stop) cuts the line at once. [quick] (a stock line already made, like "On it.") plays at
     * once when the line itself has not started after [QUICK_MS]; the line follows it without a gap.
     */
    suspend fun say(line: String, openRouter: OpenRouterVoice?, model: String?, voice: String?, language: String, quick: String? = null): Spoken {
        val started = SystemClock.elapsedRealtime()
        requestFocus()
        try {
            val cacheKey = key(model, voice, line)
            if (line in CACHEABLE) synchronized(cache) { cache[cacheKey] }?.let { (rate, bytes) ->
                play(rate, bytes)
                return Spoken(SystemClock.elapsedRealtime() - started, "cache")
            }
            val quickAudio = quick?.let { q -> synchronized(cache) { cache[key(model, voice, q)] } }
            if (openRouter != null && model != null) {
                val streamed = stream(line, openRouter, model, voice, started, quickAudio)
                if (streamed != null) {
                    if (line in CACHEABLE) synchronized(cache) { cache[cacheKey] = streamed.second }
                    return Spoken(streamed.first, "openrouter")
                }
            }
            local.speak(line, language)
            return Spoken(SystemClock.elapsedRealtime() - started, "local")
        } finally {
            _level.value = 0f
            audio?.abandonAudioFocusRequest(focus)
        }
    }

    /**
     * Makes the stock lines ahead of time, in memory, so the first "Okay." or "On it." of a drive plays at once.
     * Network: call off the main thread. Nothing is written to disk.
     */
    suspend fun prewarm(openRouter: OpenRouterVoice, model: String, voice: String?) = withContext(Dispatchers.IO) {
        for (line in STOCK) {
            val cacheKey = key(model, voice, line)
            if (synchronized(cache) { cache.containsKey(cacheKey) }) continue
            val bytes = ByteArrayOutputStream()
            var rate = 24_000
            val made = runCatching {
                openRouter.speak(model, voice, line, onStart = { rate = it }, onPcm = { b, n -> if (bytes.size() < MAX_CACHED_BYTES) bytes.write(b, 0, n) })
            }.isSuccess
            if (made && bytes.size() > 0) synchronized(cache) { cache[cacheKey] = rate to bytes.toByteArray() }
        }
    }

    private fun key(model: String?, voice: String?, line: String) = "$model|$voice|$line"

    /** Stops whatever is playing now (the coroutine that said it is cancelled by the session). */
    fun stop() {
        call?.cancel()
        runCatching { track?.pause(); track?.flush() }
        local.stop()
        _level.value = 0f
    }

    fun release() {
        stop()
        local.release()
    }

    /**
     * Streams one line; null means "too slow or failed, speak it locally". Returns first-sound time and the audio.
     * With [quick], the line's first bytes are held while the stock line plays, then flushed: never two voices at once.
     */
    private suspend fun stream(line: String, voiceApi: OpenRouterVoice, model: String, voice: String?, started: Long,
                               quick: Pair<Int, ByteArray>? = null): Pair<Long, Pair<Int, ByteArray>>? = coroutineScope {
        val first = CompletableDeferred<Long>()
        val released = CompletableDeferred<Unit>().also { if (quick == null) it.complete(Unit) }
        val lock = Any()
        val held = ByteArrayOutputStream()
        val kept = ByteArrayOutputStream()
        var rate = 24_000
        var written = 0L
        var output: AudioTrack? = null
        fun write(bytes: ByteArray, n: Int) {
            val out = output ?: newTrack(rate).also { output = it; track = it; it.play() }
            out.write(bytes, 0, n)
        }
        val job = async(Dispatchers.IO) {
            try {
                voiceApi.speak(model, voice, line, onStart = { r -> rate = r }, onPcm = { bytes, n ->
                    if (!first.isCompleted) first.complete(SystemClock.elapsedRealtime() - started)
                    synchronized(lock) { if (!released.isCompleted) held.write(bytes, 0, n) else write(bytes, n) }
                    written += n
                    if (kept.size() < MAX_CACHED_BYTES) kept.write(bytes, 0, n)
                    _level.value = level(bytes, n)
                }, track = { call = it })
                // A short line can finish arriving while the stock line still plays: wait, then play what was held.
                released.await()
                synchronized(lock) { if (held.size() > 0) { held.toByteArray().let { write(it, it.size) }; held.reset() } }
                output?.let { drain(it, written / 2) }
                if (!first.isCompleted) first.complete(NO_SOUND)
                written > 0
            } catch (error: VoiceCallException) {
                // Failed before a sound: fall back at once rather than after the first-byte wait.
                if (!first.isCompleted) first.complete(NO_SOUND)
                false
            } finally {
                output?.let { runCatching { it.stop() }; it.release() }
                if (track === output) track = null
                call = null
            }
        }
        var firstSound: Long? = null
        if (quick != null) {
            val early = withTimeoutOrNull(QUICK_MS) { first.await() }
            if (early == null || early == NO_SOUND) {
                // The line is not here yet: say "On it." now, then hand over.
                firstSound = SystemClock.elapsedRealtime() - started
                play(quick.first, quick.second)
            }
            synchronized(lock) {
                released.complete(Unit)
                if (held.size() > 0) { held.toByteArray().let { write(it, it.size) }; held.reset() }
            }
        }
        // "On it." already filled the silence: the line itself may take a little longer before the local voice steps in.
        val budget = FIRST_BYTE_MS + if (firstSound != null) QUICK_GRACE_MS else 0L
        val lineSound = withTimeoutOrNull((budget - (SystemClock.elapsedRealtime() - started)).coerceAtLeast(1)) { first.await() }
        if (lineSound == null || lineSound == NO_SOUND) {
            // Too slow: the local voice says it now; the late stream is dropped.
            call?.cancel()
            job.cancel()
            return@coroutineScope null
        }
        if (!job.await()) return@coroutineScope null
        (firstSound ?: lineSound) to (rate to kept.toByteArray())
    }

    private suspend fun play(rate: Int, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out = newTrack(rate)
        track = out
        try {
            out.play()
            var offset = 0
            while (offset < bytes.size) {
                val n = out.write(bytes, offset, minOf(4_096, bytes.size - offset))
                if (n <= 0) break
                _level.value = level(bytes.copyOfRange(offset, offset + n), n)
                offset += n
            }
            drain(out, bytes.size / 2L)
        } finally {
            runCatching { out.stop() }
            out.release()
            if (track === out) track = null
        }
    }

    /** Waits until the track has played [frames] (or stopped), so the next step starts after the last word. */
    private suspend fun drain(out: AudioTrack, frames: Long) {
        val deadline = SystemClock.elapsedRealtime() + frames * 1000 / out.sampleRate + 600
        while (SystemClock.elapsedRealtime() < deadline && out.playState == AudioTrack.PLAYSTATE_PLAYING &&
            (out.playbackHeadPosition.toLong() and 0xffffffffL) < frames) delay(20)
    }

    private fun newTrack(rate: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5))
        .build()

    private fun requestFocus() { audio?.requestAudioFocus(focus) }

    private fun level(bytes: ByteArray, n: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < n) {
            val v = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toDouble()
            sum += v * v; count++; i += 2
        }
        return if (count == 0) 0f else (sqrt(sum / count) / 6_000.0).coerceIn(0.0, 1.0).toFloat()
    }

    companion object {
        const val FIRST_BYTE_MS = 1_200L
        /** How long a confirmation may take to start before the stock "On it." fills the silence. */
        const val QUICK_MS = 450L
        private const val QUICK_GRACE_MS = 1_000L
        private const val NO_SOUND = -1L
        private const val MAX_CACHED_BYTES = 24_000 * 2 * 3
        /** Made ahead of time for each voice, so they play at once. */
        val STOCK = listOf(VoiceCopy.DEFAULT_ACK, VoiceCopy.OKAY, VoiceCopy.DONE, VoiceMoments.SENDING, VoiceCopy.STILL_WORKING)
        val CACHEABLE = setOf(VoiceCopy.DEFAULT_ACK, VoiceCopy.OKAY, VoiceCopy.DONE, VoiceCopy.STILL_WORKING, VoiceCopy.STOPPING, VoiceCopy.STOPPED,
            VoiceCopy.NEEDS_SCREEN, VoiceCopy.UNCLEAR_AGAIN, VoiceMoments.SENDING, VoiceMoments.EDITING, VoiceMoments.NOT_SENT)
    }
}

/** Android's on-device voice: the fallback, and the voice when there is no key or no network. */
private class LocalTts(private val context: Context) {
    private var engine: TextToSpeech? = null
    private var ready = CompletableDeferred<Boolean>()

    fun prepare(language: String) {
        if (engine != null) return
        val ready = CompletableDeferred<Boolean>().also { this.ready = it }
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) engine?.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            ready.complete(status == TextToSpeech.SUCCESS)
        }
        locale(language)?.let { l -> engine?.language = l }
    }

    suspend fun speak(line: String, language: String) {
        prepare(language)
        val ok = withTimeoutOrNull(3_000) { ready.await() } ?: false
        val tts = engine ?: return
        if (!ok) return
        locale(language)?.let { tts.language = it }
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { continuation ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (utteranceId == id && continuation.isActive) continuation.resume(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (utteranceId == id && continuation.isActive) continuation.resume(Unit) }
                override fun onError(utteranceId: String?, errorCode: Int) { onError(utteranceId) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id && continuation.isActive) continuation.resume(Unit) }
            })
            continuation.invokeOnCancellation { tts.stop() }
            if (tts.speak(line, TextToSpeech.QUEUE_FLUSH, Bundle(), id) != TextToSpeech.SUCCESS && continuation.isActive) continuation.resume(Unit)
        }
    }

    fun stop() { engine?.stop() }

    fun release() {
        engine?.shutdown()
        engine = null
    }

    private fun locale(language: String): Locale? = OpenRouterVoice.languageCode(language)?.let { Locale.forLanguageTag(it) }
}
