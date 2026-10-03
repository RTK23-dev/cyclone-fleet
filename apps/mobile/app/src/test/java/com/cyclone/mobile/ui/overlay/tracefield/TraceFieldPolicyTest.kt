package com.cyclone.mobile.ui.overlay.tracefield

import android.view.WindowManager
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceFieldPolicyTest {
    @Test fun windowPassesTouchesThroughAndIsNeverSecure() {
        val flags = TraceFieldPolicy.windowFlags()
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals(
            "a full-screen secure layer would black out every display screenshot",
            0,
            flags and WindowManager.LayoutParams.FLAG_SECURE,
        )
    }

    @Test fun presenceFollowsForegroundTaskPhases() {
        val idle = OverlayChromeState.IDLE
        assertEquals(TracePresence.NONE, TraceFieldPolicy.presence(null, null, idle))
        assertEquals(TracePresence.RUNNING, TraceFieldPolicy.presence(true, TaskPhase.STARTING, idle))
        assertEquals(TracePresence.RUNNING, TraceFieldPolicy.presence(true, TaskPhase.WORKING, idle))
        assertEquals(TracePresence.GATE, TraceFieldPolicy.presence(true, TaskPhase.REVIEW, idle))
        assertEquals(TracePresence.HANDOFF, TraceFieldPolicy.presence(true, TaskPhase.HUMAN, idle))
        assertEquals(TracePresence.HANDOFF, TraceFieldPolicy.presence(true, TaskPhase.PAUSED, idle))
        assertEquals(TracePresence.DONE, TraceFieldPolicy.presence(true, TaskPhase.DONE, idle))
        assertEquals(TracePresence.NONE, TraceFieldPolicy.presence(true, TaskPhase.FAILED, idle))
        assertEquals(TracePresence.NONE, TraceFieldPolicy.presence(true, TaskPhase.STOPPED, idle))
    }

    @Test fun backgroundWorkIsEdgeOnlyAndChromeGateWins() {
        assertEquals(TracePresence.BACKGROUND, TraceFieldPolicy.presence(false, TaskPhase.WORKING, OverlayChromeState.IDLE))
        assertEquals(TracePresence.NONE, TraceFieldPolicy.presence(false, TaskPhase.DONE, OverlayChromeState.IDLE))
        assertEquals(TracePresence.GATE, TraceFieldPolicy.presence(true, TaskPhase.WORKING, OverlayChromeState.GATE))
        assertEquals(TracePresence.DONE, TraceFieldPolicy.presence(null, null, OverlayChromeState.DONE))
    }

    @Test fun transitionsWakeResumeAndFinish() {
        assertEquals(
            listOf(TraceEvent.Wake(1f, 2f)),
            TraceFieldPolicy.transitions(TracePresence.NONE, TracePresence.RUNNING, 1f, 2f),
        )
        assertEquals(listOf(TraceEvent.Gate), TraceFieldPolicy.transitions(TracePresence.RUNNING, TracePresence.GATE, 0f, 0f))
        assertEquals(listOf(TraceEvent.Resume), TraceFieldPolicy.transitions(TracePresence.GATE, TracePresence.RUNNING, 0f, 0f))
        assertEquals(listOf(TraceEvent.Resume), TraceFieldPolicy.transitions(TracePresence.HANDOFF, TracePresence.RUNNING, 0f, 0f))
        assertEquals(listOf(TraceEvent.Done), TraceFieldPolicy.transitions(TracePresence.RUNNING, TracePresence.DONE, 0f, 0f))
        assertEquals(listOf(TraceEvent.Stop), TraceFieldPolicy.transitions(TracePresence.RUNNING, TracePresence.NONE, 0f, 0f))
        assertTrue(TraceFieldPolicy.transitions(TracePresence.RUNNING, TracePresence.RUNNING, 0f, 0f).isEmpty())
    }

    @Test fun transitionsInAndOutOfBackground() {
        assertEquals(
            listOf(TraceEvent.Background(true)),
            TraceFieldPolicy.transitions(TracePresence.NONE, TracePresence.BACKGROUND, 0f, 0f),
        )
        assertEquals(
            listOf(TraceEvent.Stop, TraceEvent.Background(true)),
            TraceFieldPolicy.transitions(TracePresence.RUNNING, TracePresence.BACKGROUND, 0f, 0f),
        )
        assertEquals(
            listOf(TraceEvent.Background(false), TraceEvent.Stop),
            TraceFieldPolicy.transitions(TracePresence.BACKGROUND, TracePresence.NONE, 0f, 0f),
        )
    }
}

class TraceFieldCaptureGateTest {
    private class FakeSurface : TraceFieldCaptureGate.Surface {
        val pending = mutableListOf<() -> Unit>()
        var hidden = false
        var restores = 0
        override fun hide(onHidden: () -> Unit) { hidden = true; pending += onHidden }
        override fun restore() { restores += 1; hidden = false }
        fun composite() { pending.toList().also { pending.clear() }.forEach { it() } }
    }

    @Test fun captureWaitsForTheHiddenFrameAndRestoresOnce() {
        val surface = FakeSurface()
        TraceFieldCaptureGate.surface = surface
        try {
            var captured = false
            var release: (() -> Unit)? = null
            TraceFieldCaptureGate.hold { r -> captured = true; release = r }
            assertFalse("capture must not run before the field is off screen", captured)
            assertTrue(surface.hidden)
            surface.composite()
            assertTrue(captured)
            release!!()
            release!!()
            assertEquals("double release restores once", 1, surface.restores)
            assertEquals(0, TraceFieldCaptureGate.openHolds())
        } finally {
            TraceFieldCaptureGate.surface = null
        }
    }

    @Test fun overlappingCapturesRestoreOnlyAfterTheLast() {
        val surface = FakeSurface()
        TraceFieldCaptureGate.surface = surface
        try {
            val releases = mutableListOf<() -> Unit>()
            TraceFieldCaptureGate.hold { releases += it }
            TraceFieldCaptureGate.hold { releases += it }
            surface.composite()
            assertEquals(2, releases.size)
            releases[0]()
            assertEquals(0, surface.restores)
            releases[1]()
            assertEquals(1, surface.restores)
        } finally {
            TraceFieldCaptureGate.surface = null
        }
    }

    @Test fun withoutAFieldCaptureRunsImmediately() {
        TraceFieldCaptureGate.surface = null
        var captured = false
        TraceFieldCaptureGate.hold { release -> captured = true; release() }
        assertTrue(captured)
        assertEquals(0, TraceFieldCaptureGate.openHolds())
    }
}
