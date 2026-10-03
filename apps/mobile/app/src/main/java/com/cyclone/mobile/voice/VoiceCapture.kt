package com.cyclone.mobile.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The microphone for one request (plan 24 §5.3): 16 kHz mono from the `VOICE_RECOGNITION` source, with echo and
 * noise suppression when the phone has them, fed to [VoiceActivity] until the owner stops talking.
 *
 * The clip stays in this object's memory: it is handed to the transcriber as WAV bytes and then dropped. Nothing is
 * written anywhere.
 */
class VoiceCapture(private val context: Context) {
    private val carMic = CarMic(context)

    sealed interface Outcome {
        /** Speech, trimmed to the part worth sending. */
        class Clip(val samples: ShortArray, val voicedMs: Int) : Outcome
        data object NothingHeard : Outcome
        data class Failed(val failure: VoiceFailure) : Outcome
    }

    @Volatile private var finishEarly = false

    /** The owner tapped while talking: end now and keep what was said. */
    fun finish() { finishEarly = true }

    /**
     * Brings up a connected car kit or headset as the microphone; started together with the listen sound so the link
     * is ready when the owner speaks. Null: the phone's microphone.
     */
    suspend fun routeToCar(): AudioDeviceInfo? = withContext(Dispatchers.IO) { carMic.acquire() }

    /** Hands the audio route back (music and navigation return). Safe to call more than once. */
    fun releaseCar() = carMic.release()

    /**
     * Records until the detector decides; [onLevel] gets the voice level (0..1) about 50 times a second. [car] is the
     * route from [routeToCar], released when the recording ends.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(tuning: VoiceActivity.Tuning, car: AudioDeviceInfo? = null, onSpeech: () -> Unit = {}, onLevel: (Float) -> Unit): Outcome =
        withContext<Outcome>(Dispatchers.IO) {
            try {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@withContext Outcome.Failed(VoiceFailure.NO_MIC)
                recordFrom(car, tuning, onSpeech, onLevel)
            } finally {
                carMic.release()
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun recordFrom(car: AudioDeviceInfo?, tuning: VoiceActivity.Tuning, onSpeech: () -> Unit, onLevel: (Float) -> Unit): Outcome {
        finishEarly = false
        val rate = VoiceActivity.SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return Outcome.Failed(VoiceFailure.MIC_BUSY)
        // A car kit is heard through the communication path, which also brings the platform's echo cancelling.
        val source = if (car != null) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val record = runCatching {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate / 5 * 2))
                .also { r -> car?.let { r.setPreferredDevice(it) } }
        }.getOrNull() ?: return Outcome.Failed(VoiceFailure.MIC_BUSY)
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return Outcome.Failed(VoiceFailure.MIC_BUSY) }
        val echo = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val noise = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val detector = VoiceActivity(tuning)
        // A background app's microphone can be silenced by Android: exact zeros. Found within 0.6 s, not after 4.
        val silenced = MicSilence()
        val all = ShortArray(rate * tuning.maxClipMs / 1000 + rate)
        var count = 0
        val chunk = ShortArray(rate / 50)
        var spoke = false
        return try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return Outcome.Failed(VoiceFailure.MIC_BUSY)
            while (true) {
                coroutineContext.ensureActive()
                val n = record.read(chunk, 0, chunk.size)
                if (n < 0) return Outcome.Failed(VoiceFailure.MIC_BUSY)
                if (n == 0) continue
                if (silenced.feed(chunk, n)) return Outcome.Failed(VoiceFailure.MIC_SILENCED)
                val room = minOf(n, all.size - count)
                System.arraycopy(chunk, 0, all, count, room)
                count += room
                when (val result = detector.feed(chunk, n)) {
                    is VoiceActivity.Result.Listening -> {
                        onLevel(result.level)
                        // Speech longer than a blip: never on a silent or blip-only open, which stays free.
                        if (!spoke && result.speechMs >= tuning.minSpeechMs) { spoke = true; onSpeech() }
                        if (finishEarly) return early(all, count, detector)
                    }
                    is VoiceActivity.Result.Heard ->
                        return Outcome.Clip(Wav.trim(all, count, result.startMs, result.endMs), result.voicedMs)
                    VoiceActivity.Result.NoSpeech, is VoiceActivity.Result.Blip -> return Outcome.NothingHeard
                }
            }
            @Suppress("UNREACHABLE_CODE") Outcome.NothingHeard
        } finally {
            runCatching { record.stop() }
            record.release()
            echo?.release()
            noise?.release()
            onLevel(0f)
        }
    }

    /** A tap ends the request: send what was said, if it was speech at all. */
    private fun early(all: ShortArray, count: Int, detector: VoiceActivity): Outcome {
        val final = detector.feed(ShortArray(VoiceActivity.SAMPLE_RATE * 2))
        return when (final) {
            is VoiceActivity.Result.Heard -> Outcome.Clip(Wav.trim(all, count, final.startMs, minOf(final.endMs, count * 1000 / VoiceActivity.SAMPLE_RATE)), final.voicedMs)
            else -> Outcome.NothingHeard
        }
    }
}
