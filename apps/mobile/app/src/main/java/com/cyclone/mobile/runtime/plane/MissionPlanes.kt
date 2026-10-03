package com.cyclone.mobile.runtime.plane

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.PowerManager
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironment
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironmentApi
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.mind.MindApp
import com.cyclone.mobile.mind.MindPlanes
import com.cyclone.mobile.mind.MindToolResult
import com.cyclone.mobile.mind.PhoneMindToolbox
import com.cyclone.mobile.mind.mission.Crew
import com.cyclone.mobile.runtime.background.BackgroundSetup
import com.cyclone.mobile.runtime.background.WorkspaceRuntime
import com.cyclone.mobile.runtime.session.ExecutionContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File

/** What the pill shows (plan 25 §4.5). */
data class PlaneUi(
    val missionId: String,
    val kind: PlaneKind,
    val switching: Boolean = false,
    /** Background work is possible on this phone right now. */
    val available: Boolean = false,
    /** Why background is unavailable, or why the last switch did not happen; the pill's long-press shows it. */
    val note: String? = null,
    /** The app the mission works in, when known. */
    val appLabel: String? = null,
    /** Plan 26: the mission waits for the owner to be done with this app (or yields it to them right now). */
    val waitingFor: String? = null,
    /** The background screen's session while the mission works there (the approval card shows a glimpse of it). */
    val backgroundSessionId: String? = null,
    /** Plan 27: why the last move did not happen, shown beside the pill (never silent); [outcomeSeq] counts them. */
    val outcome: String? = null,
    val outcomeSeq: Long = 0L,
    /** Plan 27: the owner's last move that committed, so the overlay can settle (island after moving behind). */
    val movedTo: PlaneKind? = null,
    val moveSeq: Long = 0L,
)

/**
 * Planes on the phone (plan 25): one mission, one current plane, switched only as a transaction between steps. This
 * object is what surfaces reach through Task Kit; a [MissionPlaneSession] does the work for the running mission.
 */
object MissionPlanes {
    private const val PREFS = "cyclone_planes"
    private const val MODE_KEY = "mode"

    private val uiState = MutableStateFlow<PlaneUi?>(null)
    val ui: StateFlow<PlaneUi?> = uiState

    @Volatile private var current: MissionPlaneSession? = null

    fun mode(context: Context): PlaneMode = PlaneMode.fromWire(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODE_KEY, PlaneMode.AUTOMATIC.wire))

    fun setMode(context: Context, mode: PlaneMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(MODE_KEY, mode.wire).apply()
    }

    fun compat(context: Context) = AppPlaneCompat(File(context.applicationContext.filesDir, "Cyclone Brain/Planes/app-compat.json"))
    fun journal(context: Context) = FilePlaneJournal(File(context.applicationContext.filesDir, "Cyclone Brain/Planes/switches.jsonl"))

    private const val ON_KEY = "background_on"
    private const val FALLBACK_KEY = "fallback"

    /** Plan 26 (A42-1): the owner's one switch. On by default where the phone can do it. */
    fun backgroundOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ON_KEY, true)

    fun setBackgroundOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ON_KEY, on).apply()
    }

    /** Plan 26 (A42-4): what to do when background is wanted but not possible right now. */
    fun fallback(context: Context): PlaneFallback =
        PlaneFallback.fromWire(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FALLBACK_KEY, null))
            ?: PlaneFallback.defaultFor(mode(context))

    fun setFallback(context: Context, fallback: PlaneFallback) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(FALLBACK_KEY, fallback.wire).apply()
    }

    /** One answer, with one next step (plan 26, A42-1). */
    fun capability(context: Context): BackgroundCapability {
        val r = BackgroundSetup.read(context)
        return BackgroundCapabilities.judge(BackgroundFacts(backgroundOn(context), r.android, r.installed, r.running, r.authorized,
            r.accessibility, r.notifications))
    }

    /**
     * Why background work is not possible right now, in the owner's words; null when it is. Plan 28: a phone whose
     * last Background Check failed in the engine itself works on the screen and says why, instead of failing mid-task.
     */
    fun blocker(context: Context): String? = capability(context).takeUnless { it.ready }?.headline
        ?: BackgroundCheck.blocker(context)

    @Volatile private var nextMode: PlaneMode? = null

    /**
     * The next mission starts in [mode], once (Drive, plan 32: background first while driving, so Maps stays on the
     * screen). Only set when background work is ready; the owner's own mode applies again after that mission.
     */
    fun preferOnce(mode: PlaneMode) { nextMode = mode }

    fun begin(context: Context, missionId: String, goal: String, traceId: String?, modeOverride: PlaneMode? = null): MissionPlaneSession {
        current?.end()
        val app = context.applicationContext
        val preferred = nextMode.also { nextMode = null }
        return MissionPlaneSession(app, missionId, goal, traceId, modeOverride ?: preferred ?: mode(context), publisher(app, missionId))
            .also { current = it }
    }

    /** Only the front mission's plane reaches the pill and the task notification. */
    private fun publisher(app: Context, missionId: String): (PlaneUi?) -> Unit = { next ->
        if (current?.missionId == missionId) {
            val changed = uiState.value?.let { it.kind != next?.kind || it.available != next?.available } ?: true
            uiState.value = next
            // The task notification carries the move action: keep it in step with the plane.
            if (changed) com.cyclone.mobile.runtime.background.WorkspaceTasks.state.value?.let { task ->
                runCatching { com.cyclone.mobile.ui.overlay.AgentTaskNotificationRuntime.renderTask(app, task) }
            }
        }
    }

    /**
     * Plan 26 §6 (parallel sessions): a mission that works behind the front one. It starts with no screen, takes only
     * background screens of its own, and waits to come to the front ([onPromote]) before it ever needs the owner's.
     */
    fun beginBehind(context: Context, missionId: String, goal: String, traceId: String?,
                    onPromote: (MissionPlaneSession) -> Unit, onWaiting: (String?) -> Unit): MissionPlaneSession {
        val app = context.applicationContext
        return MissionPlaneSession(app, missionId, goal, traceId, PlaneMode.BACKGROUND, publisher(app, missionId),
            behindStart = true, onPromote = onPromote, onWaiting = onWaiting)
    }

    /** A behind mission asks to become the front one: granted when no front mission holds the screen. */
    @Synchronized
    internal fun claimFront(session: MissionPlaneSession): Boolean {
        val front = current
        if (front != null && front !== session && !front.isEnded) return false
        current = session
        return true
    }

    /** The front mission's session, when one is running. */
    val front: MissionPlaneSession? get() = current

    // ---- app leases between missions (plan 26 §4): one Cyclone mission per app at a time ----

    private val holders = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The mission that holds [pkg] on a background screen, when it is not [missionId]. */
    fun heldByOther(pkg: String, missionId: String): String? = holders[pkg]?.takeIf { it != missionId }

    internal fun hold(missionId: String, pkg: String?) {
        holders.entries.removeAll { it.value == missionId && it.key != pkg }
        if (pkg != null) holders.putIfAbsent(pkg, missionId)
    }

    internal fun releaseApps(missionId: String) {
        holders.entries.removeAll { it.value == missionId }
    }

    internal fun ended(session: MissionPlaneSession) {
        releaseApps(session.missionId)
        synchronized(this) {
            if (current === session) {
                current = null
                uiState.value = null
            }
        }
    }

    /**
     * The owner asked to move the running task (the pill, a notification action, Glass). [to] null toggles. Runs the
     * switch on its own thread; the result shows in [ui]. False when no mission is running.
     */
    fun request(to: PlaneKind?, byOwner: Boolean = true): Boolean {
        val session = current ?: return false
        Thread({ session.ownerSwitch(to, byOwner) }, "cyclone-plane-switch").start()
        return true
    }

    /** Plan 26 (A42-5): the owner pressed Start now while the mission waits for an app they are using. */
    fun startNow(): Boolean {
        val session = current ?: return false
        return session.startNow()
    }

    /** The mission is stopping: nothing may stay held at a step boundary. */
    fun release() {
        current?.releaseGate()
    }

    /** The owner takes the phone (Task Kit TakeOver): a background task comes to the screen first. */
    fun ownerNeedsScreen() {
        val session = current ?: return
        if (session.plane is TaskPlane.Background) Thread({ session.escalate(PlaneSignal.OWNER_HANDS) }, "cyclone-plane-switch").start()
    }

    /** Long-press "Always run this app in the background". */
    fun allowCurrentApp(context: Context): Boolean {
        val pkg = current?.packageName() ?: return false
        compat(context).allow(pkg, versionOf(context, pkg))
        return true
    }

    internal fun versionOf(context: Context, packageName: String): String? =
        runCatching { context.packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
}

/** One mission's planes: hooks for the Mind, the switch transaction, the watchdog. */
class MissionPlaneSession internal constructor(
    private val context: Context,
    val missionId: String,
    private val goal: String,
    private val traceId: String?,
    private val mode: PlaneMode,
    private val publish: (PlaneUi?) -> Unit,
    /** Plan 26 §6: this mission works behind the front one (no owner's screen until it comes to the front). */
    behindStart: Boolean = false,
    private val onPromote: ((MissionPlaneSession) -> Unit)? = null,
    /** The behind mission waits for something (the owner's screen, an app another task uses); null when it goes on. */
    private val onWaiting: ((String?) -> Unit)? = null,
) : MindPlanes {
    @Volatile var plane: TaskPlane = TaskPlane.Screen
        private set
    /** True while the mission works behind the front one; it becomes the front one at most once. */
    @Volatile var behind: Boolean = behindStart
        private set
    val isEnded: Boolean get() = ended
    @Volatile private var toolbox: PhoneMindToolbox? = null
    @Volatile private var startDecided = false
    @Volatile private var startedIn: PlaneKind = PlaneKind.SCREEN
    /** A workspace whose task was surfaced to the main screen, kept so the next move back reuses it. */
    @Volatile private var parked: String? = null
    @Volatile private var ended = false
    /** The task came from the owner's screen (the pill), so it goes back there when the mission ends. */
    @Volatile private var returnToScreen = false
    @Volatile private var note: String? = null
    @Volatile private var outcome: String? = null
    @Volatile private var outcomeSeq = 0L
    @Volatile private var movedTo: PlaneKind? = null
    @Volatile private var moveSeq = 0L
    /** How long a switch may wait for the Mind's step boundary; an owner's tap waits longer than an automatic move. */
    @Volatile private var pauseBudgetMs = PAUSE_TIMEOUT_MS
    /** Plan 26: the app the mission waits for (the owner holds it), shown on the pill with Start now. */
    @Volatile private var waitingFor: String? = null
    @Volatile private var startNowPressed = false
    @Volatile private var stopping = false
    /** Asks the owner a question with choices (the mission's own card); null when unanswered. */
    @Volatile private var askOwner: ((String, List<String>) -> String?)? = null
    private val compat = MissionPlanes.compat(context)
    private val port = AndroidPlanePort()
    private val switcher = PlaneSwitcher(port, MissionPlanes.journal(context))
    private val counts = mutableMapOf<PlaneSignal, Int>()
    private var ladder = RecoveryLadder(mode)
    private var watchdog: Thread? = null
    private var backgroundSince = 0L
    private var darkTicks = 0

    init {
        runCatching { switcher.recover() }
        refresh()
    }

    /** The toolbox this session drives; set once, right after it is built. */
    fun attach(toolbox: PhoneMindToolbox, ask: ((String, List<String>) -> String?)? = null) {
        this.toolbox = toolbox
        this.askOwner = ask
        refresh()
    }

    /**
     * Plan 26 §6: this behind mission becomes the front one (the front mission ended). True when it is the front one
     * now; it keeps working where it is (its background screen, or no screen yet).
     */
    fun promote(): Boolean {
        if (!behind) return true
        if (!MissionPlanes.claimFront(this)) return false
        behind = false
        trace("CREW_FRONT", "The task came to the front")
        refresh()
        return true
    }

    fun startNow(): Boolean {
        if (waitingFor == null) return false
        startNowPressed = true
        return true
    }

    fun packageName(): String? = when (val p = plane) {
        is TaskPlane.Background -> WorkspaceRuntime.packageOf(p.sessionId)
        TaskPlane.Screen -> DeviceState.currentPackage
    }

    // ---- Mind hooks (Mind thread, outside the step gate) ----------------------------------------------------------

    override fun before(tool: String, arguments: JSONObject): String? {
        if (ended) return null
        if (tool == "owner_takeover") {
            // A mission behind the screen hands over only once it is the front one.
            if (behind && !awaitFront("This step needs you on the phone.")) return STOPPED
            if (plane is TaskPlane.Background) switchTo(PlaneKind.SCREEN, "This step needs you on the phone.", PlaneSignal.OWNER_HANDS)
            return null
        }
        if (behind && plane is TaskPlane.Screen) {
            // Plan 26 §6: no screen of its own yet, and the owner's screen is not this mission's.
            Crew.behindRefusal(tool, hasOwnScreen = false)?.let { return it }
            if (tool == "open_app") {
                startDecided = true
                val pkg = resolve(arguments.optString("app")) ?: return "NOT RUN: that app is not installed or its name fits several apps. Use apps_list."
                startPlane(pkg)
                if (ended || stopping) return STOPPED
                if (behind && plane is TaskPlane.Screen) return Crew.behindRefusal("screen_read", hasOwnScreen = false)
            }
            return null
        }
        val current = plane
        if (current is TaskPlane.Screen) {
            // An app another Cyclone task works in on a background screen is not pulled onto the owner's screen.
            if (tool == "open_app") resolve(arguments.optString("app"))?.let { pkg -> if (!waitForApp(pkg)) return STOPPED }
            if (tool == "open_app" && !startDecided) {
                startDecided = true
                val pkg = resolve(arguments.optString("app")) ?: return null
                startPlane(pkg)
            }
            return null
        }
        val background = current as TaskPlane.Background
        val here = WorkspaceRuntime.packageOf(background.sessionId)
        when (tool) {
            "home" -> return "NOT RUN: you are working on a background screen that holds only ${label(here) ?: "one app"}. " +
                "There is no Home screen there; use open_app to change apps, or back to leave a page."
            "open_app" -> {
                val pkg = resolve(arguments.optString("app")) ?: return null
                if (pkg == here) return null
                moveApp(background, pkg)
            }
            "open_link" -> {
                // Plan 26 (A42-3): a link opens in the background too: in this app when it handles it, otherwise in the
                // app that does, on its own background screen.
                val handler = linkHandler(arguments.optString("url"))
                when {
                    handler == null -> switchTo(PlaneKind.SCREEN, "This link opens something that needs your screen.", null)
                    handler != here -> moveApp(background, handler)
                }
            }
            // Plan 29: timers and alarms go straight to the clock without its screen, so they no longer pull the task
            // onto the owner's screen (only a clock without that contract falls back, and after() moves it then).
            "open_settings", "open_notification" ->
                switchTo(PlaneKind.SCREEN, "This step opens another app, so it runs on your screen.", null)
        }
        return null
    }

    override fun after(tool: String, result: MindToolResult) {
        if (ended || result.ok) return
        val background = plane as? TaskPlane.Background ?: return
        when (BackgroundFailure.classify(result.text)) {
            // The executor refuses what it cannot do safely on a background screen; the screen can.
            BackgroundFailure.NEEDS_SCREEN -> switchTo(PlaneKind.SCREEN, "This step can't be done in the background.", null)
            // A pause that nothing undid (an old approval path, a lock): Cyclone still holds this screen, so it takes
            // input back; the Mind looks again before its next action.
            BackgroundFailure.AUTHORITY_LOST -> if (!switcher.switching && waitingFor == null) {
                if (WorkspaceRuntime.reclaim(background.sessionId)) trace("PLANE_RECLAIM", "Input authority restored on the background screen")
            }
            // The owner opened the app (the watchdog yields it), the screen broke (the watchdog repairs it), the phone
            // is locked (the mission waits), or an ordinary miss: the plane stays where it is.
            else -> Unit
        }
    }

    // ---- start ---------------------------------------------------------------------------------------------------

    private fun startFacts(pkg: String): StartFacts {
        val blocker = MissionPlanes.blocker(context)
        val foreground = BackgroundSetup.foregroundPackage()
        val holder = if (blocker == null) runCatching { WorkspaceRuntime.holder(context, pkg) }.getOrDefault(TargetHolder.NOBODY)
            else if (pkg == foreground) TargetHolder.OWNER else TargetHolder.NOBODY
        return StartFacts(
            mode = mode,
            fallback = MissionPlanes.fallback(context),
            override = compat.override(pkg),
            backgroundReady = blocker == null,
            backgroundBlocker = blocker,
            ownerBusyElsewhere = ownerBusy(foreground),
            targetCompat = compat.status(pkg, MissionPlanes.versionOf(context, pkg)),
            needsHands = HANDS.containsMatchIn(goal),
            holder = holder,
            secondWindow = compat.secondWindow(pkg) ?: (pkg in LIKELY_SECOND_WINDOW),
            appLabel = label(pkg) ?: "this app",
        )
    }

    /** Plan 26 (A42-4, A42-5): where the first app of the mission runs, with the owner's preferences and who holds it. */
    @Synchronized
    private fun startPlane(pkg: String) {
        if (!waitForApp(pkg)) return
        var facts = startFacts(pkg)
        var plan = StartPolicy.begin(facts)
        if (behind) {
            // Behind the front mission only a background screen of its own will do; anything else waits for the front.
            if (plan is StartPlan.Background && enterBackground(pkg, plan.entry, plan.reason, facts)) return
            if (!awaitFront("${facts.appLabel} needs your screen, so this task waits until the current one ends.")) return
            facts = startFacts(pkg)
            plan = StartPolicy.begin(facts)
        }
        trace("PLANE_START", "${plan.javaClass.simpleName}: ${plan.reason}")
        if (plan is StartPlan.Ask) {
            val answer = askOwner?.invoke("${plan.reason} Where should Cyclone work in ${facts.appLabel}?", StartPolicy.ASK_CHOICES)
            plan = when (answer?.let(StartPolicy::answer)) {
                StartChoice.WHEN_DONE -> StartPlan.Wait("You asked Cyclone to start when you are done with ${facts.appLabel}.")
                StartChoice.TAKE_TO_BACKGROUND -> StartPlan.Background("You moved ${facts.appLabel} to the background.",
                    if (facts.holder == TargetHolder.OWNER) BackgroundEntry.TAKE_FROM_OWNER else BackgroundEntry.LAUNCH)
                else -> StartPlan.Screen("You asked Cyclone to work on your screen.")
            }
            if (plan is StartPlan.Background && !facts.backgroundReady) plan = StartPlan.Screen(facts.backgroundBlocker ?: "Background work is not available.")
        }
        if (plan is StartPlan.Wait) {
            if (!waitForOwner(pkg, facts.appLabel)) { note = "You asked Cyclone to start now, on your screen."; refresh(); return }
            facts = startFacts(pkg)
            plan = if (facts.backgroundReady && facts.holder != TargetHolder.OWNER) StartPlan.Background("You are done with ${facts.appLabel}; Cyclone works behind your screen.",
                if (facts.holder == TargetHolder.RECENTS) BackgroundEntry.ADOPT_FROM_RECENTS else BackgroundEntry.LAUNCH)
                else StartPlan.Screen(facts.backgroundBlocker ?: "Cyclone works on your screen.")
        }
        when (plan) {
            is StartPlan.Screen -> { note = plan.reason; refresh() }
            is StartPlan.Background -> enterBackground(pkg, plan.entry, plan.reason, facts)
            else -> { note = plan.reason; refresh() }
        }
    }

    /** Bring [pkg] onto a new background screen the way the plan says; on failure the mission stays where it is. */
    private fun enterBackground(pkg: String, entry: BackgroundEntry, reason: String, facts: StartFacts, from: TaskPlane.Background? = null): Boolean {
        val version = MissionPlanes.versionOf(context, pkg)
        val started = runCatching {
            when (entry) {
                BackgroundEntry.SECOND_WINDOW -> WorkspaceRuntime.openSecond(context, pkg)
                BackgroundEntry.TAKE_FROM_OWNER -> WorkspaceRuntime.adopt(context, pkg).also { returnToScreen = true }
                BackgroundEntry.LAUNCH, BackgroundEntry.ADOPT_FROM_RECENTS -> WorkspaceRuntime.create(context, pkg)
            }
        }
        if (entry == BackgroundEntry.SECOND_WINDOW) compat.recordSecondWindow(pkg, started.isSuccess)
        started.onSuccess { session ->
            from?.let { runCatching { WorkspaceRuntime.close(it.sessionId) } }
            startedIn = PlaneKind.BACKGROUND
            land(TaskPlane.Background(session.sessionId, session.displayId), when (entry) {
                BackgroundEntry.LAUNCH -> "Cyclone now works on a background screen with only ${facts.appLabel}. The owner keeps using their phone. The app starts at its first screen."
                BackgroundEntry.ADOPT_FROM_RECENTS -> "Cyclone now works on a background screen with ${facts.appLabel}, on the page it was left on. Look at the screen before acting."
                BackgroundEntry.SECOND_WINDOW -> "Cyclone works in a second window of ${facts.appLabel} on a background screen; the owner keeps theirs."
                BackgroundEntry.TAKE_FROM_OWNER -> "The owner moved ${facts.appLabel} to the background for Cyclone, on the same page. Look at the screen before acting."
            })
            note = reason
            compat.record(pkg, version, PlaneOutcome.WORKED)
            return true
        }
        val error = started.exceptionOrNull()
        trace("PLANE_START_FAILED", "${entry.name.lowercase()}: ${error?.message.orEmpty().take(160)}")
        if (entry == BackgroundEntry.SECOND_WINDOW) {
            // One window only: fall back to what the owner chose for an app they are using.
            val retry = StartPolicy.begin(facts.copy(secondWindow = false))
            if (retry is StartPlan.Background) return enterBackground(pkg, retry.entry, retry.reason, facts, from)
            note = retry.reason
            refresh()
            return false
        }
        compat.record(pkg, version, PlaneOutcome.SWITCH_FAILED, error?.message?.take(120))
        note = BackgroundSetup.failure(context, pkg, error as? Exception ?: IllegalStateException(error))
        refresh()
        return false
    }

    /**
     * Plan 26 (A42-5): wait until the owner is done with [pkg] (it leaves their screen). False when they pressed
     * Start now or the mission stopped. The Mind waits at its step boundary; nothing is typed meanwhile.
     */
    private fun waitForOwner(pkg: String, appLabel: String): Boolean {
        waitingFor = appLabel
        startNowPressed = false
        note = "Waiting for you to finish with $appLabel."
        refresh()
        trace("PLANE_WAIT", "Waiting for the owner to finish with $appLabel")
        try {
            while (!ended && !stopping && !startNowPressed) {
                val foreground = BackgroundSetup.foregroundPackage()
                if (foreground != pkg && DeviceState.currentPackage != pkg && foreground != null) {
                    // A short grace: switching between apps passes through others.
                    Thread.sleep(OWNER_LEFT_GRACE_MS)
                    if (BackgroundSetup.foregroundPackage() != pkg) return true
                }
                Thread.sleep(WAIT_POLL_MS)
            }
            return false
        } catch (_: InterruptedException) {
            return false
        } finally {
            waitingFor = null
            refresh()
        }
    }

    private fun linkHandler(url: String): String? = runCatching {
        val uri = android.net.Uri.parse(url.trim().let { if (it.contains(":")) it else "https://$it" })
        context.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, uri), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeUnless { it == "android" || it.contains("resolver", ignoreCase = true) }
    }.getOrNull()

    /** Background work moves to another app: a new background screen for it, the old one closed. */
    @Synchronized
    private fun moveApp(from: TaskPlane.Background, pkg: String) {
        if (!waitForApp(pkg)) return
        val facts = startFacts(pkg)
        if (facts.holder == TargetHolder.OWNER) {
            // Plan 26 (A42-5): the owner is using that app: a second window, waiting, asking, or the screen, as chosen.
            when (val plan = StartPolicy.begin(facts)) {
                is StartPlan.Background -> { stopWatchdog(); if (!enterBackground(pkg, plan.entry, plan.reason, facts, from)) startWatchdog(); return }
                is StartPlan.Wait -> if (waitForOwner(pkg, facts.appLabel)) {
                    stopWatchdog(); if (!enterBackground(pkg, BackgroundEntry.ADOPT_FROM_RECENTS, "You are done with ${facts.appLabel}.", startFacts(pkg), from)) startWatchdog(); return
                }
                else -> Unit
            }
            switchTo(PlaneKind.SCREEN, "You have that app open, so Cyclone continues on your screen.", PlaneSignal.OWNER_OPENED_APP)
            return
        }
        if (compat.status(pkg, MissionPlanes.versionOf(context, pkg)).needsScreen) {
            switchTo(PlaneKind.SCREEN, "${label(pkg) ?: "That app"} ${compat.status(pkg, MissionPlanes.versionOf(context, pkg)).why}.", null)
            return
        }
        stopWatchdog()
        val created = runCatching { WorkspaceRuntime.create(context, pkg) }
        created.onSuccess { session ->
            runCatching { WorkspaceRuntime.close(from.sessionId) }
            land(TaskPlane.Background(session.sessionId, session.displayId), "Cyclone opened ${label(pkg) ?: pkg} on a fresh background screen. " +
                "It starts at its first screen.")
        }.onFailure { error ->
            trace("PLANE_APP_FAILED", error.message.orEmpty().take(160))
            compat.record(pkg, MissionPlanes.versionOf(context, pkg), PlaneOutcome.SWITCH_FAILED, error.message?.take(120))
            startWatchdog()
            switchTo(PlaneKind.SCREEN, "${label(pkg) ?: "That app"} could not open in the background.", null)
        }
    }

    // ---- switching -----------------------------------------------------------------------------------------------

    /** The pill: [to] null toggles. The owner's choice wins over the app memory only through long-press Allow. */
    fun ownerSwitch(to: PlaneKind?, byOwner: Boolean) {
        val target = to ?: plane.kind.other
        if (target == plane.kind) return
        if (target == PlaneKind.BACKGROUND) {
            MissionPlanes.blocker(context)?.let { tell(it); return }
            val pkg = packageName()
            if (pkg == null || pkg == context.packageName || pkg == launcher()) {
                tell("Cyclone is not working in an app right now."); return
            }
            val known = compat.status(pkg, MissionPlanes.versionOf(context, pkg))
            if (known.needsScreen) {
                tell("${label(pkg) ?: "This app"} ${known.why}. Long-press to allow it anyway."); return
            }
        }
        // An owner's tap waits for the Mind's current step to finish instead of giving up after a moment.
        if (byOwner) pauseBudgetMs = OWNER_PAUSE_MS
        val moved = try {
            switchTo(target, if (byOwner) "You moved the task ${target.label.lowercase()}." else "Moved ${target.label.lowercase()}.", null)
        } finally { pauseBudgetMs = PAUSE_TIMEOUT_MS }
        if (moved && byOwner) {
            movedTo = target
            moveSeq++
            // Behind the screen now: the owner lands on their home screen, not on whatever Android shows next.
            if (target == PlaneKind.BACKGROUND) runCatching { com.cyclone.mobile.CycloneAccessibilityService.instance?.guidedHome() }
            refresh()
        }
    }

    /** Something during a background task calls for the screen. */
    fun escalate(signal: PlaneSignal) {
        if (plane !is TaskPlane.Background) return
        val count = synchronized(counts) { (counts[signal] ?: 0) + 1 }.also { synchronized(counts) { counts[signal] = it } }
        val decision = PlanePolicy.escalate(signal, count) ?: return
        switchTo(PlaneKind.SCREEN, decision.reason, signal)
    }

    @Synchronized
    private fun switchTo(to: PlaneKind, reason: String, signal: PlaneSignal?): Boolean {
        if (behind && to == PlaneKind.SCREEN && !awaitFront(reason)) return false
        if (ended || plane.kind == to) return plane.kind == to
        val from = plane
        val pkg = packageName()
        publish(ui().copy(switching = true))
        stopWatchdog()
        val outcome = switcher.switch(SwitchRequest(missionId, from, to, reason))
        val version = pkg?.let { MissionPlanes.versionOf(context, it) }
        when (outcome) {
            is SwitchOutcome.Committed -> {
                note = reason
                trace("PLANE_SWITCH", "${from.kind.label} → ${to.label} in ${outcome.tookMs} ms: $reason")
                if (pkg != null) when (signal) {
                    PlaneSignal.SECURE_CONTENT -> compat.record(pkg, version, PlaneOutcome.SECURE_CONTENT, reason)
                    PlaneSignal.LEFT_DISPLAY -> compat.record(pkg, version, PlaneOutcome.LEFT_DISPLAY, reason)
                    else -> if (to == PlaneKind.BACKGROUND) compat.record(pkg, version, PlaneOutcome.WORKED)
                }
            }
            is SwitchOutcome.RolledBack -> {
                note = "Couldn't move: ${outcome.reason}"
                this.outcome = "Couldn't move ${label(pkg) ?: "the task"}: ${outcome.reason.trimEnd('.').replaceFirstChar { it.lowercase() }}."
                outcomeSeq++
                trace("PLANE_ROLLBACK", "${from.kind.label} → ${to.label} rolled back after ${outcome.tookMs} ms: ${outcome.reason}")
                if (pkg != null && to == PlaneKind.BACKGROUND) compat.record(pkg, version, PlaneOutcome.SWITCH_FAILED, outcome.reason.take(120))
            }
            is SwitchOutcome.Refused -> { note = outcome.reason; this.outcome = outcome.reason; outcomeSeq++ }
        }
        if (plane is TaskPlane.Background) startWatchdog()
        refresh()
        return outcome is SwitchOutcome.Committed
    }

    /** The mission is on [where] now: the Mind acts there from its next step. */
    private fun land(where: TaskPlane, modelNote: String) {
        if (where == TaskPlane.Screen && behind && !awaitFront(modelNote)) {
            // Stopping while it waited: no screen of its own and never the owner's.
            plane = TaskPlane.Screen
            MissionPlanes.releaseApps(missionId)
            refresh()
            return
        }
        plane = where
        MissionPlanes.hold(missionId, (where as? TaskPlane.Background)?.let { WorkspaceRuntime.packageOf(it.sessionId) })
        when (where) {
            is TaskPlane.Background -> {
                // The main screen is the owner's again while Cyclone works behind it (a behind mission never had it).
                if (!behind) DeviceState.setController(DeviceState.Controller.HUMAN)
                backgroundSince = System.currentTimeMillis()
                darkTicks = 0
                ladder = RecoveryLadder(mode)
                toolbox?.rebind(environment(where), modelNote)
                startWatchdog()
            }
            TaskPlane.Screen -> {
                DeviceState.setController(DeviceState.Controller.AGENT)
                toolbox?.rebind(CycloneAgentEnvironment(context, userTaskGoal = goal, ownerMission = true), modelNote)
            }
        }
        refresh()
    }

    private fun environment(plane: TaskPlane): CycloneAgentEnvironmentApi = when (plane) {
        TaskPlane.Screen -> CycloneAgentEnvironment(context, userTaskGoal = goal, ownerMission = true)
        is TaskPlane.Background -> CycloneAgentEnvironment(context, ExecutionContext(plane.sessionId, plane.displayId), goal, ownerMission = true)
    }

    /** The environment the mission starts with (the main screen; a background start happens at the first open_app). */
    fun initialEnvironment(): CycloneAgentEnvironmentApi = environment(TaskPlane.Screen)

    /** Lets a held Mind step go (the mission is stopping); a later step on a broken plane fails on its own. */
    fun releaseGate() {
        stopping = true
        toolbox?.gate?.resume()
    }

    fun end() {
        if (ended) return
        ended = true
        stopWatchdog()
        releaseGate()
        (plane as? TaskPlane.Background)?.let { background ->
            // A task the owner sent to the background comes back to their screen, on the page Cyclone left it. A task
            // that started in the background closes with its screen (the app keeps its data, not that window).
            if (returnToScreen) runCatching { WorkspaceRuntime.handoff(background.sessionId) }
            else runCatching { WorkspaceRuntime.pause(background.sessionId) }
            runCatching { WorkspaceRuntime.close(background.sessionId) }
        }
        parked?.let { runCatching { WorkspaceRuntime.close(it) } }
        parked = null
        MissionPlanes.ended(this)
    }

    // ---- the port ------------------------------------------------------------------------------------------------

    private inner class AndroidPlanePort : PlanePort {
        private var pausedGate = false

        override fun pause(from: TaskPlane): Boolean {
            val gate = toolbox?.gate ?: return true
            pausedGate = gate.pause(pauseBudgetMs)
            if (pausedGate && from is TaskPlane.Background) runCatching { WorkspaceRuntime.pause(from.sessionId) }
            return pausedGate
        }

        override fun move(from: TaskPlane, to: PlaneKind): TaskPlane = when (from) {
            TaskPlane.Screen -> {
                val pkg = BackgroundSetup.foregroundPackage() ?: DeviceState.currentPackage
                    ?: error("Cyclone could not tell which app it is working in.")
                require(pkg != context.packageName && pkg != launcher()) { "Cyclone is not working in an app right now." }
                val reuse = parked?.takeIf { WorkspaceRuntime.packageOf(it) == pkg && WorkspaceRuntime.handedOff(it) }
                if (reuse != null) {
                    WorkspaceRuntime.resume(reuse)
                    parked = null
                    val session = WorkspaceRuntime.requireScope(ExecutionContext(reuse, displayOf(reuse)))
                    TaskPlane.Background(session.sessionId, session.displayId)
                } else {
                    parked?.let { runCatching { WorkspaceRuntime.close(it) } }
                    parked = null
                    val session = WorkspaceRuntime.adopt(context, pkg)
                    returnToScreen = true
                    TaskPlane.Background(session.sessionId, session.displayId)
                }
            }
            is TaskPlane.Background -> {
                WorkspaceRuntime.handoff(from.sessionId)
                parked = from.sessionId
                TaskPlane.Screen
            }
        }

        override fun verify(plane: TaskPlane): String? = when (plane) {
            is TaskPlane.Background -> {
                val deadline = System.currentTimeMillis() + VERIFY_MS
                var probe = WorkspaceRuntime.probe(plane.sessionId)
                while ((!probe.second || !probe.third) && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                    probe = WorkspaceRuntime.probe(plane.sessionId)
                }
                when {
                    !probe.first -> "The background service is not running."
                    !probe.second -> "The app did not stay on the background screen."
                    // A static screen may not send a new frame; the first frame after a move is what is required.
                    !probe.third && !framesEver(plane.sessionId) -> "The background screen shows nothing yet."
                    else -> null
                }
            }
            TaskPlane.Screen -> {
                val pkg = parked?.let(WorkspaceRuntime::packageOf)
                val deadline = System.currentTimeMillis() + VERIFY_MS
                while (pkg != null && BackgroundSetup.foregroundPackage() != pkg && System.currentTimeMillis() < deadline) Thread.sleep(100)
                if (pkg != null && BackgroundSetup.foregroundPackage() != pkg) "The app did not appear on your screen." else null
            }
        }

        override fun restore(original: TaskPlane, attempted: TaskPlane?): Boolean = runCatching {
            when (original) {
                TaskPlane.Screen -> {
                    val back = attempted as? TaskPlane.Background ?: return@runCatching BackgroundSetup.foregroundPackage() != null
                    WorkspaceRuntime.handoff(back.sessionId)
                    WorkspaceRuntime.close(back.sessionId)
                    true
                }
                is TaskPlane.Background -> {
                    WorkspaceRuntime.resume(original.sessionId)
                    if (parked == original.sessionId) parked = null
                    WorkspaceRuntime.probe(original.sessionId).second
                }
            }
        }.getOrDefault(false)

        override fun grant(plane: TaskPlane) {
            val changed = plane != this@MissionPlaneSession.plane
            if (changed) land(plane, when (plane) {
                is TaskPlane.Background -> "The owner's screen is theirs again: Cyclone now works on a background screen with the same app, " +
                    "on the same page. Look at the screen before acting."
                TaskPlane.Screen -> "The task moved to the owner's main screen, on the same page. Look at the screen before acting."
            }) else if (plane is TaskPlane.Background && !WorkspaceRuntime.ownsInput(plane.sessionId)) {
                runCatching { WorkspaceRuntime.resume(plane.sessionId) }
            }
            if (pausedGate) { toolbox?.gate?.resume(); pausedGate = false }
        }

        override fun locate(missionId: String): TaskPlane? = this@MissionPlaneSession.plane
    }

    private fun displayOf(sessionId: String): Int =
        LiveVisionRuntime.sessions.lookup(sessionId).displayId

    private fun framesEver(sessionId: String): Boolean = runCatching { LiveVisionRuntime.preview(sessionId)?.also(Bitmap::recycle) != null }.getOrDefault(false)

    // ---- watchdog ------------------------------------------------------------------------------------------------

    private fun startWatchdog() {
        if (watchdog?.isAlive == true || ended) return
        watchdog = Thread({ watch() }, "cyclone-plane-watchdog").apply { isDaemon = true; start() }
    }

    private fun stopWatchdog() {
        val running = watchdog ?: return
        watchdog = null
        if (running !== Thread.currentThread()) running.interrupt()
    }

    private fun watch() {
        val me = Thread.currentThread()
        while (watchdog === me && !ended) {
            try { Thread.sleep(WATCH_MS) } catch (_: InterruptedException) { return }
            val background = plane as? TaskPlane.Background ?: return
            if (switcher.switching) continue
            val (service, present, _) = WorkspaceRuntime.probe(background.sessionId)
            // Plan 26 (A42-5): the owner opened the app Cyclone was using. They win: Cyclone waits at its next step and
            // takes the app back from Recents when they leave it.
            val pkg = WorkspaceRuntime.packageOf(background.sessionId)
            if (!present && pkg != null && BackgroundSetup.foregroundPackage() == pkg) {
                yieldToOwner(background, pkg)
                continue
            }
            val verdict = BackgroundHealth.judge(HealthFacts(
                binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false),
                serviceAlive = service,
                displayValid = service,
                // Frames arrive only when the background screen changes, so a quiet screen is not a stall.
                lastFrameAgeMs = 0,
                taskPresent = present,
                deviceLocked = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true,
            ))
            when (verdict) {
                HealthVerdict.Healthy -> if (secureLooking(background.sessionId)) escalate(PlaneSignal.SECURE_CONTENT)
                HealthVerdict.Locked -> Unit
                is HealthVerdict.Broken -> recover(background, verdict.problem)
            }
        }
    }

    @Synchronized
    private fun yieldToOwner(background: TaskPlane.Background, pkg: String) {
        val gate = toolbox?.gate
        if (gate != null && !gate.pause(PAUSE_TIMEOUT_MS)) return
        val appLabel = label(pkg) ?: "this app"
        trace("PLANE_YIELD", "The owner opened $appLabel; Cyclone pauses until they leave it")
        try {
            runCatching { WorkspaceRuntime.close(background.sessionId) }
            plane = TaskPlane.Screen
            DeviceState.setController(DeviceState.Controller.HUMAN)
            waitingFor = appLabel
            note = "Paused: you have $appLabel. Cyclone continues when you leave it."
            refresh()
            while (!ended && !stopping && !startNowPressed && BackgroundSetup.foregroundPackage() == pkg) Thread.sleep(WAIT_POLL_MS)
            waitingFor = null
            if (ended || stopping) return
            if (startNowPressed) {
                startNowPressed = false
                land(TaskPlane.Screen, "The owner opened $appLabel and asked Cyclone to continue on their screen, in that app. Look at the screen before acting.")
                return
            }
            val facts = startFacts(pkg)
            val session = if (facts.backgroundReady) runCatching { WorkspaceRuntime.create(context, pkg) }.getOrNull() else null
            if (session != null) {
                land(TaskPlane.Background(session.sessionId, session.displayId), "The owner used $appLabel for a moment; Cyclone has it " +
                    "back on the background screen. The page may have changed: look at the screen before acting.")
            } else {
                land(TaskPlane.Screen, "The owner used $appLabel; Cyclone could not take it back to the background and continues on the " +
                    "owner's screen. Look at the screen before acting.")
            }
        } catch (_: InterruptedException) {
            return
        } finally {
            gate?.resume()
            refresh()
        }
    }

    /** Two looks in a row at a black background frame while the app is there: a protected screen. */
    private fun secureLooking(sessionId: String): Boolean {
        val frame = runCatching { LiveVisionRuntime.preview(sessionId) }.getOrNull() ?: return false
        val black = try { allBlack(frame) } finally { frame.recycle() }
        darkTicks = if (black) darkTicks + 1 else 0
        return darkTicks >= 2
    }

    @Synchronized
    private fun recover(background: TaskPlane.Background, problem: HealthProblem) {
        val step = ladder.next(problem)
        trace("PLANE_HEALTH", "${problem.readable}; next: ${step.name.lowercase()}")
        val pkg = WorkspaceRuntime.packageOf(background.sessionId)
        when (step) {
            RecoveryStep.REBIND -> runCatching { WorkspaceRuntime.connect(context) }
            RecoveryStep.RECREATE_AND_RETURN -> {
                if (pkg == null) { recover(background, problem); return }
                val gate = toolbox?.gate
                if (gate != null && !gate.pause(PAUSE_TIMEOUT_MS)) return
                try {
                    runCatching { WorkspaceRuntime.close(background.sessionId) }
                    val session = runCatching { WorkspaceRuntime.create(context, pkg) }.getOrNull()
                    if (session != null) {
                        land(TaskPlane.Background(session.sessionId, session.displayId), "The background screen was rebuilt after ${problem.readable}. " +
                            "The app restarted at its first screen: find your place again (go_to helps).")
                    } else {
                        if (problem == HealthProblem.TASK_GONE) pkg.let { compat.record(it, MissionPlanes.versionOf(context, it), PlaneOutcome.LEFT_DISPLAY, problem.readable) }
                        land(TaskPlane.Screen, "The background screen failed (${problem.readable}), so Cyclone continues on the owner's main screen. " +
                            "Open the app again there.")
                    }
                } finally { gate?.resume() }
            }
            RecoveryStep.MOVE_TO_SCREEN -> {
                val gate = toolbox?.gate
                if (gate != null && !gate.pause(PAUSE_TIMEOUT_MS)) return
                try {
                    runCatching { WorkspaceRuntime.close(background.sessionId) }
                    land(TaskPlane.Screen, "The background screen failed (${problem.readable}), so Cyclone continues on the owner's main screen. " +
                        "Open the app again there.")
                } finally { gate?.resume() }
            }
            RecoveryStep.PAUSE -> {
                note = "Background work stopped: ${problem.readable}. Tap the pill to continue on screen."
                val gate = toolbox?.gate
                gate?.pause(PAUSE_TIMEOUT_MS)
                refresh()
                // Held until the owner moves the task; the pill's switch resumes the gate through grant().
                stopWatchdog()
            }
        }
        refresh()
    }

    // ---- behind the front mission (plan 26 §6) -----------------------------------------------------------------

    /**
     * Waits until this behind mission may become the front one (the front mission ended), then promotes it. False when
     * it stopped meanwhile. A front mission returns true at once.
     */
    private fun awaitFront(reason: String): Boolean {
        if (!behind) return true
        trace("CREW_WAIT_FRONT", reason)
        runCatching { onWaiting?.invoke("Waiting for your screen: ${reason.trimEnd('.')}.") }
        try {
            while (!ended && !stopping) {
                if (MissionPlanes.claimFront(this)) {
                    behind = false
                    trace("CREW_FRONT", "The task came to the front")
                    runCatching { onWaiting?.invoke(null) }
                    runCatching { onPromote?.invoke(this) }
                    refresh()
                    return true
                }
                Thread.sleep(WAIT_POLL_MS)
            }
        } catch (_: InterruptedException) {
            return false
        }
        return false
    }

    /** Another Cyclone mission holds [pkg]: wait for it (one mission per app at a time). False when stopping. */
    private fun waitForApp(pkg: String): Boolean {
        if (MissionPlanes.heldByOther(pkg, missionId) == null) return true
        val appLabel = label(pkg) ?: "that app"
        trace("CREW_WAIT_APP", "Another Cyclone task uses $appLabel")
        if (behind) runCatching { onWaiting?.invoke("Waiting for $appLabel: another Cyclone task uses it.") }
        else { waitingFor = appLabel; note = "Waiting for $appLabel: another Cyclone task uses it."; refresh() }
        try {
            while (!ended && !stopping && !startNowPressed && MissionPlanes.heldByOther(pkg, missionId) != null) Thread.sleep(WAIT_POLL_MS)
        } catch (_: InterruptedException) {
            return false
        } finally {
            if (behind) runCatching { onWaiting?.invoke(null) } else { waitingFor = null; refresh() }
        }
        return !ended && !stopping
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private fun resolve(requested: String): String? {
        val wanted = requested.trim()
        if (wanted.isBlank()) return null
        val apps: List<MindApp> = runCatching {
            if (com.cyclone.mobile.fastpath.InstalledAppInventory.snapshot.isEmpty()) com.cyclone.mobile.fastpath.InstalledAppInventory.refresh(context)
            com.cyclone.mobile.fastpath.InstalledAppInventory.snapshot.map { MindApp(it.packageName, it.label) }
        }.getOrDefault(emptyList())
        return apps.firstOrNull { it.packageName.equals(wanted, true) }?.packageName
            ?: apps.filter { it.label.equals(wanted, true) }.singleOrNull()?.packageName
            ?: apps.filter { it.label.lowercase().contains(wanted.lowercase()) }.singleOrNull()?.packageName
    }

    private fun label(pkg: String?): String? = pkg?.let {
        runCatching { com.cyclone.mobile.fastpath.InstalledAppInventory.snapshot.firstOrNull { app -> app.packageName == it }?.label }.getOrNull()
    }

    private fun launcher(): String? = runCatching {
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }.getOrNull()

    private fun ownerBusy(foreground: String?): Boolean {
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        return interactive && foreground != null && foreground != context.packageName && foreground != launcher()
    }

    private fun trace(type: String, text: String) {
        val id = traceId ?: return
        runCatching { AgentTraceRuntime.event(context, id, type, text.take(300)) }
    }

    private fun ui(): PlaneUi {
        val blocker = MissionPlanes.blocker(context)
        return PlaneUi(missionId, plane.kind, switcher.switching, blocker == null, note ?: blocker, label(packageName()), waitingFor,
            (plane as? TaskPlane.Background)?.sessionId, outcome, outcomeSeq, movedTo, moveSeq)
    }

    private fun refresh() {
        if (!ended) publish(ui())
    }

    /** Plan 27: tell the owner why a move did not happen, beside the pill. */
    private fun tell(text: String) {
        note = text
        outcome = text
        outcomeSeq++
        refresh()
    }

    companion object {
        const val PAUSE_TIMEOUT_MS = 2_500L
        /** Plan 27: an owner's move waits up to this long for the Mind's step boundary (the pill says Moving). */
        const val OWNER_PAUSE_MS = 20_000L
        const val VERIFY_MS = 1_500L
        const val WATCH_MS = 1_000L
        const val WAIT_POLL_MS = 700L
        const val OWNER_LEFT_GRACE_MS = 1_500L
        private const val STOPPED = "NOT RUN: the task is stopping."
        /** Apps known to open a second window; others are learned (AppPlaneCompat.secondWindow). */
        private val LIKELY_SECOND_WINDOW = setOf("com.android.chrome", "com.google.android.apps.docs.editors.docs",
            "com.google.android.apps.docs.editors.sheets", "com.google.android.apps.docs.editors.slides")
        private val HANDS = Regex("(?i)\\b(sign in|log in|login|inloggen|password|wachtwoord|camera|photo|foto|selfie|scan|captcha)\\b")

        /** A frame is black when no sampled pixel is brighter than a dark UI's darkest text would be. */
        fun allBlack(frame: Bitmap): Boolean {
            val stepX = (frame.width / 48).coerceAtLeast(1)
            val stepY = (frame.height / 48).coerceAtLeast(1)
            var y = 0
            while (y < frame.height) {
                var x = 0
                while (x < frame.width) {
                    val c = frame.getPixel(x, y)
                    if ((c shr 16 and 0xff) > 10 || (c shr 8 and 0xff) > 10 || (c and 0xff) > 10) return false
                    x += stepX
                }
                y += stepY
            }
            return true
        }
    }
}
