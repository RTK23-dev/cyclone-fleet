package com.cyclone.mobile.voice

import java.io.ByteArrayOutputStream

/**
 * A recorded clip as a WAV file in memory. Audio never touches the disk: the bytes go into one transcription request
 * and are dropped with the clip (plan 32, "No audio persists").
 */
object Wav {
    /** 16-bit little-endian mono PCM [samples] (the first [count]) at [sampleRate], as a complete WAV file. */
    fun encode(samples: ShortArray, count: Int = samples.size, sampleRate: Int = VoiceActivity.SAMPLE_RATE): ByteArray {
        require(count in 0..samples.size) { "count out of range" }
        val dataBytes = count * 2
        val out = ByteArrayOutputStream(44 + dataBytes)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.int(36 + dataBytes)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.int(16)
        out.short(1) // PCM
        out.short(1) // mono
        out.int(sampleRate)
        out.int(sampleRate * 2) // byte rate
        out.short(2) // block align
        out.short(16) // bits per sample
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.int(dataBytes)
        for (i in 0 until count) out.short(samples[i].toInt())
        return out.toByteArray()
    }

    /** The part of a clip worth sending: the speech with a little room around it, so silence costs nothing. */
    fun trim(samples: ShortArray, count: Int, startMs: Int, endMs: Int, padMs: Int = 250, sampleRate: Int = VoiceActivity.SAMPLE_RATE): ShortArray {
        val perMs = sampleRate / 1000
        val from = ((startMs - padMs) * perMs).coerceIn(0, count)
        val to = ((endMs + padMs) * perMs).coerceIn(from, count)
        return samples.copyOfRange(from, to)
    }

    /** 16-bit little-endian PCM bytes to samples (the first [count] bytes). */
    fun samples(bytes: ByteArray, count: Int = bytes.size): ShortArray =
        ShortArray(count / 2) { i -> ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xff)).toShort() }

    /** Linear resampling, e.g. a 24 kHz speech stream to the 16 kHz a transcriber gets from the microphone. */
    fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || input.isEmpty()) return input.copyOf()
        val n = (input.size.toLong() * to / from).toInt()
        return ShortArray(n) { i ->
            val pos = i.toDouble() * from / to
            val a = pos.toInt().coerceAtMost(input.lastIndex)
            val b = (a + 1).coerceAtMost(input.lastIndex)
            val f = pos - a
            (input[a] * (1 - f) + input[b] * f).toInt().toShort()
        }
    }

    private fun ByteArrayOutputStream.int(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff); write((v shr 16) and 0xff); write((v shr 24) and 0xff)
    }

    private fun ByteArrayOutputStream.short(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff)
    }
}
