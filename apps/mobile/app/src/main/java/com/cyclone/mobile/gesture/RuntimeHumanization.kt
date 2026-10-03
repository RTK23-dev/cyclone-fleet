package com.cyclone.mobile.gesture

/** Bounded preference accepted from the typed phone-tool surface. */
enum class HumanizePreference {
    AUTO,
    OFF,
    LIGHT,
    NORMAL;

    companion object {
        /**
         * V0.3 contract: absence defaults to AUTO, but an explicit unknown value is invalid.
         * Callers that accept phone-tool input must reject the IllegalArgumentException before any
         * Android mutation is attempted rather than silently widening/coercing the request.
         */
        fun parse(raw: String?): HumanizePreference {
            if (raw == null) return AUTO
            return when (raw.trim().lowercase()) {
                "auto" -> AUTO
                "off" -> OFF
                "light" -> LIGHT
                "normal" -> NORMAL
                else -> throw IllegalArgumentException(
                    "Invalid humanize '$raw'; expected one of auto, off, light, normal",
                )
            }
        }

        fun parseOrNull(raw: String?): HumanizePreference? = runCatching { parse(raw) }.getOrNull()
    }
}

enum class RuntimeGestureKind {
    FALLBACK_TAP,
    COORDINATE_TAP,
    LONG_PRESS,
    SWIPE,
    SCROLL,
    GUIDED_TAP,
    GUIDED_LONG_PRESS,
    GUIDED_SWIPE,
    PRECISION,
}

/** Android remains authoritative for turning a bounded preference into a concrete core profile. */
object HumanGestureRuntimePolicy {
    fun resolve(preference: HumanizePreference, kind: RuntimeGestureKind): HumanizeProfile = when (preference) {
        HumanizePreference.OFF -> HumanizeProfile.OFF
        HumanizePreference.LIGHT -> HumanizeProfile.LIGHT
        HumanizePreference.NORMAL -> HumanizeProfile.NORMAL
        HumanizePreference.AUTO -> when (kind) {
            RuntimeGestureKind.FALLBACK_TAP,
            RuntimeGestureKind.COORDINATE_TAP,
            RuntimeGestureKind.LONG_PRESS,
            RuntimeGestureKind.GUIDED_TAP,
            RuntimeGestureKind.GUIDED_LONG_PRESS -> HumanizeProfile.LIGHT
            RuntimeGestureKind.SWIPE,
            RuntimeGestureKind.SCROLL,
            RuntimeGestureKind.GUIDED_SWIPE -> HumanizeProfile.NORMAL
            RuntimeGestureKind.PRECISION -> HumanizeProfile.OFF
        }
    }
}

/**
 * Cheap replay/debug seed derivation. A caller supplies a phone-local monotonic ordinal; external
 * callers never provide the final seed or RNG state. Same local identity + action geometry is stable.
 */
object HumanGestureSeed {
    fun derive(
        commandId: String?,
        action: String,
        localOrdinal: Long,
        coordinates: FloatArray = floatArrayOf(),
        durationMs: Long = 0L,
    ): Long {
        var state = 0x6A09E667F3BCC909L
        fun mix(value: Long) {
            var z = value + 0x9E3779B97F4A7C15UL.toLong()
            z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
            z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
            state = state xor (z xor (z ushr 31))
            state = java.lang.Long.rotateLeft(state, 17) * 0x9E3779B185EBCA87UL.toLong()
        }
        fun mixString(value: String) {
            value.forEach { mix(it.code.toLong()) }
        }
        mix(localOrdinal)
        mix(durationMs)
        mixString(action)
        commandId?.let(::mixString)
        coordinates.forEach { mix(it.toRawBits().toLong()) }
        return state
    }
}
