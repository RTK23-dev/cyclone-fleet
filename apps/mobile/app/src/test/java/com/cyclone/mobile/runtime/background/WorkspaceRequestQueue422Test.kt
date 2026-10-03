package com.cyclone.mobile.runtime.background

import com.cyclone.mobile.ui.overlay.TaskAttachment
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WorkspaceRequestQueue422Test {
    private fun source(path: String) = sequenceOf(
        File("src/main/java/com/cyclone/mobile/$path"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/$path"),
    ).first { it.isFile }.readText()

    @Test fun stopRemovesExactlyOneQueuedRequest() {
        val queue = WorkspaceRequestQueue()
        val first = queue.add("open chrome")
        val second = queue.add("open instagram")
        queue.remove(first.id)
        assertNull(queue.find(first.id))
        assertNotNull(queue.find(second.id))
        assertEquals(1, queue.state.value.size)
    }

    @Test fun steerPreservesGoalAndAttachment() {
        val queue = WorkspaceRequestQueue()
        val attachment = TaskAttachment(text = "reference")
        val request = queue.add("open chrome") { attachment }
        val steered = queue.steer(request.id, WorkspaceDestinationHint("Profile B", 10))!!
        assertEquals("open chrome", steered.goal)
        assertSame(attachment, steered.attachment)
        assertEquals("Profile B", steered.preferredDestination?.label)
    }

    @Test fun fifoHeadPromotesOnlyAfterTrueTerminalClosedState() {
        val queue = WorkspaceRequestQueue()
        val first = queue.add("open chrome")
        val second = queue.add("open instagram")
        assertNull(queue.promotableHead(TaskPhase.WORKING))
        assertEquals(first.id, queue.promotableHead(TaskPhase.STOPPED)?.id)
        assertEquals(first.id, queue.promotableHead(TaskPhase.DONE)?.id)
        queue.remove(first.id)
        assertEquals(second.id, queue.promotableHead(TaskPhase.FAILED)?.id)
        assertTrue(source("runtime/background/WorkspaceTaskService.kt").contains("WorkspaceTasks.scheduleQueuePromotion"))
    }

    @Test fun noPromotionWhileReviewHumanOrPaused() {
        assertFalse(WorkspaceQueuePromotionPolicy.canPromote(TaskPhase.REVIEW))
        assertFalse(WorkspaceQueuePromotionPolicy.canPromote(TaskPhase.HUMAN))
        assertFalse(WorkspaceQueuePromotionPolicy.canPromote(TaskPhase.PAUSED))
        assertTrue(WorkspaceQueuePromotionPolicy.canPromote(TaskPhase.DONE))
        assertTrue(WorkspaceQueuePromotionPolicy.canPromote(TaskPhase.STOPPED))
        assertTrue(WorkspaceQueuePromotionPolicy.canPromote(TaskPhase.FAILED))
        assertTrue(WorkspaceQueuePromotionPolicy.canPromote(null))
    }

    @Test fun queueOverflowDoesNotConsumeRejectedAttachment() {
        val queue = WorkspaceRequestQueue(capacity = 1)
        queue.add("first") { TaskAttachment(text = "kept") }
        var rejectedSupplierCalled = false
        runCatching {
            queue.add("second") {
                rejectedSupplierCalled = true
                TaskAttachment(text = "must stay pending")
            }
        }.onSuccess { fail("Expected queue capacity rejection") }
        assertFalse(rejectedSupplierCalled)
        assertEquals(1, queue.state.value.size)
    }

    @Test fun targetBindingAlsoPreservesGoalAndAttachment() {
        val queue = WorkspaceRequestQueue()
        val attachment = TaskAttachment(text = "reference")
        val request = queue.add("open chrome") { attachment }
        val bound = queue.bindTarget(request.id, "com.android.chrome", "Chrome")!!
        assertEquals(request.goal, bound.goal)
        assertSame(attachment, bound.attachment)
        assertEquals("com.android.chrome", bound.targetPackageName)
    }

    @Test fun promotionSourceReRunsExistingStartSafetyChecksAndNeverGuessesProfileB() {
        val state = source("runtime/background/WorkspaceTaskState.kt")
        assertTrue(state.contains("requests.promotableHead(state.value?.phase)"))
        assertTrue(state.contains("if (!canStartRequest() || Layer2Workspaces.gated())"))
        assertTrue(state.contains("preferred.androidUserId != own"))
        assertTrue(state.contains("OverlayChromeRuntime.submitRequest(pending.goal)"))
        assertTrue(state.contains("start(context.applicationContext, pending.goal, target.packageName, target.appLabel, pending.id)"))
    }
}
