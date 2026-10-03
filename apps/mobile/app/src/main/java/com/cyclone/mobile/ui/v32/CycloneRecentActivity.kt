package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.TaskPresentationProjector
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One Home "Recent activity" row. Consumer copy only; never raw model reasoning. */
internal data class RecentActivityItem(
    val taskId: String,
    val title: String,
    val app: String,
    val packageName: String,
    val state: CycloneTaskVisualState,
    val progress: Float?,
    val updatedAtMs: Long,
)

/**
 * Process-lifetime list of the tasks Cyclone ran recently, newest first.
 *
 * Deliberately in memory only: task goals can contain personal text, so nothing here is written to
 * disk, Brain or diagnostics. User-stopped tasks are dropped rather than shown as failures.
 */
internal object CycloneRecentActivity {
    const val LIMIT = 6

    private val mutableItems = MutableStateFlow<List<RecentActivityItem>>(emptyList())
    val items: StateFlow<List<RecentActivityItem>> = mutableItems.asStateFlow()

    fun record(task: WorkspaceTaskUi?, nowMs: Long = System.currentTimeMillis()) {
        if (task == null) return
        mutableItems.value = merge(mutableItems.value, task, nowMs)
    }

    internal fun merge(current: List<RecentActivityItem>, task: WorkspaceTaskUi, nowMs: Long): List<RecentActivityItem> {
        val rest = current.filterNot { it.taskId == task.taskId }
        if (task.phase == TaskPhase.STOPPED) return rest
        val snapshot = TaskPresentationProjector.project(task)
        val item = RecentActivityItem(
            taskId = task.taskId,
            title = snapshot.title.ifBlank { task.goal.trim() },
            app = snapshot.app,
            packageName = task.packageName,
            state = task.taskVisualState(),
            progress = snapshot.progressFraction,
            updatedAtMs = nowMs,
        )
        return (listOf(item) + rest).take(LIMIT)
    }

    /** "Completed · 2m ago", "In progress", "Needs you · 5m ago". */
    fun statusLine(item: RecentActivityItem, nowMs: Long = System.currentTimeMillis()): String {
        val label = when (item.state) {
            CycloneTaskVisualState.WORKING -> return "In progress"
            CycloneTaskVisualState.ACTION_NEEDED -> "Needs you"
            CycloneTaskVisualState.DONE -> "Completed"
            CycloneTaskVisualState.FAILED -> "Didn't finish"
        }
        return "$label · ${relativeAge(nowMs - item.updatedAtMs)}"
    }

    fun relativeAge(elapsedMs: Long): String {
        val minutes = (elapsedMs.coerceAtLeast(0L) / 60_000L)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 24 * 60 -> "${minutes / 60}h ago"
            else -> "${minutes / (24 * 60)}d ago"
        }
    }

    internal fun clearForTest() { mutableItems.value = emptyList() }
}
