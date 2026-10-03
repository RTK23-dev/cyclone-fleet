package com.cyclone.mobile.applearner

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.applearner.graphv2.AtlasRuntime
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

object AppLearnerRuntime {
    @Volatile private var initialized = false
    private lateinit var appContext: Context
    lateinit var store: AppKnowledgeStore
        private set
    lateinit var explorer: AppExplorer
        private set
    private lateinit var graphExecutor: AppGraphExecutor
    private val passiveExecutor = Executors.newSingleThreadExecutor()
    private val lastPassiveObservation = AtomicLong(0L)
    private var controllerBeforeLearning = DeviceState.Controller.AGENT
    private var overlay: AppLearnerOverlayController? = null

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        store = AppKnowledgeStore(appContext)
        AtlasRuntime.initialize(appContext, store)
        explorer = AppExplorer(appContext, store)
        graphExecutor = AppGraphExecutor(appContext, store)
        initialized = true
    }

    fun progress(): LearnerProgress = if (initialized) explorer.progress() else LearnerProgress()
    fun learnedApps(): List<LearnedApp> = if (initialized) store.listApps() else emptyList()
    fun graph(packageName: String): AppGraphSnapshot? = if (initialized) store.graph(packageName) else null

    fun start(context: Context, packageName: String, appLabel: String, instruction: String, mode: LearningMode, useAiPlanner: Boolean = true) {
        initialize(context)
        if (FollowMeLearnerRuntime.progress().active) FollowMeLearnerRuntime.stop()
        controllerBeforeLearning = DeviceState.controller
        if (mode == LearningMode.PASSIVE) DeviceState.setController(DeviceState.Controller.HUMAN)
        explorer.start(AppExplorer.SessionConfig(packageName, appLabel, instruction, mode, useAiPlanner = useAiPlanner))
        CycloneAccessibilityService.instance?.let { service ->
            overlay?.dismiss()
            overlay = AppLearnerOverlayController(service).also { it.show() }
        }
    }

    fun pause() = explorer.pause()
    fun resume() = explorer.resume()
    fun takeOver() = explorer.takeOver()
    fun returnFromTakeover() = explorer.returnFromTakeover()
    fun updateInstruction(instruction: String) = explorer.updateInstruction(instruction)

    fun stop() {
        if (!initialized) return
        explorer.stop()
        overlay?.dismiss()
        overlay = null
        if (controllerBeforeLearning == DeviceState.Controller.AGENT) {
            DeviceState.setController(DeviceState.Controller.AGENT)
            PhoneToolExecutor.execute(appContext, PhoneToolRequest("learner-stop-observe-${UUID.randomUUID()}", "phone.observe", JSONObject()))
        }
    }

    fun dismissOverlay() {
        overlay?.dismiss()
        overlay = null
    }

    fun ask(packageName: String, question: String): String {
        val graph = store.graph(packageName) ?: return "Cyclone has not learned this app yet."
        return AppGraphRetriever.answerLocally(graph, question)
    }

    fun retrieval(packageName: String, goal: String, currentScreenId: String? = null): JSONObject? =
        store.graph(packageName)?.let { AppGraphRetriever.retrieve(it, goal, currentScreenId) }

    /** Records only a Gateway action whose semantic after-state verification has already passed. */
    fun recordVerifiedPcRoute(
        context: Context,
        tool: String,
        params: JSONObject,
        outcome: PcRouteOutcomeEvidence,
    ): PcRouteLearningResult {
        initialize(context)
        return PcVerifiedRouteLearning.record(appContext, store, tool, params, outcome)
    }

    fun proposeAutomation(packageName: String, goal: String): AutomationDefinition? {
        val graph = store.graph(packageName) ?: return null
        val path = AppGraphRetriever.findBestPath(graph, goal) ?: return null
        return GraphAutomationCompiler.compile(graph, path, "$goal · ${graph.app.label}")
    }

    fun saveAutomation(proposal: AutomationDefinition): AutomationDefinition {
        com.cyclone.mobile.automation.AutomationRuntime.initialize(appContext)
        val safe = proposal.copy(enabled = false)
        com.cyclone.mobile.automation.AutomationRuntime.store.saveAutomation(safe)
        return safe
    }

    fun skillCandidates(packageName: String): List<SkillCandidate> =
        store.graph(packageName)?.let(SkillCandidateGenerator::candidates).orEmpty()

    fun saveSkill(packageName: String, candidate: SkillCandidate) =
        store.graph(packageName)?.let { SkillCandidateGenerator.saveAsSkill(appContext, it, candidate) }

    fun executeLearnedRoute(packageName: String, goal: String): Result<String> = graphExecutor.execute(packageName, goal)

    fun markScreenIncorrect(packageName: String, screenId: String) {
        val screen = store.graph(packageName)?.screens?.firstOrNull { it.id == screenId } ?: return
        store.upsertScreen(screen.copy(
            knowledgeState = KnowledgeState.STALE,
            confidence = (screen.confidence - 0.25).coerceAtLeast(0.05),
            lastSeenAt = System.currentTimeMillis(),
        ))
        store.mirror(packageName)
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!initialized) return
        // Cyclone's own Gateway/settings UI is not an app-learning target. Ignoring host-app events
        // prevents the pairing-code Compose update from feeding Follow Me / gesture intelligence back
        // into the same process while the secure challenge is being created.
        if (event.packageName?.toString() == appContext.packageName) return

        // Follow Me keeps its existing V2.9 page/click learner. V2.9.2 adds a second, additive
        // observer that turns horizontal/vertical swipe demonstrations into real reusable graph and
        // Brain transitions instead of losing their direction as a generic scroll event.
        FollowMeLearnerRuntime.onAccessibilityEvent(event)
        FollowMeGestureIntelligenceV292.onAccessibilityEvent(appContext, event)

        val p = explorer.progress()
        if (p.mode != LearningMode.PASSIVE || p.state != LearnerSessionState.LEARNING) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName != p.packageName) return

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            event.source?.let { explorer.notePassiveAction(nodeJson(it)) }
        }

        if (event.eventType in setOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
            )) {
            val now = System.currentTimeMillis()
            val previous = lastPassiveObservation.get()
            if (now - previous < 450L || !lastPassiveObservation.compareAndSet(previous, now)) return
            passiveExecutor.submit {
                Thread.sleep(120)
                val result = PhoneToolExecutor.execute(
                    appContext,
                    PhoneToolRequest("passive-observe-${UUID.randomUUID()}", "phone.observe", JSONObject()),
                )
                (result.payload as? JSONObject)?.let(explorer::observePassive)
            }
        }
    }

    private fun nodeJson(node: AccessibilityNodeInfo): JSONObject {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return JSONObject()
            .put("text", node.text?.toString().orEmpty())
            .put("contentDescription", node.contentDescription?.toString().orEmpty())
            .put("resourceId", node.viewIdResourceName.orEmpty())
            .put("role", when {
                node.isEditable -> "textbox"
                node.isCheckable -> "checkbox"
                node.isScrollable -> "scroll_container"
                node.isClickable -> "button"
                else -> "generic"
            })
            .put("clickable", node.isClickable)
            .put("editable", node.isEditable)
            .put("scrollable", node.isScrollable)
            .put("bounds", JSONObject().put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom))
    }
}
