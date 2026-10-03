package com.cyclone.mobile.voice

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Drive's sounds (plan 24 §1: an earcon the moment listening starts). Made in memory from soft sine notes, so they
 * play instantly with no file and no network. Pure: the sample data is tested; SpeechOut plays it.
 */
object Earcons {
    const val RATE = 24_000

    private data class Note(val hz: Double, val startMs: Int, val lengthMs: Int, val gain: Double = 1.0)

    private fun notes(earcon: Earcon): List<Note> = when (earcon) {
        // Two rising notes: "I'm listening".
        Earcon.LISTEN -> listOf(Note(784.0, 0, 90), Note(1175.0, 70, 130))
        // One falling breath: "never mind".
        Earcon.CLOSE_SOFT -> listOf(Note(880.0, 0, 70, 0.6), Note(587.0, 60, 120, 0.5))
        // A bright little arpeggio: done.
        Earcon.DONE -> listOf(Note(659.0, 0, 110), Note(831.0, 80, 110), Note(988.0, 160, 220))
        // Two low notes: it didn't work.
        Earcon.FAILED -> listOf(Note(392.0, 0, 140, 0.8), Note(330.0, 130, 220, 0.8))
        // A warm triple knock: Cyclone needs you.
        Earcon.NEEDS_YOU -> listOf(Note(698.0, 0, 90), Note(698.0, 130, 90), Note(932.0, 260, 200))
    }

    /** 16-bit mono PCM at [RATE]. Quiet enough to sit under navigation audio, loud enough to hear in a car. */
    fun pcm(earcon: Earcon, volume: Double = 0.28): ShortArray {
        val notes = notes(earcon)
        val totalMs = notes.maxOf { it.startMs + it.lengthMs } + 20
        val out = DoubleArray(RATE * totalMs / 1000)
        for (note in notes) {
            val start = RATE * note.startMs / 1000
            val length = RATE * note.lengthMs / 1000
            for (i in 0 until length) {
                val t = i.toDouble() / RATE
                // 6 ms attack, exponential release: no clicks.
                val attack = (i / (RATE * 0.006)).coerceAtMost(1.0)
                val release = exp(-3.2 * i / length)
                val tone = sin(2 * PI * note.hz * t) + 0.18 * sin(4 * PI * note.hz * t)
                if (start + i < out.size) out[start + i] += tone * attack * release * note.gain
            }
        }
        return ShortArray(out.size) { (out[it] * volume * 32767 / 1.18).coerceIn(-32767.0, 32767.0).toInt().toShort() }
    }

    fun lengthMs(earcon: Earcon): Int = pcm(earcon).size * 1000 / RATE
}
