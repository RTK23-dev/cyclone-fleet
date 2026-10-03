package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.runtime.background.PendingWorkspaceRequest
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.TaskConsumerState
import com.cyclone.mobile.runtime.background.TaskPresentationProjector

internal data class TaskGlassCardModel(
    val status: String,
    val taskLabel: String,
    val actionLabel: String,
    val packageName: String?,
    val actionContentDescription: String,
)

internal data class QueueGlassCardModel(
    val taskLabel: String,
    val status: String,
    val packageName: String?,
    val steerContentDescription: String,
    val stopContentDescription: String,
)

/** Pure projection. Overlay animation state never creates a task card. */
internal object TaskGlassPresentation {
    fun current(task: WorkspaceTaskUi?, resolvedApp: String? = task?.app): TaskGlassCardModel? {
        if (task == null || task.phase == TaskPhase.STOPPED) return null
        val snapshot = TaskPresentationProjector.project(
            if (resolvedApp.isNullOrBlank() || resolvedApp == task.app) task else task.copy(app = resolvedApp),
        )
        val status = when (snapshot.state) {
            TaskConsumerState.WORKING -> "Working"
            TaskConsumerState.ACTION_NEEDED -> "Action Needed"
            TaskConsumerState.NEEDS_SECRET -> "Needs Secret"
            TaskConsumerState.DONE -> "Done"
            TaskConsumerState.FAILED -> "Couldn't finish"
        }
        val action = when (snapshot.state) {
            TaskConsumerState.DONE -> "View result"
            TaskConsumerState.ACTION_NEEDED, TaskConsumerState.NEEDS_SECRET, TaskConsumerState.FAILED -> "View options"
            TaskConsumerState.WORKING -> "View progress"
        }
        return TaskGlassCardModel(
            status = status,
            taskLabel = snapshot.title,
            actionLabel = action,
            packageName = snapshot.packageName.takeIf(String::isNotBlank),
            actionContentDescription = "$action for ${snapshot.title}",
        )
    }

    fun queued(
        request: PendingWorkspaceRequest,
        appLabel: String? = request.targetAppLabel,
        packageName: String? = request.targetPackageName,
        status: String = "Queued",
    ): QueueGlassCardModel {
        val label = TaskHumanizer.humanize(request.goal, appLabel)
        return QueueGlassCardModel(
            taskLabel = label,
            status = status,
            packageName = packageName?.takeIf(String::isNotBlank),
            steerContentDescription = "Steer $label",
            stopContentDescription = "Stop queued task $label",
        )
    }
}

/** Deterministic, bounded task naming. It never spends another model call. */
internal object TaskHumanizer {
    private val knownApps = listOf(
        "google chrome" to "Chrome",
        "chrome" to "Chrome",
        "instagram" to "Instagram",
        "settings" to "Settings",
    )

    fun humanize(goal: String, resolvedApp: String? = null): String {
        val clean = goal.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return "Phone task"
        val lower = clean.lowercase()
        val app = resolvedApp?.trim()?.takeIf { it.isNotBlank() && !it.equals("Other", true) }
            ?: knownApps.firstOrNull { (needle, _) -> Regex("\\b${Regex.escape(needle)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) }?.second

        if ("battery" in lower && ("setting" in lower || app.equals("Settings", true))) {
            return "Checking battery settings"
        }
        if (Regex("\\b(logged? ?in|log ?in|login|signed? ?in|sign ?in)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            return "Checking ${app ?: "app"} login status"
        }
        if (app != null && Regex("\\b(open|launch|start)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            return "Opening $app"
        }
        if (app != null && Regex("\\b(check|verify|see|review)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            return "Checking $app"
        }
        if (app != null && Regex("\\b(find|search|look for)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            return "Searching $app"
        }

        // Unknown requests may contain typed values or credentials. Never echo them into status.
        return if (app != null) "Task in $app" else "Phone task"
    }

    private fun shorten(value: String, limit: Int): String {
        if (value.length <= limit) return value
        val prefix = value.take(limit + 1)
        val boundary = prefix.lastIndexOf(' ').takeIf { it >= limit / 2 } ?: limit
        return value.take(boundary).trimEnd(' ', '.', ',', ';', ':') + "…"
    }

    private fun sentenceCase(value: String): String = value.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase() else char.toString()
    }
}
