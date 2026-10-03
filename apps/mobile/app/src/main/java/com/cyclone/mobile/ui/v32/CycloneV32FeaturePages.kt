package com.cyclone.mobile.ui.v32

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.FollowMeLearnerRuntime
import com.cyclone.mobile.applearner.LearnerSessionState
import com.cyclone.mobile.applearner.discardFollowMeSession
import com.cyclone.mobile.debug.PageDebugSandboxV293
import com.cyclone.mobile.guided.RoutineTeachingRuntime
import com.cyclone.mobile.guided.TeachingGestureEvidenceV292

@Composable
internal fun V32TeachPage(context: Context, refreshTick: Int) {
    val follow = FollowMeLearnerRuntime.progress()
    val appProgress = AppLearnerRuntime.progress()
    val learnedApps = AppLearnerRuntime.learnedApps()
    val gestureCount = follow.teachingSessionId?.let { TeachingGestureEvidenceV292.list(context, it).size } ?: 0

    LazyColumn(contentPadding = cyclonePageInsets(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { CyclonePageIntro("Show it once", "Teach Cyclone", "Use your phone normally. Cyclone turns the useful path into reusable knowledge.") }
        item {
            CycloneHeroCard(
                title = if (follow.active) if (follow.paused) "Teaching paused" else "Learning with you" else "Follow Me",
                body = if (follow.active) follow.currentApp.ifBlank { follow.message } else "Tap, swipe and navigate naturally while Cyclone learns the before-and-after path.",
                icon = Icons.Rounded.Visibility,
                tone = CyclonePastel.MINT,
            ) {
                if (follow.active) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        V32SmallMetric("Pages", follow.screensSeen, Modifier.weight(1f))
                        V32SmallMetric("Actions", follow.actionsSeen, Modifier.weight(1f))
                        V32SmallMetric("Swipes", gestureCount, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { if (follow.paused) FollowMeLearnerRuntime.resume() else FollowMeLearnerRuntime.pause() }, modifier = Modifier.weight(1f)) {
                            Icon(if (follow.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null); Spacer(Modifier.size(5.dp)); Text(if (follow.paused) "Resume" else "Pause")
                        }
                        Button(onClick = { FollowMeLearnerRuntime.stop() }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Stop, null); Spacer(Modifier.size(5.dp)); Text("Finish") }
                    }
                    OutlinedButton(onClick = { discardFollowMeSession(context) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Close, null); Spacer(Modifier.size(5.dp)); Text("Discard") }
                } else {
                    Button(onClick = {
                        if (appProgress.state in setOf(LearnerSessionState.STARTING, LearnerSessionState.LEARNING, LearnerSessionState.PAUSED, LearnerSessionState.WAITING_FOR_HUMAN)) AppLearnerRuntime.stop()
                        FollowMeLearnerRuntime.start(context)
                        Toast.makeText(context, "Follow Me started", Toast.LENGTH_SHORT).show()
                        (context as? Activity)?.moveTaskToBack(true)
                    }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Visibility, null); Spacer(Modifier.size(6.dp)); Text("Start Follow Me") }
                }
                Text("Typed text, passwords, OTPs and sensitive fields are not stored.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { CycloneSectionTitle("Other ways to teach") }
        item {
            CycloneSimpleCard {
                V32FeatureRow(Icons.Rounded.Gesture, "Place exact steps", "Add Tap, Hold, Swipe, Check, Wait, Back and Home steps.")
                Button(onClick = {
                    val service = CycloneAccessibilityService.instance
                    if (service == null) Toast.makeText(context, "Enable phone control first", Toast.LENGTH_LONG).show()
                    else { service.showGuidedRecorderOverlay(); (context as? Activity)?.moveTaskToBack(true) }
                }, enabled = !follow.active, modifier = Modifier.fillMaxWidth()) { Text("Open manual teacher") }
            }
        }
        item {
            CycloneSimpleCard {
                V32FeatureRow(Icons.Rounded.Memory, "Understand one page", "Freeze the current page and inspect what Android and Cyclone understand.")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        val service = CycloneAccessibilityService.instance
                        if (service == null) Toast.makeText(context, "Enable phone control first", Toast.LENGTH_LONG).show()
                        else { PageDebugSandboxV293.start(service); (context as? Activity)?.moveTaskToBack(true) }
                    }, enabled = !follow.active, modifier = Modifier.weight(1f)) { Text("Capture") }
                    OutlinedButton(onClick = { PageDebugSandboxV293.launchReport(context) }, modifier = Modifier.weight(1f)) { Text("Inspect") }
                }
            }
        }
        item { OutlinedButton(onClick = { RoutineTeachingRuntime.launchReport(context, null) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.History, null); Spacer(Modifier.size(6.dp)); Text("Teaching history") } }
        item { CycloneSectionTitle("Apps Cyclone knows") }
        if (learnedApps.isEmpty()) item { V32EmptyCard("No learned apps yet", "Start Follow Me and move through one useful task.") }
        else items(learnedApps.take(20), key = { it.packageName }) { app ->
            CycloneSimpleCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(app.label, fontWeight = FontWeight.Bold)
                        Text("${(app.confidence * 100).toInt()}% confidence · ${app.knowledgeState.name.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun V32FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) { Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) } }
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun V32SmallMetric(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(label, style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun V32EmptyCard(title: String, body: String) {
    CycloneSimpleCard { Text(title, fontWeight = FontWeight.Bold); Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
