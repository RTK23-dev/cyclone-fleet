package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.runtime.background.*
import com.cyclone.mobile.ui.overlay.BackgroundGlassPolicy
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Cyclone422TaskGlassTest {
    private fun source(path: String) = sequenceOf(
        File("src/main/java/com/cyclone/mobile/$path"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/$path"),
    ).first { it.isFile }.readText()

    private fun task(phase: TaskPhase = TaskPhase.WORKING, goal: String = "open chrome") =
        WorkspaceTaskUi(
            taskId = "task-1",
            app = "Chrome",
            packageName = "com.android.chrome",
            goal = goal,
            phase = phase,
        )

    @Test fun coldLaunchNeverShowsFakeCurrentTask() {
        assertNull(TaskGlassPresentation.current(null))
    }

    @Test fun analysisWithoutTaskNeverShowsFakeCurrentTask() {
        assertNull(TaskGlassPresentation.current(null))
        assertFalse(source("ui/overlay/OverlayChrome.kt").contains("Current phone task"))
    }

    @Test fun workingVisualStateWithoutTaskNeverShowsFakeCurrentTask() {
        assertNull(TaskGlassPresentation.current(null))
        val overlay = source("ui/overlay/OverlayChrome.kt")
        assertFalse(overlay.contains("snapshot.state in setOf(OverlayChromeState.ANALYSIS"))
    }

    @Test fun realWorkingTaskRendersCurrentCardModel() {
        val card = TaskGlassPresentation.current(task())
        assertNotNull(card)
        assertEquals("Working", card!!.status)
        assertEquals("Opening Chrome", card.taskLabel)
        assertEquals("View progress", card.actionLabel)
    }

    @Test fun finishedTaskRemainsOpenableWithoutPersistentBackgroundGlass() {
        val done = task(TaskPhase.DONE)
        val card = TaskGlassPresentation.current(done)!!
        assertEquals("Done", card.status)
        assertEquals("Opening Chrome", card.taskLabel)
        assertEquals("View result", card.actionLabel)
        assertEquals("View result for Opening Chrome", card.actionContentDescription)
        assertFalse(BackgroundGlassPolicy.visible(done))
        assertTrue(BackgroundGlassPolicy.tearDown(done))
    }

    @Test fun reviewTaskUsesConsumerCopy() {
        val card = TaskGlassPresentation.current(task(TaskPhase.REVIEW))!!
        assertEquals("Action Needed", card.status)
        assertEquals("View options", card.actionLabel)
    }

    @Test fun taskControlsUseBackendCapabilitiesAndNeverAdvertiseUnavailableFutureActions() {
        val panel = source("ui/v32/CycloneAskTaskPanel.kt")
        assertTrue(panel.contains("TaskFollowUpAction.CONTINUE"))
        assertTrue(panel.contains("TaskFollowUpAction.TAKE_OVER"))
        val capabilities = source("runtime/background/TaskPresentationSnapshot.kt")
        assertTrue(capabilities.contains("interruption?.canResumeAfterHuman == true"))
        assertTrue(capabilities.contains("interruption?.canTakeOver == true"))
        assertTrue(capabilities.contains("interruption?.canAutofill == true"))
        assertTrue(capabilities.contains("val interactiveSession = !task.sessionId.isNullOrBlank() && task.displayId != null"))
        assertTrue(panel.contains("Text(\"Autofill\")"))
        assertFalse(panel.contains("Text(\"Soon\")"))
        assertFalse(panel.contains("task.resumable &&"))
    }

    @Test fun failedTaskCannotMasqueradeAsResumableReview() {
        assertEquals("Couldn't finish", TaskGlassPresentation.current(task(TaskPhase.FAILED))!!.status)
        assertNull(TaskHarnessState.interruption(task(TaskPhase.FAILED)))
    }

    @Test fun backgroundGlassIsCompactProgressiveDisclosureNotASecondFullTaskCard() {
        val compact = source("ui/overlay/BackgroundTaskGlass.kt")
        assertTrue(compact.contains("BackgroundTaskRibbon(task, onAsk)"))
        assertTrue(compact.contains("val snapshot = TaskPresentationProjector.project(task)"))
        assertTrue(compact.contains("snapshot.currentMilestone ?: snapshot.title"))
        assertFalse(compact.contains("CycloneAskTaskPanel(task)"))
        assertFalse(compact.contains("BorderStroke"))
        assertFalse(compact.contains("Text(\"Ask Cyclone…\")"))
    }

    @Test fun viewProgressUsesExactTaskProjection() {
        val panel = source("ui/v32/CycloneAskTaskPanel.kt")
        assertTrue(panel.contains("UiTask(task).open(context)"))
        val exact = task()
        assertSame(exact, UiTask(exact).source)
        assertEquals("task-1", UiTask(exact).id)
    }

    @Test fun queuedTasksExposeSteerAndStopNotStartAndRemove() {
        val queue = source("ui/v32/CyclonePendingRequests.kt")
        assertTrue(queue.contains("Text(\"Steer\")"))
        assertTrue(queue.contains("Text(\"Stop\")"))
        assertFalse(queue.contains("Text(\"Start\")"))
        assertFalse(queue.contains("Text(\"Remove\")"))
    }

    @Test fun stoppedTaskIsNotPresentedAsWorking() {
        assertNull(TaskGlassPresentation.current(task(TaskPhase.STOPPED)))
    }

    @Test fun queuedRequestOnlyMapsToQueueCard() {
        val request = PendingWorkspaceRequest("q", "open chrome", null)
        val queue = TaskGlassPresentation.queued(request)
        assertEquals("Opening Chrome", queue.taskLabel)
        assertEquals("Queued", queue.status)
    }

    @Test fun openChromeHumanizesDeterministically() {
        assertEquals("Opening Chrome", TaskHumanizer.humanize("open chrome"))
    }

    @Test fun instagramLoginHumanizesDeterministically() {
        assertEquals(
            "Checking Instagram login status",
            TaskHumanizer.humanize("please check Instagram to see if I am logged in"),
        )
    }

    @Test fun batterySettingsHumanizesDeterministically() {
        assertEquals("Checking battery settings", TaskHumanizer.humanize("open settings and check battery"))
    }

    @Test fun unknownTaskHasSafeConciseFallback() {
        val label = TaskHumanizer.humanize(
            "please compare the first several visible choices and tell me which public option looks best for this task without buying anything",
        )
        assertTrue(label.isNotBlank())
        assertTrue(label.length <= 65)
        assertFalse(label.contains("```"))
    }

    @Test fun overlayVisualStateCannotManufactureCurrentTask() {
        val overlay = source("ui/overlay/OverlayChrome.kt")
        assertTrue(overlay.contains("val task = workspace?.takeIf"))
        assertTrue(overlay.contains("else -> OverlayForegroundCard(snapshot, minimize)"))
        assertFalse(overlay.contains("WorkspaceTaskUi("))
        assertFalse(overlay.contains("Current phone task"))
    }

    @Test fun taskGlassUsesSemanticThemeColors() {
        val panel = source("ui/v32/CycloneAskTaskPanel.kt")
        assertTrue(panel.contains("MaterialTheme.colorScheme.onSurface"))
        assertTrue(panel.contains("MaterialTheme.colorScheme.onSurfaceVariant"))
        assertFalse(panel.contains("Color.Black"))
        assertFalse(panel.contains("Color.White"))
    }

    @Test fun largeFontLayoutIsBoundedAndNoLegacy150DpQueueBoxRemains() {
        val overlay = source("ui/overlay/OverlayChrome.kt")
        val queue = source("ui/v32/CyclonePendingRequests.kt")
        val drawer = source("ui/overlay/SignatureOverlayDrawer.kt")
        assertTrue(overlay.contains("SignatureDrawerGeometry.availableHeight("))
        assertTrue(overlay.contains("heightIn(max = panelHeight.dp)"))
        // Bottom-anchored: the card scrolls only when a large font makes it taller than its room.
        assertTrue(drawer.contains("verticalScroll(scroll, enabled = scroll.maxValue > 0, reverseScrolling = true)"))
        assertTrue(drawer.contains("heightIn(max = upperMaxHeight)"))
        assertTrue(overlay.contains("OverlayChromeContract.WORK_PANEL_PEEK_DP"))
        assertTrue(drawer.contains("Modifier.weight(1f, fill = false)"))
        assertFalse(queue.contains("max = 150.dp"))
    }

    @Test fun keyboardCompactionRetainsQueuedControlsSafely() {
        val queue = source("ui/v32/CyclonePendingRequests.kt")
        val overlay = source("ui/overlay/OverlayChrome.kt")
        assertTrue(queue.contains("WindowInsets.ime"))
        assertTrue(queue.contains("requests.take(KEYBOARD_VISIBLE_QUEUE_CARDS)"))
        assertTrue(queue.contains("Text(\"Steer\")"))
        assertTrue(queue.contains("Text(\"Stop\")"))
        assertTrue(overlay.contains("screenHeightDp, keyboardHeightDp"))
    }

    @Test fun accessibilityDescriptionsNameExactHumanizedTask() {
        val current = TaskGlassPresentation.current(task())!!
        assertEquals("View progress for Opening Chrome", current.actionContentDescription)
        val queued = TaskGlassPresentation.queued(PendingWorkspaceRequest("q", "open chrome", null))
        assertEquals("Steer Opening Chrome", queued.steerContentDescription)
        assertEquals("Stop queued task Opening Chrome", queued.stopContentDescription)
    }
}
