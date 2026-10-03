package com.cyclone.mobile.guided

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.TeachingCorrectionAiNote
import com.cyclone.mobile.ai.TeachingCorrectionAssistantV292
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.ui.CycloneTheme
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RoutineTeachingHistoryActivity : ComponentActivity() {
    companion object { const val EXTRA_SESSION_ID = "routineTeachingSessionId" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoutineTeachingRuntime.initialize(this)
        AdaptiveBrainRuntime.initialize(this)
        val initial = intent.getStringExtra(EXTRA_SESSION_ID)
        setContent { CycloneTheme { TeachingHistoryScreen(initial, onClose = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeachingHistoryScreen(initialSessionId: String?, onClose: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf(initialSessionId) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(900); refresh++ } }
    val sessions = RoutineTeachingRuntime.listSessions(100 + refresh * 0)
    val selected = selectedId?.let(RoutineTeachingRuntime::load)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (selected == null) "Teaching history" else selected.name, fontWeight = FontWeight.SemiBold)
                        Text("Cyclone 2.9.2 · evidence → Brain → reusable routine", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    OutlinedButton(onClick = { if (selected != null) selectedId = null else onClose() }) {
                        Icon(Icons.Rounded.ArrowBack, null); Text(if (selected != null) "History" else "Cyclone")
                    }
                },
            )
        },
    ) { padding ->
        if (selected == null) TeachingSessionList(sessions, Modifier.padding(padding)) { selectedId = it }
        else TeachingSessionDetail(selected, Modifier.padding(padding)) { refresh++ }
    }
}

@Composable
private fun TeachingSessionList(sessions: List<RoutineTeachingSession>, modifier: Modifier, onOpen: (String) -> Unit) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Rounded.History, null, modifier = Modifier.size(34.dp))
                    Text("Everything you taught Cyclone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("2.9.2 keeps the demonstration as evidence, consolidates reusable knowledge into the existing Brain, and can turn ordinary Follow Me actions into a disabled-for-review automation instead of leaving them as a dead report.")
                }
            }
        }
        if (sessions.isEmpty()) item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("No teaching rounds yet", fontWeight = FontWeight.SemiBold)
                    Text("Start Follow Me from Teach. Swipe, tap and navigate naturally, then use Stop & review.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(sessions, key = { it.id }) { session ->
            Card(onClick = { onOpen(session.id) }, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Smartphone, null); Spacer(Modifier.size(8.dp))
                        Text(session.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(session.status.lowercase(), style = MaterialTheme.typography.labelSmall)
                    }
                    Text("${session.pagesSeen} pages · ${session.actionsSeen} actions · ${session.pathsLearned} paths · ${session.steps.size} evidence items", style = MaterialTheme.typography.bodySmall)
                    if (session.aiAnalysis.isNotBlank()) Text("Brain consolidation available", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(session.modelId.substringAfter('/').ifBlank { "local / no model" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatTime(session.startedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TeachingSessionDetail(session: RoutineTeachingSession, modifier: Modifier, onChanged: () -> Unit) {
    val context = LocalContext.current
    val gestures = remember(session.id, session.endedAt) { TeachingGestureEvidenceV292.list(context, session.id) }
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(34.dp)); Spacer(Modifier.size(9.dp))
                        Column {
                            Text("Learning report", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(formatTime(session.startedAt), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(session.summary.ifBlank { "Cyclone is still collecting evidence for this teaching round." })
                    Text("Model: ${session.modelId}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("Pages", session.pagesSeen, Modifier.weight(1f))
                Metric("Actions", session.actionsSeen, Modifier.weight(1f))
                Metric("Paths", session.pathsLearned + gestures.size, Modifier.weight(1f))
            }
        }
        if (gestures.isNotEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.size(6.dp)); Text("Directional gestures Cyclone can reuse", fontWeight = FontWeight.Bold) }
                    gestures.take(14).forEach { g -> Text("• Swipe ${g.direction}: ${g.fromTitle.ifBlank { "page" }} → ${g.toTitle.ifBlank { "page" }}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (session.aiAnalysis.isNotBlank()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.size(6.dp)); Text("Cyclone consolidation", fontWeight = FontWeight.Bold) }
                    Text(session.aiAnalysis)
                }
            }
        }
        if (session.copiedAutomationId != null || session.optimizedAutomationId != null) item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.size(6.dp)); Text("Workflow output", fontWeight = FontWeight.Bold) }
                    session.copiedAutomationId?.let { Text("Deterministic copy: $it", style = MaterialTheme.typography.bodySmall) }
                    session.optimizedAutomationId?.let { Text("Model-optimized proposal: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Memory, null); Spacer(Modifier.size(7.dp))
                Column {
                    Text("Teaching timeline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Corrections now become context-aware AI notes. Cyclone checks this step, nearby steps and the saved App Graph, then tells you exactly what semantic Brain updates it applied.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(session.steps, key = { it.id }) { step -> TeachingStepCard(session.id, session.modelId, step, onChanged) }
    }
}

@Composable
private fun TeachingStepCard(sessionId: String, modelId: String, step: RoutineTeachingStep, onChanged: () -> Unit) {
    val context = LocalContext.current
    var note by remember(step.id, step.note) { mutableStateOf(step.note) }
    var aiState by remember(step.id) { mutableStateOf<String?>(null) }
    var aiResult by remember(step.id) { mutableStateOf<TeachingCorrectionAiNote?>(TeachingCorrectionAssistantV292.latest(context, sessionId, step.id)) }
    val imagePath = listOf(step.afterScreenshotPath, step.screenshotPath, step.beforeScreenshotPath).firstOrNull { !it.isNullOrBlank() && File(it).exists() }
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${step.index}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                Spacer(Modifier.size(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(step.title, fontWeight = FontWeight.SemiBold)
                    Text(step.pageTitle ?: step.packageName.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(step.replayStrategy.replace('_', ' ').lowercase(), style = MaterialTheme.typography.labelSmall)
            }
            Text(step.summary, style = MaterialTheme.typography.bodySmall)
            step.semanticSignal?.let { Text("Native signal: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
            if (step.demonstratedDurationMs != null) {
                val optimized = step.optimizedDurationMs?.let { if (it == 0L) "native / instant" else "${it}ms" } ?: "learned condition"
                Text("Demonstrated ${step.demonstratedDurationMs}ms → preferred replay: $optimized", style = MaterialTheme.typography.labelSmall)
            }
            if (imagePath != null) {
                val bitmap = remember(imagePath) { runCatching { BitmapFactory.decodeFile(imagePath) }.getOrNull() }
                if (bitmap != null) Image(bitmap.asImageBitmap(), "Screenshot for step ${step.index}", Modifier.fillMaxWidth().height(260.dp), contentScale = ContentScale.Fit)
            } else Text("Screenshot pending or unavailable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
                label = { Text("Correction / what Cyclone should understand") },
                leadingIcon = { Icon(Icons.Rounded.EditNote, null) },
                placeholder = { Text("Example: This swipe opens the next menu page; use it before tapping Orders.") },
            )
            Button(
                onClick = {
                    val clean = note.trim()
                    RoutineTeachingRuntime.updateNote(sessionId, step.id, clean)
                    aiState = "Reviewing saved context and updating Brain…"
                    TeachingCorrectionAssistantV292.applyAsync(context, sessionId, step.id, clean, modelId) { result ->
                        result.onSuccess { ai -> aiResult = ai; aiState = "Brain update complete" }
                            .onFailure { aiState = it.message ?: "Could not analyze correction" }
                        onChanged()
                    }
                },
                enabled = note.isNotBlank() && note.trim() != step.note.trim() && aiState?.startsWith("Reviewing") != true,
                modifier = Modifier.fillMaxWidth(),
            ) { Icon(Icons.Rounded.Save, null); Spacer(Modifier.size(5.dp)); Text("Save correction & update Brain") }

            aiState?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            aiResult?.let { ai ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.size(5.dp)); Text("AI note", fontWeight = FontWeight.Bold) }
                        Text(ai.aiNote, style = MaterialTheme.typography.bodySmall)
                        if (ai.updates.isNotEmpty()) {
                            Text("Updates applied", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                            ai.updates.take(5).forEach { Text("• $it", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
            Text("The AI note is an evidence-based summary, not hidden model chain-of-thought. It can add semantic Brain facts, but it cannot silently alter executable confidence or selectors.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Metric(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatTime(time: Long): String = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(time))
