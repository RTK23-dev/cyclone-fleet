package com.cyclone.mobile.runtime.background

import com.cyclone.mobile.policy.GateClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V5NeedsSecretContractTest {
    private fun task(phase: TaskPhase, interruption: TaskInterruption?) = WorkspaceTaskUi(
        taskId = "run-1",
        sessionId = "session-1",
        app = "Example",
        packageName = "com.example.app",
        goal = "Continue the user's original mission",
        phase = phase,
        interruption = interruption,
        displayId = 7,
    )

    @Test
    fun secretInterruptionProjectsAsNeedsSecretNotFailure() {
        val normalized = TaskHarnessState.normalize(
            task(TaskPhase.WORKING, null),
            task(TaskPhase.REVIEW, TaskInterruption.needsSecret()),
        )
        val snapshot = TaskPresentationProjector.project(normalized)

        assertEquals(TaskConsumerState.NEEDS_SECRET, snapshot.state)
        assertEquals("needs-secret", snapshot.state.wireValue)
        assertFalse(snapshot.state == TaskConsumerState.FAILED)
        assertTrue(snapshot.followUps.isEmpty())
        assertEquals(TaskInterruptionKind.NEEDS_SECRET, normalized.interruption?.kind)
    }

    @Test
    fun leavingHumanBoundaryClearsNeedsSecretInterruption() {
        val blocked = task(TaskPhase.REVIEW, TaskInterruption.needsSecret())
        val resumed = TaskHarnessState.normalize(blocked, blocked.copy(phase = TaskPhase.WORKING))
        assertEquals(null, resumed.interruption)
        assertEquals(TaskConsumerState.WORKING, TaskPresentationProjector.project(resumed).state)
    }

    @Test
    fun needsSecretDoesNotBecomeAPolicyGateClass() {
        assertEquals(setOf("pay", "send", "delete", "grant"), GateClass.entries.map { it.jsonKey }.toSet())
    }
}
