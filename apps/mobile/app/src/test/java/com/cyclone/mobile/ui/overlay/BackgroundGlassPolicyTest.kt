package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import org.junit.Assert.*
import org.junit.Test

class BackgroundGlassPolicyTest {
    private val task = WorkspaceTaskUi("task", "owned", "Shop", "shop", "Order tea")

    @Test
    fun backgroundGlassIsLiveStatusNotPersistentTaskHistory() {
        assertFalse(BackgroundGlassPolicy.visible(null))
        for (phase in listOf(
            TaskPhase.STARTING,
            TaskPhase.WORKING,
            TaskPhase.PAUSED,
            TaskPhase.REVIEW,
            TaskPhase.HUMAN,
        )) {
            assertTrue("$phase should stay visible while interaction is live", BackgroundGlassPolicy.visible(task.copy(phase = phase)))
            assertFalse("$phase must not tear down live glass", BackgroundGlassPolicy.tearDown(task.copy(phase = phase)))
        }

        for (phase in listOf(TaskPhase.DONE, TaskPhase.FAILED, TaskPhase.STOPPED)) {
            assertFalse("$phase must not leave a large floating result card", BackgroundGlassPolicy.visible(task.copy(phase = phase)))
            assertTrue("$phase should hand presentation back to notification/history", BackgroundGlassPolicy.tearDown(task.copy(phase = phase)))
        }
    }

    @Test
    fun progressUpdatesRetainExactTaskIdentity() {
        val tick = task.copy(message = "Checking the order")
        assertEquals("owned", tick.sessionId)
        assertEquals("Checking the order", tick.message)
    }

    @Test
    fun failedPartialAttachAndRepeatedDismissLeaveZeroWindows() {
        val removed = mutableListOf<String>()
        val windows = OverlayWindowRegistry<String> { removed += it }
        windows.attached("halo")
        windows.attached("composer")
        windows.attached("share")
        windows.clear()
        windows.clear()
        assertEquals(0, windows.size)
        assertEquals(listOf("halo", "composer", "share"), removed)
        windows.attached("fresh-composer")
        windows.clear()
        assertEquals(0, windows.size)
    }

    @Test
    fun appleComposerKeepsACompactBottomBreathingGap() {
        assertEquals(26, OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP)
        assertEquals(8, OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP - 18)
    }
}
