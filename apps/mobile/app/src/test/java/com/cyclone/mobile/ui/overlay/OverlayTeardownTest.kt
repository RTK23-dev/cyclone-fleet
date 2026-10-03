package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OverlayChromeRuntime.overlayWindowCount() is the device hook; this JVM probe is the same
 * registry contract.
 */
class OverlayTeardownTest {
    private val task = WorkspaceTaskUi("task", "owned", "Shop", "shop", "Order tea")

    @Test
    fun cancelAfterHaloComposerShareLeavesZeroWindows() {
        val registry = OverlayWindowRegistry<String> {}
        registry.attached("halo")
        registry.attached("composer")
        registry.attached("share")
        OverlayTeardownContract.cancel(registry)
        assertEquals(
            OverlayTeardownContract.WINDOWS_AFTER_CANCEL,
            OverlayTeardownContract.overlayWindowCount(registry),
        )
    }

    @Test
    fun repeatedCancelStaysAtZero() {
        val registry = OverlayWindowRegistry<String> {}
        registry.attached("halo")
        registry.attached("composer")
        registry.attached("share")
        OverlayTeardownContract.cancel(registry)
        OverlayTeardownContract.cancel(registry)
        assertEquals(OverlayTeardownContract.WINDOWS_AFTER_CANCEL, OverlayTeardownContract.overlayWindowCount(registry))
    }

    @Test
    fun partialHaloAttachThenCancelLeavesZeroWindows() {
        val registry = OverlayWindowRegistry<String> {}
        registry.attached("halo")
        OverlayTeardownContract.cancel(registry)
        assertEquals(OverlayTeardownContract.WINDOWS_AFTER_CANCEL, OverlayTeardownContract.overlayWindowCount(registry))
    }

    @Test
    fun terminalPhasesTearDownAndHideGlass() {
        for (phase in listOf(TaskPhase.DONE, TaskPhase.FAILED, TaskPhase.STOPPED)) {
            val tick = task.copy(phase = phase)
            assertTrue(BackgroundGlassPolicy.tearDown(tick))
            assertFalse(BackgroundGlassPolicy.visible(tick))
        }
    }

    @Test
    fun livePhasesKeepCompactGlassVisibleWithoutTearDown() {
        for (phase in listOf(
            TaskPhase.STARTING,
            TaskPhase.WORKING,
            TaskPhase.PAUSED,
            TaskPhase.REVIEW,
            TaskPhase.HUMAN,
        )) {
            val tick = task.copy(phase = phase)
            assertTrue(BackgroundGlassPolicy.visible(tick))
            assertFalse(BackgroundGlassPolicy.tearDown(tick))
        }
    }
}
