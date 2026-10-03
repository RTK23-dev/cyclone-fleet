package com.cyclone.mobile.ui.overlay

import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import com.cyclone.mobile.ui.v32.CycloneSignatureCard
import com.cyclone.mobile.ui.v32.taskVisualState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.runtime.background.TaskFollowUpAction
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.TaskPresentationProjector
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.v32.CycloneCollapsedAskPill
import com.cyclone.mobile.ui.v32.CycloneTaskStatusPill
import com.cyclone.mobile.ui.v32.CycloneTaskVisualState

/**
 * The host app stays visually primary while Cyclone works.
 *
 * Background work is one compact, tappable status ribbon. Tapping it expands the normal Ask
 * Cyclone surface with full task controls. Human handoff keeps one explicit continuation button.
 * Terminal results never render here; BackgroundGlassPolicy hands those to notification/history.
 */
@Composable
fun BackgroundTaskGlass(task: WorkspaceTaskUi, onAsk: () -> Unit) {
    val outsideCyclone = DeviceState.currentPackage != "com.cyclone.mobile"
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (outsideCyclone && task.phase == TaskPhase.HUMAN) {
            HumanTakeoverRibbon(task)
        } else {
            BackgroundTaskRibbon(task, onAsk)
        }
    }
}

@Composable
private fun BackgroundTaskRibbon(task: WorkspaceTaskUi, onAsk: () -> Unit) {
    val snapshot = TaskPresentationProjector.project(task)
    val taskLabel = snapshot.currentMilestone ?: snapshot.title
    CycloneSignatureCard(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onAsk)
            .semantics { contentDescription = "View progress: $taskLabel" },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    snapshot.destinationChain ?: "Cyclone",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    taskLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            CycloneTaskStatusPill(task.taskVisualState())
        }
    }
}

@Composable
private fun HumanTakeoverRibbon(task: WorkspaceTaskUi) {
    val context = LocalContext.current
    val snapshot = TaskPresentationProjector.project(task)
    CycloneSignatureCard(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Cyclone", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    CycloneTaskStatusPill(CycloneTaskVisualState.ACTION_NEEDED)
                }
                Text(
                    snapshot.supportingCopy ?: "Finish in ${task.app}, then continue Cyclone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (TaskFollowUpAction.AUTOFILL in snapshot.followUps) {
                Button(
                    onClick = { WorkspaceTasks.command(context, task, "autofill") },
                    modifier = Modifier.heightIn(min = 44.dp).semantics {
                        contentDescription = "Autofill sign-in for ${task.app}"
                    },
                    shape = RoundedCornerShape(15.dp),
                ) { Text("Autofill") }
            }
            Button(
                onClick = { WorkspaceTasks.command(context, task, "resume") },
                enabled = TaskFollowUpAction.CONTINUE in snapshot.followUps,
                modifier = Modifier.heightIn(min = 44.dp).semantics {
                    contentDescription = "I'm done, continue with Cyclone"
                },
                shape = RoundedCornerShape(15.dp),
            ) { Text("I'm Done") }
        }
    }
}
