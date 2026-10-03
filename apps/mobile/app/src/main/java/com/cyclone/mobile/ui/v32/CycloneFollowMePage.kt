package com.cyclone.mobile.ui.v32

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.applearner.FollowMeLearnerRuntime
import com.cyclone.mobile.automation.*
import com.cyclone.mobile.guided.*

/** Consumer review of the existing Follow Me timeline/compiler, never a second recorder. */
@Composable
fun CycloneFollowMePage(context: Context, refreshTick: Int, onBack: () -> Unit) {
    var progress by remember(refreshTick) { mutableStateOf(FollowMeLearnerRuntime.progress()) }
    LaunchedEffect(refreshTick) {
        while (true) {
            progress = FollowMeLearnerRuntime.progress()
            kotlinx.coroutines.delay(800)
        }
    }
    val session = remember(refreshTick, progress) {
        RoutineTeachingRuntime.listSessions().firstOrNull {
            it.id == progress.teachingSessionId && it.endedAt != null
        }
    }
    var draft by remember { mutableStateOf<AutomationDefinition?>(null) }
    var message by remember { mutableStateOf("") }

    LazyColumn(
        contentPadding = cyclonePageInsets(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to routines")
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Teach by doing", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Show Cyclone one useful path, then review what it learned.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (progress.active) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.School, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    if (progress.paused) "Teaching paused" else "Learning with you",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    progress.currentApp.ifBlank { "Follow the steps you want Cyclone to learn." },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = {
                                    if (progress.paused) FollowMeLearnerRuntime.resume() else FollowMeLearnerRuntime.pause()
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                Icon(if (progress.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(18.dp))
                                Spacer(Modifier.size(5.dp))
                                Text(if (progress.paused) "Continue" else "Pause")
                            }
                            Button(
                                onClick = { FollowMeLearnerRuntime.stop() },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            ) {
                                Text("Finish & review")
                            }
                        }
                    }
                }
            }
        } else if (draft == null) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Show it once", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Move through the task naturally. Cyclone records the useful before-and-after path, then asks you to review the proposed routine.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                if (v32AccessibilityEnabled(context)) {
                                    FollowMeLearnerRuntime.start(context)
                                    (context as? Activity)?.moveTaskToBack(true)
                                } else {
                                    message = "Enable or repair Phone control in Settings first."
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Text("Start Follow Me")
                        }
                        Text(
                            "Typed text, passwords, one-time codes, and sensitive fields are not stored.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (session != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ready to review", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(session.name, style = MaterialTheme.typography.titleMedium)
                            if (session.aiAnalysis.isNotBlank()) {
                                Text(
                                    session.aiAnalysis,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = {
                                    val existingId = session.optimizedAutomationId ?: session.copiedAutomationId
                                    draft = existingId?.let { AutomationRuntime.store.getAutomation(it) }
                                        ?: AutomationRuntime.store.listAutomations().firstOrNull {
                                            "[Follow Me session ${session.id}]" in it.description
                                        }
                                        ?: TeachingRoutineCompilerV292.compileAndSave(context, session)
                                    if (draft == null) {
                                        message = "There isn't enough reliable evidence yet. Try a shorter demonstration."
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) {
                                Text("Review proposed routine")
                            }
                        }
                    }
                }
            }
        }

        draft?.let { routine ->
            item {
                CycloneSectionTitle("Review routine")
            }
            item {
                CycloneSimpleCard(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = routine.name,
                        onValueChange = { draft = routine.copy(name = it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Routine name") },
                    )
                }
            }
            itemsIndexed(routine.steps, key = { _, step -> step.id }) { index, step ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(step.v32ReadableName(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            draft = routine.copy(steps = routine.steps.filterIndexed { i, _ -> i != index })
                        }) {
                            Text("Remove")
                        }
                    }
                }
            }
            item {
                Button(
                    enabled = routine.name.isNotBlank() && routine.steps.isNotEmpty(),
                    onClick = {
                        AutomationRuntime.store.saveAutomation(routine.copy(enabled = false))
                        message = "Saved. Open the routine to review its checks and turn it on."
                        draft = null
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text("Save routine")
                }
            }
        }

        if (message.isNotBlank()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
