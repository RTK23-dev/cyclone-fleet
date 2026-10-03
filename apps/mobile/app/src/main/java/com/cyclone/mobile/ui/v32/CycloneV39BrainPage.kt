package com.cyclone.mobile.ui.v32

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.AgentRunDiagnosticV39
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.AiTraceSession
import com.cyclone.mobile.ai.TaskResultActivityV292
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.runtime.background.TaskConsumerState
import com.cyclone.mobile.runtime.background.TaskPresentationProjector
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class V39RunRow(
    val session: AiTraceSession,
    val tools: Int,
    val failures: Int,
    val recoveries: Int,
)

/** Consumer-facing knowledge center backed by Cyclone's real learned state and run history. */
@Composable
internal fun CycloneV39BrainPage(context: Context, refreshTick: Int) {
    val store = AdaptiveBrainRuntime.store
    val skills = remember(refreshTick) { store.listMicroSkills(60) }
    val apps = remember(refreshTick) { store.listApps() }
    val paths = remember(refreshTick) { store.listPaths(40) }
    val notes = remember(refreshTick) { store.listNotes(30) }
    val task by WorkspaceTasks.state.collectAsState()
    val runs = remember(refreshTick) {
        AgentTraceRuntime.store.listSessions(24).map { session ->
            val metrics = AgentRunDiagnosticV39.metrics(AgentTraceRuntime.store.events(session.id))
            V39RunRow(session, metrics.toolCalls, metrics.failures, metrics.recoveries)
        }
    }
    val cleared = TaskCardDismissals.cleared(context)
    val visibleRuns = runs.filter { it.session.endedAt == null || "run:${it.session.id}" !in cleared }
    val verified = skills.filter { it.successCount > 0 }
    val learnedApps = apps.filter { it.openSuccessCount > 0 }
    val averageConfidence = if (verified.isEmpty()) 0 else {
        (verified.map { it.confidence.coerceIn(0.0, 1.0) }.average() * 100).toInt()
    }

    var tab by remember { mutableIntStateOf(0) }
    LazyColumn(
        contentPadding = cyclonePageInsets(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            CyclonePageHeader("Brain", "What Cyclone knows and trusts")
        }

        task?.takeIf { UiTask(it).active }?.let { active ->
            item {
                val projected = TaskPresentationProjector.project(
                    active.copy(app = appLabel(context, active.packageName).takeIf { it != "Other" } ?: active.app),
                )
                val consumerState = when (projected.state) {
                    TaskConsumerState.WORKING -> "Working"
                    TaskConsumerState.ACTION_NEEDED -> "Action needed"
                    TaskConsumerState.NEEDS_SECRET -> "Needs secret"
                    TaskConsumerState.DONE -> "Done"
                    TaskConsumerState.FAILED -> "Couldn't finish"
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f),
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Rounded.SmartToy, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text("Learning from current task", style = MaterialTheme.typography.labelMedium)
                            Text(
                                projected.currentMilestone ?: projected.title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(consumerState, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        item {
            CycloneLiquidTray(height = 74.dp, contentPadding = 6.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrainMetric("Skills", verified.size.toString(), Modifier.weight(1f))
                    BrainMetric("Apps", learnedApps.size.toString(), Modifier.weight(1f))
                    BrainMetric("Confidence", "$averageConfidence%", Modifier.weight(1f))
                }
            }
        }

        if (apps.size > learnedApps.size) {
            item {
                Text(
                    "${apps.size} apps discovered · ${learnedApps.size} with successful learned use",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { CycloneSegmentedControl(listOf("Skills", "Apps", "Outcomes"), tab, { tab = it }) }

        when (tab) {
            0 -> {
                item { CycloneSectionTitle("Verified skills") }
                if (verified.isEmpty()) {
                    item { BrainEmptyState("No verified skills yet", "Complete a task or teach Cyclone by doing. Skills appear after successful use.") }
                }
                items(verified, key = { it.signature }) { skill ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                CycloneAppIcon(skill.fromPackage, Modifier.padding(7.dp).size(30.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(skill.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${skill.successCount} successful ${if (skill.successCount == 1) "use" else "uses"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ) {
                                Text(
                                    "${(skill.confidence.coerceIn(0.0, 1.0) * 100).toInt()}%",
                                    Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
                if (paths.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(Icons.Rounded.Memory, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${paths.size} learned paths ready for reuse", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            1 -> {
                item { CycloneSectionTitle("Learned apps") }
                if (learnedApps.isEmpty()) {
                    item { BrainEmptyState("No learned apps yet", "Apps move here after Cyclone successfully navigates them.") }
                }
                items(learnedApps, key = { it.packageName }) { app ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                CycloneAppIcon(app.packageName, Modifier.padding(7.dp).size(30.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(app.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${app.openSuccessCount} successful ${if (app.openSuccessCount == 1) "visit" else "visits"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Rounded.CheckCircle, "Learned", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }

            else -> {
                item { CycloneSectionTitle("Recent outcomes") }
                if (cleared.isNotEmpty()) {
                    item {
                        Text(
                            "Restore cleared cards",
                            modifier = Modifier
                                .clickable { TaskCardDismissals.restore(context) }
                                .padding(vertical = 12.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (visibleRuns.isEmpty()) {
                    item { BrainEmptyState("No outcomes yet", "Completed Cyclone tasks will appear here with concise results.") }
                }
                items(visibleRuns, key = { it.session.id }) { run ->
                    V39RunCard(run) {
                        context.startActivity(
                            Intent(context, TaskResultActivityV292::class.java)
                                .putExtra(TaskResultActivityV292.EXTRA_SESSION_ID, run.session.id)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                if (notes.isNotEmpty()) {
                    item { CycloneSectionTitle("Recent learning") }
                    items(notes, key = { it.id }) { note ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 0.dp,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                            ) {
                                Icon(Icons.Rounded.History, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(note.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrainEmptyState(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Psychology, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun V39RunCard(run: V39RunRow, onOpen: () -> Unit) {
    val success = run.session.status == "COMPLETED"
    val duration = ((run.session.endedAt ?: System.currentTimeMillis()) - run.session.startedAt).coerceAtLeast(0)
    CycloneSwipeTaskCard("run:${run.session.id}", canClear = run.session.endedAt != null, onOpen = onOpen) {
        Card(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        if (success) Icons.Rounded.CheckCircle else Icons.Rounded.History,
                        null,
                        modifier = Modifier.size(19.dp),
                        tint = if (success) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(run.session.goal, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            run.session.result ?: if (run.session.status == "RUNNING") "Working" else "Task ended",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    "${run.session.status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }} · ${formatDuration(duration)} · ${formatRunClock(run.session.startedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (run.session.endedAt != null) CycloneRunLearnActionsForRun(run.session.id)
                OutlinedButton(
                    onClick = onOpen,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) { Text("View details", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun BrainMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

private fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "<1 sec"
    ms < 60_000 -> "${ms / 1_000} sec"
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
}

private fun formatRunClock(time: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time))
