package com.cyclone.mobile.runtime.background

import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionIdentityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class ViewProgressRouterTest {
    @Test fun planeConstantsMatchSessionContractWires() {
        assertEquals("session_kernel_vd", ViewProgressRouter.PLANE_VD)
        assertEquals("layer2_workspace", ViewProgressRouter.PLANE_LAYER2)
    }

    @Test fun namedSessionWithPositiveDisplayRoutesToVdFrames() {
        val task = WorkspaceTaskUi(
            "task-vd",
            "named-vd",
            "Maps",
            "com.maps",
            "Navigate home",
            displayId = 7,
            phase = TaskPhase.WORKING,
        )
        val target = ViewProgressRouter.target(task)
        val frames = target as ViewProgressTarget.VdSessionFrames
        assertEquals("named-vd", frames.sessionId)
        assertEquals(7, frames.displayId)
        assertTrue(frames.displayId > 0)
        assertTrue(ViewProgressRouter.showsVdFrames(task))
        assertFalse(ViewProgressRouter.showsLayer2App(task))
    }

    @Test fun layer2WorkspaceRoutesToAppNeverNamedVdFrames() {
        val task = WorkspaceTaskUi(
            "task-l2",
            "default-foreground",
            "Shop",
            "com.shop.app",
            "Order tea",
            displayId = 0,
            workspaceId = "ws-a",
            workspaceGeneration = 4L,
            phase = TaskPhase.WORKING,
        )
        when (val target = ViewProgressRouter.target(task)) {
            is ViewProgressTarget.Layer2App -> {
                assertEquals("ws-a", target.workspaceId)
                assertEquals(4L, target.workspaceGeneration)
                assertEquals("com.shop.app", target.packageName)
            }
            is ViewProgressTarget.VdSessionFrames ->
                fail("Layer 2 target must not open named VD session frames (${target.sessionId})")
            is ViewProgressTarget.ProgressDetail ->
                fail("expected Layer2App")
        }
        assertTrue(ViewProgressRouter.showsLayer2App(task))
        assertFalse(ViewProgressRouter.showsVdFrames(task))
        assertEquals("default-foreground", task.sessionId)
        assertEquals(0, task.displayId)
    }

    @Test fun unboundStartingTaskRoutesToProgressDetail() {
        val task = WorkspaceTaskUi(
            "task-starting",
            null,
            "Maps",
            "com.maps",
            "Navigate home",
            phase = TaskPhase.STARTING,
        )
        assertEquals(ViewProgressTarget.ProgressDetail("task-starting"), ViewProgressRouter.target(task))
        assertFalse(ViewProgressRouter.showsVdFrames(task))
        assertFalse(ViewProgressRouter.showsLayer2App(task))
    }

    @Test fun defaultForegroundWithoutWorkspaceIsProgressDetail() {
        val task = WorkspaceTaskUi(
            "task-fg",
            "default-foreground",
            "Maps",
            "com.maps",
            "Navigate home",
            displayId = 0,
            phase = TaskPhase.STARTING,
        )
        assertEquals(ViewProgressTarget.ProgressDetail("task-fg"), ViewProgressRouter.target(task))
        assertFalse(ViewProgressRouter.showsVdFrames(task))
        assertFalse(ViewProgressRouter.showsLayer2App(task))
    }

    @Test fun namedSessionWithoutPositiveDisplayIsProgressDetail() {
        val missingDisplay = WorkspaceTaskUi(
            "task-no-display",
            "named-vd",
            "Maps",
            "com.maps",
            "Navigate home",
        )
        val zeroDisplay = WorkspaceTaskUi(
            "task-zero-display",
            "named-vd",
            "Maps",
            "com.maps",
            "Navigate home",
            displayId = 0,
        )
        assertEquals(ViewProgressTarget.ProgressDetail("task-no-display"), ViewProgressRouter.target(missingDisplay))
        assertEquals(ViewProgressTarget.ProgressDetail("task-zero-display"), ViewProgressRouter.target(zeroDisplay))
        assertFalse(ViewProgressRouter.showsVdFrames(missingDisplay))
        assertFalse(ViewProgressRouter.showsVdFrames(zeroDisplay))
        assertFalse(ViewProgressRouter.showsLayer2App(missingDisplay))
        assertFalse(ViewProgressRouter.showsLayer2App(zeroDisplay))
    }

    @Test fun namedVdMixedWithWorkspaceIdIsPlaneMismatch() {
        val task = WorkspaceTaskUi(
            "task-mix",
            "named-vd",
            "Shop",
            "com.shop.app",
            "Order tea",
            displayId = 7,
            workspaceId = "ws-a",
            workspaceGeneration = 4L,
        )
        assertErrorClass(SessionContract.PLANE_MISMATCH) { ViewProgressRouter.target(task) }
    }

    @Test fun workspaceIdWithoutGenerationIsWorkspaceGenerationRequired() {
        val task = WorkspaceTaskUi(
            "task-ws-only",
            "default-foreground",
            "Shop",
            "com.shop.app",
            "Order tea",
            displayId = 0,
            workspaceId = "ws-a",
        )
        assertErrorClass(SessionContract.WORKSPACE_GENERATION_REQUIRED) { ViewProgressRouter.target(task) }
    }

    @Test fun generationWithoutWorkspaceIdIsWorkspaceGenerationRequired() {
        val task = WorkspaceTaskUi(
            "task-gen-only",
            "default-foreground",
            "Shop",
            "com.shop.app",
            "Order tea",
            displayId = 0,
            workspaceGeneration = 4L,
        )
        assertErrorClass(SessionContract.WORKSPACE_GENERATION_REQUIRED) { ViewProgressRouter.target(task) }
    }

    private fun assertErrorClass(expected: String, block: () -> Unit) {
        val ex = assertThrows(SessionIdentityException::class.java) { block() }
        assertEquals(expected, (ex as SessionIdentityException).errorClass)
    }
}
