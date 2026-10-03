package com.cyclone.mobile.ui.v32

internal enum class TaskCardSwipeTarget { CLOSED, OPEN_BUTTON, CLEAR_BUTTON, OPEN, CLEAR }

/** Physical directions match notification cards: right opens, left clears only old results. */
internal object TaskCardSwipePolicy {
    fun settle(offset: Float, width: Float, actionWidth: Float, canClear: Boolean): TaskCardSwipeTarget {
        if (width <= 0f || actionWidth <= 0f) return TaskCardSwipeTarget.CLOSED
        val full = maxOf(width * .72f, actionWidth * 1.5f)
        return when {
            offset >= full -> TaskCardSwipeTarget.OPEN
            canClear && offset <= -full -> TaskCardSwipeTarget.CLEAR
            offset >= actionWidth * .45f -> TaskCardSwipeTarget.OPEN_BUTTON
            canClear && offset <= -actionWidth * .45f -> TaskCardSwipeTarget.CLEAR_BUTTON
            else -> TaskCardSwipeTarget.CLOSED
        }
    }
}

/** The queue only shares the surface after the current card has actually gone away. */
internal fun taskCardVisible(
    task: com.cyclone.mobile.runtime.background.WorkspaceTaskUi?,
    cleared: Set<String>,
): Boolean = task != null && task.phase != com.cyclone.mobile.runtime.background.TaskPhase.STOPPED &&
    (UiTask(task).active || "task:${task.taskId}" !in cleared)
