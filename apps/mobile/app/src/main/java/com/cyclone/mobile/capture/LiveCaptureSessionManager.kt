package com.cyclone.mobile.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScreenSharePhase { OFF, REQUESTING_PERMISSION, STARTING, LIVE, STOPPING, ERROR, REVOKED }
enum class CaptureScope { USER_CHOICE, WHOLE_DISPLAY }
data class ScreenShareState(
    val phase: ScreenSharePhase = ScreenSharePhase.OFF,
    val generation: Long = 0,
    val scope: CaptureScope = CaptureScope.USER_CHOICE,
    val message: String? = null,
) {
    val active: Boolean get() = phase in setOf(ScreenSharePhase.REQUESTING_PERMISSION,
        ScreenSharePhase.STARTING, ScreenSharePhase.LIVE, ScreenSharePhase.STOPPING)
}

/** Process-local lifecycle; old consent and service callbacks cannot revive a stopped session. */
object LiveCaptureSessionManager {
    private val mutable = MutableStateFlow(ScreenShareState())
    val state = mutable.asStateFlow()
    private var serviceGeneration: Long? = null
    @Synchronized fun claimService(generation: Long): Boolean {
        if (serviceGeneration != null || mutable.value.generation != generation ||
            mutable.value.phase != ScreenSharePhase.STARTING) return false
        serviceGeneration = generation
        return true
    }
    @Synchronized fun releaseService(generation: Long) {
        if (serviceGeneration == generation) serviceGeneration = null
    }
    @Synchronized fun request(scope: CaptureScope): Long? {
        if (mutable.value.active || serviceGeneration != null) return null
        val generation = mutable.value.generation + 1
        mutable.value = ScreenShareState(ScreenSharePhase.REQUESTING_PERMISSION, generation, scope)
        return generation
    }
    @Synchronized fun transition(generation: Long, phase: ScreenSharePhase, message: String? = null): Boolean {
        val old = mutable.value
        if (generation != old.generation) return false
        val allowed = when (phase) {
            ScreenSharePhase.STARTING -> old.phase == ScreenSharePhase.REQUESTING_PERMISSION || old.phase == ScreenSharePhase.LIVE || old.phase == ScreenSharePhase.STARTING
            ScreenSharePhase.LIVE -> old.phase == ScreenSharePhase.STARTING || old.phase == ScreenSharePhase.LIVE
            ScreenSharePhase.STOPPING -> old.active && old.phase != ScreenSharePhase.STOPPING
            ScreenSharePhase.OFF -> old.active
            ScreenSharePhase.ERROR, ScreenSharePhase.REVOKED -> old.active && old.phase != ScreenSharePhase.STOPPING
            ScreenSharePhase.REQUESTING_PERMISSION -> false
        }
        if (!allowed) return false
        mutable.value = old.copy(phase = phase, message = message)
        return true
    }
}

/** Cheap decision BEFORE copying RGBA pixels. Stable screens retain a fresh heartbeat. */
class CaptureFrameSampler {
    private var lastFrameAt = -1L
    private var burstUntil = -1L
    private var stable = false
    @Synchronized fun requestBurst(nowMs: Long) { burstUntil = nowMs + 1_000 }
    @Synchronized fun admit(nowMs: Long): Boolean {
        val interval = when { nowMs <= burstUntil -> 100; stable -> 500; else -> 300 }
        if (lastFrameAt >= 0 && nowMs - lastFrameAt < interval) return false
        lastFrameAt = nowMs
        return true
    }
    @Synchronized fun contentChanged(changed: Boolean) { stable = !changed }
}
