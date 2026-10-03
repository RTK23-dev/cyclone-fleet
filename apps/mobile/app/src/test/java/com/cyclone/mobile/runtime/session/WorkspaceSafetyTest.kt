package com.cyclone.mobile.runtime.session

import com.cyclone.mobile.runtime.background.WorkspaceCommands
import com.cyclone.mobile.runtime.background.WorkspaceLifecycle
import com.cyclone.mobile.runtime.background.WorkspaceState
import com.cyclone.mobile.ai.vision.live.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceSafetyTest {
    @Test fun handbackRequiresOriginalTaskPackageAndDisplay() {
        val original = WorkspaceCommands.Task(10, 42, 0, "com.example.app")
        assertEquals(original, WorkspaceCommands.exactTask(listOf(original), 42, 0, "com.example.app"))
        for (changed in listOf(original.copy(taskId = 43), original.copy(displayId = 9), original.copy(packageName = "other"))) {
            assertThrows(IllegalStateException::class.java) {
                WorkspaceCommands.exactTask(listOf(changed), 42, 0, "com.example.app")
            }
        }
    }
    @Test fun notificationCannotRouteToAnotherTaskOrSession() {
        val task = com.cyclone.mobile.runtime.background.WorkspaceTaskUi("task-a", "session-a", "App", "app", "goal")
        assertTrue(com.cyclone.mobile.runtime.background.WorkspaceTasks.matches(task, "task-a", "session-a"))
        assertFalse(com.cyclone.mobile.runtime.background.WorkspaceTasks.matches(task, "task-b", "session-a"))
        assertFalse(com.cyclone.mobile.runtime.background.WorkspaceTasks.matches(task, "task-a", "session-b"))
        assertFalse(com.cyclone.mobile.runtime.background.WorkspaceTasks.matches(null, "task-a", "session-a"))
    }

    @Test fun queuedLeaseCannotActAfterPauseHandoffOrResume() {
        for (state in listOf(WorkspaceState.PAUSED, WorkspaceState.WAITING_FOR_CONFIRMATION,
                WorkspaceState.FAILED, WorkspaceState.CANCELLED)) {
            val lifecycle = WorkspaceLifecycle("B", 9)
            lifecycle.transition(WorkspaceState.BACKGROUND_OK)
            val queued = lifecycle.lease()
            lifecycle.requireMutation(queued, true, true)
            lifecycle.transition(state)
            assertEquals(InputOwner.HUMAN, lifecycle.owner)
            assertThrows(IllegalStateException::class.java) { lifecycle.requireMutation(queued, true, true) }
            if (state == WorkspaceState.PAUSED) {
                lifecycle.transition(WorkspaceState.BACKGROUND_OK)
                assertThrows(IllegalStateException::class.java) { lifecycle.requireMutation(queued, true, true) }
                lifecycle.requireMutation(lifecycle.lease(), true, true)
            }
        }
    }
    @Test fun destroyedDisplayBackendDeathAndForeignSessionRejectBeforeMutation() {
        val a = WorkspaceLifecycle("A", 8).apply { transition(WorkspaceState.BACKGROUND_OK) }
        val b = WorkspaceLifecycle("B", 9).apply { transition(WorkspaceState.BACKGROUND_OK) }
        assertThrows(IllegalStateException::class.java) { b.requireMutation(a.lease(), true, true) }
        assertThrows(IllegalStateException::class.java) { b.requireMutation(b.lease(), false, true) }
        assertThrows(IllegalStateException::class.java) { b.requireMutation(b.lease(), true, false) }
    }
    @Test fun everyPrivilegedInputNamesTheOwnedNonzeroDisplay() {
        val operations = listOf(
            Triple(WorkspaceCommands.TAP, floatArrayOf(20f, 30f), ""),
            Triple(WorkspaceCommands.SWIPE, floatArrayOf(20f, 30f, 20f, 90f, 300f), ""),
            Triple(WorkspaceCommands.BACK, floatArrayOf(), ""),
            Triple(WorkspaceCommands.TEXT, floatArrayOf(), "pizza near me"),
        )
        operations.forEach { (kind, coordinates, text) ->
            assertEquals(listOf("/system/bin/input", "-d", "9"), WorkspaceCommands.input(9, kind, coordinates, text).take(3))
            assertThrows(IllegalArgumentException::class.java) { WorkspaceCommands.input(0, kind, coordinates, text) }
            assertThrows(IllegalArgumentException::class.java) { WorkspaceCommands.input(-1, kind, coordinates, text) }
        }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceCommands.input(9, 99, floatArrayOf(), "") }
        assertThrows(IllegalArgumentException::class.java) { WorkspaceCommands.input(9, WorkspaceCommands.TEXT, floatArrayOf(), "%s") }
    }
    @Test fun actionEvidenceRejectsDelayedPreActionWrongDisplayAndStalledFrames() {
        val boundary = ActionFrameBoundary("B", 9, 10, 200)
        val frame = LiveFrame("B", 9, 11, 210, 720, 1280, FrameSourceType.VIRTUAL_DISPLAY_SURFACE)
        fun eligible(f: LiveFrame) = FrameSelection.eligible(f, "B", 9, 220, 50, boundary)
        assertTrue(eligible(frame))
        assertFalse(eligible(frame.copy(sessionId = "A")))
        assertFalse(eligible(frame.copy(displayId = 0)))
        assertFalse(eligible(frame.copy(frameId = 10)))
        assertFalse(eligible(frame.copy(capturedAtMonotonicMs = 199)))
        assertFalse(eligible(frame.copy(capturedAtMonotonicMs = 200)))
        assertFalse(eligible(frame.copy(capturedAtMonotonicMs = 221)))
        assertFalse(FrameSelection.eligible(frame, "B", 9, 1000, 50, boundary))
    }
    @Test fun unknownTaskOutputCannotProveIsolation() {
        assertTrue(WorkspaceCommands.tasks("OEM task format without IDs").isEmpty())
        val parsed = WorkspaceCommands.tasks("""
            RootTask id=10 bounds=[0,0][720,1280] displayId=9 userId=0
              taskId=42: com.example.pizza/.MainActivity bounds=[0,0][720,1280] userId=0 visible=true
            RootTask id=1 bounds=[0,0][1080,2400] displayId=0 userId=0
              taskId=20: com.example.human/.MainActivity userId=0 visible=true
        """.trimIndent())
        assertEquals(listOf(WorkspaceCommands.Task(10, 42, 9, "com.example.pizza"),
            WorkspaceCommands.Task(1, 20, 0, "com.example.human")), parsed)
    }
}
