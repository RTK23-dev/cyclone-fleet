package com.cyclone.mobile.ui.overlay

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the exact multi-surface regressions reproduced from the 4.3.8 device screenshots. */
class VisualHierarchyRegressionTest {
    private fun source(path: String) = sequenceOf(
        File("src/main/java/com/cyclone/mobile/$path"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/$path"),
    ).first { it.isFile }.readText()

    @Test
    fun queuedWorkStaysVisibleUnderTheLiveTask() {
        val pending = source("ui/v32/CyclonePendingRequests.kt")
        assertTrue(pending.contains("if (requests.isEmpty()) return"))
        assertFalse(pending.contains("if (requests.isEmpty() || currentCardVisible) return"))
        assertTrue(pending.contains("WorkspaceTasks.tryPromoteNext(context)"))
        assertFalse(pending.contains("WorkspaceActivity::class.java"))
    }

    @Test
    fun installedAppPickerCannotGrowIntoAFullScreenList() {
        val workspace = source("runtime/background/WorkspaceActivity.kt")
        assertTrue(workspace.contains("Modifier.heightIn(max = 320.dp)"))
    }

    @Test
    fun taskUpdatesPreserveTypingAndDeadControlsStayGone() {
        val taskPanel = source("ui/v32/CycloneAskTaskPanel.kt")
        assertFalse(taskPanel.contains("clearFocus("))
        assertFalse(taskPanel.contains("keyboard?.hide()"))
        assertFalse(taskPanel.contains("CycloneSwipeTaskCard("))
        assertFalse(taskPanel.contains("Text(\"Show less\")"))
        assertFalse(taskPanel.contains("Text(\"Details\")"))
        assertFalse(taskPanel.contains("Text(\"View details\")"))
        assertTrue(taskPanel.contains("TaskFollowUpAction.AUTOFILL in snapshot.followUps"))
        assertTrue(taskPanel.contains("Text(\"Autofill\")"))
        assertFalse(taskPanel.contains("Text(\"Soon\")"))
    }

    @Test
    fun compactBackgroundGlassDoesNotContainAnotherComposerOrFullTaskPanel() {
        val compact = source("ui/overlay/BackgroundTaskGlass.kt")
        assertTrue(compact.contains("BackgroundTaskRibbon(task, onAsk)"))
        assertFalse(compact.contains("CycloneAskTaskPanel(task)"))
        assertFalse(compact.contains("Ask Cyclone…"))
    }
}
