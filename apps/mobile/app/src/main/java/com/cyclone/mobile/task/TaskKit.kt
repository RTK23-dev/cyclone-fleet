package com.cyclone.mobile.task

import com.cyclone.mobile.runtime.background.WorkspaceTaskUi

/**
 * Task Kit: the one contract between everything the owner presses (overlay, notification, Ask, progress screen,
 * task glass) and whatever engine runs the task. Surfaces never call an engine directly; they send a [TaskCommand]
 * through [TaskCommandBus], which finds the engine that owns the task and records what happened. An engine either
 * handles a command or says why not; a press is never dropped silently.
 */

/** Which engine runs a task. Every task has exactly one. */
enum class TaskEngine { MIND, CLASSIC_FOREGROUND, BACKGROUND_WORKSPACE }

/** What the owner asked the task to do. [wire] is the legacy action string still used by service intents. */
sealed class TaskCommand(val wire: String, val label: String) {
    data object Stop : TaskCommand("cancel", "Stop")
    data object TakeOver : TaskCommand("handoff", "Take over")
    data object Pause : TaskCommand("pause", "Pause")
    /** "I'm done": the owner is finished with the phone; the task continues. */
    data object Done : TaskCommand("resume", "I'm done")
    data object Autofill : TaskCommand("autofill", "Autofill")
    data class Confirm(val token: String?) : TaskCommand("confirm", "Confirm")
    data object Approve : TaskCommand("approve", "Approve")
    data object Decline : TaskCommand("decline", "Decline")
    /** The owner's answer to the task's open question (Owner Moments). */
    data class Reply(val text: String) : TaskCommand("reply", "Send")
    /** Values the owner typed on the check-in card, keyed by field label. */
    data class Fill(val values: Map<String, String>, val remember: Boolean) : TaskCommand("fill", "Fill in")
    /** Planes (plan 25): Cyclone keeps working behind the owner's screen. */
    data object MoveToBackground : TaskCommand("to_background", "Work in background")
    /** Planes: the task comes to the owner's screen, on the page it is on. */
    data object MoveToForeground : TaskCommand("to_screen", "Show on screen")
    /** The pill's long-press: this app may always run in the background, whatever Cyclone learned. */
    data object AllowBackground : TaskCommand("allow_background", "Always in background")
    /** Plan 26: the mission waits for an app the owner is using; start now, on the owner's screen. */
    data object StartNow : TaskCommand("start_now", "Start now")
    /** Plan 38: the owner changes the task they are viewing (typed in the Ask bar while Cyclone works). */
    data class Steer(val text: String) : TaskCommand("steer", "Steer")
    /** Plan 38: the typed text runs as its own task after this one. */
    data class Queue(val text: String) : TaskCommand("queue", "Queue")
    /** Plan 38: the typed text starts now as its own task, behind the screen. */
    data class Parallel(val text: String) : TaskCommand("parallel", "Parallel")
    /** Plan 38: a paused task continues. */
    data object Unpause : TaskCommand("unpause", "Resume")

    companion object {
        // Lazy: the command objects extend this class, so they do not exist yet while its companion initializes.
        val ALL: List<TaskCommand> by lazy { listOf(Stop, TakeOver, Pause, Done, Autofill, Confirm(null), Approve, Decline, Reply(""), Fill(emptyMap(), false),
            MoveToBackground, MoveToForeground, AllowBackground, StartNow, Steer(""), Queue(""), Parallel(""), Unpause) }

        fun parse(action: String?, confirmation: String? = null, text: String? = null): TaskCommand? = when (action?.trim()?.lowercase()) {
            "reply" -> text?.trim()?.takeIf { it.isNotEmpty() }?.let { Reply(it.take(2_000)) }
            "cancel", "stop" -> Stop
            "handoff", "takeover", "take_over" -> TakeOver
            "pause" -> Pause
            "resume", "done", "continue" -> Done
            "autofill" -> Autofill
            "confirm" -> Confirm(confirmation)
            "approve" -> Approve
            "decline" -> Decline
            "to_background", "background" -> MoveToBackground
            "to_screen", "foreground", "screen" -> MoveToForeground
            "allow_background" -> AllowBackground
            "start_now" -> StartNow
            "steer" -> text?.trim()?.takeIf { it.isNotEmpty() }?.let { Steer(it.take(2_000)) }
            "queue" -> text?.trim()?.takeIf { it.isNotEmpty() }?.let { Queue(it.take(2_000)) }
            "parallel" -> text?.trim()?.takeIf { it.isNotEmpty() }?.let { Parallel(it.take(2_000)) }
            "unpause" -> Unpause
            else -> null
        }
    }
}

data class TaskCommandResult(val handled: Boolean, val engine: TaskEngine?, val detail: String) {
    companion object {
        fun done(engine: TaskEngine, detail: String) = TaskCommandResult(true, engine, detail)
        fun refused(engine: TaskEngine?, detail: String) = TaskCommandResult(false, engine, detail)
    }
}

/** One engine's side of the contract. */
interface TaskController {
    val engine: TaskEngine
    /** The commands this engine understands at all. Anything else is refused with a reason by the bus. */
    val supported: Set<Class<out TaskCommand>>
    fun handle(task: WorkspaceTaskUi, command: TaskCommand): TaskCommandResult

    fun supports(command: TaskCommand): Boolean = command.javaClass in supported
}

object TaskEngines {
    /** The explicit engine when the task names one; otherwise derived from how it was started. */
    fun of(task: WorkspaceTaskUi): TaskEngine = task.engine ?: when {
        task.taskId.startsWith(MIND_TASK_PREFIX) -> TaskEngine.MIND
        task.foreground -> TaskEngine.CLASSIC_FOREGROUND
        else -> TaskEngine.BACKGROUND_WORKSPACE
    }

    const val MIND_TASK_PREFIX = "mission-"
}

/**
 * The single way in. [current] supplies the live task; [log] receives every command and its outcome (trace,
 * diagnostics), handled or not.
 */
class TaskCommandBus(
    private val controllers: List<TaskController>,
    private val current: () -> WorkspaceTaskUi?,
    private val log: (WorkspaceTaskUi?, TaskCommand, TaskCommandResult) -> Unit = { _, _, _ -> },
    /** Tasks working behind the front one (plan 26 §6); a command may name one of them. */
    private val others: () -> List<WorkspaceTaskUi> = { emptyList() },
) {
    init {
        val engines = controllers.map { it.engine }
        require(engines.size == engines.toSet().size) { "one controller per engine" }
    }

    fun controller(engine: TaskEngine): TaskController? = controllers.firstOrNull { it.engine == engine }

    fun send(taskId: String, command: TaskCommand): TaskCommandResult {
        val task = current()?.takeIf { it.taskId == taskId } ?: runCatching { others() }.getOrDefault(emptyList()).firstOrNull { it.taskId == taskId }
        val result = when {
            task == null -> TaskCommandResult.refused(null, "That task is no longer current.")
            else -> {
                val engine = TaskEngines.of(task)
                val controller = controller(engine)
                when {
                    controller == null -> TaskCommandResult.refused(engine, "No controller is registered for $engine.")
                    !controller.supports(command) -> TaskCommandResult.refused(engine, "${command.label} is not available for this task.")
                    else -> runCatching { controller.handle(task, command) }
                        .getOrElse { TaskCommandResult.refused(engine, "${command.label} failed: ${it.message ?: it.javaClass.simpleName}") }
                }
            }
        }
        runCatching { log(task, command, result) }
        return result
    }
}
