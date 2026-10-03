package com.cyclone.mobile.ui.overlay.tracefield

import android.view.WindowManager
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.ui.overlay.OverlayChromeState

/** What the field should be showing, derived only from task and chrome state. */
enum class TracePresence { NONE, RUNNING, GATE, HANDOFF, DONE, BACKGROUND }

object TraceFieldPolicy {
    fun presence(foreground: Boolean?, phase: TaskPhase?, chrome: OverlayChromeState): TracePresence {
        if (chrome == OverlayChromeState.GATE) return TracePresence.GATE
        if (chrome == OverlayChromeState.DONE) return TracePresence.DONE
        if (foreground == null || phase == null) return TracePresence.NONE
        if (foreground == false) {
            return if (phase == TaskPhase.STARTING || phase == TaskPhase.WORKING) TracePresence.BACKGROUND else TracePresence.NONE
        }
        return when (phase) {
            TaskPhase.STARTING, TaskPhase.WORKING -> TracePresence.RUNNING
            TaskPhase.REVIEW -> TracePresence.GATE
            TaskPhase.PAUSED, TaskPhase.HUMAN -> TracePresence.HANDOFF
            TaskPhase.DONE -> TracePresence.DONE
            TaskPhase.FAILED, TaskPhase.STOPPED -> TracePresence.NONE
        }
    }

    /** Events that move the choreographer from one presence to the next. */
    fun transitions(previous: TracePresence, next: TracePresence, wakeX: Float, wakeY: Float): List<TraceEvent> {
        if (previous == next) return emptyList()
        val events = mutableListOf<TraceEvent>()
        if (previous == TracePresence.BACKGROUND) events += TraceEvent.Background(false)
        when (next) {
            TracePresence.RUNNING -> events += when (previous) {
                TracePresence.GATE, TracePresence.HANDOFF -> TraceEvent.Resume
                else -> TraceEvent.Wake(wakeX, wakeY)
            }
            TracePresence.GATE -> events += TraceEvent.Gate
            TracePresence.HANDOFF -> events += TraceEvent.Handoff
            TracePresence.DONE -> events += TraceEvent.Done
            TracePresence.NONE -> events += TraceEvent.Stop
            TracePresence.BACKGROUND -> {
                if (previous != TracePresence.NONE) events += TraceEvent.Stop
                events += TraceEvent.Background(true)
            }
        }
        return events
    }

    /**
     * The field covers the whole display, so it must never be FLAG_SECURE: secure layers are
     * captured as black, which would blind every full-display screenshot. Capture exclusion is
     * handled by [TraceFieldCaptureGate] instead.
     */
    fun windowFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
}

/**
 * Screenshots must never contain the field. Capturers hold the gate; the window hides while any
 * hold is open and signals when a frame without the field has been composited.
 */
object TraceFieldCaptureGate {
    interface Surface {
        /** Idempotent: hide now and call [onHidden] once a frame without the field is on screen. */
        fun hide(onHidden: () -> Unit)
        fun restore()
    }

    private val lock = Any()
    private var holds = 0
    @Volatile var surface: Surface? = null

    /** Runs [capture] once the field is hidden; [capture] must call the release it receives exactly once. */
    fun hold(capture: (release: () -> Unit) -> Unit) {
        val target = surface
        synchronized(lock) { holds += 1 }
        var released = false
        val release = {
            val last = synchronized(lock) {
                if (released) return@synchronized false
                released = true
                holds = (holds - 1).coerceAtLeast(0)
                holds == 0
            }
            if (last) target?.restore()
        }
        if (target == null) {
            capture(release)
            return
        }
        target.hide { capture(release) }
    }

    fun openHolds(): Int = synchronized(lock) { holds }
}
