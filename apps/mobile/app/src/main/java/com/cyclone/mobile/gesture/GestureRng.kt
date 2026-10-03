package com.cyclone.mobile.gesture

/** Small deterministic RNG surface so planning can be replayed and tested without global randomness. */
interface GestureRng {
    /** Returns a value in [0, 1). */
    fun nextUnit(): Double

    fun nextSignedUnit(): Double = nextUnit() * 2.0 - 1.0
}

/**
 * SplitMix64. It is tiny, fast, platform-independent, and its algorithm is fully owned here so
 * seeded replay does not depend on java.util.Random implementation details.
 */
class SeededGestureRng(seed: Long) : GestureRng {
    private var state: Long = seed

    private fun nextLongValue(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    override fun nextUnit(): Double =
        (nextLongValue() ushr 11).toDouble() / DOUBLE_UNIT_DENOMINATOR

    private companion object {
        val GOLDEN_GAMMA: Long = 0x9E3779B97F4A7C15UL.toLong()
        val MIX_1: Long = 0xBF58476D1CE4E5B9UL.toLong()
        val MIX_2: Long = 0x94D049BB133111EBUL.toLong()
        const val DOUBLE_UNIT_DENOMINATOR: Double = 9007199254740992.0 // 2^53
    }
}
