package com.cyclone.mobile.mind.mission

import android.content.Context
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironment
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.ProviderCancellation
import com.cyclone.mobile.mind.MindBudget
import com.cyclone.mobile.mind.MindCheckpoint
import com.cyclone.mobile.mind.MindConversation
import com.cyclone.mobile.mind.MindListener
import com.cyclone.mobile.mind.MindLoop
import com.cyclone.mobile.mind.MindMessage
import com.cyclone.mobile.mind.MindModel
import com.cyclone.mobile.mind.MindOutcome
import com.cyclone.mobile.mind.MindPlanStep
import com.cyclone.mobile.mind.MindPrompt
import com.cyclone.mobile.mind.MindStatus
import com.cyclone.mobile.mind.MindToolCall
import com.cyclone.mobile.mind.MindToolResult
import com.cyclone.mobile.mind.OpenRouterMindModel
import com.cyclone.mobile.mind.PhoneMindToolbox
import com.cyclone.mobile.runtime.background.TaskInterruption
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.TaskAttachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Cyclone Mind on the phone: one model, one continuous conversation per mission, journaled after every turn so an
 * interrupted mission can pick up where it stopped. This object only wires the engine to Android; the decisions are
 * the model's and the boundaries are the harness's.
 *
 * Plan 26 §6 (parallel sessions, alpha.65): one mission is in **front** (the owner's screen, the overlay, the pill,
 * the task card) and up to two work **behind** it, each on a background screen of its own, with a quiet notification
 * each. A behind mission never touches the owner's screen: when it needs it, it waits and comes to the front when the
 * front mission ends. All missions share one [OwnerInbox]; the owner sees one question at a time.
 */
object MindMissions {
    private const val PREFS = "cyclone_ai"
    private const val ENABLED_KEY = "mind_runtime_enabled"
    private const val MINUTES_KEY = "mind_mission_minutes"
    private const val PARALLEL_KEY = "mind_parallel_enabled"
    private const val WORKSPACE_KEY = "mind_workspace_enabled"

    val inbox = OwnerInbox()
    private val liveState = MutableStateFlow<Mission?>(null)
    private val historyState = MutableStateFlow<List<Mission>>(emptyList())
    private val behindState = MutableStateFlow<List<Mission>>(emptyList())
    private val behindTasksState = MutableStateFlow<List<WorkspaceTaskUi>>(emptyList())
    /** The front mission. */
    val live: StateFlow<Mission?> = liveState
    val history: StateFlow<List<Mission>> = historyState
    /** Missions working behind the front one, oldest first. */
    val behind: StateFlow<List<Mission>> = behindState
    /** Their task cards (Task Kit reaches them by id; they are never the front card). */
    val behindTasks: StateFlow<List<WorkspaceTaskUi>> = behindTasksState

    private val lock = Any()
    private val runs = LinkedHashMap<String, Run>()
    @Volatile private var store: MissionStore? = null
    @Volatile private var recovered = false
    @Volatile private var resumeCandidate: String? = null
    private const val AUTO_RESUME_WINDOW_MS = 5 * 60_000L
    private const val AUTO_RESUME_LIMIT = 3
    private const val QUEUE_START_DELAY_MS = 1_500L
    @Volatile private var memory: com.cyclone.mobile.mind.MindMemory? = null

    /** One running mission and everything that belongs to it alone. */
    private class Run(initial: Mission, @Volatile var front: Boolean) {
        val id: String = initial.id
        val taskId = "mission-$id"
        val startedAt = System.currentTimeMillis()
        @Volatile var mission: Mission = initial
        @Volatile var stopRequested = false
        /** Plan 42: the baton from a lower mode, and whether this is a Flash run. */
        @Volatile var handover: String? = null
        @Volatile var flash = false
        /** Plan 43 T6: the app whose sign-up this mission maps. */
        @Volatile var signup: String? = null
        /** Plan 43 T7: the account this mission creates, with its sign-up map and values. */
        @Volatile var setup: com.cyclone.mobile.mind.signup.AccountSetupPlan? = null
        val cancellation = ProviderCancellation()
        val ownerMessages = ConcurrentLinkedQueue<String>()
        /** Plan 38: the owner's steers; each becomes a new goal version at the next step. */
        val steers = ConcurrentLinkedQueue<String>()
        /** Plan 38: paused by the owner; the loop holds before its next step. */
        @Volatile var paused = false
        /** Saves a change to the mission (set while it runs). */
        @Volatile var saver: (((Mission) -> Mission) -> Unit)? = null
        @Volatile var thread: Thread? = null
        @Volatile var planes: com.cyclone.mobile.runtime.plane.MissionPlaneSession? = null
        @Volatile var metrics: com.cyclone.mobile.mind.lab.MissionMetrics? = null
        @Volatile var trail: com.cyclone.mobile.mind.learn.MindTrailRecorder? = null
        /** The task card while behind; the front mission's card lives in [WorkspaceTasks]. */
        @Volatile var card: WorkspaceTaskUi? = null
    }

    private val hooks = object : OverlayChromeRuntime.MissionHooks {
        override fun stop() = MindMissions.stop()
        override fun ownerText(text: String): Boolean = steer(text)
    }

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED_KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED_KEY, enabled).apply()
    }

    fun workingMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(MINUTES_KEY, 30).coerceIn(5, 120)

    fun setWorkingMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(MINUTES_KEY, minutes.coerceIn(5, 120)).apply()
    }

    /** Plan 26 §6: tasks at the same time (behind the front one). On by default where the phone can do it. */
    fun parallelEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PARALLEL_KEY, true)

    fun setParallelEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PARALLEL_KEY, enabled).apply()
    }

    /** Plan 37: the mission workspace for the owner's missions. Off until the Lab promotes it. */
    fun workspaceEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(WORKSPACE_KEY, false)

    fun setWorkspaceEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(WORKSPACE_KEY, enabled).apply()
    }

    /** How many missions may work behind the front one on this phone. */
    fun behindSlots(context: Context): Int = runCatching {
        val info = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
        Crew.behindSlots(info.totalMem)
    }.getOrDefault(0)

    /** Where a new task would go now: in front, behind, or the queue (with why). */
    fun admission(context: Context, goal: String, lab: Boolean = false): Crew.Admit {
        val (frontLive, behindRunning) = synchronized(lock) { runs.values.any { it.front } to runs.values.count { !it.front } }
        // A Lab run in front is a measurement: nothing runs next to it (and it may hold the screen without a plane).
        val frontLab = synchronized(lock) { runs.values.any { it.front && it.mission.lab != null } }
        return Crew.admit(Crew.Facts(
            enabled = parallelEnabled(context),
            frontLive = frontLive || isLive(),
            behindRunning = behindRunning,
            slots = behindSlots(context),
            backgroundBlocker = if (frontLive) com.cyclone.mobile.runtime.plane.MissionPlanes.blocker(context) else null,
            lab = lab || frontLab,
            needsHands = Crew.needsHands(goal),
        ))
    }

    /** A new task can start now (in front or behind). */
    fun canAccept(context: Context, goal: String = ""): Boolean = admission(context, goal) !is Crew.Admit.Queue

    /** Learned-screen advice for the Mind; missing knowledge (or a store that will not open) just means no advice. */
    /** Plan 48 run 4: the mission's Cyclone Ports, only while the owner's PC is polling this phone. */
    private fun portsLink(runId: String, allowed: Boolean): com.cyclone.mobile.mind.MindPortsLink? =
        if (allowed && com.cyclone.mobile.ports.PortOutbox.shared.connected()) com.cyclone.mobile.ports.PortOutboxLink(com.cyclone.mobile.ports.PortOutbox.shared, runId)
        else null

    private fun learnedHints(context: Context): ((String, String) -> String?)? = runCatching {
        com.cyclone.mobile.applearner.AppLearnerRuntime.initialize(context.applicationContext)
        val hints = com.cyclone.mobile.mind.learn.LearnedHints(com.cyclone.mobile.mind.learn.AppKnowledgeReader(com.cyclone.mobile.applearner.AppLearnerRuntime.store))
        val fn: (String, String) -> String? = { pkg, key -> hints.forScreen(pkg, key) }
        fn
    }.getOrNull()

    /** The learned maps for one mission, with walks and surprises fed back into the app knowledge store. */
    private fun missionMaps(context: Context): com.cyclone.mobile.mind.map.MindMaps? = runCatching {
        com.cyclone.mobile.applearner.AppLearnerRuntime.initialize(context.applicationContext)
        val store = com.cyclone.mobile.applearner.AppLearnerRuntime.store
        com.cyclone.mobile.mind.map.MindMaps(com.cyclone.mobile.mind.learn.AppKnowledgeReader(store)) { pkg ->
            com.cyclone.mobile.mind.map.AppKnowledgeMapFeedback(store, pkg)
        }
    }.getOrNull()

    fun store(context: Context): MissionStore = store ?: synchronized(lock) {
        store ?: MissionStore(File(context.applicationContext.filesDir, "Cyclone Brain/Missions")).also { store = it }
    }

    fun refresh(context: Context) {
        val missions = store(context)
        if (!recovered) {
            recovered = true
            val now = System.currentTimeMillis()
            val running = synchronized(lock) { runs.keys.toSet() }
            // A mission that was working moments ago died with the process, not by the owner's choice.
            resumeCandidate = missions.list().firstOrNull {
                it.status == MissionStatus.RUNNING && it.id !in running &&
                    now - it.updatedAtMs <= AUTO_RESUME_WINDOW_MS && it.resumes < AUTO_RESUME_LIMIT
            }?.id
            missions.recover(now, liveState.value?.id)
        }
        historyState.value = missions.list()
    }

    /**
     * Called when Cyclone's Accessibility service (re)connects. A mission interrupted by a crash or process death a
     * few minutes ago continues on its own; anything older waits for the owner's Resume.
     */
    fun onServiceReady(context: Context) {
        refresh(context)
        // Plan 26: is background work still ready? Told once, quietly, not in the middle of a task.
        runCatching { com.cyclone.mobile.runtime.plane.BackgroundWatch.check(context) }
        // Plan 28: after an install or update, check the background path once, on a hidden screen, before a task needs it.
        runCatching { com.cyclone.mobile.runtime.plane.BackgroundCheck.maybeAuto(context) }
        val id = resumeCandidate ?: return
        resumeCandidate = null
        if (!enabled(context) || isLive()) return
        val mission = store(context).load(id) ?: return
        if (System.currentTimeMillis() - mission.createdAtMs > 3 * 60 * 60_000L) return
        resume(context, id)
    }

    /** A mission is running (in front, and maybe others behind it). */
    fun isLive(): Boolean = synchronized(lock) { runs.values.any { it.thread?.isAlive == true } }

    /** [missionId] works behind the front mission right now. */
    fun isBehind(missionId: String): Boolean = synchronized(lock) { runs[missionId]?.front == false }

    /** The running mission [missionId] (front or behind), or null. */
    fun find(missionId: String): Mission? = synchronized(lock) { runs[missionId]?.mission }

    @Volatile private var queue: MissionQueue? = null

    fun queue(context: Context): MissionQueue = queue ?: synchronized(lock) {
        queue ?: MissionQueue(File(context.applicationContext.filesDir, "Cyclone Brain/Missions/queue.json")).also { queue = it }
    }

    /**
     * Owner text while a mission runs. A clearly separate task starts behind the front one when the phone can (plan 26
     * §6), otherwise it waits as "Runs next" (A42-8); anything else steers the front mission. Returns the new task's
     * goal, or null when it steered.
     */
    fun offer(context: Context, text: String): String? {
        if (!isLive() || !MissionQueue.isNewTask(text)) { steer(text); return null }
        val goal = text.trim().take(2_000)
        if (admission(context, goal) is Crew.Admit.Behind) {
            val id = newId()
            if (launch(context.applicationContext, Mission(id, goal, MissionStatus.RUNNING, System.currentTimeMillis(), System.currentTimeMillis(), "", ""),
                    resume = null, attachment = null, front = false)) {
                frontCard { it.copy(message = "Also working behind your screen: ${goal.take(80)}") }
                return goal
            }
        }
        val next = queue(context).add(text) ?: run { steer(text); return null }
        frontCard { it.copy(message = "Runs next: ${next.goal.take(80)}") }
        return next.goal
    }

    private fun frontCard(change: (WorkspaceTaskUi) -> WorkspaceTaskUi) {
        val front = synchronized(lock) { runs.values.firstOrNull { it.front } } ?: return
        WorkspaceTasks.update(front.taskId, change)
    }

    private fun appLabel(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().take(80)
    }.getOrDefault(pkg.take(80))

    private fun appVersion(context: Context, pkg: String): String = runCatching {
        context.packageManager.getPackageInfo(pkg, 0).versionName.orEmpty().take(64)
    }.getOrDefault("")

    private fun newId(): String = "m" + System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().take(8)

    /** Starts a new mission in front. Returns false when a mission is already in front. */
    /**
     * [handover] is plan 42's baton: what a lower mode (Instant) already did and why it stopped, read before anything
     * else so the mission continues instead of redoing it. [flash] asks for a quick run: plan it whole, hand routine
     * steps to the Pilot when Fast mode is on.
     */
    fun start(context: Context, goal: String, attachment: TaskAttachment? = null, handover: String? = null, flash: Boolean = false): Boolean {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val mission = Mission(newId(), goal.trim().take(2_000), MissionStatus.RUNNING, now, now, "", "")
        return launch(app, mission, resume = null, attachment = attachment, front = true, handover = handover, flash = flash)
    }

    /**
     * Plan 33 (C0): starts a mission the PC's Command Center assigned. The same Mind and boundaries as a mission the
     * owner types. In front when nothing runs, behind the front one when the phone can (plan 26 §6); returns the
     * mission id so the PC can follow it, or null when the phone cannot take it now.
     */
    fun startAssigned(context: Context, goal: String, signup: String? = null,
                      setup: com.cyclone.mobile.mind.signup.AccountSetupPlan? = null): String? {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val clean = goal.trim().take(2_000)
        val front = when (admission(app, clean)) {
            Crew.Admit.Front -> true
            // Mapping a sign-up or creating an account works on the owner's screen (their details, verification, the
            // final approval): never on a hidden background screen. The PC waits and tries again when the front is free.
            Crew.Admit.Behind -> if (signup != null || setup != null) return null else false
            is Crew.Admit.Queue -> return null
        }
        val mission = Mission(newId(), clean, MissionStatus.RUNNING, now, now, "", "")
        return mission.id.takeIf { launch(app, mission, resume = null, attachment = null, front = front, signup = signup, setup = setup) }
    }

    /**
     * Starts a Cyclone Lab mission: same Mind, same boundaries, with [variant] applied to this one mission and the
     * run tagged so the PC can score it. Always alone and in front. Returns the mission id, or null when busy.
     */
    fun startLab(context: Context, goal: String, runId: String, variant: com.cyclone.mobile.mind.lab.MindLabVariant): String? {
        if (isLive()) return null
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val mission = Mission(newId(), goal.trim().take(2_000), MissionStatus.RUNNING, now, now, "", "",
            lab = com.cyclone.mobile.mind.lab.MissionLab(runId, variant))
        return mission.id.takeIf { launch(app, mission, resume = null, attachment = null, front = true) }
    }

    /** A running mission's metrics so far (the PC lab polls this), or null when [id] is not running. */
    fun liveMetrics(id: String): org.json.JSONObject? = synchronized(lock) { runs[id]?.metrics }?.toJson()

    /** Continues a paused, failed or interrupted mission with its full conversation, in front. */
    fun resume(context: Context, id: String): Boolean {
        val app = context.applicationContext
        val missions = store(app)
        val mission = missions.load(id)?.takeIf { it.status.resumable } ?: return false
        val journal = missions.loadJournal(id) ?: return false
        val reason = when (mission.status) {
            MissionStatus.PAUSED -> "its working time ran out and the owner gave it more"
            MissionStatus.INTERRUPTED -> "Cyclone was stopped or restarted"
            else -> "it failed and the owner asked to try again"
        }
        return launch(app, mission.copy(status = MissionStatus.RUNNING, resumes = mission.resumes + 1, summary = ""), journal, null, reason, front = true)
    }

    /** Stops the front mission (the overlay's Stop). Missions behind it keep working; the oldest comes to the front. */
    fun stop() {
        val front = synchronized(lock) { runs.values.firstOrNull { it.front } } ?: return
        stop(front.id)
    }

    /** Stops one mission, front or behind. False when it is not running. */
    fun stop(missionId: String): Boolean {
        val run = synchronized(lock) { runs[missionId] } ?: return false
        run.stopRequested = true
        run.planes?.releaseGate()
        run.cancellation.cancel()
        inbox.withdrawMission(missionId)
        return true
    }

    private fun frontRun(): Run? = synchronized(lock) { runs.values.firstOrNull { it.front && it.thread?.isAlive == true } }

    // ---- plan 38 (alpha.68): steer, queue, parallel, pause ------------------------------------------------------------

    /** The task the owner is viewing, or the front task. */
    private fun viewed(missionId: String?): Run? = synchronized(lock) { missionId?.let { runs[it] } } ?: frontRun()

    /**
     * Steer: the owner changes the task they are viewing. An open question of that task takes it as the answer;
     * otherwise it is a new goal version the mission re-plans for at its next step. Never asked back.
     */
    fun steerTask(missionId: String?, text: String): Boolean {
        val run = viewed(missionId) ?: return false
        val clean = text.trim().take(2_000)
        if (clean.isBlank()) return false
        inbox.openFor(run.id)?.takeIf { it.kind == OwnerRequestKind.QUESTION }?.let { request ->
            if (inbox.respond(request.id, OwnerResponse.Answer(clean))) return true
        }
        run.steers += clean
        if (run.paused) run.paused = false
        return true
    }

    /** Queue: the text runs as its own task after the current one ends. Returns its goal, or null when it can't. */
    fun queueTask(context: Context, text: String): String? {
        val next = queue(context).add(text) ?: return null
        frontCard { it.copy(message = "Next: ${next.goal.take(80)}") }
        return next.goal
    }

    /** Why a task can't start at the same time right now, or null when it can (behind the screen, or in front when idle). */
    fun parallelBlocker(context: Context, text: String): String? {
        if (!isLive()) return null
        return when (val admit = admission(context, text.trim().take(2_000))) {
            is Crew.Admit.Behind, Crew.Admit.Front -> null
            is Crew.Admit.Queue -> admit.reason
        }
    }

    /** Parallel: the text starts now as its own task, behind the screen. Null when it started, else why not. */
    fun parallelTask(context: Context, text: String): String? {
        val goal = text.trim().take(2_000)
        if (goal.isBlank()) return "The task is empty."
        if (!isLive()) return if (start(context, goal)) null else "Cyclone could not start it."
        parallelBlocker(context, goal)?.let { return it }
        val now = System.currentTimeMillis()
        return if (launch(context.applicationContext, Mission(newId(), goal, MissionStatus.RUNNING, now, now, "", ""), resume = null,
                attachment = null, front = false)) {
            frontCard { it.copy(message = "Also working behind your screen: ${goal.take(80)}") }
            null
        } else "Cyclone could not start it."
    }

    /** Pause: the mission holds before its next step. The screen and app stay as they are. */
    fun pause(context: Context, missionId: String?): Boolean {
        val run = viewed(missionId) ?: return false
        if (run.paused) return true
        run.paused = true
        run.saver?.invoke { it.copy(paused = true, status = MissionStatus.WAITING, waitingFor = "Paused") }
        card(context, run) { it.copy(phase = com.cyclone.mobile.runtime.background.TaskPhase.PAUSED, message = "Paused. Tap Resume to continue.") }
        return true
    }

    fun unpause(context: Context, missionId: String?): Boolean {
        val run = viewed(missionId) ?: return false
        if (!run.paused) return false
        run.paused = false
        run.saver?.invoke { it.copy(paused = false, status = MissionStatus.RUNNING, waitingFor = null) }
        card(context, run) { it.copy(phase = com.cyclone.mobile.runtime.background.TaskPhase.WORKING, message = "Continuing.") }
        return true
    }

    fun isPaused(missionId: String?): Boolean = viewed(missionId)?.paused == true

    /** The Task Kit id of the task the owner is viewing ([taskId] when it still runs), or the front task; null when none. */
    fun viewedTaskId(taskId: String?): String? = viewed(taskId?.removePrefix("mission-"))?.taskId

    /**
     * Plan 38: the rows the Ask bar offers for [text] while the viewed task works. Answer replaces Steer when that task
     * waits on a question; Parallel is greyed with the reason when it can't start now; the highlight is only a hint.
     */
    fun askOptions(context: Context, taskId: String?, text: String): List<com.cyclone.mobile.task.AskWhileWorking.Option> {
        val run = viewed(taskId?.removePrefix("mission-"))
        val question = run?.let { inbox.openFor(it.id) }?.takeIf { it.kind == OwnerRequestKind.QUESTION }?.text
        return com.cyclone.mobile.task.AskWhileWorking.options(question, parallelBlocker(context, text), MissionQueue.isNewTask(text))
    }

    /**
     * Owner text while a mission runs: it answers the front mission's open question, or joins its conversation as a
     * new instruction the model reads at its next turn.
     */
    fun steer(text: String): Boolean {
        val front = frontRun() ?: return false
        val clean = text.trim().take(2_000)
        if (clean.isBlank()) return true
        (inbox.openFor(front.id) ?: inbox.pending.value)?.let { request ->
            when (request.kind) {
                OwnerRequestKind.QUESTION -> if (inbox.respond(request.id, OwnerResponse.Answer(clean))) return true
                OwnerRequestKind.CONTROL -> if (clean.lowercase() in setOf("done", "ok", "klaar", "go")) {
                    if (inbox.respond(request.id, OwnerResponse.Done)) return true
                }
                else -> Unit
            }
        }
        front.ownerMessages += clean
        return true
    }

    fun answer(requestId: String, response: OwnerResponse): Boolean = inbox.respond(requestId, response)

    /**
     * "I'm done" from any surface. Whatever the front mission is waiting for gets its answer: a hand-back completes, an
     * open question learns the owner did it on the screen, an open check-in card counts as done by hand. Cyclone always
     * gets the phone back.
     */
    fun ownerDone(): Boolean {
        val front = frontRun() ?: return false
        inbox.openFor(front.id)?.let { request ->
            when (request.kind) {
                OwnerRequestKind.CONTROL, OwnerRequestKind.VALUES -> inbox.respond(request.id, OwnerResponse.Done)
                OwnerRequestKind.QUESTION -> inbox.respond(request.id,
                    OwnerResponse.Answer("I did it myself on the phone. Look at the screen again."))
                OwnerRequestKind.APPROVAL, OwnerRequestKind.SECRET -> Unit
            }
        }
        OverlayChromeRuntime.missionHandBack()
        return true
    }

    /** The owner took the phone from the task card; the front mission's next action waits until they hand it back. */
    fun ownerTakesPhone(): Boolean {
        val front = frontRun() ?: return false
        com.cyclone.mobile.runtime.plane.MissionPlanes.ownerNeedsScreen()
        OverlayChromeRuntime.missionHandoff()
        WorkspaceTasks.update(front.taskId) {
            it.copy(phase = TaskPhase.HUMAN, message = "You have the phone. Tap I'm done to let Cyclone continue.",
                interruption = TaskInterruption(reason = "MIND_OWNER_HAS_PHONE", prompt = "You have the phone", canResumeAfterHuman = true))
        }
        return true
    }

    fun delete(context: Context, id: String) {
        if (synchronized(lock) { id in runs }) return
        store(context).delete(id)
        refresh(context)
    }

    // ---- running missions -------------------------------------------------------------------------------------------

    private fun launch(context: Context, initial: Mission, resume: MissionJournal?, attachment: TaskAttachment?, resumeReason: String = "",
                       front: Boolean, handover: String? = null, flash: Boolean = false, signup: String? = null,
                       setup: com.cyclone.mobile.mind.signup.AccountSetupPlan? = null): Boolean {
        synchronized(lock) {
            if (front && runs.values.any { it.front }) return false
            if (!front && runs.values.none { it.front }) return false
            if (initial.id in runs) return false
            val run = Run(initial, front)
            run.handover = handover?.trim()?.take(1_500)?.takeIf { it.isNotBlank() }
            run.flash = flash
            run.signup = signup
            run.setup = setup
            runs[run.id] = run
            if (front) {
                liveState.value = initial
                OverlayChromeRuntime.attachMission(hooks)
            } else {
                inbox.label(run.id, Crew.label(initial.goal))
                publishBehind()
            }
            val thread = Thread({ execute(context, run, resume, attachment, resumeReason) }, "cyclone-mind-${run.id.takeLast(6)}")
            run.thread = thread
            thread.start()
        }
        return true
    }

    private fun publishBehind() {
        val behind = runs.values.filter { !it.front }.sortedBy { it.startedAt }
        behindState.value = behind.map { it.mission }
        behindTasksState.value = behind.mapNotNull { it.card }
    }

    /** Updates [run]'s task card: the front card in [WorkspaceTasks], a behind card in its own notification. */
    private fun card(context: Context, run: Run, change: (WorkspaceTaskUi) -> WorkspaceTaskUi) {
        if (run.front) {
            WorkspaceTasks.update(run.taskId, change)
            return
        }
        val next = change(run.card ?: baseCard(run.mission))
        synchronized(lock) {
            run.card = next
            publishBehind()
        }
        BehindNotifications.post(context, next, inbox.openFor(run.id))
    }

    private fun baseCard(mission: Mission): WorkspaceTaskUi =
        WorkspaceTaskUi("mission-${mission.id}", "default-foreground", "Cyclone Mind", "", mission.goal, phase = TaskPhase.WORKING,
            engine = com.cyclone.mobile.task.TaskEngine.MIND,
            message = "On it.", displayId = 0, startedAtMs = System.currentTimeMillis(), traceSessionId = mission.traceId,
            plannedMilestones = mission.plan.map { it.text })

    /**
     * [run] becomes the front mission (the front one ended, or it needs the owner's screen and it is free): its task
     * card moves to the overlay and the task notification, and its questions stop naming it.
     */
    private fun promote(context: Context, run: Run) {
        val card = synchronized(lock) {
            if (run.front || runs[run.id] !== run) return
            if (runs.values.any { it.front && it !== run }) return
            run.front = true
            publishBehind()
            run.card
        }
        runCatching { run.planes?.promote() }
        inbox.label(run.id, null)
        BehindNotifications.cancel(context, run.taskId)
        liveState.value = run.mission
        OverlayChromeRuntime.attachMission(hooks)
        val task = (card ?: baseCard(run.mission)).copy(message = "Now in front: ${card?.message ?: "working"}".take(160))
        runCatching { WorkspaceTasks.publishStart(task) }.onFailure { WorkspaceTasks.update(run.taskId) { task } }
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.start(context)
        MindMissionService.start(context, run.taskId, (workingMinutes(context) + 60) * 60_000L)
        OverlayChromeRuntime.missionWorking(run.taskId, "Thinking")
        run.mission.traceId?.let { AgentTraceRuntime.event(context, it, "CREW_FRONT", "The task came to the front") }
    }

    private fun execute(context: Context, run: Run, resume: MissionJournal?, attachment: TaskAttachment?, resumeReason: String) {
        val missions = store(context)
        val taskId = run.taskId
        fun save(change: (Mission) -> Mission) {
            val next = change(run.mission).copy(updatedAtMs = System.currentTimeMillis())
            run.mission = next
            if (run.front) liveState.value = next else synchronized(lock) { publishBehind() }
            runCatching { missions.save(next) }
        }
        run.saver = ::save
        val mission0 = run.mission
        if (run.front) {
            publishTask(context, taskId, mission0)
            MindMissionService.start(context, taskId, (workingMinutes(context) + 60) * 60_000L)
            DeviceState.setController(DeviceState.Controller.AGENT)
            OverlayChromeRuntime.missionWorking(taskId, "Thinking")
        } else {
            card(context, run) { it.copy(message = "Starting behind your screen.") }
        }
        var traceId: String? = null
        var outcome: MindOutcome?
        var failure: String?
        try {
            val key = OpenRouterSecretStore.read(context)
            require(key.isNotBlank()) { "Add your OpenRouter API key in Cyclone's AI settings first." }
            // A lab variant changes only what it names, for this mission only. Lab runs never fall back to a backup
            // model, so a result always belongs to the model the variant asked for.
            val variant = run.mission.lab?.variant
            val primaryId = variant?.modelId?.let(OpenRouterCatalogStore::canonicalId) ?: OpenRouterCatalogStore.activeId(context)
            require(primaryId.isNotBlank()) { "Choose a verified model in Cyclone's AI settings first." }
            val backupId = if (variant != null) null else OpenRouterCatalogStore.backupId(context).takeIf { it.isNotBlank() }
            val effort = variant?.effort ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("openrouter_reasoning_effort", "medium")
            traceId = run.mission.traceId?.takeIf { resume != null } ?: AgentTraceRuntime.start(context, run.mission.goal, primaryId)
            val trace = traceId
            fun model(id: String): MindModel {
                val preset = OpenRouterCatalogStore.preset(context, id)
                return OpenRouterMindModel(key, id, preset.label, preset.vision, effort, trace, run.cancellation, { run.stopRequested }) { phase, _ ->
                    if (phase.isNotBlank() && run.front) OverlayChromeRuntime.missionStatus("Thinking")
                }
            }
            val primary = model(primaryId)
            val backup = backupId?.let(::model)
            save { it.copy(modelId = primaryId, modelLabel = primary.label, traceId = trace) }
            card(context, run) { it.copy(traceSessionId = trace) }
            AgentTraceRuntime.event(context, trace, if (resume == null) "MISSION_START" else "MISSION_RESUME",
                (if (resume == null) "Mission started with ${primary.label}" else "Mission resumed (${run.mission.resumes}) with ${primary.label}") +
                    if (run.front) "" else ", behind the owner's screen")

            val device = AndroidMindDevice(context)
            val owner = AndroidMindOwner(context, inbox, run.id, { run.stopRequested },
                onWaiting = { question ->
                    waiting(context, run, question)
                    save { it.copy(status = if (question == null) MissionStatus.RUNNING else MissionStatus.WAITING, waitingFor = question) }
                },
                onStatus = { text -> status(context, run, text) },
                onPlan = { steps -> save { it.copy(plan = steps) }; planToTask(context, run, steps) },
                onHuman = { instruction ->
                    human(context, run, instruction)
                    save { it.copy(status = if (instruction == null) MissionStatus.RUNNING else MissionStatus.WAITING, waitingFor = instruction) }
                },
                onEvent = { text -> save { it.withEvent(MissionEvent(System.currentTimeMillis(), text)) } },
                onPlanVersion = { version, label, history ->
                    save { it.copy(planVersion = version, planLabel = label, planHistory = history.takeLast(MAX_PLAN_HISTORY)) }
                })
            // Plan 37: a Lab arm chooses its context; the owner's missions follow the setting. Lab runs without the
            // knob stay classic, so older experiments measure the same thing.
            val workspace = if (variant?.context == com.cyclone.mobile.mind.lab.MindLabVariant.WORKSPACE ||
                variant == null && workspaceEnabled(context)) {
                com.cyclone.mobile.mind.workspace.MissionWorkspace.fromJson(resume?.workspace) ?: com.cyclone.mobile.mind.workspace.MissionWorkspace()
            } else null
            // Planes (plan 25): the owner's missions may move between the main screen and a background screen. Lab
            // runs stay on the screen so a measurement never depends on where it ran. A behind mission (plan 26 §6)
            // starts with no screen at all and takes background screens of its own.
            val labPlane = variant?.plane?.let(com.cyclone.mobile.runtime.plane.PlaneMode::fromWire)
            val planes = when {
                !run.front -> com.cyclone.mobile.runtime.plane.MissionPlanes.beginBehind(context, run.id, run.mission.goal, trace,
                    onPromote = { promote(context, run) },
                    onWaiting = { text -> card(context, run) { it.copy(message = (text ?: "Working behind your screen.").take(160)) } })
                run.mission.lab == null || labPlane != null -> com.cyclone.mobile.runtime.plane.MissionPlanes.begin(context, run.id,
                    run.mission.goal, trace, modeOverride = labPlane)
                else -> null
            }
            run.planes = planes
            val environment = planes?.initialEnvironment() ?: CycloneAgentEnvironment(context, userTaskGoal = run.mission.goal, ownerMission = true)
            // Fresh lab runs neither read nor write the owner's memory: each run starts from the same place.
            val fresh = variant?.freshMemory == true
            val labMemoryFile = if (fresh) File(context.cacheDir, "lab-memory-${run.id}.json").also { it.delete() } else null
            val memory = labMemoryFile?.let { com.cyclone.mobile.mind.MindMemory(it) } ?: memory(context)
            val trail = com.cyclone.mobile.mind.learn.MindTrailRecorder(run.id).also { run.trail = it }
            // The map is on for the owner; a Lab arm can turn it off to measure what it is worth.
            val useMap = variant?.useMap != false
            // Plan 41: Fast mode gives the Mind the Pilot; off (the default) keeps every mission exactly as before.
            val fastSettings = com.cyclone.mobile.mind.pilot.FastMode.settings(context)
            val fast = com.cyclone.mobile.mind.pilot.FastMode.decider(context, fastSettings)
                ?.let { com.cyclone.mobile.mind.pilot.PilotSetup(it, fastSettings.pilot(), fastSettings.activeModel,
                    com.cyclone.mobile.mind.pilot.MindPilotAdvisor(primary)) }
            val toolbox = PhoneMindToolbox(environment, owner, device, run.mission.goal, { run.stopRequested }, memory = memory, missionId = run.id,
                marker = if (variant?.marks == false) null else AndroidMindImageMarker, trail = trail,
                learned = if (useMap) learnedHints(context) else null,
                maps = if (useMap) missionMaps(context) else null,
                glossary = if (useMap) ({ pkg -> com.cyclone.mobile.manual.ManualRuntime.glossary(context, pkg) }) else null,
                manual = if (useMap) com.cyclone.mobile.manual.ManualRuntime.mindPort(context) else null,
                skill = if (useMap && run.mission.lab == null) runCatching { com.cyclone.mobile.market.Marketplace.groundedSkillFor(context, run.mission.goal) }.getOrNull()
                    ?.let { (listing, anchor) -> anchor?.let { com.cyclone.mobile.mind.MindSkillBrief(listing.name, it) } } else null,
                planes = planes, workspace = workspace, fast = fast,
                signup = run.signup?.let { pkg -> com.cyclone.mobile.mind.signup.SignupRecorder(pkg, appLabel(context, pkg), appVersion(context, pkg)) { System.currentTimeMillis() } },
                saveSignup = { map -> com.cyclone.mobile.mind.signup.SignupMapStore.save(context, map) },
                setup = run.setup, setupProgress = { progress -> com.cyclone.mobile.mind.signup.AccountSetupProgress.set(run.id, progress) },
                // Plan 48 run 4: Cyclone Ports only while the owner's PC is polling this phone; never in a Lab mission.
                ports = portsLink(run.id, run.mission.lab == null),
                portPhoto = com.cyclone.mobile.mind.MindPortPhoto.fromDataUrl(attachment?.imageDataUrl),
                // Plan 49: codes sent by text to this phone's own number fill themselves; never in a Lab mission.
                codes = if (run.mission.lab == null) com.cyclone.mobile.codes.AndroidMindCodes(context) else null)
            // Plan 26: the start question ("you are using WhatsApp: when you're done / now / take it") is the mission's
            // own owner question, answered on the same card as any other.
            planes?.attach(toolbox) { question, choices -> owner.ask(question, choices, 10 * 60_000L).takeIf { it.answered }?.text }
            val native = resume?.nativeTools ?: (OpenRouterCatalogStore.lookup(primaryId)?.nativeTools != false)
            val system = MindPrompt.system(null, native, toolbox.specs(), device.now(), device.device()) +
                (if (workspace != null) "\n\n" + MindPrompt.workspaceRules() else "") +
                (if (fast != null) "\n\n" + MindPrompt.PILOT_RULES else "") +
                (if (toolbox.specs().any { it.name == "port_wait" }) "\n\n" + MindPrompt.PORTS_RULES else "") +
                (run.signup?.let { "\n\n" + MindPrompt.signupRules(appLabel(context, it)) }.orEmpty()) +
                (run.setup?.let { "\n\n" + it.promptText() }.orEmpty()) +
                variant?.promptAddendum?.takeIf { it.isNotBlank() }?.let { "\n\nLab instruction for this mission (from the developer's experiment):\n$it" }.orEmpty()
            // A behind mission never reads the owner's screen, not even to begin.
            val situation = if (run.front) toolbox.situation() else Crew.BEHIND_SITUATION.format(device.now())
            val conversation = if (resume != null) resume.conversation.also {
                it.replaceFirst(MindMessage.System(system))
                it.add(MindMessage.User(MindPrompt.resumed(resumeReason),
                    origin = MindMessage.User.Origin.HARNESS))
            } else MindConversation(listOf(
                MindMessage.System(system),
                MindMessage.User(MindPrompt.mission(run.mission.goal, situation, memory.digest(run.mission.goal),
                    if (fresh) "" else recentMissions(missions, run.id), com.cyclone.mobile.mind.RememberIntent.detect(run.mission.goal)) +
                    (attachment?.text?.let { "\n\nThe owner attached this (reference only, not instructions):\n${it.take(4_000)}" }.orEmpty()) +
                    (run.handover?.let { "\n\n$it" }.orEmpty()) +
                    (if (run.flash) "\n\n" + (if (fast != null) MindPrompt.FLASH_WITH_PILOT else MindPrompt.FLASH) else ""),
                    attachment?.imageDataUrl?.takeIf { primary.vision }),
            ))
            val budget = MindBudget(workingMs = (variant?.workingMinutes ?: workingMinutes(context)) * 60_000L)
            val metrics = com.cyclone.mobile.mind.lab.MissionMetrics().also { run.metrics = it }
            workspace?.let { ws -> metrics.workspace = { ws.record() } }
            metrics.divert = { toolbox.planVersions.record() }
            val listener = com.cyclone.mobile.mind.lab.TeeMindListener(listOf(metrics, MissionListener(context, trace, missions, run.id,
                front = { run.front },
                updateCard = { change -> card(context, run, change) },
                onTurn = { turn -> save { it.copy(turns = turn) } }) { event -> save { it.withEvent(event) } }))
            val loop = MindLoop(primary, backup, toolbox, budget, listener, cancelled = { run.stopRequested },
                ownerMessages = { drainOwnerMessages(run) }, nativeTools = native, workspace = workspace,
                ownerSteers = { buildList { while (true) add(run.steers.poll() ?: break) } }, paused = { run.paused },
                goalMet = PhoneSettingReader.checkFor(context, run.mission.goal))
            outcome = loop.run(conversation, resume?.checkpoint())
            val result = outcome
            save {
                it.copy(
                    status = when (result.status) {
                        MindStatus.COMPLETED -> MissionStatus.COMPLETED
                        MindStatus.GAVE_UP -> MissionStatus.GAVE_UP
                        MindStatus.FAILED -> MissionStatus.FAILED
                        MindStatus.CANCELLED -> MissionStatus.CANCELLED
                        MindStatus.OUT_OF_BUDGET -> MissionStatus.PAUSED
                    },
                    summary = result.summary, evidence = result.evidence.orEmpty(), turns = result.turns, workingMs = result.workingMs,
                    usage = result.usage, modelLabel = result.modelLabel, waitingFor = null,
                    metrics = metrics.toJson(),
                )
            }
        } catch (error: Throwable) {
            failure = error.message ?: error.javaClass.simpleName
            save { it.copy(status = MissionStatus.FAILED, summary = "Cyclone could not run this mission: $failure", waitingFor = null) }
        } finally {
            finish(context, run, missions, traceId, ::save)
        }
    }

    private fun finish(context: Context, run: Run, missions: MissionStore, traceId: String?, save: ((Mission) -> Mission) -> Unit) {
        run.planes?.end()
        run.planes = null
        run.metrics?.let { live -> if (run.mission.metrics == null) save { it.copy(metrics = live.toJson()) } }
        run.metrics = null
        // A resumed mission adds to the trail it already had.
        run.trail?.let { live ->
            runCatching {
                val now = live.snapshot()
                val earlier = missions.loadTrail(run.id)
                missions.saveTrail(if (earlier == null) now else com.cyclone.mobile.mind.learn.MissionTrail(run.id,
                    (earlier.screens + now.screens).distinctBy { it.pageKey }, earlier.steps + now.steps))
            }
        }
        run.trail = null
        var mission = run.mission
        // A Lab arm with the map learns every mission as it ends, so its later trials start from what it saw.
        if (mission.lab?.variant?.useMap == true && !mission.status.live) {
            runCatching {
                (com.cyclone.mobile.mind.learn.MissionLearning.learn(context, mission.id) as? com.cyclone.mobile.mind.learn.LearnOutcome.Learned)
                    ?.let { learned -> mission = mission.copy(learned = learned.report); run.mission = mission }
            }
        }
        runCatching { File(context.cacheDir, "lab-memory-${mission.id}.json").delete() }
        // A saved skill that ran to the end keeps its place on the map fresh (plan 23). Lab runs never touch skills.
        if (mission.status == MissionStatus.COMPLETED && mission.lab == null) {
            runCatching { com.cyclone.mobile.market.Marketplace.regroundAfterRun(context, mission.id, mission.goal) }
        }
        val ok = mission.status == MissionStatus.COMPLETED
        traceId?.let { trace ->
            AgentTraceRuntime.finish(context, trace, when (mission.status) {
                MissionStatus.COMPLETED -> "COMPLETED"
                MissionStatus.CANCELLED -> "CANCELLED"
                MissionStatus.PAUSED -> "PAUSED"
                else -> "FAILED"
            }, mission.summary.take(500), mission.turns)
        }
        inbox.withdrawMission(run.id)
        val wasFront = run.front
        if (wasFront) {
            finishTask(context, run.taskId, mission)
            OverlayChromeRuntime.missionFinished(run.taskId, ok, mission.summary.take(200).ifBlank { "Mission ended." })
        } else {
            finishBehind(context, run, mission)
        }
        val next = synchronized(lock) {
            runs.remove(run.id)
            publishBehind()
            if (wasFront) {
                OverlayChromeRuntime.detachMission(hooks)
                // A behind mission that already took the owner's screen (it needed it while this one was ending) comes
                // to the front; otherwise the one that started first.
                val screen = com.cyclone.mobile.runtime.plane.MissionPlanes.front
                runs.values.firstOrNull { !it.front && screen != null && it.planes === screen }
                    ?: Crew.next(runs.values.filter { !it.front }.map { it.id to it.startedAt })?.let(runs::get)
            } else null
        }
        if (wasFront) {
            liveState.value = null
            // Plan 26 §6: the oldest task behind the screen comes to the front, so the owner always sees one.
            if (next != null) promote(context, next) else MindMissionService.stop(context)
        }
        historyState.value = runCatching { missions.list() }.getOrDefault(historyState.value)
        WorkspaceTasks.scheduleQueuePromotion(context)
        // Plan 26 (A42-8): the next queued task starts once a mission has fully ended (not after a Stop).
        if (mission.status != MissionStatus.CANCELLED) startQueued(context)
    }

    private fun startQueued(context: Context) {
        val peek = runCatching { queue(context) }.getOrNull() ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val admit = runCatching { peek.all().firstOrNull()?.let { admission(context, it.goal) } }.getOrNull() ?: return@postDelayed
            if (admit is Crew.Admit.Queue) return@postDelayed
            val next = runCatching { peek.take() }.getOrNull() ?: return@postDelayed
            if (admit is Crew.Admit.Front) start(context, next.goal)
            else {
                val now = System.currentTimeMillis()
                launch(context.applicationContext, Mission(newId(), next.goal.trim().take(2_000), MissionStatus.RUNNING, now, now, "", ""),
                    resume = null, attachment = null, front = false)
            }
        }, QUEUE_START_DELAY_MS)
    }

    fun memory(context: Context): com.cyclone.mobile.mind.MindMemory = memory ?: synchronized(lock) {
        // Plan 37 W3: sealed with an Android Keystore key at rest; the plain file of earlier versions is sealed on its next write.
        memory ?: com.cyclone.mobile.mind.MindMemory(File(context.applicationContext.filesDir, "Cyclone Brain/Mind memory.json"),
            sealer = KeystoreMemorySealer).also { memory = it }
    }

    /** The last few missions of the past day, so a follow-up ("now do the same for…") has its context. */
    private fun recentMissions(missions: MissionStore, currentId: String): String {
        val since = System.currentTimeMillis() - 24 * 60 * 60_000L
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ENGLISH)
        // Lab missions are experiments, not the owner's history; they never become a follow-up's context.
        return MindPrompt.recentMissions(missions.list().filter { it.id != currentId && it.updatedAtMs >= since && it.lab == null }.map { m ->
            val outcome = m.summary.ifBlank { m.status.name.lowercase() }
            "${format.format(java.util.Date(m.createdAtMs))} \"${MindRedaction.scrub(m.goal).take(140)}\" → ${m.status.name.lowercase()}: ${MindRedaction.scrub(outcome).take(200)}"
        })
    }

    private const val MAX_PLAN_HISTORY = 5

    private fun drainOwnerMessages(run: Run): List<String> = buildList { while (true) add(run.ownerMessages.poll() ?: break) }

    // ---- the task card and notification --------------------------------------------------------------------------

    private fun publishTask(context: Context, taskId: String, mission: Mission) {
        val task = baseCard(mission).copy(taskId = taskId)
        runCatching { WorkspaceTasks.publishStart(task) }.onFailure { WorkspaceTasks.update(taskId) { task } }
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.start(context)
    }

    private fun status(context: Context, run: Run, text: String) {
        card(context, run) { it.copy(message = text.take(160), phase = TaskPhase.WORKING, interruption = null) }
        if (!run.front) return
        OverlayChromeRuntime.missionStatus(text.take(120))
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.progress(context, text)
    }

    /** The owner has the phone for a step: the task card and overlay ribbon offer "I'm done". */
    private fun human(context: Context, run: Run, instruction: String?) {
        if (instruction == null) {
            card(context, run) { it.copy(phase = TaskPhase.WORKING, interruption = null) }
            if (run.front) OverlayChromeRuntime.refreshExternalSurface()
            return
        }
        card(context, run) {
            it.copy(phase = TaskPhase.HUMAN, message = instruction.take(160),
                interruption = TaskInterruption(reason = "MIND_OWNER_HAS_PHONE", prompt = instruction.take(300), canResumeAfterHuman = true))
        }
        if (!run.front) return
        OverlayChromeRuntime.missionStatus("Your turn: ${instruction.take(100)}")
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.waiting(context, instruction)
        OverlayChromeRuntime.refreshExternalSurface()
    }

    private fun waiting(context: Context, run: Run, question: String?) {
        // The overlay window changes shape (focusable card) when a check-in card opens or closes.
        OverlayChromeRuntime.refreshExternalSurface()
        if (question == null) {
            card(context, run) { it.copy(phase = TaskPhase.WORKING, interruption = null) }
            return
        }
        card(context, run) {
            it.copy(phase = TaskPhase.REVIEW, message = question.take(160),
                interruption = TaskInterruption(reason = "MIND_OWNER_REQUEST", prompt = question.take(300), canResumeAfterHuman = true))
        }
        if (!run.front) return
        OverlayChromeRuntime.missionStatus(question.take(120))
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.waiting(context, question)
    }

    private fun planToTask(context: Context, run: Run, steps: List<MindPlanStep>) {
        card(context, run) { task ->
            // Plan 38: the overlay shows the plan as it is now; a branch step after a diversion reads "↳ …".
            val shown = steps.filterNot { it.dropped }
            task.copy(plannedMilestones = shown.map { (if (it.branch) "↳ " else "") + it.text.take(80) },
                plannedMilestoneIndex = shown.indexOfFirst { it.status == "doing" || it.status == "todo" }.coerceAtLeast(0))
        }
    }

    private fun finishTask(context: Context, taskId: String, mission: Mission) {
        val phase = when (mission.status) {
            MissionStatus.COMPLETED -> TaskPhase.DONE
            MissionStatus.CANCELLED -> TaskPhase.STOPPED
            else -> TaskPhase.FAILED
        }
        val message = mission.summary.ifBlank { "Mission ended." }.take(300)
        WorkspaceTasks.update(taskId) { it.copy(phase = phase, message = message, outcome = message, interruption = null,
            resumable = mission.status.resumable) }
        com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.finish(context, mission.status == MissionStatus.COMPLETED, message)
    }

    /** A behind mission ended: its notification says how, once, and then stays dismissible. */
    private fun finishBehind(context: Context, run: Run, mission: Mission) {
        val phase = when (mission.status) {
            MissionStatus.COMPLETED -> TaskPhase.DONE
            MissionStatus.CANCELLED -> TaskPhase.STOPPED
            else -> TaskPhase.FAILED
        }
        val message = mission.summary.ifBlank { "Mission ended." }.take(300)
        val done = (run.card ?: baseCard(mission)).copy(phase = phase, message = message, outcome = message, interruption = null,
            resumable = mission.status.resumable)
        run.card = done
        if (mission.status == MissionStatus.CANCELLED) BehindNotifications.cancel(context, run.taskId)
        else BehindNotifications.post(context, done, null)
    }

    /** Journals every turn and mirrors the model-visible story into the run trace. Never provider reasoning. */
    private class MissionListener(
        private val context: Context,
        private val traceId: String,
        private val store: MissionStore,
        private val missionId: String,
        private val front: () -> Boolean,
        private val updateCard: ((WorkspaceTaskUi) -> WorkspaceTaskUi) -> Unit,
        private val onTurn: (Int) -> Unit,
        private val onEvent: (MissionEvent) -> Unit,
    ) : MindListener {
        override fun onModelStart(turn: Int, model: MindModel) {
            if (front()) OverlayChromeRuntime.missionStatus("Thinking")
            onTurn(turn)
        }

        override fun onAssistant(turn: Int, message: MindMessage.Assistant) {
            val said = MindRedaction.scrub(message.text.trim())
            val calls = message.toolCalls.joinToString { it.name }
            AgentTraceRuntime.event(context, traceId, "MIND_TURN", "Turn $turn: ${said.take(300).ifBlank { calls.ifBlank { "(no action)" } }}",
                code = "MIND_TURN", detail = if (calls.isBlank()) null else "calls: $calls")
            if (said.isNotBlank()) updateCard { it.copy(message = said.take(160)) }
        }

        override fun onToolStart(turn: Int, call: MindToolCall) {
            AgentTraceRuntime.event(context, traceId, "MIND_ACTION", call.name, code = call.name,
                detail = MindRedaction.scrub(call.arguments).take(600))
        }

        override fun onToolResult(turn: Int, call: MindToolCall, result: MindToolResult) {
            val brief = MindRedaction.scrub(result.brief)
            AgentTraceRuntime.event(context, traceId, "MIND_RESULT", brief.take(300), code = call.name, ok = result.ok,
                detail = MindRedaction.scrub(result.text).take(1_500))
            if (call.name !in QUIET_TOOLS) onEvent(MissionEvent(System.currentTimeMillis(), brief.take(200), result.ok))
        }

        override fun onNotice(turn: Int, text: String) {
            AgentTraceRuntime.event(context, traceId, "MIND_NOTICE", text.take(300), code = "MIND_NOTICE")
            onEvent(MissionEvent(System.currentTimeMillis(), text.take(200)))
        }

        override fun checkpoint(checkpoint: MindCheckpoint) {
            runCatching { store.saveJournal(missionId, checkpoint) }
        }

        private companion object {
            val QUIET_TOOLS = setOf("screen_read", "note")
        }
    }
}
