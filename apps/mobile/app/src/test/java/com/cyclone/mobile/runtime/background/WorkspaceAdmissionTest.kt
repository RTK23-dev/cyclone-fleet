package com.cyclone.mobile.runtime.background

import org.junit.Assert.*
import org.junit.Test

class WorkspaceAdmissionTest {
    @Test fun emptyComposerDoesNotOwnExecution() {
        assertTrue(WorkspaceQueuePromotionPolicy.canStart(null, false))
        assertFalse(WorkspaceQueuePromotionPolicy.canStart(null, true))
    }
    @Test fun suspendedAndRetainedTasksKeepTheirSlot() {
        listOf(TaskPhase.STARTING, TaskPhase.WORKING, TaskPhase.PAUSED, TaskPhase.HUMAN,
            TaskPhase.REVIEW).forEach {
            assertFalse(WorkspaceQueuePromotionPolicy.canStart(it, false))
        }
    }
    @Test fun failedPromotionExplainsBlockWithoutLosingRequest() {
        val queue = WorkspaceRequestQueue()
        val request = queue.add("open Chrome")
        queue.blocked(request.id, "Enable Accessibility")
        assertEquals("Enable Accessibility", queue.peek()?.blockedReason)
        queue.steer(request.id, WorkspaceDestinationHint("Profile A", 0))
        assertNull(queue.peek()?.blockedReason)
        assertEquals(request.goal, queue.peek()?.goal)
    }
    @Test fun stoppedFailedAndFinishedTasksReleaseTheirSlot() {
        listOf(TaskPhase.STOPPED, TaskPhase.FAILED, TaskPhase.DONE).forEach {
            assertTrue(WorkspaceQueuePromotionPolicy.canStart(it, false))
            assertFalse(WorkspaceQueuePromotionPolicy.canStart(it, true))
        }
    }
}
