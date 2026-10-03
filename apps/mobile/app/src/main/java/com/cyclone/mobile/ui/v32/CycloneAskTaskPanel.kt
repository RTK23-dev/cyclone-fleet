package com.cyclone.mobile.ui.v32

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.TaskConsumerState
import com.cyclone.mobile.runtime.background.TaskFollowUpAction
import com.cyclone.mobile.runtime.background.TaskPresentationProjector
import com.cyclone.mobile.runtime.background.TaskPresentationSnapshot
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.WorkspaceTasks

/**
 * Ask Cyclone's single stateful task surface.
 *
 * One physical card morphs between Working, Action needed, Done and Failed. Copy/progress comes
 * from TaskPresentationProjector, so in-app chat, overlay and notifications can converge on the
 * same grounded task evidence instead of inventing state separately.
 */
@Composable
fun CycloneAskTaskPanel(task: WorkspaceTaskUi) {
    CycloneSignatureTheme { SignatureAskTaskPanel(task) }
}

@Composable
private fun SignatureAskTaskPanel(task: WorkspaceTaskUi) {
    val context = LocalContext.current
    val resolvedApp = remember(task.packageName) { appLabel(context, task.packageName) }
    val projectedTask = remember(task, resolvedApp) {
        if (resolvedApp.isNotBlank() && resolvedApp != "Other") task.copy(app = resolvedApp) else task
    }
    val snapshot = remember(projectedTask) { TaskPresentationProjector.project(projectedTask) }
    val visualState = task.taskVisualState()
    val moment = com.cyclone.mobile.owner.rememberOwnerMoment()
    var progressExpanded by rememberSaveable(task.taskId) { mutableStateOf(true) }
    val palette = cycloneConversationPalette()

    // Progress updates must preserve composer focus and the user's expansion choice.
    val targetOutline = when (visualState) {
        CycloneTaskVisualState.WORKING -> palette.cardOutline
        CycloneTaskVisualState.ACTION_NEEDED -> palette.attention.copy(alpha = .26f)
        CycloneTaskVisualState.DONE -> palette.success.copy(alpha = .26f)
        CycloneTaskVisualState.FAILED -> palette.failure.copy(alpha = .28f)
    }
    val outline by animateColorAsState(
        targetValue = targetOutline,
        animationSpec = tween(CycloneConversationTokens.stateTransitionMs),
        label = "task-card-outline",
    )

    CycloneSignatureCard(
        modifier = Modifier.fillMaxWidth()
            .animateContentSize(animationSpec = tween(CycloneConversationTokens.stateTransitionMs))
            .clip(RoundedCornerShape(CycloneConversationTokens.taskRadius))
            .semantics { stateDescription = if (progressExpanded) "Expanded" else "Collapsed" }
            .clickable(
                onClickLabel = if (progressExpanded) "Collapse task progress" else "Expand task progress",
            ) { progressExpanded = !progressExpanded }
            .border(.8.dp, outline, RoundedCornerShape(CycloneConversationTokens.taskRadius)),
        cornerRadius = CycloneConversationTokens.taskRadius,
        // Teal Matrix state tint: attention glows coral, done glows mint, working stays teal.
        accent = when (visualState) {
            CycloneTaskVisualState.WORKING -> MatrixTone.ACTIVE.accent
            CycloneTaskVisualState.ACTION_NEEDED, CycloneTaskVisualState.FAILED -> MatrixTone.ATTENTION.accent
            CycloneTaskVisualState.DONE -> MatrixTone.SUCCESS.accent
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(
                horizontal = CycloneConversationTokens.space16,
                vertical = 15.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space12),
        ) {
            TaskCardHeader(snapshot, visualState, progressExpanded)

            Text(
                snapshot.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            AnimatedContent(
                targetState = visualState,
                transitionSpec = {
                    fadeIn(tween(CycloneConversationTokens.stateTransitionMs)) togetherWith
                        fadeOut(tween(CycloneConversationTokens.fastTransitionMs))
                },
                label = "Cyclone task state",
            ) { state ->
                when (state) {
                    CycloneTaskVisualState.WORKING -> WorkingBody(
                        snapshot = snapshot,
                        expanded = progressExpanded,
                    )
                    // Owner Moments: whatever engine runs the task, what it needs from the owner is one card.
                    CycloneTaskVisualState.ACTION_NEEDED -> moment?.takeIf { it.taskId == task.taskId }?.let { CycloneOwnerCard(it, framed = false) } ?: ActionNeededBody(
                        task = task,
                        snapshot = snapshot,
                        onTakeOver = { WorkspaceTasks.command(context, task, "handoff") },
                        onAutofill = { WorkspaceTasks.command(context, task, "autofill") },
                        onDone = { WorkspaceTasks.command(context, task, "resume") },
                        expanded = progressExpanded,
                        onReviewRequest = { UiTask(task).open(context) },
                    )
                    CycloneTaskVisualState.DONE -> TerminalBody(
                        snapshot = snapshot,
                        primaryAction = TaskFollowUpAction.RUN_AGAIN,
                        onPrimary = { rerunTask(context, task) },
                        expanded = progressExpanded,
                        onOpenApp = { openInstalledApp(context, task.packageName) },
                    )
                    CycloneTaskVisualState.FAILED -> TerminalBody(
                        snapshot = snapshot,
                        primaryAction = TaskFollowUpAction.TRY_AGAIN,
                        onPrimary = { rerunTask(context, task) },
                        expanded = progressExpanded,
                        onOpenApp = { openInstalledApp(context, task.packageName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCardHeader(
    snapshot: TaskPresentationSnapshot,
    state: CycloneTaskVisualState,
    expanded: Boolean,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8),
    ) {
        CycloneAppIcon(com.cyclone.mobile.ui.overlay.WorkingApp.forTask(snapshot.taskId, snapshot.packageName), Modifier.size(26.dp))
        Text(
            snapshot.app.ifBlank { "Cyclone" },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CycloneTaskStatusPill(state)
        Icon(
            if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorkingBody(
    snapshot: TaskPresentationSnapshot,
    expanded: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8)) {
        CycloneTaskProgressIndicator(snapshot.progressFraction)

        val countLabel = snapshot.totalCount?.let { total ->
            "${snapshot.completedCount} of $total complete"
        } ?: snapshot.supportingCopy
        countLabel?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            CycloneTaskCheckpoints(snapshot)
        } else {
            snapshot.currentMilestone?.takeIf(String::isNotBlank)?.let { current ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8),
                ) {
                    CycloneNineDotSpinner()
                    Text(
                        current,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionNeededBody(
    task: WorkspaceTaskUi,
    snapshot: TaskPresentationSnapshot,
    onTakeOver: () -> Unit,
    onAutofill: () -> Unit,
    onDone: () -> Unit,
    expanded: Boolean,
    onReviewRequest: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space12)) {
        snapshot.supportingCopy?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (TaskFollowUpAction.AUTOFILL in snapshot.followUps) {
            Button(
                onClick = onAutofill,
                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp).semantics {
                    contentDescription = "Autofill sign-in for ${task.app}"
                },
                shape = RoundedCornerShape(15.dp),
            ) { Text("Autofill") }
        }

        val takeOver = TaskFollowUpAction.TAKE_OVER in snapshot.followUps
        val continueTask = TaskFollowUpAction.CONTINUE in snapshot.followUps
        if (takeOver || continueTask) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8)) {
                if (takeOver) {
                    OutlinedButton(
                        onClick = onTakeOver,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) { Text("Take Over") }
                }
                if (continueTask) {
                    OutlinedButton(
                        onClick = onDone,
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = RoundedCornerShape(15.dp),
                    ) { Text("I'm Done") }
                }
            }
        }

        if (expanded && snapshot.milestones.isNotEmpty()) {
            CycloneTaskCheckpoints(snapshot)
        }
        // Keep access to actionable confirmations, not duplicate progress pages.
        if (task.confirmation != null) {
            TextButton(
                onClick = onReviewRequest,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
            ) { Text("Review request") }
        }
    }
}

@Composable
private fun TerminalBody(
    snapshot: TaskPresentationSnapshot,
    primaryAction: TaskFollowUpAction,
    onPrimary: () -> Unit,
    expanded: Boolean,
    onOpenApp: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space12)) {
        snapshot.outcomeCopy?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } ?: snapshot.supportingCopy?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (snapshot.completedMilestones.isNotEmpty() && snapshot.state == TaskConsumerState.DONE) {
            Text(
                "${snapshot.completedMilestones.size} verified step${if (snapshot.completedMilestones.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded && snapshot.milestones.isNotEmpty()) {
            CycloneTaskCheckpoints(snapshot)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8),
        ) {
            if (primaryAction in snapshot.followUps) {
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Text(
                        when (primaryAction) {
                            TaskFollowUpAction.RUN_AGAIN -> "Run again"
                            TaskFollowUpAction.TRY_AGAIN -> "Try again"
                            else -> "Continue"
                        },
                    )
                }
            }
        }

        if (TaskFollowUpAction.OPEN_APP in snapshot.followUps) {
            TextButton(
                onClick = onOpenApp,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
            ) { Text("Open app") }
        }
    }
}

private fun openInstalledApp(context: android.content.Context, packageName: String) {
    if (packageName.isBlank()) return
    context.packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
        runCatching {
            context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

private fun rerunTask(context: android.content.Context, task: WorkspaceTaskUi) {
    runCatching {
        WorkspaceTasks.queueRequest(
            goal = task.goal,
            targetPackageName = task.packageName.takeIf(String::isNotBlank),
            targetAppLabel = task.app.takeIf(String::isNotBlank),
        )
        WorkspaceTasks.tryPromoteNext(context.applicationContext)
    }
}
