package com.cyclone.mobile.runtime.background
import org.junit.Assert.*
import org.junit.Test
class TaskNotificationProjectionTest {
    private val task = WorkspaceTaskUi("t", "s", "Reddit", "reddit", "goal", displayId = 7)
    @Test fun notificationsProjectTheSameStateAndCapabilities() {
        for (phase in TaskPhase.entries) {
            val state = TaskHarnessState.normalize(task, task.copy(phase = phase))
            assertEquals(TaskPresentationProjector.project(state).title, TaskNotificationProjection.title(state))
            val snapshot = TaskPresentationProjector.project(state)
            assertEquals(if (state.working) snapshot.currentMilestone else snapshot.supportingCopy,
                TaskNotificationProjection.body(state))
            assertEquals(state.interruption?.canResumeAfterHuman == true,
                TaskNotificationProjection.actions(state).any { it.first == "resume" })
            assertEquals(state.interruption?.canAutofill == true,
                TaskNotificationProjection.actions(state).any { it.first == "autofill" })
        }
    }
    @Test fun aWorkingMissionOffersTheOtherPlaneBeforeStop() {
        val working = task.copy(phase = TaskPhase.WORKING)
        assertEquals(listOf("to_background", "cancel"), TaskNotificationProjection.actions(working, "screen").map { it.first })
        assertEquals(listOf("to_screen", "cancel"), TaskNotificationProjection.actions(working, "background").map { it.first })
        assertEquals("unavailable background offers no move", listOf("cancel"),
            TaskNotificationProjection.actions(working, "screen", backgroundAvailable = false).map { it.first })
        assertEquals("tasks without planes are unchanged", listOf("cancel"), TaskNotificationProjection.actions(working).map { it.first })
        assertEquals("a waiting mission offers Start now", listOf("start_now", "cancel"),
            TaskNotificationProjection.actions(working, "screen", waiting = true).map { it.first })
    }
    @Test fun failedIsTerminalRatherThanActionNeeded() {
        val failed = TaskHarnessState.normalize(task, task.copy(phase = TaskPhase.FAILED, resumable = false))
        val snapshot = TaskPresentationProjector.project(failed)
        assertEquals(TaskConsumerState.FAILED, snapshot.state)
        assertEquals(snapshot.title, TaskNotificationProjection.title(failed))
        assertTrue(TaskNotificationProjection.actions(failed).isEmpty())
    }
    @Test fun arbitraryProviderOrSecretMessageNeverBecomesNotificationCopy() {
        val state = task.copy(message = "password=123456 sk-or-secret Tap x=421 Model tool call")
        assertEquals("Getting your task ready", state.subtitle)
        assertFalse(state.copy(phase = TaskPhase.WORKING).subtitle.contains("secret"))
        assertFalse(TaskNotificationProjection.body(state).contains("secret", ignoreCase = true))
    }
    @Test fun commandMatchingNeverTreatsMissingSessionAsWildcard() {
        assertFalse(WorkspaceTasks.matches(task, "t", null))
        assertFalse(WorkspaceTasks.matches(task, "t", "other"))
        assertTrue(WorkspaceTasks.matches(task, "t", "s"))
    }

    @Test fun notificationShowsCurrentStepAndOnlyUsesGroundedProgress() {
        val unplanned = task.copy(phase = TaskPhase.WORKING)
        assertNull(TaskNotificationProjection.progressPercent(unplanned))
        val planned = unplanned.copy(plannedMilestones = listOf("Opening Reddit", "Checking login"),
            plannedMilestoneIndex = 1)
        assertEquals(50, TaskNotificationProjection.progressPercent(planned))
        assertEquals("Checking login", TaskNotificationProjection.body(planned))
        assertEquals(listOf("cancel" to "Stop task"), TaskNotificationProjection.actions(planned))
        for (phase in listOf(TaskPhase.DONE, TaskPhase.STOPPED, TaskPhase.FAILED, TaskPhase.HUMAN)) {
            assertNull(TaskNotificationProjection.progressPercent(planned.copy(phase = phase)))
        }
    }
}
