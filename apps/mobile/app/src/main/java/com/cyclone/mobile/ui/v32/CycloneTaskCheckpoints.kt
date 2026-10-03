package com.cyclone.mobile.ui.v32

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.SemanticStepState
import com.cyclone.mobile.runtime.background.TaskMilestoneProjector
import com.cyclone.mobile.runtime.background.TaskPresentationProjector
import com.cyclone.mobile.runtime.background.TaskPresentationSnapshot
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import kotlin.math.cos
import kotlin.math.sin

internal data class CheckpointRow(val label: String, val state: SemanticStepState)

internal fun checkpointRows(task: WorkspaceTaskUi): List<CheckpointRow> {
    val rows = TaskMilestoneProjector.project(task.semanticSteps)
        .takeLast(4)
        .map { CheckpointRow(it.label, it.state) }
    val activeLabel = task.subtitle.trim()
    val alreadyRepresented = rows.any { row ->
        row.label.trim().equals(activeLabel, ignoreCase = true)
    }
    return if (
        task.working &&
        task.confirmation == null &&
        activeLabel.isNotBlank() &&
        rows.none { it.state == SemanticStepState.ACTIVE } &&
        !alreadyRepresented
    ) rows + CheckpointRow(activeLabel, SemanticStepState.ACTIVE)
    else rows
}

@Composable
fun CycloneNineDotSpinner(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "Working dots")
    val rotation by transition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "Dot rotation")
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(16.dp).semantics { contentDescription = "Working" }) {
        val orbit = size.minDimension * .34f
        repeat(9) { dot ->
            val angle = Math.toRadians((rotation + dot * 40f).toDouble())
            drawCircle(color.copy(alpha = .2f + .8f * (dot + 1) / 9f), size.minDimension * .065f,
                Offset(center.x + cos(angle).toFloat() * orbit, center.y + sin(angle).toFloat() * orbit))
        }
    }
}

@Composable
fun CycloneTaskCheckpoints(task: WorkspaceTaskUi) {
    CycloneTaskCheckpoints(TaskPresentationProjector.project(task))
}

@Composable
fun CycloneTaskCheckpoints(snapshot: TaskPresentationSnapshot) {
    val rows = snapshot.milestones.ifEmpty {
        snapshot.currentMilestone?.takeIf(String::isNotBlank)?.let {
            listOf(com.cyclone.mobile.runtime.background.TaskPresentationMilestone(it, SemanticStepState.ACTIVE))
        }.orEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8)) {
        rows.forEach { row ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8),
            ) {
                CycloneCheckpointMarker(row.state)
                Text(
                    row.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.state == SemanticStepState.ACTIVE) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun CycloneCheckpointMarker(state: SemanticStepState) {
    val palette = cycloneConversationPalette()
    when (state) {
        SemanticStepState.ACTIVE -> Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            CycloneNineDotSpinner(Modifier.size(18.dp))
        }
        SemanticStepState.DONE -> Surface(
            modifier = Modifier.size(18.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = palette.active,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, Modifier.size(12.dp))
            }
        }
        SemanticStepState.ACTION_NEEDED -> Surface(
            modifier = Modifier.size(18.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = palette.attentionSoft,
            contentColor = palette.attention,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PriorityHigh, null, Modifier.size(12.dp))
            }
        }
        SemanticStepState.FAILED -> Surface(
            modifier = Modifier.size(18.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = palette.failureSoft,
            contentColor = palette.failure,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(12.dp))
            }
        }
        SemanticStepState.PENDING -> Canvas(
            Modifier.size(18.dp).semantics { contentDescription = "Pending" },
        ) {
            drawCircle(
                color = palette.cardOutline,
                radius = size.minDimension * .34f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx()),
            )
        }
    }
}
