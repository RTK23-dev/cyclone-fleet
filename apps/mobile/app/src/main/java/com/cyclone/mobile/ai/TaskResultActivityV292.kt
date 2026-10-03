package com.cyclone.mobile.ai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cyclone.mobile.ui.CycloneTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TaskResultNotifierV292 {
    private const val CHANNEL_ID = "cyclone_ai_task_results_v292"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Cyclone AI task results",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Final results and decision timelines for Cyclone phone tasks"
                },
            )
        }
    }

    fun notify(context: Context, sessionId: String, ok: Boolean, result: String, consolidation: String?) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val intent = Intent(context, TaskResultActivityV292::class.java)
            .putExtra(TaskResultActivityV292.EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = consolidation?.takeIf(String::isNotBlank)?.take(180)
            ?: TracePrivacy.clean(result).take(180).ifBlank { if (ok) "Task completed." else "Task failed." }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (ok) "Cyclone task completed" else "Cyclone task failed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n\nTap to inspect the decision and evidence timeline."))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        manager.notify(sessionId.hashCode(), notification)
    }
}

class TaskResultActivityV292 : ComponentActivity() {
    companion object {
        const val EXTRA_SESSION_ID = "cycloneAiTraceSessionId"
        const val EXTRA_EXPORT = "cycloneAiTraceExport"
    }

    private var exportSessionId: String? = null
    private val createDiagnosticDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val sessionId = exportSessionId
        exportSessionId = null
        if (uri == null || sessionId == null) return@registerForActivityResult
        val result = AgentRunDiagnosticV39.writeToUriDetailed(this, sessionId, uri)
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentTraceRuntime.initialize(this)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val exportImmediately = intent.getBooleanExtra(EXTRA_EXPORT, false)
        setContent {
            CycloneTheme {
                TaskResultScreenV292(
                    sessionId = sessionId,
                    onClose = { finish() },
                    onDownload = { filename ->
                        exportSessionId = sessionId
                        createDiagnosticDocument.launch(filename)
                    },
                )
            }
        }
        if (exportImmediately && sessionId.isNotBlank()) {
            val session = AgentTraceRuntime.store.session(sessionId)
            exportSessionId = sessionId
            createDiagnosticDocument.launch(
                session?.let(AgentRunDiagnosticV39::suggestedFilename) ?: "Cyclone-run.txt",
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TaskResultScreenV292(sessionId: String, onClose: () -> Unit, onDownload: (String) -> Unit) {
    val session = AgentTraceRuntime.store.session(sessionId)
    val events = if (session == null) emptyList() else AgentTraceRuntime.store.events(session.id)
    val status = session?.status
    val ok = status == "COMPLETED"
    val activeOrWaiting = status in setOf("RUNNING", "SUSPENDED", "HUMAN_OR_GATE")
    val visible = events.filter { it.kind !in setOf("MODEL", "OBSERVE") }
    val metrics = AgentRunDiagnosticV39.metrics(events)
    val liveTurns = maxOf(
        session?.decisions ?: 0,
        events.count { it.kind == "PLAN" && it.code == "model.page_decision" },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Cyclone task result", fontWeight = FontWeight.SemiBold)
                        Text("3.9.2 decision & evidence timeline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                val container = when {
                    ok -> MaterialTheme.colorScheme.secondaryContainer
                    activeOrWaiting -> MaterialTheme.colorScheme.tertiaryContainer
                    else -> MaterialTheme.colorScheme.errorContainer
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = container),
                    shape = RoundedCornerShape(26.dp),
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                when {
                                    ok -> Icons.Rounded.CheckCircle
                                    activeOrWaiting -> Icons.Rounded.AutoAwesome
                                    else -> Icons.Rounded.Error
                                },
                                null,
                                modifier = Modifier.size(34.dp),
                            )
                            Spacer(Modifier.size(9.dp))
                            Text(traceStatusTitle(status), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                        Text(session?.goal ?: "Task history unavailable")
                        session?.result?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        session?.endedAt?.let { Text(formatTraceTime(it), style = MaterialTheme.typography.labelSmall) }
                        if (session != null) {
                            Text(
                                "$liveTurns turns · ${metrics.toolCalls} tools · ${metrics.failures} failures · ${metrics.recoveries} recovery events",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = { onDownload(AgentRunDiagnosticV39.suggestedFilename(session)) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.Download, null)
                                Spacer(Modifier.size(7.dp))
                                Text("Download diagnostic log")
                            }
                        }
                    }
                }
            }
            item {
                Card(shape = RoundedCornerShape(20.dp)) {
                    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null)
                        Spacer(Modifier.size(8.dp))
                        Column {
                            Text("What you are seeing", fontWeight = FontWeight.SemiBold)
                            Text("Cyclone records compact model-visible context, explicit decisions, requested tools, Android results, verification and recovery evidence. Provider-private hidden chain-of-thought and secrets are never recorded.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(visible, key = { it.id }) { event -> TraceEventCardV292(event) }
            item {
                val learning = visible.filter { it.kind == "LEARNING" || it.kind.startsWith("LEARNING_") }.lastOrNull()
                if (learning != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(20.dp)) {
                        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.Memory, null)
                            Spacer(Modifier.size(8.dp))
                            Column {
                                Text("Compiled into Cyclone Brain", fontWeight = FontWeight.Bold)
                                Text(learning.displayText, style = MaterialTheme.typography.bodySmall)
                                learning.detail?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceEventCardV292(event: AiTraceEvent) {
    val indicator = when (event.ok) {
        true -> Color(0xFF2E9B63)
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.primary
    }
    val title = when (event.kind) {
        "PAGE" -> "UI understood"
        "BRAIN" -> "Brain recall"
        "MODEL_CONTEXT" -> "Model saw"
        "MODEL_DECISION", "DECISION" -> "Model decision"
        "TOOL_REQUESTED", "ACTION_REQUESTED" -> "Tool requested"
        "TOOL_RESULT", "ANDROID_EXECUTION" -> "Tool result"
        "REPLAY" -> "Learned route"
        "RESULT" -> "Verified"
        "RECOVERY", "RECOVERY_SELECTED" -> "Recovery"
        "FREE_MODE_ENTER" -> "Free mode"
        "FREE_MODE_EXIT" -> "Structured mode"
        "VISION", "VISION_ESCALATION" -> "Visual check"
        "BOUNDARY", "GATE", "GATE_SUSPEND" -> "Needs you"
        "GATE_RESUME" -> "Resumed"
        "LEARNING", "LEARNING_ACCEPTED", "LEARNING_REJECTED" -> "Learning"
        "DONE" -> "Completed"
        "STOPPED" -> "Failed / stopped"
        else -> event.kind.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
    }
    Card(shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 4.dp).size(10.dp).background(indicator, CircleShape))
            Spacer(Modifier.size(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(formatTraceClock(event.timestampMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(event.displayText)
                event.code?.takeIf { it.isNotBlank() && event.ok == false }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                event.detail?.takeIf { it.isNotBlank() && event.kind in DETAIL_KINDS }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val DETAIL_KINDS = setOf(
    "MODEL_CONTEXT", "MODEL_DECISION", "TOOL_REQUESTED", "ACTION_REQUESTED", "TOOL_RESULT", "ANDROID_EXECUTION", "VERIFICATION",
    "RECOVERY", "RECOVERY_SELECTED", "FREE_MODE_ENTER", "FREE_MODE_EXIT", "BOUNDARY", "GATE", "GATE_SUSPEND", "LEARNING", "LEARNING_ACCEPTED",
    "LEARNING_REJECTED", "RESULT",
)

private fun traceStatusTitle(status: String?): String = when (status) {
    "COMPLETED" -> "Task completed"
    "RUNNING" -> "Task running"
    "SUSPENDED", "HUMAN_OR_GATE" -> "Waiting for you"
    "CANCELLED" -> "Task cancelled"
    "NON_CONVERGENT", "NON_CONVERGENCE" -> "Task did not converge"
    else -> "Task failed"
}

private fun formatTraceTime(time: Long) = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.getDefault()).format(Date(time))
private fun formatTraceClock(time: Long) = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
