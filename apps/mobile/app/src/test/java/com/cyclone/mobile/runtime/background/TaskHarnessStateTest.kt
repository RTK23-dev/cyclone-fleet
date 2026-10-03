package com.cyclone.mobile.runtime.background

import org.junit.Assert.*
import org.junit.Test

class TaskHarnessStateTest {
    private fun task() = TaskHarnessState.begin(WorkspaceTaskUi("task", "session", "Reddit", "reddit", "goal",
        phase = TaskPhase.WORKING, displayId = 7), "phone.open_app")
    private fun evidence() = TaskOperationEvidence("session", 7, 0, true, true, true, "EXPECTED_PACKAGE")
    @Test fun transportIsNotCompletion() {
        val result = TaskHarnessState.finish(task(), evidence().copy(verified = false))
        assertEquals(SemanticStepState.FAILED, result.semanticSteps.last().state)
        assertTrue(result.steps.isEmpty())
    }
    @Test fun doneRequiresFreshVerifiedEvidence() {
        assertEquals(SemanticStepState.DONE, TaskHarnessState.finish(task(), evidence()).semanticSteps.last().state)
        assertEquals(SemanticStepState.FAILED, TaskHarnessState.finish(task(), evidence().copy(freshObservation = false)).semanticSteps.last().state)
    }
    @Test fun wrongSessionDisplayOrRevisionCannotComplete() {
        for (proof in listOf(evidence().copy(sessionId = "other"), evidence().copy(displayId = 0), evidence().copy(controlRevision = 1), evidence().copy(workspaceId = "other")))
            assertEquals(task(), TaskHarnessState.finish(task(), proof))
    }
    @Test fun handoffInvalidatesInFlightEvidenceAndExposesTruthfulCapabilities() {
        val t = task()
        val human = TaskHarnessState.normalize(t, t.copy(phase = TaskPhase.HUMAN))
        assertEquals(1L, human.controlRevision)
        assertTrue(human.interruption!!.canResumeAfterHuman)
        assertFalse(human.interruption!!.canAutofill)
        val login = TaskHarnessState.normalize(
            t,
            t.copy(phase = TaskPhase.REVIEW, loginAutofill = true, sessionId = "session", displayId = 7),
        )
        assertTrue(login.interruption!!.canAutofill)
        assertTrue(login.interruption!!.canTakeOver)
        assertTrue(login.interruption!!.canResumeAfterHuman)
        val resumed = TaskHarnessState.begin(human.copy(phase = TaskPhase.WORKING), "phone.back")
        assertEquals(resumed, TaskHarnessState.finish(resumed, evidence()))
        assertFalse(TaskHarnessState.interruption(t.copy(phase = TaskPhase.REVIEW))!!.canResumeAfterHuman)
        assertFalse(TaskHarnessState.interruption(human.copy(resumable = false))!!.canResumeAfterHuman)
    }
    @Test fun terminalTaskCannotKeepClaimingAnActiveOperation() {
        val t = task()
        val terminal = TaskHarnessState.normalize(t, t.copy(phase = TaskPhase.FAILED))
        assertEquals(SemanticStepState.FAILED, terminal.semanticSteps.last().state)
        assertEquals("The task could not finish.", terminal.subtitle)
        assertTrue(terminal.steps.isEmpty())
    }
    @Test fun secretOrRawActionValuesAreNeverUsedAsLabels() {
        for (tool in listOf("phone.type", "password=abc123", "Tap x=421 y=782", "sk-or-secret")) {
            val label = TaskHarnessState.operationLabel(tool, "secret@example.com")
            assertFalse(label.contains("abc123")); assertFalse(label.contains("secret")); assertFalse(label.contains("421"))
        }
    }
}
