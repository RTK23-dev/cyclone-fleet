package com.cyclone.mobile.owner

import com.cyclone.mobile.mind.mission.OwnerField
import com.cyclone.mobile.mind.mission.OwnerRequest
import com.cyclone.mobile.mind.mission.OwnerRequestKind
import com.cyclone.mobile.runtime.background.TaskInterruptionKind
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskEngine
import com.cyclone.mobile.task.TaskEngines

/**
 * Owner Moments: every time a task needs its owner, whatever engine runs it, described one way.
 *
 * A moment is never stored; it is derived from the single source of truth: a Cyclone Mind mission's open inbox
 * request, or the task state the classic agent and background workspaces already keep (secure-input wall,
 * confirmation token, handed-over phase). Derived means no stale or duplicate card can exist. Every button on a
 * moment is a [TaskCommand] that goes through Task Kit to the engine that owns the task.
 */
enum class MomentKind { QUESTION, VALUES, APPROVAL, SECRET, HANDOVER }

/** A button on a moment. [needsInput] commands (Reply, Fill) are completed with what the owner typed. */
data class MomentAction(val command: TaskCommand, val label: String, val primary: Boolean = false) {
    val needsInput: Boolean get() = command is TaskCommand.Reply || command is TaskCommand.Fill
}

data class OwnerMoment(
    val taskId: String,
    val engine: TaskEngine,
    val kind: MomentKind,
    val text: String,
    val actions: List<MomentAction>,
    val choices: List<String> = emptyList(),
    val fields: List<OwnerField> = emptyList(),
    /** The Mind inbox request behind this moment; null for moments derived from task state. */
    val requestId: String? = null,
    /** An approval's GATE class ("send", "pay", …) when known. */
    val gate: String? = null,
    /** A send approval's exact message: what is approved is what is sent. */
    val send: com.cyclone.mobile.mind.mission.OwnerSend? = null,
) {
    val title: String get() = when (kind) {
        MomentKind.VALUES -> "Cyclone needs a few details"
        MomentKind.QUESTION -> "Cyclone asks"
        MomentKind.APPROVAL -> "Approve this?"
        MomentKind.SECRET -> "Secure input"
        MomentKind.HANDOVER -> "Your turn"
    }
    /** The action for the card's close button: "not now", which never leaves the task waiting on nothing. */
    val dismissal: MomentAction? get() = when (kind) {
        MomentKind.QUESTION, MomentKind.VALUES -> MomentAction(TaskCommand.Decline, "Not now")
        else -> null
    }
}

/** A notification button for a moment; [reply] buttons carry an inline text box whose text completes the command. */
data class NotificationAction(val command: TaskCommand, val label: String, val reply: Boolean = false)

object OwnerMoments {
    private val OWNER_PHASES = setOf(TaskPhase.REVIEW, TaskPhase.HUMAN, TaskPhase.PAUSED)

    /**
     * The moment the owner should see for [task] right now, or null. [request] is the Mind inbox's open request;
     * [gatePending] is true while the overlay approval card waits on a classic-agent action.
     */
    fun project(task: WorkspaceTaskUi?, request: OwnerRequest?, gatePending: Boolean = false): OwnerMoment? {
        task ?: return null
        if (task.phase in setOf(TaskPhase.DONE, TaskPhase.FAILED, TaskPhase.STOPPED)) return null
        val engine = TaskEngines.of(task)
        if (engine == TaskEngine.MIND) {
            val open = request?.takeIf { task.taskId == TaskEngines.MIND_TASK_PREFIX + it.missionId }
            if (open != null) return fromRequest(task.taskId, open)
            if (task.phase == TaskPhase.HUMAN) return handover(task, engine)
            return null
        }
        val interruption = task.interruption
        return when {
            interruption?.kind == TaskInterruptionKind.NEEDS_SECRET ->
                OwnerMoment(task.taskId, engine, MomentKind.SECRET, interruption.prompt.ifBlank { "Secure input is required to continue." },
                    listOf(MomentAction(TaskCommand.Stop, "Stop task")))
            // Confirmation tokens are the background workspace's; only its engine can redeem them.
            task.confirmation != null && engine == TaskEngine.BACKGROUND_WORKSPACE -> OwnerMoment(task.taskId, engine, MomentKind.APPROVAL, task.confirmation.explanation, listOf(
                MomentAction(TaskCommand.Stop, "Don't do it"),
                MomentAction(TaskCommand.Confirm(task.confirmation.token), task.confirmation.button, primary = true),
            ))
            gatePending && engine == TaskEngine.CLASSIC_FOREGROUND -> OwnerMoment(task.taskId, engine, MomentKind.APPROVAL,
                task.message.ifBlank { "Cyclone stopped before a consequential action that needs your confirmation." }, listOf(
                    MomentAction(TaskCommand.Decline, "Stop"),
                    MomentAction(TaskCommand.Approve, "Approve", primary = true),
                ))
            task.phase in OWNER_PHASES && (task.phase == TaskPhase.HUMAN || interruption?.canResumeAfterHuman == true) -> handover(task, engine)
            task.phase in OWNER_PHASES && interruption?.canTakeOver == true -> OwnerMoment(task.taskId, engine, MomentKind.HANDOVER,
                interruption.prompt.ifBlank { task.message }, buildList {
                    if (interruption.canAutofill) add(MomentAction(TaskCommand.Autofill, "Autofill"))
                    add(MomentAction(TaskCommand.TakeOver, "Take over", primary = true))
                })
            else -> null
        }
    }

    private fun handover(task: WorkspaceTaskUi, engine: TaskEngine): OwnerMoment = OwnerMoment(
        task.taskId, engine, MomentKind.HANDOVER,
        task.interruption?.prompt?.takeIf { it.isNotBlank() } ?: task.message.ifBlank { "Finish on the phone, then tap I'm done." },
        buildList {
            if (task.interruption?.canAutofill == true && engine != TaskEngine.MIND) add(MomentAction(TaskCommand.Autofill, "Autofill"))
            add(MomentAction(TaskCommand.Done, "I'm done", primary = true))
        },
    )

    private fun fromRequest(taskId: String, request: OwnerRequest): OwnerMoment = when (request.kind) {
        OwnerRequestKind.QUESTION -> OwnerMoment(taskId, TaskEngine.MIND, MomentKind.QUESTION, request.text, listOf(
            MomentAction(TaskCommand.TakeOver, "Take over"),
            MomentAction(TaskCommand.Reply(""), "Send", primary = true),
        ), choices = request.choices, requestId = request.id)
        OwnerRequestKind.VALUES -> OwnerMoment(taskId, TaskEngine.MIND, MomentKind.VALUES, request.text, listOf(
            MomentAction(TaskCommand.TakeOver, "Take over"),
            MomentAction(TaskCommand.Fill(emptyMap(), false), "Fill in", primary = true),
        ), fields = request.fields, requestId = request.id)
        OwnerRequestKind.APPROVAL -> OwnerMoment(taskId, TaskEngine.MIND, MomentKind.APPROVAL, request.text, listOf(
            MomentAction(TaskCommand.Decline, "Decline"),
            MomentAction(TaskCommand.Approve, "Approve", primary = true),
        ), requestId = request.id, gate = request.gate, send = request.send)
        OwnerRequestKind.SECRET -> OwnerMoment(taskId, TaskEngine.MIND, MomentKind.SECRET,
            request.text.ifBlank { "Use the Secrets Card on screen." }, emptyList(), requestId = request.id)
        OwnerRequestKind.CONTROL -> OwnerMoment(taskId, TaskEngine.MIND, MomentKind.HANDOVER, request.text,
            listOf(MomentAction(TaskCommand.Done, "I'm done", primary = true)), requestId = request.id)
    }

    /**
     * The same moment as notification buttons (at most three). A question is answered inline ([NotificationAction.reply]);
     * a values card needs its fields, so the notification offers the other ways forward and its tap opens the card.
     * Stop is added when there is room, so a waiting task can always be ended from the shade.
     */
    fun notificationActions(moment: OwnerMoment): List<NotificationAction> {
        val actions = moment.actions.mapNotNull {
            when (it.command) {
                is TaskCommand.Reply -> NotificationAction(it.command, "Reply", reply = true)
                is TaskCommand.Fill -> null
                else -> NotificationAction(it.command, it.label)
            }
        }.toMutableList()
        moment.dismissal?.let { dismissal -> if (actions.none { it.command == dismissal.command }) actions.add(0, NotificationAction(dismissal.command, dismissal.label)) }
        if (actions.none { it.command == TaskCommand.Stop } && actions.size < MAX_NOTIFICATION_ACTIONS) actions.add(0, NotificationAction(TaskCommand.Stop, "Stop"))
        return actions.takeLast(MAX_NOTIFICATION_ACTIONS)
    }

    const val MAX_NOTIFICATION_ACTIONS = 3

    /** Buttons that act for the owner (approve, confirm, answer, hand-back) require an unlocked phone; Stop never does. */
    fun needsUnlock(action: NotificationAction): Boolean = action.reply || action.command is TaskCommand.Approve ||
        action.command is TaskCommand.Confirm || action.command is TaskCommand.Done || action.command is TaskCommand.Autofill

    /** Kinds shown as a card over other apps; hand-overs keep the slim task ribbon so the owner can use the app. */
    fun overlayCard(moment: OwnerMoment?): Boolean = moment != null && moment.kind in setOf(MomentKind.QUESTION, MomentKind.VALUES)
}
