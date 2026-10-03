package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.runtime.background.*
import org.junit.Assert.*
import org.junit.Test

class TaskCheckpointTest {
    private fun task(phase: TaskPhase, steps: List<SemanticTaskStep>) = WorkspaceTaskUi(
        "t",
        app = "Chrome",
        packageName = "com.android.chrome",
        goal = "Open Chrome",
        phase = phase,
        semanticSteps = steps,
    )

    @Test
    fun checkingCurrentPageRemainsActiveAfterVerifiedStep() {
        val rows = checkpointRows(task(TaskPhase.WORKING, listOf(SemanticTaskStep(1, "Opened Chrome", SemanticStepState.DONE))))
        assertEquals(listOf(SemanticStepState.DONE, SemanticStepState.ACTIVE), rows.map { it.state })
    }

    @Test
    fun unverifiedStepNeverGetsCompletionCheck() {
        val rows = checkpointRows(task(TaskPhase.DONE, listOf(SemanticTaskStep(1, "Open Chrome", SemanticStepState.FAILED))))
        assertEquals(SemanticStepState.FAILED, rows.single().state)
    }

    @Test
    fun activeStepIsNotDuplicated() {
        assertEquals(
            1,
            checkpointRows(task(TaskPhase.WORKING, listOf(SemanticTaskStep(1, "Opening Chrome", SemanticStepState.ACTIVE)))).size,
        )
    }

    @Test
    fun fallbackDoesNotRepeatTheSameSemanticLabelWithDifferentState() {
        val working = task(TaskPhase.WORKING, emptyList())
        val sameLabel = working.subtitle
        val rows = checkpointRows(
            working.copy(
                semanticSteps = listOf(SemanticTaskStep(1, sameLabel, SemanticStepState.DONE)),
            ),
        )
        assertEquals(1, rows.size)
        assertEquals(sameLabel, rows.single().label)
        assertEquals(SemanticStepState.DONE, rows.single().state)
    }

    @Test
    fun traceHudHasNoWindowCreationAndCheckpointsStayInAsk() {
        fun source(path: String) = sequenceOf(
            java.io.File("src/main/java/com/cyclone/mobile/$path"),
            java.io.File("apps/mobile/app/src/main/java/com/cyclone/mobile/$path"),
        ).first { it.isFile }.readText()
        val trace = source("ai/AiTraceOverlayV27.kt")
        assertFalse(trace.contains("WindowManager"))
        assertFalse(trace.contains("addView"))
        assertTrue(source("ui/v32/CycloneAskTaskPanel.kt").contains("CycloneTaskCheckpoints(snapshot)"))
        assertFalse(source("runtime/background/WorkspaceProgressActivity.kt").contains("SemanticProgress("))
        assertTrue(source("ui/overlay/BackgroundTaskGlass.kt").contains("enabled = TaskFollowUpAction.CONTINUE in snapshot.followUps"))
    }

    @Test
    fun doneDoesNotFabricateNewWorkingRow() {
        assertTrue(checkpointRows(task(TaskPhase.DONE, emptyList())).isEmpty())
    }
}
