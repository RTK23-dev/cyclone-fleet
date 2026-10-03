package com.cyclone.mobile.applearner

import com.cyclone.mobile.places.PlaceResolver

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.RoutineTeachingAnalyzer
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.applearner.graphv2.AtlasRuntime
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.guided.RoutineTeachingOverlayRuntime
import com.cyclone.mobile.guided.RoutineTeachingRuntime
import com.cyclone.mobile.uiSnapshotFromJson
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * V2.9 user-driven whole-phone learning.
 *
 * HUMAN keeps control. Accessibility events are debounced and collapsed into stable PageContexts.
 * The LEARN overlay remains visible until Stop & review, while ordinary human taps/holds/scrolls and
 * explicit guided placements feed one persistent teaching timeline and the existing App Graph.
 */
data class FollowMeProgress(
    val active: Boolean = false,
    val paused: Boolean = false,
    val startedAt: Long? = null,
    val currentApp: String = "",
    val currentPackage: String = "",
    val currentScreen: String = "",
    val appsSeen: Int = 0,
    val screensSeen: Int = 0,
    val actionsSeen: Int = 0,
    val pathsLearned: Int = 0,
    val teachingSessionId: String? = null,
    val selectedModelId: String? = null,
    val message: String = "Ready",
)

object FollowMeLearnerRuntime {
    private val executor = Executors.newSingleThreadExecutor()
    private val lastObservation = AtomicLong(0L)
    private val lastScrollCapture = AtomicLong(0L)
    @Volatile private var state = FollowMeProgress()
    @Volatile private var appContext: Context? = null
    private var previousController = DeviceState.Controller.AGENT
    private var previousPackage: String? = null
    private var previousPageKey: String? = null
    private var previousScreenId: String? = null
    private var pendingAction: PendingUserAction? = null
    private val seenApps = linkedSetOf<String>()
    private val seenScreens = linkedSetOf<String>()
    private val seenActions = linkedSetOf<String>()
    private val seenPaths = linkedSetOf<String>()

    private data class PendingUserAction(
        val packageName: String,
        val screenId: String?,
        val label: String,
        val selector: JSONObject,
        val risk: ActionRisk,
        val advertisedActions: List<String>,
        val kind: String,
    )

    fun progress(): FollowMeProgress = state

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        AppLearnerRuntime.initialize(app)
        AdaptiveBrainRuntime.initialize(app)
        PageAwarenessRuntime.initialize(app)
        RoutineTeachingRuntime.initialize(app)
        previousController = DeviceState.controller
        DeviceState.setController(DeviceState.Controller.HUMAN)
        previousPackage = null
        previousPageKey = null
        previousScreenId = null
        pendingAction = null
        lastObservation.set(0L)
        lastScrollCapture.set(0L)
        seenApps.clear(); seenScreens.clear(); seenActions.clear(); seenPaths.clear()

        val prefs = app.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE)
        val selectedModel = prefs.getString("openrouter_model", OpenRouterModelPresets.DEFAULT.id)
            .orEmpty().ifBlank { OpenRouterModelPresets.DEFAULT.id }
        val session = RoutineTeachingRuntime.start(app, "Teach my phone routine", selectedModel)
        state = FollowMeProgress(
            active = true,
            startedAt = System.currentTimeMillis(),
            teachingSessionId = session.id,
            selectedModelId = selectedModel,
            message = "Learning is live. Use your phone normally or tap the LEARN bubble to guide an exact Tap, Hold, Swipe, Check or Wait step.",
        )
        val service = CycloneAccessibilityService.instance
        if (service != null) {
            RoutineTeachingOverlayRuntime.show(service, session)
        } else {
            state = state.copy(message = "Enable Cyclone Accessibility so the teaching overlay and semantic page capture can run.")
        }
    }

    fun pause() {
        if (!state.active) return
        state = state.copy(paused = true, message = "Teaching paused. No navigation is being learned.")
    }

    fun resume() {
        if (!state.active) return
        state = state.copy(paused = false, message = "Teaching resumed. Keep using your phone normally.")
    }

    @Synchronized
    fun stop() {
        if (!state.active) return
        if (RoutineTeachingOverlayRuntime.isShowing()) RoutineTeachingOverlayRuntime.requestStop()
        else finishFromOverlay(null, null)
    }

    /** Called by the V2.9 overlay after explicit guided steps have been compiled or cancelled. */
    @Synchronized
    fun finishFromOverlay(copiedAutomationId: String?, optimizedAutomationId: String?) {
        if (!state.active) return
        val app = appContext ?: return
        val finalStats = state
        state = state.copy(active = false, paused = false, message = "Teaching stopped. Building your review report and updating Cyclone Brain.")
        pendingAction = null
        RoutineTeachingOverlayRuntime.dismiss()
        if (previousController == DeviceState.Controller.AGENT) {
            DeviceState.setController(DeviceState.Controller.AGENT)
            PhoneToolExecutor.execute(app, PhoneToolRequest("follow-me-final-observe-${UUID.randomUUID()}", "phone.observe", JSONObject()))
        }
        runCatching { AdaptiveBrainRuntime.store.writeMirror() }

        val finished = RoutineTeachingRuntime.finish(
            appsSeen = finalStats.appsSeen,
            pagesSeen = finalStats.screensSeen,
            actionsSeen = finalStats.actionsSeen,
            pathsLearned = finalStats.pathsLearned,
            copiedAutomationId = copiedAutomationId,
            optimizedAutomationId = optimizedAutomationId,
        )
        if (finished != null) {
            RoutineTeachingRuntime.launchReport(app, finished.id)
            if (OpenRouterSecretStore.hasKey(app) && finished.modelId.isNotBlank()) {
                executor.submit {
                    RoutineTeachingAnalyzer.analyze(app, finished)?.takeIf(String::isNotBlank)?.let {
                        RoutineTeachingRuntime.saveAiAnalysis(finished.id, it)
                    }
                }
            }
        }
    }

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val app = appContext ?: return
        if (!state.active || state.paused) return
        val packageName = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (packageName == app.packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> event.source?.let { captureUserAction(packageName, it, "click") }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> event.source?.let { captureUserAction(packageName, it, "long_click") }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> event.source?.let { captureScroll(packageName, it) }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> Unit // typed contents are deliberately ignored
        }

        if (event.eventType in setOf(
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED,
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            )) {
            val now = System.currentTimeMillis()
            val previous = lastObservation.get()
            if (now - previous < 650L || !lastObservation.compareAndSet(previous, now)) return
            executor.submit {
                Thread.sleep(180)
                if (!state.active || state.paused) return@submit
                val result = PhoneToolExecutor.execute(app, PhoneToolRequest("follow-me-observe-${UUID.randomUUID()}", "phone.observe", JSONObject()))
                (result.payload as? JSONObject)?.let(::learnSnapshot)
            }
        }
    }

    private fun captureScroll(packageName: String, node: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        val last = lastScrollCapture.get()
        if (now - last < 900L || !lastScrollCapture.compareAndSet(last, now)) return
        val json = nodeJson(node)
        if (ActionSafetyPolicy.looksSensitiveField(json)) return
        val selector = selectorFrom(json)
        val actions = actionsFrom(json)
        val label = json.optString("contentDescription").takeIf(String::isNotBlank)
            ?: json.optString("resourceId").substringAfterLast('/').takeIf(String::isNotBlank)
            ?: "this page"
        seenActions += "$packageName|scroll|${previousPageKey.orEmpty()}"
        RoutineTeachingRuntime.recordHumanAction(
            packageName = packageName,
            label = label.take(120),
            kind = "scroll",
            selector = selector,
            advertisedActions = actions,
            pageTitle = state.currentScreen.takeIf(String::isNotBlank),
            pageKey = previousPageKey,
        )
        publish(message = "Saw you scroll · Cyclone is checking whether Android exposes a faster semantic scroll action")
    }

    private fun captureUserAction(packageName: String, node: AccessibilityNodeInfo, kind: String) {
        val json = nodeJson(node)
        if (ActionSafetyPolicy.looksSensitiveField(json)) return
        val label = json.optString("text").trim()
            .ifBlank { json.optString("contentDescription").trim() }
            .ifBlank { json.optString("resourceId").substringAfterLast('/') }
        if (label.isBlank()) return
        val selector = selectorFrom(json)
        val actions = actionsFrom(json)
        val risk = ActionSafetyPolicy.classify(label, json.optString("resourceId"), json.optString("contentDescription"))
        pendingAction = PendingUserAction(
            packageName = packageName,
            screenId = if (packageName == previousPackage) previousScreenId else null,
            label = label.take(120),
            selector = selector,
            risk = risk,
            advertisedActions = actions,
            kind = kind,
        )
        seenActions += "$packageName|${PageSignatureEngine.normalizeLabel(label)}"
        RoutineTeachingRuntime.recordHumanAction(
            packageName = packageName,
            label = label.take(120),
            kind = kind,
            selector = selector,
            advertisedActions = actions,
            pageTitle = state.currentScreen.takeIf(String::isNotBlank),
            pageKey = previousPageKey,
        )
        publish(message = "Saw you ${if (kind == "long_click") "hold" else "tap"} $label · capturing what that action changes")
    }

    private fun learnSnapshot(snapshot: JSONObject) {
        val app = appContext ?: return
        val packageName = snapshot.optString("package").takeIf { it.isNotBlank() } ?: return
        if (packageName == app.packageName) return
        val page = PageAwarenessRuntime.capture(app, snapshot)
        runCatching { uiSnapshotFromJson(snapshot) }.getOrNull()?.let {
            RoutineTeachingRuntime.recordPage(it, page.pageKey, page.title)
        }

        if (packageName == previousPackage && page.pageKey == previousPageKey) {
            publish(
                currentApp = appLabel(packageName),
                currentPackage = packageName,
                currentScreen = page.title,
                message = "Still on ${page.title} · ${page.observationCount} Android events merged into one semantic page",
            )
            return
        }

        val label = appLabel(packageName)
        val store = AppLearnerRuntime.store
        val packageInfo = runCatching { app.packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val existingApp = store.getApp(packageName)
        store.upsertApp((existingApp ?: LearnedApp(packageName = packageName, label = label)).copy(
            label = label,
            versionName = packageInfo?.versionName,
            versionCode = packageInfo?.longVersionCode,
            knowledgeState = if (existingApp == null) KnowledgeState.DISCOVERED else existingApp.knowledgeState,
            confidence = maxOf(existingApp?.confidence ?: 0.0, 0.60),
            lastLearnedAt = System.currentTimeMillis(),
            instructionSummary = "Observed in V2.9 unified teaching mode as stable semantic pages while the user navigated normally.",
        ))

        val rawCandidate = ScreenSemanticizer.fromSnapshot(packageName, snapshot)
        val recognition = rawCandidate.recognition.copy(
            semanticFingerprint = page.pageKey,
            structuralFingerprint = page.structuralKey,
            stableAnchors = page.controls.map { PageSignatureEngine.normalizeLabel(it.label) }.filter(String::isNotBlank).distinct().take(24),
            className = page.className ?: rawCandidate.recognition.className,
        )
        val candidate = rawCandidate.copy(
            identity = PageSignatureEngine.semanticName(page.title, "page"),
            title = page.title.ifBlank { rawCandidate.title },
            recognition = recognition,
        )
        val match = store.findBestScreenMatch(packageName, candidate.recognition, threshold = 0.56)
        val screen = if (match != null) {
            val current = match.first
            current.copy(
                title = if (candidate.title != "Screen") candidate.title else current.title,
                purpose = if (current.purpose.startsWith("A learned screen")) candidate.purpose else current.purpose,
                recognition = candidate.recognition,
                knowledgeState = if (match.second >= 0.86) KnowledgeState.UNDERSTOOD else current.knowledgeState,
                confidence = maxOf(current.confidence, match.second.coerceIn(0.62, 0.97)),
                appVersion = packageInfo?.versionName,
                lastSeenAt = System.currentTimeMillis(),
            )
        } else {
            LearnedScreen(
                packageName = packageName,
                identity = uniqueIdentity(store, packageName, candidate.identity),
                title = candidate.title,
                purpose = candidate.purpose,
                recognition = candidate.recognition,
                knowledgeState = KnowledgeState.DISCOVERED,
                confidence = 0.70,
                appVersion = packageInfo?.versionName,
            )
        }
        store.upsertScreen(screen)
        page.controls.forEach { control ->
            store.upsertAction(LearnedAction(
                packageName = packageName,
                screenId = screen.id,
                semanticName = control.semanticName,
                label = control.label,
                androidActions = control.androidActions,
                selectorJson = control.selector.toString(),
                risk = control.risk,
                requiredInput = null,
                knowledgeState = KnowledgeState.UNDERSTOOD,
                confidence = maxOf(control.confidence, 0.68),
            ))
        }

        // Promote the same stable Follow Me observation into the durable V5 Atlas immediately.
        // The legacy App Graph remains the source for its own 4.8 behavior; Atlas receives only
        // semantic structure and hashed selector metadata through FollowMeAtlasPromoter.
        store.getApp(packageName)?.takeUnless { PlaceResolver.isChromePackage(packageName) }?.let { learnedApp ->
            val learnedActions = store.listActions(packageName).filter { it.screenId == screen.id }
            runCatching {
                AtlasRuntime.followMe.observeScreen(
                    app = learnedApp,
                    screen = screen,
                    actions = learnedActions,
                    persona = AtlasPersona.LIVE,
                )
            }
        }

        seenApps += packageName
        seenScreens += page.pageKey
        val previousPkg = previousPackage
        val previousScreen = previousScreenId
        val previousPage = previousPageKey
        val action = pendingAction
        if (previousPkg != null && previousPkg == packageName && previousScreen != null && previousScreen != screen.id && action != null && action.packageName == packageName) {
            val learned = LearnedAction(
                packageName = packageName,
                screenId = previousScreen,
                semanticName = PageSignatureEngine.semanticName(action.label, "button"),
                label = action.label,
                androidActions = (action.advertisedActions + "USER_DEMONSTRATED").distinct(),
                selectorJson = action.selector.toString(),
                risk = action.risk,
                knowledgeState = KnowledgeState.UNDERSTOOD,
                confidence = 0.82,
            )
            store.upsertAction(learned)
            val storedAction = store.listActions(packageName).lastOrNull {
                it.screenId == previousScreen && it.semanticName == learned.semanticName
            }
            if (storedAction != null) {
                val observedAt = System.currentTimeMillis()
                store.upsertTransition(LearnedTransition(
                    packageName = packageName,
                    fromScreenId = previousScreen,
                    actionId = storedAction.id,
                    toScreenId = screen.id,
                    knowledgeState = KnowledgeState.UNDERSTOOD,
                    confidence = 0.84,
                    lastObservedAt = observedAt,
                ))
                val fromScreen = store.graph(packageName)?.screens?.firstOrNull { it.id == previousScreen }
                val learnedApp = store.getApp(packageName)
                if (fromScreen != null && learnedApp != null &&
                    !PlaceResolver.isChromePackage(packageName)) {
                    runCatching {
                        AtlasRuntime.followMe.demonstrateTransition(
                            app = learnedApp,
                            fromScreen = fromScreen,
                            action = storedAction,
                            toScreen = screen,
                            observedAtEpochMillis = observedAt,
                            persona = AtlasPersona.LIVE,
                        )
                    }
                }
                seenPaths += "$previousPage|${storedAction.semanticName}|${page.pageKey}"
            }
        } else if (previousPkg != null && previousPkg != packageName) {
            AdaptiveBrainRuntime.store.recordHumanTransition(
                goalHint = "open $label",
                fromPackage = previousPkg,
                fromFingerprint = previousPage,
                targetPackage = packageName,
                targetFingerprint = page.pageKey,
                selector = action?.selector ?: JSONObject(),
            )
            seenPaths += "$previousPkg->$packageName"
        }

        previousPackage = packageName
        previousPageKey = page.pageKey
        previousScreenId = screen.id
        pendingAction = null
        store.mirror(packageName)
        publish(
            currentApp = label,
            currentPackage = packageName,
            currentScreen = screen.title,
            message = "Learned ${screen.title} · ${page.controls.size} semantic controls mapped · screenshot added to teaching timeline",
        )
    }

    private fun publish(
        currentApp: String = state.currentApp,
        currentPackage: String = state.currentPackage,
        currentScreen: String = state.currentScreen,
        message: String = state.message,
    ) {
        state = state.copy(
            currentApp = currentApp,
            currentPackage = currentPackage,
            currentScreen = currentScreen,
            appsSeen = seenApps.size,
            screensSeen = seenScreens.size,
            actionsSeen = seenActions.size,
            pathsLearned = seenPaths.size,
            selectedModelId = RoutineTeachingRuntime.activeSession()?.modelId ?: state.selectedModelId,
            message = message,
        )
    }

    private fun selectorFrom(json: JSONObject): JSONObject = JSONObject().apply {
        json.optString("resourceId").takeIf(String::isNotBlank)?.let { put("resourceId", it.take(180)) }
        json.optString("text").takeIf(String::isNotBlank)?.let { put("text", it.take(180)) }
        json.optString("contentDescription").takeIf(String::isNotBlank)?.let { put("contentDescription", it.take(180)) }
        json.optString("role").takeIf(String::isNotBlank)?.let { put("role", it) }
        if (json.optBoolean("scrollable")) put("scrollable", true)
    }

    private fun actionsFrom(json: JSONObject): List<String> = json.optJSONArray("actions")?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.orEmpty()

    private fun appLabel(packageName: String): String {
        val app = appContext ?: return packageName
        return runCatching {
            val info = app.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            app.packageManager.getApplicationLabel(info).toString()
        }.getOrElse { packageName.substringAfterLast('.') }
    }

    private fun uniqueIdentity(store: AppKnowledgeStore, packageName: String, base: String): String {
        val known = store.listScreens(packageName).map { it.identity }.toSet()
        if (base !in known) return base
        var index = 2
        while ("${base}_$index" in known) index++
        return "${base}_$index"
    }

    private fun nodeJson(node: AccessibilityNodeInfo): JSONObject {
        val rect = Rect().also(node::getBoundsInScreen)
        val actionNames = node.actionList.orEmpty().map { action ->
            when (action.id) {
                AccessibilityNodeInfo.ACTION_CLICK -> "ACTION_CLICK"
                AccessibilityNodeInfo.ACTION_LONG_CLICK -> "ACTION_LONG_CLICK"
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ACTION_SCROLL_FORWARD"
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "ACTION_SCROLL_BACKWARD"
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id -> "ACTION_SCROLL_TO_POSITION"
                AccessibilityNodeInfo.ACTION_SET_TEXT -> "ACTION_SET_TEXT"
                AccessibilityNodeInfo.ACTION_EXPAND -> "ACTION_EXPAND"
                AccessibilityNodeInfo.ACTION_COLLAPSE -> "ACTION_COLLAPSE"
                else -> action.label?.toString()?.takeIf(String::isNotBlank) ?: "ACTION_${action.id}"
            }
        }.distinct()
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
            .put("password", node.isPassword)
            .put("actions", JSONArray(actionNames))
            .put("bounds", JSONObject().put("left", rect.left).put("top", rect.top).put("right", rect.right).put("bottom", rect.bottom))
    }
}
