package com.cyclone.mobile.ui.v32

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskCardSwipePolicyTest {
    private fun settle(offset: Float, clear: Boolean = true) = TaskCardSwipePolicy.settle(offset, 400f, 100f, clear)
    @Test fun shortDragsReturnToRest() {
        assertEquals(TaskCardSwipeTarget.CLOSED, settle(44f))
        assertEquals(TaskCardSwipeTarget.CLOSED, settle(-44f))
    }
    @Test fun partialSwipesRevealMatchingAction() {
        assertEquals(TaskCardSwipeTarget.OPEN_BUTTON, settle(100f))
        assertEquals(TaskCardSwipeTarget.CLEAR_BUTTON, settle(-100f))
    }
    @Test fun deliberateFullSwipesPerformAction() {
        assertEquals(TaskCardSwipeTarget.OPEN, settle(288f))
        assertEquals(TaskCardSwipeTarget.CLEAR, settle(-288f))
    }
    @Test fun activeTasksNeverClear() {
        assertEquals(TaskCardSwipeTarget.CLOSED, settle(-100f, false))
        assertEquals(TaskCardSwipeTarget.CLOSED, settle(-400f, false))
        assertEquals(TaskCardSwipeTarget.OPEN, settle(400f, false))
    }
    @Test fun narrowCardsStillRequireMoreThanOneActionWidth() {
        assertEquals(TaskCardSwipeTarget.CLEAR_BUTTON, TaskCardSwipePolicy.settle(-100f, 180f, 100f, true))
        assertEquals(TaskCardSwipeTarget.CLEAR, TaskCardSwipePolicy.settle(-150f, 180f, 100f, true))
    }
    @Test fun unmeasuredCardCannotTriggerAction() {
        assertEquals(TaskCardSwipeTarget.CLOSED, TaskCardSwipePolicy.settle(100f, 0f, 100f, true))
    }
    @Test fun activeCardsKeepPriorityEvenIfTheirIdWasPreviouslyCleared() {
        com.cyclone.mobile.runtime.background.TaskPhase.entries.forEach { phase ->
            val task = com.cyclone.mobile.runtime.background.WorkspaceTaskUi(
                "t", app = "App", packageName = "com.app", goal = "Goal", phase = phase,
            )
            assertEquals(UiTask(task).active, taskCardVisible(task, setOf("task:t")))
            assertEquals(phase != com.cyclone.mobile.runtime.background.TaskPhase.STOPPED, taskCardVisible(task, emptySet()))
        }
        assertEquals(false, taskCardVisible(null, emptySet()))
    }
}
