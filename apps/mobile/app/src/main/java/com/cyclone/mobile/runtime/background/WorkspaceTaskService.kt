package com.cyclone.mobile.runtime.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.cyclone.mobile.ai.*
import com.cyclone.mobile.runtime.session.ExecutionContext
import com.cyclone.mobile.ui.overlay.GlassStepKind
import com.cyclone.mobile.ui.overlay.TaskGlassStep
import kotlinx.coroutines.*

/** One task, one existing agent, one owned display. UI dismissal never ends execution. */
class WorkspaceTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var taskId: String? = null
    @Volatile private var sessionId: String? = null
    private var agent: OpenRouterAdaptiveAgent? = null
    private var running: Job? = null
    private var switching = false
    @Volatile private var stopped = false
    private var observer: Job? = null
    private val current get() = WorkspaceTasks.state.value?.takeIf { it.taskId == taskId }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (intent.action != null) {
            val shared = WorkspaceTasks.state.value
            if (shared?.foreground == true) {
                if (WorkspaceTasks.matches(shared, intent.getStringExtra("task"), intent.getStringExtra("session")) &&
                    intent.getIntExtra("display", -1) == shared.displayId && intent.getStringExtra("workspace") == null &&
                    intent.getLongExtra("generation", -1) == -1L) {
                    // Legacy notification intents for a foreground task: Task Kit routes them to the owning engine.
                    com.cyclone.mobile.task.TaskCommand.parse(intent.action, intent.getStringExtra("confirmation"))?.let { command ->
                        com.cyclone.mobile.task.TaskCommands.send(applicationContext, shared.taskId, command)
                    }
                }
                if (taskId == null) stopSelf()
                return START_NOT_STICKY
            }
            // Commands can create a fresh service after the original failed and stopped itself.
            // Bind only the exact store identity carried by the command, never instance defaults.
            if (taskId == null && intent.action == "cancel") {
                val saved = WorkspaceTasks.state.value
                if (WorkspaceTasks.matches(saved, intent.getStringExtra("task"), intent.getStringExtra("session"))) {
                    taskId = saved!!.taskId
                    sessionId = saved.sessionId
                }
            }
            if (!WorkspaceTasks.matches(current, intent.getStringExtra("task"), intent.getStringExtra("session"))) {
                if (taskId == null) stopSelf()
                return START_NOT_STICKY
            }
            val target = current ?: return START_NOT_STICKY
            if (intent.getIntExtra("display", -1) != (target.displayId ?: -1) ||
                intent.getStringExtra("workspace") != target.workspaceId ||
                intent.getLongExtra("generation", -1) != (target.workspaceGeneration ?: -1L)) return START_NOT_STICKY
            when (intent.action) {
                "cancel" -> stopTask()
                "handoff" -> transferToHuman()
                "resume" -> continueTask()
                "autofill" -> autofillLogin()
                "confirm" -> {
                    val token = intent.getStringExtra("confirmation")
                    if (token != null && token == current?.confirmation?.token && current?.phase == TaskPhase.REVIEW) {
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { sessionId?.let { WorkspaceRuntime.approveConfirmation(it, token) } } }
                                .onSuccess { update { it.copy(confirmation = null) }; continueTask(confirmed = true) }
                        }
                    }
                }
                "pause" -> pauseTask()
            }
            return START_NOT_STICKY
        }
        if (taskId != null) return START_NOT_STICKY
        val task = WorkspaceTasks.state.value
        if (task == null || task.taskId != intent.getStringExtra("task")) { stopSelf(); return START_NOT_STICKY }
        taskId = task.taskId
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cyclone tasks", NotificationManager.IMPORTANCE_LOW))
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(task), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification(task), 0)
        observer = scope.launch { WorkspaceTasks.state.collect { state ->
            if (state != null && state.taskId == taskId) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
        } }
        scope.launch {
            while (!stopped) {
                delay(1_000)
                val missing = BackgroundSetup.read(applicationContext).setupFailure
                if (missing != null) {
                    stopTask()
                    update { it.copy(phase = TaskPhase.FAILED, message = missing, resumable = false) }
                    return@launch
                }
            }
        }
        running = scope.launch {
            try {
                withTimeout(5_000) {
                    while (!BackgroundSetup.read(applicationContext, task.packageName).ready) {
                        BackgroundSetup.read(applicationContext, task.packageName).setupFailure?.let { error(it) }
                        delay(200)
                    }
                }
                val session = withContext(Dispatchers.IO + NonCancellable) {
                    WorkspaceRuntime.create(applicationContext, task.packageName).also {
                        sessionId = it.sessionId
                        if (stopped) WorkspaceRuntime.close(it.sessionId)
                    }
                }
                sessionId = session.sessionId
                if (stopped) { withContext(Dispatchers.IO) { WorkspaceRuntime.close(session.sessionId) }; return@launch }
                update { it.copy(
                    sessionId = session.sessionId,
                    displayId = session.displayId,
                    phase = TaskPhase.WORKING,
                    message = "Opening ${it.app}",
                    outcome = null,
                    glassStepKind = GlassStepKind.FAST_PATH,
                    steps = emptyList(),
                ) }
                val settings = getSharedPreferences("cyclone_ai", MODE_PRIVATE)
                val profile = CycloneAiAccessProfileStore.read(applicationContext)
                val config = QuickAgentConfig(
                    model = OpenRouterModelPresets.byId(com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(applicationContext)).copy(
                        reasoningEffort = settings.getString("openrouter_reasoning_effort", "medium")?.takeIf { it in setOf("low", "medium", "high", "max") } ?: "medium"),
                    safeMode = profile != CycloneAiAccessProfile.FULL, accessProfile = profile,
                    attachment = WorkspaceTasks.takeAttachment(task.taskId))
                awaitWorkspace(session.sessionId, ExecutionContext.from(session))
                agent = OpenRouterAdaptiveAgent(applicationContext, ExecutionContext.from(session)).also { agent ->
                    var revision = 0L
                    agent.onTrajectory = { trajectory ->
                        update { TaskHarnessState.applyTrajectory(it, trajectory) }
                    }
                    agent.onTraceSession = { traceId ->
                        update { it.copy(traceSessionId = traceId) }
                    }
                    agent.onOperation = { tool, result ->
                        if (result == null) { revision = current?.controlRevision ?: -1; update { TaskHarnessState.begin(it, tool) } }
                        else update { TaskHarnessState.finish(it, TaskOperationEvidence(session.sessionId, session.displayId,
                            revision, result.androidExecutionOk, result.verification.passed,
                            result.afterObservationId != null && result.afterObservationId != result.beforeObservationId &&
                                result.after?.sessionId == session.sessionId && result.after?.displayId == session.displayId,
                            result.verification.basis)) }
                    }
                }
                finishTask(agent!!.execute(task.goal, config) { text -> progress(text) })
            } catch (error: Exception) {
                WorkspaceTasks.takeAttachment(task.taskId)
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                sessionId?.let { withContext(Dispatchers.IO) { WorkspaceRuntime.close(it, WorkspaceState.FAILED) } }
                sessionId = null
                val diagnostic = BackgroundSetup.failure(applicationContext, task.packageName, error)
                update {
                    it.copy(
                        phase = TaskPhase.FAILED,
                        resumable = false,
                        message = diagnostic,
                        outcome = "I couldn't finish this task. Your place is saved.",
                    )
                }
                stopForeground(STOP_FOREGROUND_DETACH); stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun finishTask(result: QuickAgentResult) {
        if (stopped) return
        val task = current ?: return
        if (task.phase == TaskPhase.HUMAN || task.phase == TaskPhase.PAUSED || switching) {
            if (result.ok || result.classification != "HUMAN_OR_GATE")
                update { it.copy(resumable = false) }
            return
        }
        val id = sessionId ?: return
        val secretWall = if (result.classification == "HUMAN_OR_GATE") {
            agent?.currentSecretWallRequest()
        } else null

        // A secret fill is an agent-authorized mutation on the exact existing workspace. Do not
        // revoke the workspace lease here: the suspended agent is idle and the one-shot card fill
        // is the only permitted mutation until verification succeeds.
        if (secretWall != null) {
            update {
                it.copy(
                    phase = TaskPhase.REVIEW,
                    message = "Secure input is required to continue.",
                    outcome = null,
                    resumable = true,
                    loginAutofill = false,
                    interruption = TaskInterruption.needsSecret(),
                )
            }
            val blocked = current ?: return
            val expectedRevision = blocked.controlRevision
            com.cyclone.mobile.secrets.SecretsPhoneFacade.requestForRun(
                context = applicationContext,
                request = secretWall.request,
                target = secretWall.target,
            ) { resolution ->
                if (!resolution.taskMayResume) return@requestForRun
                resumeAfterSecret(blocked.taskId, expectedRevision)
            }
            return
        }

        // Even verified completion retains its exact app page until Open or Stop.
        withContext(Dispatchers.IO) { WorkspaceRuntime.pause(id, WorkspaceState.BACKGROUND_NEEDS_HANDOFF) }
        update {
            when {
                result.ok -> {
                    val safeOutcome = WorkspaceCopy.result(result.message)
                    it.copy(
                        phase = TaskPhase.DONE,
                        resumable = false,
                        message = safeOutcome,
                        outcome = safeOutcome,
                        steps = it.steps + "Checked the result",
                    )
                }
                result.classification == "HUMAN_OR_GATE" -> it.copy(
                    phase = TaskPhase.REVIEW,
                    message = if (result.gateClass == "login")
                        "This screen needs your sign-in. Take Over, Autofill, or tap I'm Done when finished."
                    else "Review the prepared page in ${it.app} before continuing.",
                    loginAutofill = result.gateClass == "login",
                )
                else -> {
                    val safeFailure = OutcomeStageCopy.terminalFailure(it.plannedStages, result.message, resumable = false)
                    it.copy(
                        phase = TaskPhase.FAILED,
                        resumable = false,
                        message = safeFailure,
                        outcome = safeFailure,
                    )
                }
            }
        }
    }

    private fun resumeAfterSecret(expectedTaskId: String, expectedRevision: Long) {
        if (switching || stopped) return
        val blocked = current ?: return
        if (blocked.taskId != expectedTaskId ||
            blocked.controlRevision != expectedRevision ||
            blocked.interruption?.kind != TaskInterruptionKind.NEEDS_SECRET
        ) return

        switching = true
        val previousRun = running
        running = scope.launch {
            try {
                previousRun?.join()
                if (stopped) return@launch
                val id = sessionId ?: error("Missing workspace session")
                val currentTask = current ?: error("Task unavailable")
                check(currentTask.taskId == expectedTaskId && currentTask.controlRevision == expectedRevision) {
                    "STALE_SECRET_RESUME"
                }
                check(WorkspaceRuntime.ownsInput(id)) { "Workspace input authority changed" }
                val exact = ExecutionContext(id, currentTask.displayId ?: error("Missing task display"))
                val fresh = withContext(Dispatchers.IO) {
                    com.cyclone.mobile.gateway.GatewayObservationAdapter.capture(
                        applicationContext,
                        currentTask.identityJson(),
                    )
                }
                check(fresh.execution == exact && fresh.id.isNotBlank()) {
                    "Fresh session evidence is required"
                }
                update {
                    it.copy(
                        phase = TaskPhase.WORKING,
                        message = "Continuing from the current page",
                        outcome = null,
                        interruption = null,
                        loginAutofill = false,
                        glassStepKind = GlassStepKind.FAST_PATH,
                    )
                }
                switching = false
                finishTask(agent?.resume { text -> progress(text) } ?: error("Task unavailable"))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                val id = sessionId
                if (id != null) withContext(Dispatchers.IO) { runCatching { WorkspaceRuntime.pause(id) } }
                update {
                    it.copy(
                        phase = TaskPhase.PAUSED,
                        message = "Couldn't continue after secure input. Your task is paused safely.",
                    )
                }
            } finally {
                switching = false
            }
        }
    }

    private fun pauseTask() {
        val id = sessionId ?: return
        if (switching || current?.working != true) return
        switching = true
        update { it.copy(phase = TaskPhase.PAUSED, message = "Pausing your task") }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WorkspaceRuntime.pause(id) }
                update { it.copy(phase = TaskPhase.PAUSED, message = "Take your time. Your place is saved.") }
            } catch (_: Exception) {
                update { it.copy(phase = TaskPhase.PAUSED, message = "The workspace is unavailable. Your task has stopped safely.") }
            } finally { switching = false }
        }
    }
    private fun transferToHuman() {
        val id = sessionId ?: return
        if (switching || current?.interruption?.canTakeOver != true && current?.phase != TaskPhase.DONE && current?.working != true) return
        switching = true
        update { it.copy(phase = TaskPhase.PAUSED, message = "Transferring control to you") }
        scope.launch {
            try {
                // Revoke before moving; the agent suspends at its next normal execution boundary.
                withContext(Dispatchers.IO) { WorkspaceRuntime.handoff(id) }
                update { it.copy(phase = TaskPhase.HUMAN, confirmation = null, message = "Your prepared page is open in ${it.app}.") }
            } catch (_: Exception) {
                update { it.copy(phase = TaskPhase.PAUSED, message = "Couldn't move this page. Your task is paused safely.") }
            } finally { switching = false }
        }
    }
    private fun autofillLogin() {
        val task = current ?: return
        if (switching || task.interruption?.canAutofill != true) return
        agent?.authorizeAutofill()
        continueTask(confirmed = true)
    }
    private fun continueTask(confirmed: Boolean = false) {
        val id = sessionId ?: return
        val task = current ?: return
        if (switching || !task.resumable || (!confirmed && task.interruption?.canResumeAfterHuman != true)) return
        switching = true
        val previousRun = running
        running = scope.launch {
            try {
                // Do not race the in-flight model call or restart its agent/checkpoint.
                previousRun?.join()
                if (stopped) return@launch
                withContext(Dispatchers.IO) { WorkspaceRuntime.resume(id) }
                val exact = ExecutionContext(id, task.displayId ?: error("Missing task display"))
                awaitWorkspace(id, exact)
                val fresh = withContext(Dispatchers.IO) {
                    com.cyclone.mobile.gateway.GatewayObservationAdapter.capture(applicationContext, task.identityJson())
                }
                check(fresh.execution == exact && fresh.id.isNotBlank()) { "Fresh session evidence is required" }
                update {
                    it.copy(
                        phase = TaskPhase.WORKING,
                        message = "Continuing from the current page",
                        outcome = null,
                        glassStepKind = GlassStepKind.FAST_PATH,
                    )
                }
                switching = false
                finishTask(agent?.resume { text -> progress(text) } ?: error("Task unavailable"))
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                withContext(Dispatchers.IO) { runCatching { WorkspaceRuntime.pause(id) } }
                update { it.copy(phase = TaskPhase.PAUSED, message = "Couldn't continue this page. Take control to finish it.") }
            } finally { switching = false }
        }
    }
    private suspend fun awaitWorkspace(id: String, execution: ExecutionContext) {
        withTimeout(10_000) {
            while (!withContext(Dispatchers.IO) {
                runCatching { WorkspaceRuntime.observe(execution).nodes.isNotEmpty() &&
                    com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.healthy(id) }.getOrDefault(false)
            }) delay(200)
        }
    }
    private fun stopTask() {
        if (stopped) return
        val closing = current ?: return
        stopped = true
        agent?.cancelActiveTask()
        observer?.cancel()
        scope.launch {
            try {
                // Join creation too: its NonCancellable block releases any display created late.
                running?.cancelAndJoin()
                if (closing.workspaceId != null) {
                    val released = withContext(Dispatchers.IO) {
                        com.cyclone.mobile.PhoneToolExecutor.execute(applicationContext, com.cyclone.mobile.PhoneToolRequest(
                            java.util.UUID.randomUUID().toString(), "workspace.close_task", closing.identityJson()))
                    }
                    check(released.ok) { released.error?.message ?: "Couldn't release this workspace." }
                } else {
                sessionId?.let { withContext(Dispatchers.IO) { WorkspaceRuntime.close(it) } }
                }
                sessionId = null
                WorkspaceTasks.clearClosedTask(closing.taskId, closing.sessionId)
                stopForeground(STOP_FOREGROUND_REMOVE)
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
                com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.clearBackgroundChrome()
                WorkspaceTasks.scheduleQueuePromotion(applicationContext)
                stopSelf()
            } catch (error: Exception) {
                stopped = false
                update { it.copy(phase = TaskPhase.FAILED, confirmation = null, resumable = false,
                    message = "Couldn't finish closing this task. Try Close task again.") }
            }
        }
    }

    private fun progress(text: String) {
        // Raw provider summaries can contain user-entered data. Harness operation callbacks own progress.
        update { if (it.working && it.semanticSteps.isEmpty()) it.copy(message = "Checking the current page") else it }
    }
    private fun update(change: (WorkspaceTaskUi) -> WorkspaceTaskUi) { taskId?.let { WorkspaceTasks.update(it, change) } }
    private fun notification(task: WorkspaceTaskUi): Notification =
        TaskProgressNotification.build(this, CHANNEL, task)
    override fun onDestroy() {
        // Only a task that was already truly terminal/closed may release the FIFO head.
        val promoteAfterDestroy = current?.phase in setOf(TaskPhase.FAILED, TaskPhase.STOPPED)
        taskId?.let { WorkspaceTasks.takeAttachment(it) }
        stopped = true
        agent?.cancelActiveTask()
        scope.cancel()
        sessionId?.let { id -> Thread { runCatching { WorkspaceRuntime.close(id) } }.start() }
        if (current?.phase !in setOf(TaskPhase.FAILED, TaskPhase.STOPPED))
            update { it.copy(phase = TaskPhase.STOPPED, message = "Task ended. Start a new task when you're ready.", resumable = false) }
        super.onDestroy()
        if (promoteAfterDestroy) WorkspaceTasks.scheduleQueuePromotion(applicationContext)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { private const val CHANNEL = "cyclone-workspace-task"; private const val NOTIFICATION = 902 }
}

internal object WorkspaceCopy {
    fun result(message: String): String {
        val technical = Regex("(?i)brain|tool[._ ]|provider|reasoning|gate|policy|observation|session|json|```|\\b(?:verified_|completion\\.)")
        return if (message.isBlank() || technical.containsMatchIn(message)) "Your task is complete. Open the app to see the result."
        else message.trim().take(600)
    }
}
