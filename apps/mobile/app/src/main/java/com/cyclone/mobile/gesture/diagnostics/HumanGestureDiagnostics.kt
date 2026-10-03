package com.cyclone.mobile.gesture.diagnostics

import com.cyclone.mobile.gesture.GestureBounds
import com.cyclone.mobile.gesture.HumanizePreference
import com.cyclone.mobile.gesture.HumanizeProfile
import com.cyclone.mobile.gesture.StrokePlan
import com.cyclone.mobile.gesture.TapPlan
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/**
 * Bounded deterministic evidence for one synthesized gesture plan.
 *
 * This type deliberately contains no session identity, page content, selectors, screenshots,
 * typed text, request IDs, or arbitrary metadata. Higher layers may attach it only after their
 * existing authorization and execution policy has run.
 */
data class HumanGesturePlanDiagnostics(
    val controlVersion: String,
    val traceVersion: String,
    val hashVersion: String,
    val engineName: String,
    val engineVersion: String,
    val gestureType: HumanGestureDiagnosticGestureType,
    val resolvedProfile: HumanizeProfile,
    val traceHashSha256: String,
) {
    init {
        require(controlVersion == HumanGestureDiagnostics.CONTROL_VERSION) {
            "Unsupported Human Gesture control version"
        }
        require(traceVersion == HumanGestureDiagnostics.TRACE_VERSION) {
            "Unsupported Human Gesture trace version"
        }
        require(hashVersion == HumanGestureDiagnostics.HASH_VERSION) {
            "Unsupported Human Gesture hash version"
        }
        require(engineName == HumanGestureTraceAdapter.ENGINE_NAME) {
            "Unsupported Human Gesture engine name"
        }
        require(engineVersion == HumanGestureTraceAdapter.ENGINE_VERSION) {
            "Unsupported Human Gesture engine version"
        }
        require(traceHashSha256.length == 64 && traceHashSha256.all { it in '0'..'9' || it in 'a'..'f' }) {
            "traceHashSha256 must be a lowercase SHA-256 hex digest"
        }
    }
}

enum class HumanGestureDiagnosticGestureType(val wireName: String) {
    TAP("tap"),
    SWIPE("swipe"),
}

/** Runtime-owned execution classification. The core defines the bounded vocabulary only. */
enum class HumanGestureExecutionMode {
    SEMANTIC_NATIVE,
    SYNTHESIZED_TOUCH,
    COMPATIBILITY_TOUCH,
    NOT_EXECUTED,
}

/** Bounded backend capability vocabulary; this is not a session/display model. */
enum class HumanGestureExecutionBackend {
    ACCESSIBILITY_SEMANTIC,
    ACCESSIBILITY_GESTURE,
    ENDPOINT_ONLY,
    UNKNOWN_BOUNDED,
}

/** Bounded reasons a higher layer may attach when requested and executed behavior differ. */
enum class HumanGestureDowngradeReason {
    SEMANTIC_PREFERRED,
    BACKEND_LIMITATION,
    PROFILE_UNSUPPORTED,
    DEGENERATE_GEOMETRY,
    OTHER_BOUNDED,
}

/**
 * Small projection shape Agent 2 can populate after actual execution.
 *
 * Runtime metadata remains runtime-owned. This class does not authorize, route, or execute input.
 */
data class HumanGestureExecutionDiagnostics(
    val requestedPreference: HumanizePreference?,
    val resolvedProfile: HumanizeProfile?,
    val executionMode: HumanGestureExecutionMode,
    val backend: HumanGestureExecutionBackend,
    val plan: HumanGesturePlanDiagnostics? = null,
    val synthesisCpuNanos: Long? = null,
    val downgradeReason: HumanGestureDowngradeReason? = null,
) {
    init {
        require(synthesisCpuNanos == null || synthesisCpuNanos >= 0L) {
            "synthesisCpuNanos must be non-negative when present"
        }
        if (plan != null && resolvedProfile != null) {
            require(plan.resolvedProfile == resolvedProfile) {
                "Execution resolvedProfile must match plan diagnostics"
            }
        }
    }
}

/**
 * Authoritative core diagnostic facade.
 *
 * The canonical plan hash uses a fixed swipe sampling resolution so callers cannot accidentally
 * make replay evidence depend on a debug/UI sampling choice.
 */
object HumanGestureDiagnostics {
    const val CONTROL_VERSION: String = "cyclone.human_gesture.control.v1"
    const val TRACE_VERSION: String = HumanGestureTraceAdapter.SCHEMA
    const val HASH_VERSION: String = HumanGestureTraceHasher.HASH_VERSION
    const val CANONICAL_SWIPE_SEGMENTS: Int = 24

    fun forSwipe(plan: StrokePlan, viewport: GestureBounds): HumanGesturePlanDiagnostics {
        val trace = HumanGestureTraceAdapter.fromSwipe(
            plan = plan,
            viewport = viewport,
            seed = null,
            segments = CANONICAL_SWIPE_SEGMENTS,
        )
        return fromTrace(trace, HumanGestureDiagnosticGestureType.SWIPE)
    }

    fun forTap(plan: TapPlan, viewport: GestureBounds, target: GestureBounds): HumanGesturePlanDiagnostics {
        val trace = HumanGestureTraceAdapter.fromTap(
            plan = plan,
            viewport = viewport,
            target = target,
            seed = null,
        )
        return fromTrace(trace, HumanGestureDiagnosticGestureType.TAP)
    }

    fun fromTrace(trace: NormalizedGestureTrace): HumanGesturePlanDiagnostics {
        require(trace.schema == TRACE_VERSION) { "Unsupported production trace schema: ${trace.schema}" }
        require(trace.engineName == HumanGestureTraceAdapter.ENGINE_NAME) {
            "Unsupported production engine name: ${trace.engineName}"
        }
        require(trace.engineVersion == HumanGestureTraceAdapter.ENGINE_VERSION) {
            "Unsupported production engine version: ${trace.engineVersion}"
        }
        val type = when (trace.gestureType) {
            "tap" -> HumanGestureDiagnosticGestureType.TAP
            "swipe" -> HumanGestureDiagnosticGestureType.SWIPE
            else -> throw IllegalArgumentException("Unsupported production gesture type: ${trace.gestureType}")
        }
        return fromTrace(trace, type)
    }

    private fun fromTrace(
        trace: NormalizedGestureTrace,
        type: HumanGestureDiagnosticGestureType,
    ): HumanGesturePlanDiagnostics = HumanGesturePlanDiagnostics(
        controlVersion = CONTROL_VERSION,
        traceVersion = trace.schema,
        hashVersion = HASH_VERSION,
        engineName = trace.engineName,
        engineVersion = trace.engineVersion,
        gestureType = type,
        resolvedProfile = trace.profile,
        traceHashSha256 = HumanGestureTraceHasher.sha256Hex(trace),
    )
}

/**
 * Canonical SHA-256 for normalized production traces.
 *
 * Canonical bytes are versioned binary data, not JSON. Every scalar is encoded in big-endian order.
 * Strings are UTF-8 prefixed by a signed 32-bit byte length. Doubles use exact IEEE-754 raw bits.
 * Longs use signed 64-bit representation. Lists use a signed 32-bit element count.
 *
 * Included fields: hash version, trace schema, engine name/version, gesture type, resolved profile,
 * viewport width/height, duration, optional target bounds, and normalized (u,v,t) points.
 *
 * Deliberately excluded: seed and source. Those are replay/provenance metadata, not executed motion;
 * changing either without changing the normalized motion must not change the trace hash.
 */
object HumanGestureTraceHasher {
    const val HASH_VERSION: String = "cyclone.human_gesture.trace_hash.v1"

    fun sha256Hex(trace: NormalizedGestureTrace): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalBytes(trace))
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = HEX[value ushr 4]
            hex[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(hex)
    }

    /** Exposed for deterministic cross-language fixtures; ordinary runtime responses need only hash. */
    fun canonicalBytes(trace: NormalizedGestureTrace): ByteArray {
        val bytes = ByteArrayOutputStream(512)
        DataOutputStream(bytes).use { out ->
            out.writeCanonicalString(HASH_VERSION)
            out.writeCanonicalString(trace.schema)
            out.writeCanonicalString(trace.engineName)
            out.writeCanonicalString(trace.engineVersion)
            out.writeCanonicalString(trace.gestureType)
            out.writeCanonicalString(trace.profile.name)
            out.writeCanonicalDouble(trace.viewport.widthPx)
            out.writeCanonicalDouble(trace.viewport.heightPx)
            out.writeLong(trace.durationMs)
            val target = trace.target
            out.writeBoolean(target != null)
            if (target != null) {
                out.writeCanonicalDouble(target.left)
                out.writeCanonicalDouble(target.top)
                out.writeCanonicalDouble(target.right)
                out.writeCanonicalDouble(target.bottom)
            }
            out.writeInt(trace.points.size)
            trace.points.forEach { point ->
                out.writeCanonicalDouble(point.u)
                out.writeCanonicalDouble(point.v)
                out.writeCanonicalDouble(point.t)
            }
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeCanonicalString(value: String) {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        writeInt(utf8.size)
        write(utf8)
    }

    private fun DataOutputStream.writeCanonicalDouble(value: Double) {
        require(value.isFinite()) { "Canonical trace values must be finite" }
        writeLong(java.lang.Double.doubleToRawLongBits(value))
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
