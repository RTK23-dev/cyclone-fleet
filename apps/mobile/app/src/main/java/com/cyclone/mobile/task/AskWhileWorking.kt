package com.cyclone.mobile.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plan 38: what the Ask bar offers when the owner sends typed text while Cyclone works. The owner chooses; Cyclone only
 * highlights the likely row (D1). Every choice becomes a Task Kit command for the task being viewed (D5, D7).
 */
object AskWhileWorking {
    enum class Choice { ANSWER, STEER, QUEUE, PARALLEL }

    data class Option(
        val choice: Choice,
        val title: String,
        val subtitle: String,
        val enabled: Boolean = true,
        /** Why a greyed row can't run now; shown when it is tapped. */
        val reason: String? = null,
        val suggested: Boolean = false,
    )

    /**
     * The rows, top to bottom. [question] is the viewed task's open question (Answer replaces Steer when there is one),
     * [parallelBlocker] why a parallel task can't start now (null when it can), [looksNew] whether the text reads like a
     * separate task (then Queue is highlighted; otherwise the first row).
     */
    fun options(question: String?, parallelBlocker: String?, looksNew: Boolean): List<Option> {
        val first = if (question != null) Option(Choice.ANSWER, "Answer", question.take(80), suggested = !looksNew)
            else Option(Choice.STEER, "Steer", "Change this task", suggested = !looksNew)
        return listOf(
            first,
            Option(Choice.QUEUE, "Queue", "Do it after this", suggested = looksNew),
            Option(Choice.PARALLEL, "Parallel", if (parallelBlocker == null) "Do it at the same time" else parallelBlocker,
                enabled = parallelBlocker == null, reason = parallelBlocker),
        )
    }

    fun command(choice: Choice, text: String): TaskCommand = when (choice) {
        Choice.ANSWER -> TaskCommand.Reply(text)
        Choice.STEER -> TaskCommand.Steer(text)
        Choice.QUEUE -> TaskCommand.Queue(text)
        Choice.PARALLEL -> TaskCommand.Parallel(text)
    }

    /** The task the owner is viewing in the Ask page (a task behind the screen when its row is opened); null = front. */
    private val viewing = MutableStateFlow<String?>(null)
    val viewed: StateFlow<String?> = viewing
    fun view(taskId: String?) { viewing.value = taskId }
}
