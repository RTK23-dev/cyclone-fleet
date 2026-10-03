package com.cyclone.mobile.task

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.mind.mission.OwnerRequestKind
import com.cyclone.mobile.mind.mission.OwnerResponse
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.plane.MissionPlanes
import com.cyclone.mobile.runtime.plane.PlaneKind
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayUserAction

/** The app's Task Kit bus: the three engines, the live task, and a record of every command in the run trace. */
object TaskCommands {
    @Volatile private var app: Context? = null

    private val bus: TaskCommandBus by lazy {
        TaskCommandBus(listOf(MindTaskController, ClassicForegroundTaskController, BackgroundWorkspaceTaskController),
            current = { WorkspaceTasks.state.value }, log = ::record, others = { MindMissions.behindTasks.value })
    }

    fun send(context: Context, taskId: String, command: TaskCommand): TaskCommandResult {
        app = context.applicationContext
        return bus.send(taskId, command)
    }

    internal fun context(): Context = checkNotNull(app) { "TaskCommands used before send()" }

    /**
     * The notification button for [command]. Background workspaces keep their own service intent (that service owns
     * them and is already running); every other task goes through [TaskCommandReceiver] into the bus.
     */
    fun pendingIntent(context: Context, task: WorkspaceTaskUi, command: TaskCommand, reply: Boolean = false): PendingIntent {
        if (TaskEngines.of(task) == TaskEngine.BACKGROUND_WORKSPACE && !reply) {
            val intent = WorkspaceTasks.commandIntent(context, task, command.wire)
            (command as? TaskCommand.Confirm)?.token?.let { intent.putExtra("confirmation", it) }
            return PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val intent = Intent(context, TaskCommandReceiver::class.java).setAction(ACTION)
            .setData(android.net.Uri.parse("cyclone://task/${task.taskId}/${command.wire}"))
            .putExtra(EXTRA_TASK, task.taskId).putExtra(EXTRA_COMMAND, command.wire)
        (command as? TaskCommand.Confirm)?.token?.let { intent.putExtra(EXTRA_CONFIRMATION, it) }
        // An inline reply needs a mutable intent so the system can add the typed text; the intent stays explicit
        // (this app's private receiver), so nothing else can redirect it.
        val mutability = if (reply) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, mutability or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun record(task: WorkspaceTaskUi?, command: TaskCommand, result: TaskCommandResult) {
        DeviceState.addLog("Task command ${command.label}: ${if (result.handled) "handled" else "refused"} by ${result.engine ?: "none"} - ${result.detail}")
        val trace = task?.traceSessionId ?: return
        val context = app ?: return
        runCatching {
            AgentTraceRuntime.event(context, trace, "TASK_COMMAND", "${command.label}: ${result.detail}", code = command.wire,
                ok = result.handled, detail = "engine=${result.engine}")
        }
    }

    const val ACTION = "com.cyclone.mobile.TASK_COMMAND"
    const val EXTRA_TASK = "task"
    const val EXTRA_COMMAND = "command"
    const val EXTRA_CONFIRMATION = "confirmation"
    /** RemoteInput key of a notification reply. The text goes straight to the task and is never logged. */
    const val EXTRA_REPLY = "reply"
}

/** Notification buttons for foreground tasks. Not exported: only Cyclone's own PendingIntents reach it. */
class TaskCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TaskCommands.ACTION) return
        val taskId = intent.getStringExtra(TaskCommands.EXTRA_TASK) ?: return
        val reply = android.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(TaskCommands.EXTRA_REPLY)?.toString()
        val command = TaskCommand.parse(intent.getStringExtra(TaskCommands.EXTRA_COMMAND), intent.getStringExtra(TaskCommands.EXTRA_CONFIRMATION), reply)
        val result = command?.let { TaskCommands.send(context, taskId, it) }
        // A handled command changes the task, which re-posts its notification. Otherwise re-post it now, so an inline
        // reply never spins forever and the owner sees the moment is still open.
        if (result?.handled != true) WorkspaceTasks.state.value?.takeIf { it.taskId == taskId }?.let { AgentTaskNotificationRuntime.renderTask(context, it) }
    }
}

/** Cyclone Mind missions: every command resolves against what the mission is actually waiting for. */
object MindTaskController : TaskController {
    override val engine = TaskEngine.MIND
    override val supported: Set<Class<out TaskCommand>> = setOf(TaskCommand.Stop::class.java, TaskCommand.TakeOver::class.java,
        TaskCommand.Pause::class.java, TaskCommand.Done::class.java, TaskCommand.Approve::class.java, TaskCommand.Decline::class.java,
        TaskCommand.Reply::class.java, TaskCommand.Fill::class.java, TaskCommand.MoveToBackground::class.java,
        TaskCommand.MoveToForeground::class.java, TaskCommand.AllowBackground::class.java, TaskCommand.StartNow::class.java,
        TaskCommand.Steer::class.java, TaskCommand.Queue::class.java, TaskCommand.Parallel::class.java, TaskCommand.Unpause::class.java)

    /** The task a command is for: its own open request first; the front card also answers what the owner sees now. */
    private val target = ThreadLocal<WorkspaceTaskUi?>()

    private fun missionOf(task: WorkspaceTaskUi?): String? = task?.taskId?.removePrefix("mission-")

    private fun open(kind: OwnerRequestKind): com.cyclone.mobile.mind.mission.OwnerRequest? {
        val id = missionOf(target.get())
        id?.let { MindMissions.inbox.openFor(it) }?.takeIf { it.kind == kind }?.let { return it }
        // A task behind the screen answers only its own requests; the front card answers the one on screen.
        if (id != null && MindMissions.isBehind(id)) return null
        return MindMissions.inbox.pending.value?.takeIf { it.kind == kind }
    }

    private fun answer(kind: OwnerRequestKind, response: OwnerResponse, ok: String): TaskCommandResult {
        val request = open(kind) ?: return TaskCommandResult.refused(engine, "Cyclone is not waiting for that any more.")
        return if (MindMissions.answer(request.id, response)) TaskCommandResult.done(engine, ok)
        else TaskCommandResult.refused(engine, "That request was already answered.")
    }

    private val BEHIND_COMMANDS = setOf(TaskCommand.Stop::class.java, TaskCommand.Approve::class.java, TaskCommand.Decline::class.java,
        TaskCommand.Reply::class.java, TaskCommand.Fill::class.java,
        // Plan 38: the owner steers, pauses and resumes the task they are viewing, behind the screen too.
        TaskCommand.Steer::class.java, TaskCommand.Pause::class.java, TaskCommand.Unpause::class.java,
        TaskCommand.Queue::class.java, TaskCommand.Parallel::class.java)

    override fun handle(task: WorkspaceTaskUi, command: TaskCommand): TaskCommandResult {
        target.set(task)
        try {
            val id = missionOf(task)
            // Plan 26 §6: a task working behind the screen can be stopped and answered; the screen stays the owner's.
            ask(id, command)?.let { return it }
            if (id != null && MindMissions.isBehind(id)) {
                if (command == TaskCommand.Stop) return if (MindMissions.stop(id)) TaskCommandResult.done(engine, "Stopping that task.")
                    else TaskCommandResult.refused(engine, "That task is not running.")
                if (command.javaClass !in BEHIND_COMMANDS) return TaskCommandResult.refused(engine,
                    "That task works behind your screen; it comes to the front when the current one ends.")
            }
            return handleFront(task, command)
        } finally {
            target.remove()
        }
    }

    private fun handleFront(task: WorkspaceTaskUi, command: TaskCommand): TaskCommandResult = when (command) {
        is TaskCommand.Reply -> when {
            command.text.isBlank() -> TaskCommandResult.refused(engine, "The answer is empty.")
            // A send waiting for approval: the answer is a change to the message ("change it to …"); nothing is sent.
            open(OwnerRequestKind.APPROVAL)?.send != null ->
                answer(OwnerRequestKind.APPROVAL, OwnerResponse.Answer(command.text.trim()), "Cyclone will change the message first.")
            else -> answer(OwnerRequestKind.QUESTION, OwnerResponse.Answer(command.text.trim()), "Answer sent.")
        }
        is TaskCommand.Fill -> if (command.values.values.none { it.isNotBlank() }) TaskCommandResult.refused(engine, "No values were given.")
            else answer(OwnerRequestKind.VALUES, OwnerResponse.Values(command.values.filterValues { it.isNotBlank() }, command.remember), "Details sent.")
        TaskCommand.Stop -> if (MindMissions.isLive()) {
            OverlayChromeRuntime.dispatch(OverlayUserAction.STOP_TASK)
            TaskCommandResult.done(engine, "Stopping the mission.")
        } else {
            WorkspaceTasks.clearClosedTask(task.taskId, task.sessionId)
            AgentTaskNotificationRuntime.cancel(TaskCommands.context())
            TaskCommandResult.done(engine, "Closed the finished mission.")
        }
        TaskCommand.Done -> if (MindMissions.ownerDone()) TaskCommandResult.done(engine, "Cyclone has the phone back and continues.")
            else TaskCommandResult.refused(engine, "The mission is not running.")
        // Take over from an open question or check-in card: the mission hands the phone over itself and waits.
        TaskCommand.TakeOver if (open(OwnerRequestKind.VALUES) ?: open(OwnerRequestKind.QUESTION)) != null -> {
            val request = (open(OwnerRequestKind.VALUES) ?: open(OwnerRequestKind.QUESTION))!!
            if (MindMissions.answer(request.id, OwnerResponse.TakeOver)) TaskCommandResult.done(engine, "You have the phone; tap I'm done to continue.")
            else TaskCommandResult.refused(engine, "That request was already answered.")
        }
        TaskCommand.TakeOver -> if (MindMissions.ownerTakesPhone()) TaskCommandResult.done(engine, "You have the phone; tap I'm done to continue.")
            else TaskCommandResult.refused(engine, "The mission is not running.")
        TaskCommand.Approve -> answer(OwnerRequestKind.APPROVAL, OwnerResponse.Approve, "Approved.")
        // Decline is "not now" for whatever is open: a question learns the owner would rather not answer.
        TaskCommand.Decline -> when {
            open(OwnerRequestKind.APPROVAL) != null -> answer(OwnerRequestKind.APPROVAL, OwnerResponse.Decline, "Declined.")
            open(OwnerRequestKind.VALUES) != null -> answer(OwnerRequestKind.VALUES, OwnerResponse.Decline, "Details declined.")
            open(OwnerRequestKind.QUESTION) != null -> answer(OwnerRequestKind.QUESTION,
                OwnerResponse.Answer("I'd rather not answer that; continue without it."), "Question skipped.")
            else -> TaskCommandResult.refused(engine, "Nothing is waiting for you.")
        }
        // Planes: the switch runs as a transaction off the caller's thread; the pill shows how it went.
        TaskCommand.MoveToBackground -> move(PlaneKind.BACKGROUND)
        TaskCommand.MoveToForeground -> move(PlaneKind.SCREEN)
        TaskCommand.AllowBackground -> if (MissionPlanes.allowCurrentApp(TaskCommands.context()))
            TaskCommandResult.done(engine, "This app may run in the background from now on.")
            else TaskCommandResult.refused(engine, "Cyclone is not working in an app right now.")
        TaskCommand.StartNow -> if (MissionPlanes.startNow()) TaskCommandResult.done(engine, "Starting now, on your screen.")
            else TaskCommandResult.refused(engine, "Cyclone is not waiting for an app.")
        else -> TaskCommandResult.refused(engine, "${command.label} is not available for Cyclone Mind.")
    }

    /**
     * Plan 38: what the owner sends from the Ask bar while Cyclone works (Steer, Queue, Parallel) and a real Pause and
     * Resume, for the task they are viewing. Null for every other command.
     */
    private fun ask(id: String?, command: TaskCommand): TaskCommandResult? {
        val context = TaskCommands.context()
        return when (command) {
            is TaskCommand.Steer -> if (MindMissions.steerTask(id, command.text)) TaskCommandResult.done(engine, "Changed: ${command.text.take(80)}")
                else TaskCommandResult.refused(engine, "No task is running to change.")
            is TaskCommand.Queue -> MindMissions.queueTask(context, command.text)?.let { TaskCommandResult.done(engine, "Next: ${it.take(80)}") }
                ?: TaskCommandResult.refused(engine, "The queue is full.")
            is TaskCommand.Parallel -> MindMissions.parallelTask(context, command.text)?.let { TaskCommandResult.refused(engine, it) }
                ?: TaskCommandResult.done(engine, "Also working on it, behind your screen.")
            TaskCommand.Pause -> if (MindMissions.pause(context, id)) TaskCommandResult.done(engine, "Paused. Tap Resume to continue.")
                else TaskCommandResult.refused(engine, "The mission is not running.")
            TaskCommand.Unpause -> if (MindMissions.unpause(context, id)) TaskCommandResult.done(engine, "Continuing.")
                else TaskCommandResult.refused(engine, "The mission is not paused.")
            else -> null
        }
    }

    private fun move(to: PlaneKind): TaskCommandResult {
        if (!MindMissions.isLive()) return TaskCommandResult.refused(engine, "The mission is not running.")
        MissionPlanes.blocker(TaskCommands.context())?.takeIf { to == PlaneKind.BACKGROUND }?.let { return TaskCommandResult.refused(engine, it) }
        return if (MissionPlanes.request(to)) TaskCommandResult.done(engine, if (to == PlaneKind.BACKGROUND) "Moving to the background." else "Moving to your screen.")
            else TaskCommandResult.refused(engine, "The mission is not running.")
    }
}

/** The classic foreground agent, through its existing overlay runtime. */
object ClassicForegroundTaskController : TaskController {
    override val engine = TaskEngine.CLASSIC_FOREGROUND
    override val supported: Set<Class<out TaskCommand>> = setOf(TaskCommand.Stop::class.java, TaskCommand.TakeOver::class.java,
        TaskCommand.Pause::class.java, TaskCommand.Done::class.java, TaskCommand.Autofill::class.java,
        TaskCommand.Approve::class.java, TaskCommand.Decline::class.java)

    override fun handle(task: WorkspaceTaskUi, command: TaskCommand): TaskCommandResult {
        // The overlay approval card: Approve is its confirm button; Decline stops the task, as the card's Stop does.
        if (command == TaskCommand.Approve) {
            if (OverlayChromeRuntime.gateWait() != OverlayChromeRuntime.GateWait.PENDING) return TaskCommandResult.refused(engine, "Nothing is waiting for approval.")
            OverlayChromeRuntime.dispatch(OverlayUserAction.GATE_CONFIRM)
            return TaskCommandResult.done(engine, "Approved.")
        }
        val wire = if (command == TaskCommand.Decline) TaskCommand.Stop.wire else command.wire
        val refused = OverlayChromeRuntime.commandForegroundTask(task.taskId, wire)
        return if (refused == null) TaskCommandResult.done(engine, "${command.label} done.") else TaskCommandResult.refused(engine, refused)
    }
}

/** Background workspace tasks, owned by WorkspaceTaskService. */
object BackgroundWorkspaceTaskController : TaskController {
    override val engine = TaskEngine.BACKGROUND_WORKSPACE
    override val supported: Set<Class<out TaskCommand>> = setOf(TaskCommand.Stop::class.java, TaskCommand.TakeOver::class.java,
        TaskCommand.Pause::class.java, TaskCommand.Done::class.java, TaskCommand.Autofill::class.java, TaskCommand.Confirm::class.java)

    override fun handle(task: WorkspaceTaskUi, command: TaskCommand): TaskCommandResult {
        val context = TaskCommands.context()
        val intent = WorkspaceTasks.commandIntent(context, task, command.wire)
        (command as? TaskCommand.Confirm)?.token?.let { intent.putExtra("confirmation", it) }
        context.startService(intent)
        return TaskCommandResult.done(engine, "${command.label} sent to the workspace.")
    }
}
