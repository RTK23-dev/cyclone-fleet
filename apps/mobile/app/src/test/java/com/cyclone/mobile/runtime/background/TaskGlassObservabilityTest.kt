package com.cyclone.mobile.runtime.background

import com.cyclone.mobile.runtime.session.SessionPlaneKind
import com.cyclone.mobile.runtime.workspaces.Workspace
import com.cyclone.mobile.ui.overlay.GlassStepKind
import com.cyclone.mobile.ui.overlay.TaskGlassStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskGlassObservabilityTest {
    @Test
    fun vdTaskSubtitleTracksFastPathAndSkillWithoutClobberingPlane() {
        val fast = TaskGlassStep.fromProgress("tap Search")
        val skill = TaskGlassStep.fromProgress("Replaying compiled skill · Open search")
        val vd = WorkspaceTaskUi(
            "task-vd",
            "workspace-owned",
            "Chrome",
            "com.android.chrome",
            "Search Pixel",
            displayId = 7,
            glassStepKind = fast!!.kind,
            message = fast.label,
        )
        assertEquals(GlassStepKind.FAST_PATH, vd.glassStepKind)
        assertEquals("Getting your task ready", vd.subtitle)
        assertEquals(SessionPlaneKind.SESSION_KERNEL_VD, vd.plane().kind)
        assertTrue(ViewProgressRouter.showsVdFrames(vd))
        assertFalse(ViewProgressRouter.showsLayer2App(vd))
        val skilled = vd.copy(message = skill!!.label, glassStepKind = skill.kind)
        assertEquals(GlassStepKind.SKILL, skilled.glassStepKind)
        assertEquals("Getting your task ready", skilled.subtitle)
        assertEquals(SessionPlaneKind.SESSION_KERNEL_VD, skilled.plane().kind)
    }

    @Test
    fun layer2SliceTaskOpensAppPlaneNotVdFrames() {
        val step = TaskGlassStep.layer2Slice("Shop")
        val layer2 = WorkspaceTaskUi(
            "layer2-a",
            "default-foreground",
            "Shop",
            "example.shop",
            "Order tea",
            displayId = 0,
            workspaceId = "ws-a",
            workspaceGeneration = 4L,
            glassStepKind = step.kind,
            message = step.label,
        )
        assertEquals(GlassStepKind.LAYER2_SLICE, layer2.glassStepKind)
        assertEquals("Getting your task ready", layer2.subtitle)
        assertEquals(SessionPlaneKind.LAYER2_WORKSPACE, layer2.plane().kind)
        assertTrue(ViewProgressRouter.showsLayer2App(layer2))
        assertFalse(ViewProgressRouter.showsVdFrames(layer2))
    }

    @Test
    fun namedVdTaskBlocksLayer2GlassPublish() {
        val vd = WorkspaceTaskUi("task-vd", "workspace-owned", "Chrome", "com.android.chrome", "Search", displayId = 7)
        assertFalse(WorkspaceTasks.canPublishLayer2Slice(vd))
        assertTrue(WorkspaceTasks.canPublishLayer2Slice(null))
        assertTrue(
            WorkspaceTasks.canPublishLayer2Slice(
                vd.copy(phase = TaskPhase.STOPPED),
            ),
        )
        val layer2 = WorkspaceTaskUi(
            "layer2-a",
            "default-foreground",
            "Shop",
            "example.shop",
            "Order tea",
            displayId = 0,
            workspaceId = "ws-a",
            workspaceGeneration = 1L,
        )
        assertTrue(WorkspaceTasks.canPublishLayer2Slice(layer2))
        assertEquals(0, Workspace("ws-a", "Shop", "example.shop").displayId)
    }
}
