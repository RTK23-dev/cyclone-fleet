package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi

enum class CycloneTaskVisualState { WORKING, ACTION_NEEDED, DONE, FAILED }

fun WorkspaceTaskUi.taskVisualState(): CycloneTaskVisualState {
    if (confirmation != null) return CycloneTaskVisualState.ACTION_NEEDED
    return when (phase) {
        TaskPhase.STARTING, TaskPhase.WORKING -> CycloneTaskVisualState.WORKING
        TaskPhase.DONE -> CycloneTaskVisualState.DONE
        TaskPhase.PAUSED, TaskPhase.REVIEW, TaskPhase.HUMAN -> CycloneTaskVisualState.ACTION_NEEDED
        TaskPhase.FAILED, TaskPhase.STOPPED -> CycloneTaskVisualState.FAILED
    }
}

fun WorkspaceTaskUi.canTakeOverFromUi(): Boolean =
    !sessionId.isNullOrBlank() && displayId != null && interruption?.canTakeOver == true &&
        phase !in setOf(TaskPhase.HUMAN, TaskPhase.DONE, TaskPhase.FAILED, TaskPhase.STOPPED)

fun WorkspaceTaskUi.canContinueAfterHumanFromUi(): Boolean =
    !sessionId.isNullOrBlank() && displayId != null && interruption?.canResumeAfterHuman == true &&
        resumable && confirmation == null && phase in setOf(TaskPhase.HUMAN, TaskPhase.PAUSED, TaskPhase.REVIEW)

fun WorkspaceTaskUi.canAutofillFromUi(): Boolean =
    !sessionId.isNullOrBlank() && displayId != null && interruption?.canAutofill == true &&
        resumable && confirmation == null && phase in setOf(TaskPhase.REVIEW, TaskPhase.HUMAN, TaskPhase.PAUSED)

@Composable
fun CycloneTaskStatusPill(
    state: CycloneTaskVisualState,
    modifier: Modifier = Modifier,
) {
    val palette = cycloneConversationPalette()
    val container = when (state) {
        CycloneTaskVisualState.WORKING -> palette.activeSoft
        CycloneTaskVisualState.ACTION_NEEDED -> palette.attentionSoft
        CycloneTaskVisualState.DONE -> palette.successSoft
        CycloneTaskVisualState.FAILED -> palette.failureSoft
    }
    val content = when (state) {
        CycloneTaskVisualState.WORKING -> palette.active
        CycloneTaskVisualState.ACTION_NEEDED -> palette.attention
        CycloneTaskVisualState.DONE -> palette.success
        CycloneTaskVisualState.FAILED -> palette.failure
    }
    val label = when (state) {
        CycloneTaskVisualState.WORKING -> "Working"
        CycloneTaskVisualState.ACTION_NEEDED -> "Action needed"
        CycloneTaskVisualState.DONE -> "Done"
        CycloneTaskVisualState.FAILED -> "Couldn't finish"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = container,
        contentColor = content,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            when (state) {
                CycloneTaskVisualState.WORKING -> CycloneNineDotSpinner()
                CycloneTaskVisualState.ACTION_NEEDED -> Text(
                    "✦",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                CycloneTaskVisualState.DONE -> Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = content,
                )
                CycloneTaskVisualState.FAILED -> Text(
                    "!",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
        }
    }
}
