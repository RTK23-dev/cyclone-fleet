package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.runtime.background.*
import com.cyclone.mobile.ui.overlay.GlassStepKind
import org.junit.Assert.*
import org.junit.Test

class CycloneTaskPresentationTest {
    @Test fun projectionRetainsExactIdentity() {
        val source = WorkspaceTaskUi("task", "default-foreground", "Instagram", "com.instagram.android", "Find creators", displayId = 0, workspaceId = "creator", workspaceGeneration = 19)
        assertSame(source, UiTask(source).source)
        assertEquals(19L, UiTask(source).source.workspaceGeneration)
    }

    @Test fun profileDetailRejectsOtherPlanesAndIncompleteIdentity() {
        val source = WorkspaceTaskUi("task", "default-foreground", "App", "com.app", "Goal", displayId = 0, workspaceId = "profile", workspaceGeneration = 4)
        assertTrue(UiTask(source).belongsToProfile("profile"))
        assertFalse(UiTask(source).belongsToProfile("other"))
        assertFalse(UiTask(source.copy(sessionId = "named-vd",
            displayId = 7)).belongsToProfile("profile"))
        assertFalse(UiTask(source.copy(workspaceGeneration = null)).belongsToProfile("profile"))
        assertFalse(UiTask(source.copy(workspaceId = null, workspaceGeneration = null)).belongsToProfile("profile"))
    }

    @Test fun consumerCopyDoesNotExposeExecutionPlane() {
        val source = WorkspaceTaskUi("task", "named-vd", "Instagram", "com.instagram.android", "Find creators", phase = TaskPhase.WORKING, displayId = 7, glassStepKind = GlassStepKind.FAST_PATH, message = "Fast Path · phone.click")
        assertEquals("Checking the current page", UiTask(source).subtitle)
        assertEquals("named-vd", UiTask(source).source.sessionId)
        assertEquals(7, UiTask(source).source.displayId)
    }

    @Test fun terminalPhasesAreNotActive() {
        TaskPhase.entries.forEach { phase ->
            val task = UiTask(WorkspaceTaskUi("t", app = "App", packageName = "com.app", goal = "Goal", phase = phase))
            assertEquals(phase !in setOf(TaskPhase.DONE, TaskPhase.STOPPED, TaskPhase.FAILED), task.active)
        }
    }

    @Test fun consumerVisualStateSeparatesWorkingActionNeededDoneAndFailed() {
        fun task(phase: TaskPhase) = WorkspaceTaskUi(
            taskId = "t",
            app = "App",
            packageName = "com.app",
            goal = "Goal",
            phase = phase,
        )

        assertEquals(CycloneTaskVisualState.WORKING, task(TaskPhase.STARTING).taskVisualState())
        assertEquals(CycloneTaskVisualState.WORKING, task(TaskPhase.WORKING).taskVisualState())
        assertEquals(CycloneTaskVisualState.ACTION_NEEDED, task(TaskPhase.PAUSED).taskVisualState())
        assertEquals(CycloneTaskVisualState.ACTION_NEEDED, task(TaskPhase.REVIEW).taskVisualState())
        assertEquals(CycloneTaskVisualState.ACTION_NEEDED, task(TaskPhase.HUMAN).taskVisualState())
        assertEquals(CycloneTaskVisualState.FAILED, task(TaskPhase.FAILED).taskVisualState())
        assertEquals(CycloneTaskVisualState.FAILED, task(TaskPhase.STOPPED).taskVisualState())
        assertEquals(CycloneTaskVisualState.DONE, task(TaskPhase.DONE).taskVisualState())
    }

    @Test fun explicitConfirmationAlwaysWinsAsActionNeeded() {
        val confirmation = WorkspaceConfirmation(
            token = "token",
            action = "phone.click",
            nodeId = "node",
            fingerprint = "fingerprint",
            kind = "send",
        )
        val task = WorkspaceTaskUi(
            taskId = "t",
            sessionId = "named-vd",
            displayId = 2,
            app = "Messages",
            packageName = "com.messages",
            goal = "Send the message",
            phase = TaskPhase.WORKING,
            confirmation = confirmation,
        )

        assertEquals(CycloneTaskVisualState.ACTION_NEEDED, task.taskVisualState())
        assertFalse(task.canTakeOverFromUi()) // No backend capability: fail closed.
        assertTrue(task.copy(interruption = com.cyclone.mobile.runtime.background.TaskInterruption(
            "CONFIRMATION", "Review", canTakeOver = true)).canTakeOverFromUi())
        assertFalse(task.canContinueAfterHumanFromUi())
    }

    @Test fun humanControlOnlyOffersContinueWhenTaskIsResumable() {
        val human = WorkspaceTaskUi(
            taskId = "t",
            sessionId = "named-vd",
            displayId = 2,
            app = "Salesforce",
            packageName = "com.salesforce",
            goal = "Sign in",
            phase = TaskPhase.HUMAN,
            resumable = true,
            interruption = com.cyclone.mobile.runtime.background.TaskInterruption(
                "HUMAN_CONTROL", "Finish your changes", canResumeAfterHuman = true),
        )

        assertFalse(human.canTakeOverFromUi())
        assertTrue(human.canContinueAfterHumanFromUi())
        assertFalse(human.copy(resumable = false).canContinueAfterHumanFromUi())
        assertFalse(human.copy(displayId = null).canContinueAfterHumanFromUi())
        assertFalse(human.copy(interruption = null).canContinueAfterHumanFromUi())
    }
}
