package com.cyclone.mobile.runtime.background

import org.junit.Assert.*
import org.junit.Test

class WorkspaceTaskCloseTest {
    @Test fun failedTaskClosesWithoutOriginalServiceAndCannotResurrect() {
        val task = WorkspaceTaskUi("failed-before-session", app = "Chrome", packageName = "com.android.chrome", goal = "open")
        WorkspaceTasks.publishStart(task)
        WorkspaceTasks.update(task.taskId) { it.copy(phase = TaskPhase.FAILED) }
        assertTrue(WorkspaceTasks.clearClosedTask(task.taskId, null))
        WorkspaceTasks.update(task.taskId) { it.copy(phase = TaskPhase.WORKING) }
        assertNull(WorkspaceTasks.state.value)
        assertTrue(WorkspaceTasks.history.value.any { it.taskId == task.taskId && it.phase == TaskPhase.FAILED })
    }
    @Test fun wrongSessionCloseRetainsExactCurrentTask() {
        val task = WorkspaceTaskUi("owned-task", "owned-session", "Chrome", "com.android.chrome", "open")
        WorkspaceTasks.publishStart(task)
        try {
            assertFalse(WorkspaceTasks.clearClosedTask(task.taskId, "different-session"))
            assertEquals(task, WorkspaceTasks.state.value)
        } finally { WorkspaceTasks.clearClosedTask(task.taskId, task.sessionId) }
    }
    @Test fun staleCloseCannotMatchAnotherTask() {
        val task = WorkspaceTaskUi("new", "session-new", "Chrome", "com.android.chrome", "open")
        assertFalse(WorkspaceTasks.matches(task, "old", "session-old"))
        assertFalse(WorkspaceTasks.matches(task, "new", "session-old"))
        assertTrue(WorkspaceTasks.matches(task, "new", "session-new"))
    }
    @Test fun closeAfterAlreadyClearedIsIdempotent() {
        assertFalse(WorkspaceTasks.clearClosedTask("missing", null))
        assertNull(WorkspaceTasks.state.value)
    }
}
