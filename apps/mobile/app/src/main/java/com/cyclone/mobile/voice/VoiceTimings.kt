package com.cyclone.mobile.voice

/**
 * How fast Drive answers on this phone (plan 24 §7), kept in memory for Settings → Voice. [confirms] are the last
 * end-of-speech to first-sound times of a confirmation; the targets are p50 2.0 s and p90 3.0 s.
 */
data class VoiceTimings(
    val transcribeMs: Long? = null,
    val understandMs: Long? = null,
    val firstSoundMs: Long? = null,
    val engine: String? = null,
    val confirms: List<Long> = emptyList(),
) {
    fun record(firstSoundMs: Long, confirmMs: Long, engine: String): VoiceTimings =
        copy(firstSoundMs = firstSoundMs, engine = engine, confirms = (confirms + confirmMs.coerceAtLeast(0)).takeLast(KEEP))

    val p50: Long? get() = percentile(0.5)
    val p90: Long? get() = percentile(0.9)

    /** Nearest-rank percentile of [confirms]. */
    fun percentile(p: Double): Long? {
        if (confirms.isEmpty()) return null
        val sorted = confirms.sorted()
        val rank = kotlin.math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    companion object {
        const val KEEP = 20
        const val TARGET_P50_MS = 2_000L
        const val TARGET_P90_MS = 3_000L
        const val TARGET_FIRST_SOUND_MS = 1_200L
        const val TARGET_TRANSCRIBE_MS = 900L
        const val TARGET_UNDERSTAND_MS = 900L

        /** "0.8 s" for a timing. */
        fun seconds(ms: Long?): String = if (ms == null) "–" else String.format(java.util.Locale.ROOT, "%.1f s", ms / 1000.0)
    }
}
