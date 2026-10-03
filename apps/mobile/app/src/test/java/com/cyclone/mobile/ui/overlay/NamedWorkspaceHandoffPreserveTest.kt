package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.runtime.session.NamedWorkspaceControlPolicy
import com.cyclone.mobile.runtime.session.SessionKernel
import com.cyclone.mobile.runtime.session.SessionPlaneKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Named-VD Fast Path / skills must not bypass GATE, Take control, or Continue.
 * Legacy overlay controls remain stable; progress-page labels follow the 4.3.5 consumer UI.
 */
class NamedWorkspaceHandoffPreserveTest {
    @Test
    fun workspaceCtasPreserveExactCommandsWithConsumerLabels() {
        assertEquals("Take control", OverlayCopy.LIVE_RIGHT)
        assertEquals("Resume", OverlayCopy.RESUME)
        assertEquals("Cyclone needs you to confirm before finishing this.", OverlayCopy.GATE)
        assertEquals("Stop task", OverlayCopy.LIVE_LEFT)
        assertEquals(OverlayCopy.LIVE_RIGHT, NamedWorkspaceControlPolicy.TAKE_CONTROL)
        assertEquals(OverlayCopy.RESUME, NamedWorkspaceControlPolicy.RESUME)
        assertEquals(OverlayCopy.GATE, NamedWorkspaceControlPolicy.GATE_COPY)
        assertEquals("Continue with Cyclone", NamedWorkspaceControlPolicy.CONTINUE)

        val progress = workspaceProgressSource()
        assertTrue(progress.contains("\"Take Over\""))
        assertTrue(progress.contains("\"I'm Done\""))
        assertTrue(progress.contains("WorkspaceTasks.command(this@WorkspaceProgressActivity, task, \"handoff\")"))
        assertTrue(progress.contains("WorkspaceTasks.command(this@WorkspaceProgressActivity, task, \"autofill\")"))
        assertTrue(progress.contains("TaskFollowUpAction.AUTOFILL in snapshot.followUps"))
        assertTrue(progress.contains("enabled = TaskFollowUpAction.TAKE_OVER in snapshot.followUps"))
        assertTrue(progress.contains("enabled = TaskFollowUpAction.CONTINUE in snapshot.followUps"))
        assertEquals(1, SessionKernel.PRODUCT_HOT_BACKGROUND_LIMIT)
        assertEquals(3, SessionPlaneKind.entries.size)
    }

    @Test
    fun takeControlOnNamedVdPausesThenSecondTakeControlResumesWithoutHostClick() {
        val events = mutableListOf<OverlayChromeEvent>()
        val effects = RecordingEffects()
        val machine = OverlayChromeMachine(emit = { events += it }, cycloneState = effects)

        machine.enterWorking("named-vd")
        machine.enterLive()
        assertEquals("named-vd", machine.snapshot().sessionId)
        assertEquals(OverlayChromeState.LIVE, machine.state())
        assertFalse(machine.snapshot().userPaused)
        events.clear()
        effects.pauses = 0
        effects.resumes = 0

        machine.dispatch(OverlayUserAction.TAKE_CONTROL)
        assertEquals(OverlayChromeState.LIVE, machine.state())
        assertTrue(machine.snapshot().userPaused)
        assertEquals("named-vd", machine.snapshot().sessionId)
        assertEquals(1, effects.pauses)
        assertEquals(0, effects.resumes)
        val paused = events.single()
        assertEquals(OverlayChromeEventKind.TAKE_CONTROL, paused.kind)
        assertFalse(paused.clicksHost)
        assertFalse(paused.dispatchAccessibilityAction)
        assertTrue(paused.userPaused)

        machine.dispatch(OverlayUserAction.TAKE_CONTROL)
        assertEquals(OverlayChromeState.LIVE, machine.state())
        assertFalse(machine.snapshot().userPaused)
        assertEquals("named-vd", machine.snapshot().sessionId)
        assertEquals(1, effects.resumes)
        val resumed = events.last()
        assertEquals(OverlayChromeEventKind.TAKE_CONTROL, resumed.kind)
        assertFalse(resumed.clicksHost)
        assertFalse(resumed.dispatchAccessibilityAction)
        assertFalse(resumed.userPaused)
        assertEquals(OverlayCopy.RESUME, NamedWorkspaceControlPolicy.RESUME)
        assertEquals(NamedWorkspaceControlPolicy.CONTINUE, "Continue with Cyclone")
    }

    @Test
    fun pcAutoApproveStillGatesNamedVdAndTakeControlDoesNotDismissGate() {
        listOf(OverlayGateClass.PAY, OverlayGateClass.SEND).forEach { gateClass ->
            val events = mutableListOf<OverlayChromeEvent>()
            val effects = RecordingEffects()
            val machine = OverlayChromeMachine(emit = { events += it }, cycloneState = effects)
            machine.enterWorking("named-vd")
            machine.enterGate(gateClass, pcAutoApprove = true)

            assertEquals(OverlayChromeState.GATE, machine.state())
            assertEquals("named-vd", machine.snapshot().sessionId)
            assertEquals(gateClass, machine.snapshot().gateClass)
            assertTrue(machine.snapshot().pcAutoApproveIgnored)
            assertTrue(machine.snapshot().userPaused)
            assertTrue(OverlayCopy.visibleFor(machine.snapshot()).contains(OverlayCopy.GATE))
            val gateEvent = events.single { it.kind == OverlayChromeEventKind.GATE }
            assertTrue(gateEvent.pcAutoApproveIgnored)
            assertFalse(gateEvent.clicksHost)
            assertFalse(gateEvent.dispatchAccessibilityAction)

            machine.dispatch(OverlayUserAction.TAKE_CONTROL)
            assertEquals(OverlayChromeState.GATE, machine.state())
            assertEquals(gateClass, machine.snapshot().gateClass)
            assertTrue(machine.snapshot().userPaused)
            assertTrue(events.none { it.kind == OverlayChromeEventKind.GATE_CONFIRM })

            machine.dispatch(OverlayUserAction.GATE_CONFIRM)
            assertEquals(OverlayChromeState.DONE, machine.state())
            assertTrue(events.any { it.kind == OverlayChromeEventKind.GATE_CONFIRM })
            assertTrue(events.none { it.clicksHost || it.dispatchAccessibilityAction })
        }
    }

    private fun workspaceProgressSource(): String {
        val relative = "src/main/java/com/cyclone/mobile/runtime/background/WorkspaceProgressActivity.kt"
        val candidates = listOf(
            File(relative),
            File("app/$relative"),
            File("apps/mobile/app/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(System.getProperty("user.dir"), "app/$relative"),
            File(System.getProperty("user.dir"), "apps/mobile/app/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate WorkspaceProgressActivity.kt from ${System.getProperty("user.dir")}")
        return file.readText()
    }

    private class RecordingEffects : OverlayCycloneStateEffects {
        var pauses = 0
        var resumes = 0
        override fun pauseAgentForUser() {
            pauses += 1
        }
        override fun resumeAgent() {
            resumes += 1
        }
    }
}
