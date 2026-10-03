package com.cyclone.mobile.runtime.background

/** Both notification surfaces use the task's state and the same service commands as in-app controls. */
object TaskNotificationProjection {
    fun title(task: WorkspaceTaskUi): String = TaskPresentationProjector.project(task).title
    fun body(task: WorkspaceTaskUi): String {
        val snapshot = TaskPresentationProjector.project(task)
        return (if (task.working) snapshot.currentMilestone else snapshot.supportingCopy)
            ?: TaskConsumerCopy.subtitle(task)
    }
    /** No invented percentage when the agent has no stable plan. */
    fun progressPercent(task: WorkspaceTaskUi): Int? = if (!task.working) null else
        TaskPresentationProjector.project(task).progressFraction?.let { (it * 100).toInt().coerceIn(0, 100) }
    /** [plane] is where a Mind mission works now ("screen" / "background"), when planes apply to it (plan 25). */
    fun actions(task: WorkspaceTaskUi, plane: String? = null, backgroundAvailable: Boolean = true, waiting: Boolean = false): List<Pair<String, String>> = buildList {
        // Plan 26: a mission waiting for an app the owner is using can start now instead.
        if (waiting && task.working) add("start_now" to "Start now")
        if (task.interruption?.canTakeOver == true) add("handoff" to "Take Over")
        if (task.interruption?.canAutofill == true) add("autofill" to "Autofill")
        if (task.interruption?.canResumeAfterHuman == true) add("resume" to "I'm Done")
        if (task.confirmation != null && task.interruption?.confirmationToken == task.confirmation.token)
            add("confirm" to task.confirmation.button)
        if (task.working && task.interruption == null && !waiting) when {
            plane == "background" -> add("to_screen" to "Show on screen")
            plane == "screen" && backgroundAvailable -> add("to_background" to "Work in background")
        }
        if (task.working) add("cancel" to "Stop task")
    }.take(3)
}
