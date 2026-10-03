package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.agent.CycloneTaskClassification
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.CycloneAiAccessProfileStore
import com.cyclone.mobile.ai.OpenRouterAdaptiveAgent
import com.cyclone.mobile.ai.OverlayChromeController
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.QuickAgentConfig
import com.cyclone.mobile.ai.QuickAgentResult
import com.cyclone.mobile.runtime.background.*
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Facade that attaches one Compose overlay window to the existing Accessibility service.
 * Overlay buttons change Cyclone controller state only; they never click host nodes.
 */
object OverlayChromeRuntime {
    private val lock = Any()
    private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cycloneState = object : OverlayCycloneStateEffects {
        override fun pauseAgentForUser() {
            DeviceState.setController(DeviceState.Controller.HUMAN)
        }

        override fun resumeAgent() {
            DeviceState.setController(DeviceState.Controller.AGENT)
        }
    }

    private var machine = OverlayChromeMachine(
        emit = OverlayChromeBus::publish,
        cycloneState = cycloneState,
    )
    private val mutableActivity = kotlinx.coroutines.flow.MutableStateFlow(machine.state())
    val activity: kotlinx.coroutines.flow.StateFlow<OverlayChromeState> = mutableActivity
    private var controller: OverlayChromeController? = null
    private var service: CycloneAccessibilityService? = null
    private var aiJob: Job? = null
    private var workspaceJob: Job? = null
    private var adaptiveAgent: OpenRouterAdaptiveAgent? = null
    private var foregroundTaskId: String? = null
    private var foregroundResuming = false
    private var suspendedTaskId: String? = null

    private data class GateChallenge(
        val gateClass: OverlayGateClass,
        val action: String,
        val signature: String,
        val sessionId: String,
        val expiresAtMs: Long,
    )

    private var pendingGateChallenge: GateChallenge? = null
    private var approvedGateChallenge: GateChallenge? = null

    /** A running Cyclone Mind mission, which owns the foreground task while it lives. */
    interface MissionHooks {
        fun stop()
        /** Text from the composer while a mission runs: an answer or a new instruction. True when consumed. */
        fun ownerText(text: String): Boolean

    }

    @Volatile private var missionHooks: MissionHooks? = null

    fun attachMission(hooks: MissionHooks) { missionHooks = hooks }
    fun detachMission(hooks: MissionHooks) { if (missionHooks === hooks) missionHooks = null }

    enum class GateWait { NONE, PENDING, APPROVED }

    fun gateWait(): GateWait = synchronized(lock) {
        val now = System.currentTimeMillis()
        when {
            approvedGateChallenge?.let { it.expiresAtMs >= now } == true -> GateWait.APPROVED
            pendingGateChallenge != null -> GateWait.PENDING
            else -> GateWait.NONE
        }
    }

    /** The class of the action waiting at GATE (pay, send, delete, grant), or null when nothing waits. */
    fun pendingGateClass(): OverlayGateClass? = synchronized(lock) { pendingGateChallenge?.gateClass }

    /** A mission may wait longer than a live user would; keep its exact challenge open while it waits. */
    fun keepGateChallengeAlive() = synchronized(lock) {
        pendingGateChallenge = pendingGateChallenge?.copy(expiresAtMs = System.currentTimeMillis() + GATE_CHALLENGE_TTL_MS)
    }

    /** Approval from the mission's own request card (app or notification) instead of the overlay button. */
    fun approveGateForMission(): Boolean {
        val before = snapshot()
        if (before.state != OverlayChromeState.GATE) return false
        approvePendingGateChallenge(before)
        mutate { it.dispatch(OverlayUserAction.GATE_CONFIRM) }
        return gateWait() == GateWait.APPROVED
    }

    fun declineGateForMission() {
        synchronized(lock) {
            pendingGateChallenge = null
            approvedGateChallenge = null
        }
        mutate { it.gateDecline() }
    }

    /** Shows the mission as the working foreground task and hands input to Cyclone. */
    /**
     * Alpha.78: the running work was asked for by voice. Voice mode keeps the screen: the Ask panel never opens for it,
     * and a mission it started shows as the minimized island, not the full working panel. Cleared when that work ends or
     * the owner types a request.
     */
    @Volatile private var voiceOwnedAt = 0L
    /** A voice request in the last few seconds: the mission it starts shows minimized. Expires, so nothing lingers. */
    private val voiceOwned: Boolean get() = android.os.SystemClock.elapsedRealtime() - voiceOwnedAt < VOICE_OWNS_MS

    fun missionWorking(sessionId: String, status: String? = null) {
        foregroundTaskId = sessionId
        mutate { machine ->
            when (machine.state()) {
                OverlayChromeState.IDLE, OverlayChromeState.DONE -> {
                    machine.startAnalysis(sessionId)
                    machine.enterWorking(sessionId)
                    if (voiceOwned) machine.dispatch(OverlayUserAction.MINIMIZE)
                }
                OverlayChromeState.ANALYSIS, OverlayChromeState.LIVE -> machine.enterWorking(sessionId)
                else -> Unit
            }
            status?.let(machine::updateStatus)
        }
    }

    fun missionStatus(status: String) = mutate { it.updateStatus(status) }

    /** The mission hands the phone to the owner: Cyclone's input stops and the overlay shows it paused. */
    fun missionHandoff() {
        DeviceState.setController(DeviceState.Controller.HUMAN)
        mutate { if (!it.snapshot().userPaused) it.dispatch(OverlayUserAction.TAKE_CONTROL) }
    }

    /** The owner handed the phone back from the mission's own card. */
    fun missionHandBack() {
        mutate { if (it.snapshot().userPaused) it.dispatch(OverlayUserAction.TAKE_CONTROL) }
        DeviceState.setController(DeviceState.Controller.AGENT)
    }

    fun missionFinished(sessionId: String, ok: Boolean, message: String) {
        voiceOwnedAt = 0L
        synchronized(lock) {
            pendingGateChallenge = null
            approvedGateChallenge = null
        }
        mutate { machine ->
            if (machine.state() == OverlayChromeState.GATE) machine.gateDecline()
            if (ok) machine.completeDone(sessionId) else machine.finishStopped(message)
        }
    }

    fun isAttached(): Boolean = synchronized(lock) { controller != null }

    fun snapshot(): OverlayChromeSnapshot = synchronized(lock) { machine.snapshot() }

    /** Re-evaluate externally-owned sibling surfaces such as the phone-local Secrets Card. */
    fun refreshExternalSurface() {
        synchronized(lock) { controller?.render(machine.snapshot()) }
    }

    fun attach(service: CycloneAccessibilityService) {
        synchronized(lock) {
            if (controller != null && this.service === service) {
                controller?.render(machine.snapshot())
                return
            }
            workspaceJob?.cancel()
            controller?.dismiss()
            this.service = service
            val next = OverlayChromeController(
                service = service,
                onAction = { action -> dispatch(action) },
                onComposerChanged = { text -> updateComposer(text) },
                onRequestSubmitted = { text -> submitRequest(text) },
                onVoiceStateChanged = { listening, transcript, message ->
                    updateVoice(listening, transcript, message)
                },
                getAiSettings = { readAiSettings(service) },
                onAiSettingsChanged = { settings -> saveAiSettings(service, settings) },
            )
            controller = next
            OverlayGesturePassthrough.bind { next.syncHostGesturePassthrough() }
            next.show(machine.snapshot())
            // Driver mode (plan 32): the AI button and AI mode, in their own windows next to the chrome.
            // Alpha 95: the accessibility service also attaches it on its own, so a chrome failure can't take the orb away.
            runCatching { DriverOverlay.attach(service) }
            workspaceJob = aiScope.launch {
                var previousTask: String? = null
                com.cyclone.mobile.runtime.background.WorkspaceTasks.state.collect { task ->
                    if (task != null && task.taskId != previousTask && task.working) {
                        mutate { it.showTaskProgress() }
                    }
                    if (task?.foreground == true) {
                        controller?.background(task)
                        service?.let { AgentTaskNotificationRuntime.renderTask(it, task) }
                        previousTask = task.taskId
                        return@collect
                    }
                    if (BackgroundGlassPolicy.tearDown(task) || (task == null && previousTask != null)) clearBackgroundChrome()
                    else if (BackgroundGlassPolicy.visible(task)) {
                        controller?.background(task)
                    }
                    previousTask = task?.taskId
                }
            }
        }
    }

    fun detach() {
        val context = synchronized(lock) { service }
        synchronized(lock) {
            workspaceJob?.cancel(); workspaceJob = null
            DriverOverlay.detach()
            OverlayGesturePassthrough.unbind()
            controller?.dismiss()
            controller = null
            service = null
            adaptiveAgent?.cancelActiveTask()
            adaptiveAgent = null
            suspendedTaskId = null
            pendingGateChallenge = null
            approvedGateChallenge = null
            aiJob?.cancel()
            aiJob = null
            machine = OverlayChromeMachine(
                emit = OverlayChromeBus::publish,
                cycloneState = cycloneState,
            )
        }
        context?.let {
                    foregroundTaskId?.let { id -> WorkspaceTasks.update(id) { task -> task.copy(phase = TaskPhase.STOPPED, resumable = false) } }
                    AgentTaskNotificationRuntime.finish(it, false, "Task stopped.")
                    com.cyclone.mobile.runtime.background.WorkspaceTasks.scheduleQueuePromotion(it)
                }
    }

    fun clearBackgroundChrome() {
        synchronized(lock) {
            OverlayExternalInteraction.active.value = false
            // Task presentation ends; the accessibility-owned entry point does not.
            if (!hasExecutingTask()) machine.resetIdle(idleChipVisible = true)
            mutableActivity.value = machine.state()
            controller?.background(null)
            controller?.render(machine.snapshot())
        }
    }
    /** Device hook: task cleanup retains the launcher; only service detach removes all windows. */
    fun overlayWindowCount(): Int = synchronized(lock) { controller?.attachedWindowCount() ?: 0 }
    fun overlayLauncherCount(): Int = synchronized(lock) { controller?.attachedLauncherCount() ?: 0 }

    fun startAnalysis(
        sessionId: String,
        bullets: List<String> = emptyList(),
        cta: OverlayAnalysisCta = OverlayAnalysisCta.CONFIRM,
    ) {
        mutate { it.startAnalysis(sessionId, bullets, cta) }
    }

    fun enterWorking(sessionId: String = snapshot().sessionId) {
        mutate { it.enterWorking(sessionId) }
    }

    fun enterLive() {
        mutate { it.enterLive() }
    }

    fun enterGate(gateClass: OverlayGateClass, pcAutoApprove: Boolean = false, sessionId: String = snapshot().sessionId) {
        mutate { it.enterGate(gateClass, pcAutoApprove, sessionId) }
    }

    fun completeDone(sessionId: String = snapshot().sessionId) {
        mutate { it.completeDone(sessionId) }
    }

    fun resetIdle(idleChipVisible: Boolean = true) {
        synchronized(lock) {
            pendingGateChallenge = null
            approvedGateChallenge = null
        }
        mutate { it.resetIdle(idleChipVisible) }
    }

    /**
     * Records the exact host action that triggered GATE. Labels are normalized in memory only and
     * never persisted or logged. Confirmation can authorize this exact challenge once.
     */
    fun registerGateChallenge(gateClass: OverlayGateClass, action: String, labels: List<String>) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            pendingGateChallenge = GateChallenge(
                gateClass = gateClass,
                action = action,
                signature = gateSignature(action, labels),
                sessionId = machine.snapshot().sessionId,
                expiresAtMs = now + GATE_CHALLENGE_TTL_MS,
            )
            approvedGateChallenge = null
        }
    }

    fun hasGateApproval(gateClass: OverlayGateClass, action: String, labels: List<String>): Boolean =
        synchronized(lock) {
            val grant = approvedGateChallenge ?: return@synchronized false
            val now = System.currentTimeMillis()
            if (grant.expiresAtMs < now) {
                approvedGateChallenge = null
                return@synchronized false
            }
            val currentSession = machine.snapshot().sessionId
            grant.gateClass == gateClass &&
                grant.action == action &&
                grant.signature == gateSignature(action, labels) &&
                (grant.sessionId.isBlank() || grant.sessionId == currentSession)
        }

    fun consumeGateApproval(gateClass: OverlayGateClass, action: String, labels: List<String>): Boolean =
        synchronized(lock) {
            val grant = approvedGateChallenge ?: return@synchronized false
            val now = System.currentTimeMillis()
            if (grant.expiresAtMs < now) {
                approvedGateChallenge = null
                return@synchronized false
            }
            val currentSession = machine.snapshot().sessionId
            val matches = grant.gateClass == gateClass &&
                grant.action == action &&
                grant.signature == gateSignature(action, labels) &&
                (grant.sessionId.isBlank() || grant.sessionId == currentSession)
            if (matches) approvedGateChallenge = null
            matches
        }

    fun dispatch(action: OverlayUserAction) {
        val before = snapshot()
        if (action == OverlayUserAction.ASK_CYCLONE) {
            val waitingForSecret = WorkspaceTasks.state.value?.interruption?.kind ==
                TaskInterruptionKind.NEEDS_SECRET
            if (waitingForSecret && com.cyclone.mobile.secrets.SecretsCardRuntime.reopenWaiting()) {
                return
            }
        }
        if (action == OverlayUserAction.TAKE_CONTROL) {
            WorkspaceTasks.state.value?.takeIf { it.foreground && it.taskId == foregroundTaskId }?.let { task ->
                val context = synchronized(lock) { service }
                if (context != null) {
                    com.cyclone.mobile.task.TaskCommands.send(context, task.taskId,
                        if (before.userPaused) com.cyclone.mobile.task.TaskCommand.Done else com.cyclone.mobile.task.TaskCommand.TakeOver)
                    return
                }
            }
        }
        if (action == OverlayUserAction.GATE_CONFIRM) approvePendingGateChallenge(before)
        mutate { it.dispatch(action) }
        when (action) {
            OverlayUserAction.EXIT, OverlayUserAction.STOP_TASK -> {
                missionHooks?.stop()
                val context = synchronized(lock) { service }
                synchronized(lock) {
                    adaptiveAgent?.cancelActiveTask()
                    adaptiveAgent = null
                    suspendedTaskId = null
                    pendingGateChallenge = null
                    approvedGateChallenge = null
                    aiJob?.cancel()
                    aiJob = null
                }
                context?.let {
                    foregroundTaskId?.let { id -> WorkspaceTasks.update(id) { task -> task.copy(phase = TaskPhase.STOPPED, resumable = false) } }
                    AgentTaskNotificationRuntime.finish(it, false, "Task stopped.")
                    com.cyclone.mobile.runtime.background.WorkspaceTasks.scheduleQueuePromotion(it)
                }
            }
            OverlayUserAction.GATE_CONFIRM -> resumeSuspendedTask()
            OverlayUserAction.TAKE_CONTROL -> {
                if (before.userPaused && DeviceState.controller == DeviceState.Controller.AGENT) {
                    resumeSuspendedTask()
                }
            }
            else -> Unit
        }
    }

    fun updateComposer(text: String) {
        mutate { it.updateComposer(text) }
    }

    /** Composer animation is not execution ownership. Suspended/GATE tasks still own their slot. */
    fun hasExecutingTask(): Boolean = synchronized(lock) {
        aiJob?.isActive == true || suspendedTaskId != null || pendingGateChallenge != null || missionHooks != null
    }

    /**
     * A new request, typed or spoken. [driving] is Drive's (plan 32): the work goes to the background when the phone
     * can do it, otherwise it borrows the screen and gives it back to the app the owner was in ([DriveScreen]).
     */
    /**
     * [onModes] (plan 42, Live voice) hears how the request ended: done by Instant, answered, or handed to a mission.
     * Every path that does not reach the modes router reports it as handed on, so a caller never waits forever.
     */
    fun submitRequest(text: String, driving: Boolean = false, onModes: ((com.cyclone.mobile.mind.modes.ModeResult) -> Unit)? = null) {
        val handedOn = { onModes?.invoke(com.cyclone.mobile.mind.modes.ModeResult(com.cyclone.mobile.mind.modes.Mode.MIND, null, true, promoted = true)); Unit }
        val request = text.trim().take(2_000)
        if (request.isBlank()) return handedOn()
        voiceOwnedAt = if (driving) android.os.SystemClock.elapsedRealtime() else 0L
        if (driving) synchronized(lock) { service }?.let { DriveScreen.begin(it) }
        if (missionHooks?.ownerText(request) == true) {
            updateComposer("")
            return handedOn()
        }
        val context = synchronized(lock) { service } ?: return handedOn()
        synchronized(lock) { controller?.keyboardClosed() }
        val busy = !com.cyclone.mobile.runtime.background.WorkspaceTasks.canStartRequest()
        if (busy) {
            runCatching { com.cyclone.mobile.runtime.background.WorkspaceTasks.queueRequest(request) }
                .onSuccess { updateComposer("") }
                .onFailure { android.widget.Toast.makeText(context, it.message, android.widget.Toast.LENGTH_LONG).show() }
            return handedOn()
        }
        // Only explicit intent selects isolation. Naming an app is ordinary foreground use.
        val target = com.cyclone.mobile.runtime.background.ExecutionTargetResolver.resolve(request)
        if (target is com.cyclone.mobile.runtime.background.ExecutionTarget.Profile) {
            android.widget.Toast.makeText(context, "Open the requested profile in Profiles before continuing.", android.widget.Toast.LENGTH_LONG).show()
            return handedOn()
        }
        // Alpha 91: the launcher apps and their labels are read once a minute, not on the main thread for every request
        // (loading every label cost a 0.5 s freeze on each Ask).
        val apps = launcherApps(context)
        val matches = apps.filter { (_, label) ->
            label.length >= 3 && Regex("(?i)(?<![\\p{L}\\p{N}])" + Regex.escape(label) + "(?![\\p{L}\\p{N}])").containsMatchIn(request)
        }.map { it.first }
        if (target == com.cyclone.mobile.runtime.background.ExecutionTarget.BackgroundWorkspace) {
            synchronized(lock) { adaptiveAgent?.cancelActiveTask(); aiJob?.cancel() }
            if (matches.size == 1) {
                val app = matches.single()
                runCatching { com.cyclone.mobile.runtime.background.WorkspaceTasks.start(context, request,
                    app.activityInfo.packageName, app.loadLabel(context.packageManager).toString()) }
                    .onFailure { error -> clearBackgroundChrome(); android.widget.Toast.makeText(context, error.message ?: "Open Background tasks setup to recheck access.", android.widget.Toast.LENGTH_LONG).show() }
                updateComposer("")
            } else {
                context.startActivity(android.content.Intent(context, com.cyclone.mobile.runtime.background.WorkspaceActivity::class.java)
                    .putExtra("goal", request).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return handedOn()
        }
        // Alpha.78: a spoken request stays in voice mode. The Ask panel (analysis, the composer) is for typed requests;
        // voice shows its own panel and speaks the result, so the request goes straight to the modes router.
        if (driving) {
            synchronized(lock) { pendingGateChallenge = null; approvedGateChallenge = null }
            return runAiRequest(request, null, onModes)
        }
        val accepted = synchronized(lock) {
            pendingGateChallenge = null
            approvedGateChallenge = null
            if (machine.state() !in setOf(OverlayChromeState.ANALYSIS, OverlayChromeState.WORKING, OverlayChromeState.LIVE)) {
                machine.startAnalysis(java.util.UUID.randomUUID().toString())
            }
            val before = machine.snapshot()
            machine.submitRequest(request)
            val changed = before.state == OverlayChromeState.ANALYSIS ||
                before.state == OverlayChromeState.WORKING ||
                before.state == OverlayChromeState.LIVE
            mutableActivity.value = machine.state()
            controller?.render(machine.snapshot())
            changed
        }
        if (accepted) {
            val launch = matches.singleOrNull()?.takeIf {
                !PendingTaskAttachment.present.value && com.cyclone.mobile.runtime.background.ExecutionTargetResolver.isSimpleLaunch(
                    request, it.loadLabel(context.packageManager).toString())
            }?.activityInfo?.packageName
            runAiRequest(request, launch, onModes)
        } else handedOn()
    }

    private const val VOICE_OWNS_MS = 10_000L
    private const val APPS_TTL_MS = 60_000L
    @Volatile private var appsCache: Pair<Long, List<Pair<android.content.pm.ResolveInfo, String>>>? = null

    /** The launcher apps with their labels, kept for a minute. */
    private fun launcherApps(context: android.content.Context): List<Pair<android.content.pm.ResolveInfo, String>> {
        val now = android.os.SystemClock.elapsedRealtime()
        appsCache?.let { (at, apps) -> if (now - at < APPS_TTL_MS) return apps }
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName }.distinctBy { it.activityInfo.packageName }
            .map { it to it.loadLabel(pm).toString() }
        appsCache = now to apps
        return apps
    }

    /** Voice's Stop (alpha.78): stops a quick action that voice started, at once. Missions stop through Task Kit. */
    fun stopVoiceRequest() {
        com.cyclone.mobile.mind.modes.CycloneModes.cancel()
    }

    fun updateVoice(listening: Boolean, transcript: String? = null, message: String? = null) {
        mutate { it.updateVoice(listening, transcript, message) }
    }

    fun beginVoiceInput() {
        synchronized(lock) { controller?.beginVoiceInput() }
    }

    private fun runAiRequest(request: String, launchPackage: String? = null,
                             onModes: ((com.cyclone.mobile.mind.modes.ModeResult) -> Unit)? = null) {
        val handedOn = { onModes?.invoke(com.cyclone.mobile.mind.modes.ModeResult(com.cyclone.mobile.mind.modes.Mode.MIND, null, true, promoted = true)); Unit }
        val context = synchronized(lock) { service } ?: return handedOn()
        synchronized(lock) {
            aiJob?.cancel()
            adaptiveAgent?.cancelActiveTask()
        }
        // Taken exactly once per request; a simple app launch never carries an attachment.
        val attachment = if (launchPackage == null) PendingTaskAttachment.take() else null
        // Cyclone Mind: one model, one conversation, one mission. The classic agent remains for owners who turn it off.
        // Plan 42: the modes router picks Instant, Flash or the Mind first; a request while a mission runs still goes to
        // that mission ("Runs next" or a steer).
        if (com.cyclone.mobile.mind.mission.MindMissions.enabled(context)) {
            com.cyclone.mobile.mind.modes.CycloneModes.handle(context, request, attachment, voice = onModes != null) { onModes?.invoke(it) }
            return
        }
        handedOn()
        val shared = TaskHarnessState.applyTrajectory(
            WorkspaceTaskUi(
                "foreground-${java.util.UUID.randomUUID()}",
                "default-foreground",
                "your app",
                launchPackage ?: "",
                request,
                phase = TaskPhase.WORKING,
                displayId = 0,
                startedAtMs = System.currentTimeMillis(),
            ),
            com.cyclone.mobile.agent.plan.TaskTrajectory.seed(request),
        )
        WorkspaceTasks.publishStart(shared)
        foregroundTaskId = shared.taskId
        AgentTaskNotificationRuntime.start(context)
        val agent = OpenRouterAdaptiveAgent(context).also { agent ->
            var revision = 0L
            agent.onTrajectory = { trajectory ->
                WorkspaceTasks.update(shared.taskId) { task ->
                    TaskHarnessState.applyTrajectory(task, trajectory)
                }
            }
            agent.onTraceSession = { traceId ->
                WorkspaceTasks.update(shared.taskId) { it.copy(traceSessionId = traceId) }
            }
            agent.onOperation = { tool, result ->
                WorkspaceTasks.update(shared.taskId) { task ->
                    if (result == null) { revision = task.controlRevision; TaskHarnessState.begin(task, tool) }
                    else TaskHarnessState.finish(task, TaskOperationEvidence("default-foreground", 0, revision,
                        result.androidExecutionOk, result.verification.passed,
                        result.afterObservationId != null && result.afterObservationId != result.beforeObservationId &&
                            result.after?.sessionId == "default-foreground" && result.after?.displayId == 0,
                        result.verification.basis))
                }
            }
        }
        synchronized(lock) {
            adaptiveAgent = agent
            suspendedTaskId = null
        }
        val job = aiScope.launch {
            try {
            mutate {
                it.enterWorking()
                it.updateStatus("Starting…")
            }
            if (launchPackage != null) {
                val launchTrace = AgentTraceRuntime.start(context, request, "local-launch")
                val outcome = runCatching {
                    val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        com.cyclone.mobile.PhoneToolExecutor.execute(context, com.cyclone.mobile.PhoneToolRequest(
                            java.util.UUID.randomUUID().toString(), "phone.open_app",
                            org.json.JSONObject().put("package", launchPackage)
                                .put("sessionId", "default-foreground").put("displayId", 0)))
                    }
                    check(result.ok) { result.error?.message ?: "Couldn't open the app." }
                    var observed = false
                    repeat(10) {
                        if (com.cyclone.mobile.runtime.background.BackgroundSetup.foregroundPackage() == launchPackage) observed = true
                        if (!observed) kotlinx.coroutines.delay(150)
                    }
                    check(observed) { "Couldn't verify the app opened." }
                }
                AgentTraceRuntime.finish(context, launchTrace,
                    if (outcome.isSuccess) "COMPLETED" else if (outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException) "CANCELLED" else "FAILED",
                    if (outcome.isSuccess) "App opened and verified." else "App launch failed or was stopped.", 1)
                outcome.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
                handleAgentResult(QuickAgentResult(outcome.isSuccess,
                    if (outcome.isSuccess) "App opened." else outcome.exceptionOrNull()?.message ?: "Couldn't open the app.",
                    1, "local-launch", taskId = launchTrace, classification = if (outcome.isSuccess) "COMPLETE" else "FAILED"), shared.taskId)
                return@launch
            }
            val settings = readAiSettings(context)
            val accessProfile = CycloneAiAccessProfileStore.read(context)
            val config = QuickAgentConfig(
                attachment = attachment,
                model = OpenRouterModelPresets.byId(settings.modelId).copy(
                    reasoningEffort = settings.reasoningEffort,
                ),
                safeMode = accessProfile != CycloneAiAccessProfile.FULL,
                accessProfile = accessProfile,
            )
            val progress: (String) -> Unit = { status ->
                val clean = status.trim()
                // Brain consolidation is internal bookkeeping. Do not surface it as a task/composer
                // state; the trace already records the successful local Brain write.
                if (!clean.equals(INTERNAL_BRAIN_UPDATED, ignoreCase = true)) {
                    AgentTaskNotificationRuntime.progress(context, clean)
                    mutate { it.updateStatus("Checking the current page") }
                }
            }
            val result = agent.execute(request, config, progress)
            handleAgentResult(result, shared.taskId)
            } catch (error: Exception) {
                val cancelled = error is kotlinx.coroutines.CancellationException
                val result = QuickAgentResult(false,
                    if (cancelled) "Request stopped." else "Request startup failed (${error.javaClass.simpleName}). Open Outcomes for details.",
                    0, "request-startup", taskId = agent.requestTraceId,
                    classification = if (cancelled) "CANCELLED" else "HARD_BLOCKER")
                val recorded = if (result.taskId == null && launchPackage == null)
                    persistPreflightFailure(context, request, result.model, result) else result
                handleAgentResult(recorded, shared.taskId)
                if (cancelled) throw error
            }
        }
        synchronized(lock) { aiJob = job }
    }

    private fun persistPreflightFailure(
        context: Context,
        request: String,
        modelId: String,
        result: QuickAgentResult,
    ): QuickAgentResult {
        val traceId = AgentTraceRuntime.start(context, request, modelId)
        val classification = result.classification ?: preflightClassification(result.message)
        AgentTraceRuntime.event(
            context = context,
            sessionId = traceId,
            kind = "OBSERVATION_FAILED",
            displayText = "Initial screen observation failed",
            code = "observation.initial_failed",
            ok = false,
            detail = result.message,
        )
        AgentTraceRuntime.finish(context, traceId, if (classification == "CANCELLED") "CANCELLED" else "FAILED", result.message, result.decisions)
        return result.copy(taskId = traceId, classification = classification)
    }

    private fun preflightClassification(message: String): String {
        val terminal = listOf(
            "permission",
            "service is unavailable",
            "disconnected",
            "session or display",
            "target app is not observable",
            "workspace",
        ).any { message.contains(it, ignoreCase = true) }
        return if (terminal) CycloneTaskClassification.HARD_BLOCKER.name
        else CycloneTaskClassification.NON_CONVERGENCE.name
    }

    /** Exact-task service command; retains the original foreground agent and controller machinery. */
    /**
     * The classic foreground agent's side of Task Kit (ClassicForegroundTaskController). Returns null when the command
     * was carried out, or the reason it could not be. Surfaces reach this only through TaskCommands.
     */
    fun commandForegroundTask(id: String, command: String): String? {
        val task = WorkspaceTasks.state.value?.takeIf { it.foreground && it.taskId == id && id == foregroundTaskId }
            ?: return "This task is no longer the foreground task."
        when (command) {
            "handoff", "pause" -> {
                if (!task.working && task.interruption?.canTakeOver != true && task.phase != TaskPhase.DONE) return "Cyclone is not working on it right now."
                WorkspaceTasks.update(id) { it.copy(phase = TaskPhase.HUMAN) }
                DeviceState.setController(DeviceState.Controller.HUMAN)
                if (!snapshot().userPaused) mutate { it.dispatch(OverlayUserAction.TAKE_CONTROL) }
            }
            "resume" -> {
                if (task.interruption?.canResumeAfterHuman != true) return "The task is not waiting for you."
                resumeForeground(id, task)
            }
            "autofill" -> {
                if (task.interruption?.canAutofill != true) return "There is no sign-in to fill."
                adaptiveAgent?.authorizeAutofill()
                DeviceState.setController(DeviceState.Controller.AGENT)
                resumeForeground(id, task, requireResumeCapability = false)
            }
            "cancel" -> {
                dispatch(OverlayUserAction.STOP_TASK)
                WorkspaceTasks.clearClosedTask(id, task.sessionId)
                service?.let { AgentTaskNotificationRuntime.cancel(it) }
                foregroundTaskId = null
            }
            else -> return "Unknown command $command."
        }
        return null
    }

    private fun resumeForeground(
        id: String,
        task: WorkspaceTaskUi,
        requireResumeCapability: Boolean = true,
    ) {
        val prior = synchronized(lock) {
            if (foregroundResuming) return
            foregroundResuming = true
            aiJob
        }
        aiScope.launch {
            try {
                prior?.join()
                val current = WorkspaceTasks.state.value
                if (current?.taskId != id || current.controlRevision != task.controlRevision) return@launch
                if (requireResumeCapability && current.interruption?.canResumeAfterHuman != true) return@launch
                DeviceState.setController(DeviceState.Controller.AGENT)
                if (snapshot().userPaused) mutate { it.dispatch(OverlayUserAction.TAKE_CONTROL) }
                resumeSuspendedTask()
            } finally {
                synchronized(lock) { foregroundResuming = false }
            }
        }
    }

    private fun resumeSuspendedTask() {
        val agent = synchronized(lock) { adaptiveAgent } ?: return
        val taskId = synchronized(lock) { suspendedTaskId } ?: return
        val context = synchronized(lock) { service } ?: return
        val expectedTaskId = foregroundTaskId
        val job = aiScope.launch {
            mutate { machine ->
                when (machine.state()) {
                    OverlayChromeState.DONE -> {
                        machine.startAnalysis(taskId)
                        machine.enterWorking(taskId)
                    }
                    OverlayChromeState.LIVE -> machine.enterWorking(taskId)
                    OverlayChromeState.WORKING -> Unit
                    else -> return@mutate
                }
                machine.updateStatus("Re-observing after handoff…")
            }
            foregroundTaskId?.let { id -> WorkspaceTasks.update(id) { it.copy(phase = TaskPhase.WORKING, message = "Checking the current page") } }
            AgentTaskNotificationRuntime.progress(context, "Checking the current page")
            val result = agent.resume { progress ->
                val clean = progress.trim()
                if (!clean.equals(INTERNAL_BRAIN_UPDATED, ignoreCase = true)) {
                    AgentTaskNotificationRuntime.progress(context, clean)
                    mutate { it.updateStatus("Checking the current page") }
                }
            }
            handleAgentResult(result, expectedTaskId)
        }
        synchronized(lock) { aiJob = job }
    }

    private fun handleAgentResult(result: QuickAgentResult, expectedTaskId: String? = foregroundTaskId) {
        if (expectedTaskId != foregroundTaskId) return
        val context = synchronized(lock) { service }
        val secretWall = if (result.classification == "HUMAN_OR_GATE") {
            synchronized(lock) { adaptiveAgent }?.currentSecretWallRequest()
        } else null

        foregroundTaskId?.let { id ->
            WorkspaceTasks.update(id) { task ->
                when {
                    secretWall != null -> task.copy(
                        message = "Secure input is required to continue.",
                        outcome = null,
                        phase = TaskPhase.REVIEW,
                        resumable = true,
                        loginAutofill = false,
                        interruption = TaskInterruption.needsSecret(),
                    )
                    !task.working && result.classification == "HUMAN_OR_GATE" -> task.copy(
                        resumable = true,
                        outcome = null,
                        loginAutofill = result.gateClass == "login",
                    )
                    else -> {
                        val nextPhase = when (result.classification) {
                            "COMPLETE" -> TaskPhase.DONE
                            "HUMAN_OR_GATE" -> TaskPhase.REVIEW
                            "CANCELLED" -> TaskPhase.STOPPED
                            else -> TaskPhase.FAILED
                        }
                        val safeOutcome = when (nextPhase) {
                            TaskPhase.DONE -> WorkspaceCopy.result(result.message)
                            TaskPhase.FAILED -> OutcomeStageCopy.terminalFailure(
                                task.plannedStages,
                                result.message,
                                resumable = false,
                            )
                            TaskPhase.STOPPED -> "The task was stopped."
                            else -> null
                        }
                        task.copy(
                            message = safeOutcome ?: result.message,
                            outcome = safeOutcome,
                            phase = nextPhase,
                            resumable = result.classification == "HUMAN_OR_GATE",
                            loginAutofill = result.gateClass == "login",
                        )
                    }
                }
            }
        }

        when (result.classification) {
            "HUMAN_OR_GATE" -> {
                synchronized(lock) { suspendedTaskId = result.taskId }
                if (secretWall != null && context != null) {
                    val task = foregroundTaskId?.let { id ->
                        WorkspaceTasks.state.value?.takeIf { it.taskId == id }
                    }
                    val expectedRevision = task?.controlRevision
                    AgentTaskNotificationRuntime.waiting(context, "Secure input is required to continue.")
                    if (task != null && expectedRevision != null) {
                        com.cyclone.mobile.secrets.SecretsPhoneFacade.requestForRun(
                            context = context,
                            request = secretWall.request,
                            target = secretWall.target,
                        ) { resolution ->
                            if (!resolution.taskMayResume) return@requestForRun
                            aiScope.launch {
                                val current = WorkspaceTasks.state.value
                                if (current?.taskId != task.taskId ||
                                    current.controlRevision != expectedRevision ||
                                    current.interruption?.kind != TaskInterruptionKind.NEEDS_SECRET
                                ) return@launch
                                resumeForeground(
                                    id = task.taskId,
                                    task = current,
                                    requireResumeCapability = false,
                                )
                            }
                        }
                    }
                    mutate { machine ->
                        if (machine.state() == OverlayChromeState.WORKING) machine.enterLive()
                        machine.updateStatus("Secure input is required to continue.")
                    }
                } else {
                    context?.let { AgentTaskNotificationRuntime.waiting(it, result.message) }
                    val gate = result.gateClass?.let { raw ->
                        runCatching { OverlayGateClass.parse(raw) }.getOrNull()
                    }
                    if (gate != null) {
                        mutate { it.enterGate(gate, sessionId = result.taskId ?: it.snapshot().sessionId) }
                    } else {
                        mutate { machine ->
                            if (machine.state() == OverlayChromeState.WORKING) machine.enterLive()
                            machine.updateStatus(result.message)
                            if (!machine.snapshot().userPaused) machine.dispatch(OverlayUserAction.TAKE_CONTROL)
                        }
                    }
                }
            }
            "COMPLETE" -> {
                context?.let { AgentTaskNotificationRuntime.finish(it, true, result.message) }
                synchronized(lock) {
                    suspendedTaskId = null
                    adaptiveAgent = null
                    aiJob = null
                }
                when (snapshot().state) {
                    OverlayChromeState.GATE, OverlayChromeState.IDLE -> Unit
                    OverlayChromeState.WORKING, OverlayChromeState.LIVE -> mutate { it.completeDone() }
                    OverlayChromeState.DONE -> Unit
                    else -> Unit
                }
            }
            "CANCELLED" -> {
                context?.let { AgentTaskNotificationRuntime.finish(it, false, result.message) }
                synchronized(lock) {
                    suspendedTaskId = null
                    adaptiveAgent = null
                    aiJob = null
                }
                mutate { it.finishStopped(result.message) }
            }
            else -> {
                context?.let { AgentTaskNotificationRuntime.finish(it, false, result.message) }
                synchronized(lock) { suspendedTaskId = null; adaptiveAgent = null; aiJob = null }
                mutate { it.finishStopped(result.message) }
            }
        }
        if (result.classification != "HUMAN_OR_GATE") {
            context?.let { com.cyclone.mobile.runtime.background.WorkspaceTasks.scheduleQueuePromotion(it) }
        }
    }

    private fun mutate(block: (OverlayChromeMachine) -> Unit) {
        synchronized(lock) {
            block(machine)
            mutableActivity.value = machine.state()
            controller?.render(machine.snapshot())
        }
    }

    private fun approvePendingGateChallenge(before: OverlayChromeSnapshot) {
        synchronized(lock) {
            val pending = pendingGateChallenge ?: return
            val now = System.currentTimeMillis()
            if (before.state != OverlayChromeState.GATE ||
                before.gateClass != pending.gateClass ||
                pending.expiresAtMs < now ||
                (pending.sessionId.isNotBlank() && pending.sessionId != before.sessionId)
            ) {
                pendingGateChallenge = null
                return
            }
            approvedGateChallenge = pending.copy(expiresAtMs = now + GATE_APPROVAL_TTL_MS)
            pendingGateChallenge = null
        }
    }

    private fun gateSignature(action: String, labels: List<String>): String =
        buildString {
            append(action.trim().lowercase())
            append('|')
            labels.asSequence()
                .map { it.trim().lowercase().replace(Regex("\\s+"), " ") }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .forEach { label -> append(label).append('|') }
        }

    private const val GATE_CHALLENGE_TTL_MS = 60_000L
    private const val GATE_APPROVAL_TTL_MS = 30_000L
    private const val INTERNAL_BRAIN_UPDATED = "Cyclone Brain updated"

    private fun readAiSettings(context: Context): OverlayAiSettings {
        val prefs = context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
        val modelId = com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context)
        val effort = prefs.getString(EFFORT_KEY, "medium").orEmpty()
            .takeIf { it in REASONING_LEVELS } ?: "medium"
        return OverlayAiSettings(modelId, effort)
    }

    private fun saveAiSettings(context: Context, settings: OverlayAiSettings) {
        val canonical = OpenRouterModelPresets.byId(settings.modelId).id
        val modelId = canonical.takeIf { it in com.cyclone.mobile.ai.OpenRouterCatalogStore.selectedIds(context) }.orEmpty()
        val effort = settings.reasoningEffort.takeIf { it in REASONING_LEVELS } ?: "medium"
        context.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE).edit()
            .putString(MODEL_KEY, modelId)
            .putString(EFFORT_KEY, effort)
            .apply()
    }

    private const val AI_PREFS = "cyclone_ai"
    private const val MODEL_KEY = "openrouter_model"
    private const val EFFORT_KEY = "openrouter_reasoning_effort"
    private val REASONING_LEVELS = setOf("low", "medium", "high", "max")
}
