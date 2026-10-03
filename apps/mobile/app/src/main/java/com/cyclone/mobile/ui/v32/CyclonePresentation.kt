package com.cyclone.mobile.ui.v32

import android.content.Context
import android.widget.ImageView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cyclone.mobile.runtime.background.*

/** Read-only projection: retain the canonical task, including every execution-plane identifier. */
data class UiTask(val source: WorkspaceTaskUi) {
    val id get() = source.taskId
    val active get() = source.phase !in setOf(TaskPhase.DONE, TaskPhase.FAILED, TaskPhase.STOPPED)
    val consumerStatus get() = when (source.phase) {
        TaskPhase.STARTING, TaskPhase.WORKING -> "Working"
        TaskPhase.PAUSED, TaskPhase.REVIEW, TaskPhase.HUMAN, TaskPhase.FAILED -> "Action Needed"
        TaskPhase.DONE -> "Done"
        TaskPhase.STOPPED -> "Stopped"
    }
    val subtitle get() = source.subtitle
    fun belongsToProfile(id: String): Boolean = source.workspaceId == id &&
        source.workspaceGeneration?.let { it >= 0 } == true && source.displayId == 0 &&
        (source.sessionId == null || source.sessionId == "default-foreground")
    fun open(context: Context) = context.startActivity(ViewProgressRouter.intent(context, source))
}

@Composable
fun CycloneAppIcon(packageName: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val drawable = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName.orEmpty()) }.getOrNull()
            ?: context.getDrawable(com.cyclone.mobile.R.drawable.ic_cyclone_mark_42)
    }
    AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { it.setImageDrawable(drawable) }, modifier = modifier.size(38.dp))
}
fun appLabel(context: Context, pkg: String): String = runCatching {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault("Other")

@Composable
fun CycloneTaskProgress(task: WorkspaceTaskUi, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ui = UiTask(task)
    CycloneSwipeTaskCard("task:${task.taskId}", canClear = !ui.active, onOpen = { ui.open(context) }, modifier = modifier) {
        CycloneGlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CycloneAppIcon(com.cyclone.mobile.ui.overlay.WorkingApp.forTask(task.taskId, task.packageName))
                    Column(Modifier.weight(1f)) {
                        Text(task.title, style = MaterialTheme.typography.titleSmall)
                        Text(ui.subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                    }
                    if (task.phase != TaskPhase.STOPPED) CycloneTaskStatusPill(task.taskVisualState())
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { ui.open(context) }) { Text(if (task.confirmation != null) "Review" else "View progress") }
                    // Layer 2 commands belong to its runtime, never the VD service.
                    if (runCatching { task.plane().kind == com.cyclone.mobile.runtime.session.SessionPlaneKind.SESSION_KERNEL_VD }.getOrDefault(false)) {
                        if (task.working) TextButton(onClick = { WorkspaceTasks.command(context, task, "pause") }) { Text("Pause") }
                        if (task.resumable && task.phase in setOf(TaskPhase.PAUSED, TaskPhase.HUMAN))
                            TextButton(onClick = { WorkspaceTasks.command(context, task, "resume") }) { Text("I'm Done") }
                        TextButton(onClick = { WorkspaceTasks.command(context, task, "cancel") }) { Text(if (ui.active) "Stop task" else "Close task") }
                    }
                }
            }
        }
    }
}
