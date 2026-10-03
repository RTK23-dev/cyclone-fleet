package com.cyclone.mobile

import com.cyclone.mobile.ui.overlay.tracefield.TraceActKind
import com.cyclone.mobile.ui.overlay.tracefield.TraceFieldCaptureGate
import com.cyclone.mobile.ui.overlay.tracefield.TraceFieldRuntime as TraceField
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.Selector as AutomationSelector
import com.cyclone.mobile.guided.GuidedRecorderOverlayController
import com.cyclone.mobile.guided.RoutineTeachingOverlayRuntime
import com.cyclone.mobile.gesture.HumanizePreference
import com.cyclone.mobile.gesture.RuntimeGestureKind
import com.cyclone.mobile.ui.overlay.ClickGateIntercept
import com.cyclone.mobile.ui.overlay.GateBlockedException
import com.cyclone.mobile.ui.overlay.OverlayChromeObservation
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

class CycloneAccessibilityService : AccessibilityService() {
    data class ScreenshotArtifact(
        val file: File,
        val width: Int,
        val height: Int,
        val crop: UiBounds?,
        val timestampMs: Long,
        val liveFrame: com.cyclone.mobile.ai.vision.live.LiveFrame? = null,
        val displayBounds: UiBounds? = null,
        val capturedAtMonotonicMs: Long? = null,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("filePath", file.absolutePath)
            .put("bytes", file.length())
            .put("width", width)
            .put("height", height)
            .put("timestampMs", timestampMs)
            .put("crop", crop?.toJson() ?: JSONObject.NULL)
            .put("displayBounds", (displayBounds ?: crop)?.toJson() ?: JSONObject.NULL)
            .put("sessionId", liveFrame?.sessionId ?: "default-foreground")
            .put("displayId", liveFrame?.displayId ?: 0)
            .put("frameId", liveFrame?.frameId ?: JSONObject.NULL)
            .put("capturedAtMonotonicMs", liveFrame?.capturedAtMonotonicMs ?: capturedAtMonotonicMs ?: JSONObject.NULL)
            .put("source", liveFrame?.source?.name ?: "ACCESSIBILITY_SCREENSHOT")
    }

    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val runtimeInitExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var automationRuntimeReady = false
    @Volatile private var appLearnerRuntimeReady = false
    private var lastAutomationPackage: String? = null
    private var guidedOverlay: GuidedRecorderOverlayController? = null
    private val observationRevisions = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.atomic.AtomicLong>()
    private val sensitiveEditableHint = Regex(
        "(?i)(password|passcode|passwd|secret|otp|one.?time|verification.?code|cvv|cvc|card.?number|pin|api.?key|token)"
    )

    /** Window metadata only: this must never traverse semantic children. Overlay chrome is excluded. */
    fun observationSurface(sessionId: String, displayId: Int, scope: String, profileId: Int?): com.cyclone.mobile.agent.ObservationSurface {
        val display = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId)
            ?: error("DISPLAY_GONE")
        val metrics = createDisplayContext(display).resources.displayMetrics
        val listed = windowsOnAllDisplays.get(displayId).orEmpty()
        val signature = com.cyclone.mobile.agent.SemanticCaptureBoundary.windowSignature(listed.map { window ->
                val bounds = Rect().also { window.getBoundsInScreen(it) }
                UiWindowSnapshot(window.id, "", window.type, window.layer, window.isActive, window.isFocused,
                    UiBounds(bounds.left, bounds.top, bounds.right, bounds.bottom))
            })
        return com.cyclone.mobile.agent.ObservationSurface(sessionId, displayId, scope, signature,
            metrics.widthPixels, metrics.heightPixels, display.rotation,
            observationRevisions.computeIfAbsent(displayId) { java.util.concurrent.atomic.AtomicLong() }.get(), profileId)
    }

    private fun recordObservationEvent(event: AccessibilityEvent, listed: android.util.SparseArray<List<AccessibilityWindowInfo>>) {
        val owner = (0 until listed.size()).firstOrNull { i -> listed.valueAt(i).any { it.id == event.windowId } }
        val window = owner?.let { listed.valueAt(it).first { window -> window.id == event.windowId } }
        if (window?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return
        if (window == null && event.packageName?.toString() == packageName &&
            preferredForegroundRoot(listed.get(0).orEmpty())?.packageName?.toString() != packageName) return
        // A removed or unidentified window cannot safely be assigned to one display.
        val displays = owner?.let { listOf(listed.keyAt(it)) } ?: observationRevisions.keys.toList()
        displays.forEach { displayId ->
            observationRevisions.computeIfAbsent(displayId) { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        }
    }

    companion object {
        private const val PASTE_SETTLE_MS = 150L
        /** Alpha 93: the wait before a typed field is read back a second time. */
        private const val TYPE_SETTLE_MS = 150L
        /** How long the foreground task's package is reused between structural events. */
        private const val HOST_TTL_MS = 1_000L
        @Volatile var instance: CycloneAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        CycloneProcessDiagnostics.install(applicationContext)
        CycloneProcessDiagnostics.markStage(this, "primary.accessibility.onServiceConnected")
        instance = this
        DeviceState.accessibilityConnected = true
        DeviceState.addLog("Accessibility connected")

        runCatching { OverlayChromeRuntime.attach(this) }
            .onFailure { CycloneProcessDiagnostics.recordNonFatal(this, "primary.accessibility.overlay.attach", it) }
        // Alpha 95: Driver mode's orb attaches on its own (it used to come only at the end of the chrome's attach).
        runCatching { com.cyclone.mobile.ui.overlay.DriverOverlay.attach(this) }
            .onFailure { CycloneProcessDiagnostics.recordNonFatal(this, "primary.accessibility.drive.attach", it) }
        // A Cyclone Mind mission cut off by a crash or process death minutes ago continues once the phone is reachable.
        val app = applicationContext
        Thread({ runCatching { Thread.sleep(3_000); com.cyclone.mobile.mind.mission.MindMissions.onServiceReady(app) } }, "cyclone-mind-recover").start()
        runCatching { com.cyclone.mobile.ui.overlay.tracefield.TraceFieldRuntime.attach(this) }
            .onFailure { CycloneProcessDiagnostics.recordNonFatal(this, "primary.accessibility.tracefield.attach", it) }

        // Android owns this callback boundary. Optional Cyclone runtimes are deliberately initialized
        // away from it so a corrupt DB, migration issue, legacy bridge config, or app-learning bug can
        // never make the AccessibilityService itself fail to connect.
        runCatching {
            runtimeInitExecutor.execute {
                automationRuntimeReady = runCatching {
                    CycloneProcessDiagnostics.markStage(applicationContext, "primary.runtime.automation.init")
                    AutomationRuntime.initialize(applicationContext)
                    true
                }.onFailure {
                    CycloneProcessDiagnostics.recordNonFatal(applicationContext, "primary.runtime.automation.init", it)
                    DeviceState.addLog("Automation runtime disabled after safe initialization failure")
                }.getOrDefault(false)

                appLearnerRuntimeReady = runCatching {
                    CycloneProcessDiagnostics.markStage(applicationContext, "primary.runtime.applearner.init")
                    AppLearnerRuntime.initialize(applicationContext)
                    true
                }.onFailure {
                    CycloneProcessDiagnostics.recordNonFatal(applicationContext, "primary.runtime.applearner.init", it)
                    DeviceState.addLog("App Learner disabled after safe initialization failure")
                }.getOrDefault(false)

                CycloneProcessDiagnostics.markStage(
                    applicationContext,
                    "primary.runtime.ready.a${if (automationRuntimeReady) 1 else 0}.l${if (appLearnerRuntimeReady) 1 else 0}",
                )
            }
        }.onFailure {
            CycloneProcessDiagnostics.recordNonFatal(this, "primary.runtime.executor.submit", it)
        }
    }

    /** The home screen apps: never shown as "the app Cyclone works in". */
    private val homePackages: Set<String> by lazy {
        runCatching {
            packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME), 0).mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // This runs on the main thread for every screen event (hundreds a second in a busy app): read Android's window
        // list once per event, and resolve the foreground task's root (one call per window) only when the screen's
        // structure changed. A freeze here is what the PC used to see as a lost phone (alpha 87).
        val listed = runCatching { windowsOnAllDisplays }.getOrNull() ?: return
        runCatching { recordObservationEvent(event, listed) }
        val front = listed.get(0).orEmpty()
        // Background windows must not update the global foreground package, learning or UI state.
        if (event.windowId != -1 && front.none { it.id == event.windowId }) return
        try {
            val packageName = event.packageName?.toString()?.takeIf { it.isNotBlank() }
            val eventWindow = front.firstOrNull { it.id == event.windowId }
            val structural = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            val refreshed = structural || hostStale(front)
            val host = if (refreshed) preferredForegroundRoot(front).also { rememberHost(front, it) } else null
            val hostPackage = if (refreshed) host?.packageName?.toString() else rememberedHostPackage
            if (!TaskSurfaceWindows.eventBelongsToTask(eventWindow?.type, packageName.orEmpty(), hostPackage)) return
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                host?.packageName?.toString()?.let {
                    DeviceState.currentPackage = it
                    // The working status shows the app Cyclone works in, not the home screen or Cyclone itself.
                    com.cyclone.mobile.ui.overlay.WorkingApp.seen(it, homePackages)
                }
                host?.className?.toString()?.let { DeviceState.currentClassName = it }
            }
            DeviceState.lastUiEventAtMs = System.currentTimeMillis()

            if (automationRuntimeReady) {
                runCatching {
                    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && packageName != null && packageName != lastAutomationPackage) {
                        lastAutomationPackage = packageName
                        AutomationRuntime.onAppOpened(this, packageName)
                    }
                    if (AutomationRuntime.recorder.isRecording()) {
                        when (event.eventType) {
                            AccessibilityEvent.TYPE_VIEW_CLICKED -> event.source?.let { AutomationRuntime.recorder.recordClick(automationSelector(it)) }
                            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> event.source?.let { AutomationRuntime.recorder.recordText(automationSelector(it)) }
                            AccessibilityEvent.TYPE_VIEW_SCROLLED -> AutomationRuntime.recorder.recordScroll("forward")
                        }
                    }
                }.onFailure {
                    CycloneProcessDiagnostics.recordNonFatal(this, "primary.accessibility.event.automation", it)
                    DeviceState.addLog("Automation accessibility event failed safely")
                }
            }

            if (appLearnerRuntimeReady) {
                runCatching { AppLearnerRuntime.onAccessibilityEvent(event) }
                    .onFailure {
                        CycloneProcessDiagnostics.recordNonFatal(this, "primary.accessibility.event.applearner", it)
                        DeviceState.addLog("App Learner accessibility event failed safely")
                    }
            }
        } catch (error: Throwable) {
            // Never let application logic escape an Android AccessibilityService callback. A failure is
            // diagnosable and feature-local; the phone-control service remains bound and usable.
            CycloneProcessDiagnostics.recordNonFatal(this, "primary.accessibility.event.boundary", error)
            DeviceState.addLog("Accessibility event rejected safely")
        }
    }

    // Android sends onInterrupt when an app asks accessibility feedback (speech) to stop. It is not a loss of the
    // service, so background screens stay; only onDestroy (the service really going away) ends them.
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        com.cyclone.mobile.runtime.background.WorkspaceRuntime.invalidateAll()
        CycloneProcessDiagnostics.markStage(this, "primary.accessibility.onDestroy")
        runCatching { guidedOverlay?.dismiss() }
            .onFailure { CycloneProcessDiagnostics.recordNonFatal(this, "primary.accessibility.destroy.guided", it) }
        guidedOverlay = null
        runCatching { OverlayChromeRuntime.detach() }
            .onFailure { CycloneProcessDiagnostics.recordNonFatal(this, "primary.accessibility.destroy.overlay", it) }
        runCatching { com.cyclone.mobile.ui.overlay.DriverOverlay.detach() }
        runCatching { com.cyclone.mobile.ui.overlay.tracefield.TraceFieldRuntime.detach() }
            .onFailure { CycloneProcessDiagnostics.recordNonFatal(this, "primary.accessibility.destroy.tracefield", it) }
        runCatching { RoutineTeachingOverlayRuntime.dismiss() }
            .onFailure { CycloneProcessDiagnostics.recordNonFatal(this, "primary.accessibility.destroy.teaching", it) }
        instance = null
        automationRuntimeReady = false
        appLearnerRuntimeReady = false
        DeviceState.accessibilityConnected = false
        runCatching { runtimeInitExecutor.shutdownNow() }
        runCatching { screenshotExecutor.shutdownNow() }
        super.onDestroy()
    }

    /** Opens Cyclone V2.4+'s floating teach-a-routine bubble over any app. */
    fun showGuidedRecorderOverlay() {
        if (guidedOverlay == null) guidedOverlay = GuidedRecorderOverlayController(this)
        guidedOverlay?.show()
    }

    fun hideGuidedRecorderOverlay() {
        guidedOverlay?.dismiss()
        guidedOverlay = null
    }

    fun observe(markFresh: Boolean = true): UiSnapshot {
        if (markFresh) waitForUiQuiet()
        val root = preferredForegroundRoot()
        val metrics = resources.displayMetrics
        val nodes = mutableListOf<UiNodeSnapshot>()
        val consumedWindows = mutableSetOf<Int>()
        if (root != null) {
            collectNode(root, "0", null, 0, nodes)
            consumedWindows += root.windowId
        }
        includeSiblingApplicationWindows(nodes, consumedWindows, root?.packageName?.toString().orEmpty())
        val folded = AccessibilityRoles.foldTalkBackHosts(nodes)
        nodes.clear()
        nodes.addAll(folded)
        val windowsSnapshot = windowsOnAllDisplays.get(0).orEmpty().filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }.map { window ->
            val rect = Rect().also { window.getBoundsInScreen(it) }
            UiWindowSnapshot(
                id = window.id,
                title = window.title?.toString().orEmpty(),
                type = window.type,
                layer = window.layer,
                active = window.isActive,
                focused = window.isFocused,
                bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom),
            )
        }.toMutableList()
        OverlayChromeObservation.appendChrome(
            nodes,
            windowsSnapshot,
            OverlayChromeRuntime.snapshot(),
            metrics.widthPixels,
            metrics.heightPixels,
        )
        val packageName = root?.packageName?.toString()?.takeIf { it.isNotBlank() }
        val fingerprint = screenFingerprint(packageName, nodes)
        val snapshot = UiSnapshot(
            packageName = packageName,
            className = root?.className?.toString(),
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            timestampMs = System.currentTimeMillis(),
            fingerprint = fingerprint,
            controller = DeviceState.controller.name.lowercase(),
            windows = windowsSnapshot,
            nodes = nodes,
        )
        if (markFresh) {
            DeviceState.markObserved()
            TraceField.observed(fingerprint)
        }
        return snapshot
    }

    /** Reads only windows returned for this display; never uses rootInActiveWindow. */
    fun observeDisplay(displayId: Int, targetPackage: String): UiSnapshot {
        require(displayId > 0)
        val display = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId)
            ?: error("DISPLAY_GONE")
        val listed = windowsOnAllDisplays.get(displayId).orEmpty()
        // Plan 28: the display is Cyclone's alone, so whatever is on top there is part of the task: the app itself, or a
        // permission dialog, share sheet or sign-in page it opened. The top application window leads the snapshot.
        val roots = listed.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }.mapNotNull { it.root }
            .filter { it.packageName?.toString() != packageName }
        val root = roots.firstOrNull()
            ?: error("BACKGROUND_MODE_UNAVAILABLE: no target Accessibility window on this display")
        val shownPackage = root.packageName?.toString() ?: targetPackage
        val nodes = mutableListOf<UiNodeSnapshot>()
        val ownedWindows = listed.filter { it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        ownedWindows.forEach { window ->
            window.root?.takeIf { it.packageName?.toString() != packageName }?.let {
                collectNode(it, "w${window.id}/0", null, 0, nodes)
            }
        }
        val metrics = createDisplayContext(display).resources.displayMetrics
        val folded = AccessibilityRoles.foldTalkBackHosts(nodes)
        return UiSnapshot(shownPackage, root.className?.toString(), metrics.widthPixels, metrics.heightPixels,
            System.currentTimeMillis(), screenFingerprint(shownPackage, folded), "agent",
            ownedWindows.map { window ->
                val rect = Rect().also { window.getBoundsInScreen(it) }
                UiWindowSnapshot(window.id, window.title?.toString().orEmpty(), window.type, window.layer,
                    window.isActive, window.isFocused, UiBounds(rect.left, rect.top, rect.right, rect.bottom))
            }, folded)
    }

    fun find(selector: ElementSelector, limit: Int = 20): List<SelectorMatch> =
        SelectorEngine.resolve(observe(markFresh = false), selector, limit)

    fun click(
        selector: ElementSelector,
        humanize: HumanizePreference = HumanizePreference.AUTO,
        commandId: String? = null,
    ): Boolean {
        if (!agentCanAct()) return false
        val clicked = clickResolved(selector, humanize, commandId)
        if (!clicked) TraceField.recovering()
        return clicked
    }

    private fun clickResolved(
        selector: ElementSelector,
        humanize: HumanizePreference,
        commandId: String?,
    ): Boolean {
        repeat(2) {
            val snapshot = observe(markFresh = false)
            val match = SelectorEngine.resolve(snapshot, selector, 1).firstOrNull() ?: return@repeat
            val snapshotNode = match.node
            val node = liveNodeAtSnapshotPath(snapshotNode) ?: return@repeat
            if (!sameNode(snapshotNode, node)) return@repeat
            val activation = AccessibilityRoles.resolveActivationTarget(snapshot.nodes, snapshotNode)
            val labels = ClickGateIntercept.labelsFor(snapshotNode, activation, selector)
            val decision = ClickGateIntercept.decide("phone.click", labels, OverlayChromeRuntime.snapshot().state)
            if (!decision.performClick) {
                if (decision.enterGate && decision.gateClass != null) {
                    OverlayChromeRuntime.enterGate(decision.gateClass)
                }
                throw GateBlockedException(decision.gateClass)
            }
            TraceField.targeted(activation.bounds, activation.path)
            TraceField.acted(TraceActKind.TAP, activation.bounds.centerX, activation.bounds.centerY)
            val targetLive = if (activation.path == snapshotNode.path) {
                node
            } else {
                liveNodeAtSnapshotPath(activation)?.takeIf { sameNode(activation, it) } ?: node
            }
            if (AccessibilityRoles.shouldPreferActivatableRelative(snapshotNode) &&
                clickActivatableRelative(node)
            ) {
                return true
            }
            if (targetLive !== node && activateNode(targetLive, activation.role)) return true
            val liveActions = accessibilityActionNames(targetLive)
            val preferHost = AccessibilityRoles.preferClickableAncestor(
                activation.role, targetLive.isClickable, liveActions,
            )
            if (preferHost && clickActivatableAncestor(targetLive)) return true
            if (activateNode(targetLive, activation.role)) return true
            if (!preferHost && clickActivatableAncestor(targetLive)) return true
            return HumanGestureDispatch.tap(
                service = this,
                x = activation.bounds.centerX,
                y = activation.bounds.centerY,
                preference = humanize,
                kind = RuntimeGestureKind.FALLBACK_TAP,
                commandId = commandId,
                targetBounds = activation.bounds,
            )
        }
        return false
    }

    private fun activateNode(node: AccessibilityNodeInfo, role: String): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        if (role.equals("tab", ignoreCase = true) && node.performAction(AccessibilityNodeInfo.ACTION_SELECT)) return true
        return false
    }

    private fun clickActivatableRelative(node: AccessibilityNodeInfo): Boolean {
        fun tryClick(candidate: AccessibilityNodeInfo): Boolean {
            val actions = accessibilityActionNames(candidate)
            if (!AccessibilityRoles.isStrongActivatable(candidate.isClickable, actions)) return false
            if (candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            if (candidate.performAction(AccessibilityNodeInfo.ACTION_SELECT)) return true
            return false
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (tryClick(child)) return true
            for (j in 0 until child.childCount) {
                val grand = child.getChild(j) ?: continue
                if (tryClick(grand)) return true
            }
        }
        val parent = node.parent ?: return false
        for (i in 0 until parent.childCount) {
            val sib = parent.getChild(i) ?: continue
            if (sib === node) continue
            if (tryClick(sib)) return true
        }
        return false
    }

    private fun clickActivatableAncestor(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        repeat(6) {
            if (parent == null) return false
            val actions = accessibilityActionNames(parent!!)
            if (AccessibilityRoles.isActivatable(parent!!.isClickable, actions)) {
                if (parent!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                if (parent!!.performAction(AccessibilityNodeInfo.ACTION_SELECT)) return true
            }
            parent = parent!!.parent
        }
        return false
    }

    private fun includeSiblingApplicationWindows(nodes: MutableList<UiNodeSnapshot>, consumedWindows: MutableSet<Int>, hostPackage: String) {
        // GATE is added separately as bounded synthetic state, never as the animated Compose tree.
        for (window in windowsOnAllDisplays.get(0).orEmpty()) {
            val wroot = window.root ?: continue
            if (wroot.windowId in consumedWindows) continue
            val pkg = wroot.packageName?.toString().orEmpty()
            val isWeb = isWebishWindow(window, wroot)
            if (!TaskSurfaceWindows.includeSibling(window.type, pkg, hostPackage)) continue
            if (!OverlayChromeObservation.shouldCollectSiblingWindow(window.type, pkg, isWeb)) continue
            collectNode(wroot, "w${window.id}/0", null, 0, nodes)
            consumedWindows += wroot.windowId
        }
    }

    private fun isWebishWindow(window: AccessibilityWindowInfo, root: AccessibilityNodeInfo): Boolean {
        val pkg = root.packageName?.toString().orEmpty().lowercase()
        val title = window.title?.toString().orEmpty().lowercase()
        val cls = root.className?.toString().orEmpty().lowercase()
        return "chrome" in pkg || "webview" in pkg || "browser" in pkg ||
            "chrome" in title || "webview" in cls || "web_view" in cls
    }

    // The foreground task's package from the last structural event, and the window layout it was read for.
    @Volatile private var rememberedHostPackage: String? = null
    private var rememberedHostWindows: List<Int> = emptyList()
    private var rememberedHostAt = 0L

    private fun hostStale(front: List<AccessibilityWindowInfo>): Boolean =
        front.map { it.id } != rememberedHostWindows || android.os.SystemClock.uptimeMillis() - rememberedHostAt > HOST_TTL_MS

    private fun rememberHost(front: List<AccessibilityWindowInfo>, host: AccessibilityNodeInfo?) {
        rememberedHostPackage = host?.packageName?.toString()
        rememberedHostWindows = front.map { it.id }
        rememberedHostAt = android.os.SystemClock.uptimeMillis()
    }

    private fun preferredForegroundRoot(listed: List<AccessibilityWindowInfo> = windowsOnAllDisplays.get(0).orEmpty()): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        // Each window's root is read once: reading it again could come back null for a dialog (alpha 91).
        val roots = listed.associate { it.id to it.root }
        val ordered = TaskSurfaceWindows.ordered(listed.map { window ->
            TaskSurfaceWindows.Window(window.id, window.type, roots[window.id]?.packageName?.toString().orEmpty(),
                window.layer, window.isActive, window.isFocused)
        }, active?.windowId)
        ordered.firstNotNullOfOrNull { roots[it.id] }?.let { return it }
        // OEM fallback only when enumeration is unavailable. Never use Cyclone's untyped overlay root.
        return active?.takeIf { listed.isEmpty() && !it.packageName.isNullOrBlank() && it.packageName?.toString() !in setOf("com.cyclone.mobile", "com.android.systemui") }
    }

    internal fun foregroundTaskWindowId(): Int? = preferredForegroundRoot()?.windowId

    /**
     * V2.9 replay optimization: a human may demonstrate a two-second hold, but if Android exposes
     * ACTION_LONG_CLICK Cyclone sends that semantic action immediately. The original gesture duration
     * remains only as a compatibility fallback for apps that do not expose a native long-click.
     */
    fun longPress(
        selector: ElementSelector,
        durationMs: Long = 650,
        humanize: HumanizePreference = HumanizePreference.AUTO,
        commandId: String? = null,
    ): Boolean {
        if (!agentCanAct()) return false
        repeat(2) {
            val target = resolveLiveTarget(selector) ?: return@repeat
            val (snapshotNode, node) = target
            if (!sameNode(snapshotNode, node)) return@repeat
            val advertisesLongClick = snapshotNode.actions.contains("ACTION_LONG_CLICK") || node.isLongClickable
            if (advertisesLongClick && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                DeviceState.addLog("Semantic ACTION_LONG_CLICK used instead of timed hold")
                return true
            }
            return HumanGestureDispatch.longPress(
                service = this,
                x = snapshotNode.bounds.centerX,
                y = snapshotNode.bounds.centerY,
                durationMs = durationMs,
                preference = humanize,
                kind = RuntimeGestureKind.LONG_PRESS,
                commandId = commandId,
                targetBounds = snapshotNode.bounds,
            )
        }
        return false
    }

    /** Presses the keyboard action key (Enter, Search, Go) on the grounded editable field. */
    fun imeEnter(selector: ElementSelector): Boolean {
        if (!agentCanAct()) return false
        repeat(2) {
            val target = resolveLiveTarget(selector) ?: return@repeat
            val (snapshotNode, node) = target
            if (!sameNode(snapshotNode, node)) return@repeat
            if (!node.isEditable) return false
            return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return false
    }

    fun typeEditable(
        plan: PhoneTypeEngine.ExecutePlan,
        value: CharSequence,
        redactObservedText: Boolean = false,
    ): PhoneTypeEngine.LiveResult {
        if (!agentCanAct()) {
            return PhoneTypeEngine.LiveResult(
                ok = false,
                error = PhoneToolError(PhoneToolErrorCode.ACTION_FAILED, "Agent cannot act"),
                elementId = plan.elementId,
                rawNodeId = plan.rawNodeId,
            )
        }
        return PhoneTypeEngine.perform(
            plan,
            value,
            AccessibilityTypeLive(displayId = 0, targetPackage = null),
            redactObservedText = redactObservedText,
        )
    }

    /**
     * Workspace-scoped secure input. Authority is proved by PhoneToolExecutor before entry;
     * this method only resolves the exact node on [displayId] and performs ACTION_SET_TEXT.
     */
    internal fun typeEditableOnDisplay(
        plan: PhoneTypeEngine.ExecutePlan,
        value: CharSequence,
        displayId: Int,
        targetPackage: String,
        /** True for secrets (set-text only, exact in-process match); false for ordinary text (the full ladder). */
        redact: Boolean = true,
    ): PhoneTypeEngine.LiveResult {
        if (displayId <= 0 || targetPackage.isBlank()) {
            return PhoneTypeEngine.LiveResult(
                ok = false,
                error = PhoneToolError(PhoneToolErrorCode.INVALID_REQUEST, "Invalid workspace target"),
                elementId = plan.elementId,
                rawNodeId = plan.rawNodeId,
            )
        }
        return PhoneTypeEngine.perform(
            plan,
            value,
            AccessibilityTypeLive(displayId = displayId, targetPackage = targetPackage),
            redactObservedText = redact,
        )
    }

    private inner class AccessibilityTypeLive(
        private val displayId: Int,
        private val targetPackage: String?,
    ) : PhoneTypeEngine.LiveHost {

        override fun resolve(plan: PhoneTypeEngine.ExecutePlan): Any? {
            val node = nodeAtTaskPath(plan.path, displayId, targetPackage) ?: return null
            return if (node.isEditable) AccessibilityTypeHandle(plan.path, node, plan.rawNodeId) else null
        }

        override fun view(handle: Any, redactText: Boolean): PhoneTypeEngine.LiveView? {
            val target = handle as? AccessibilityTypeHandle ?: return null
            val node = target.node
            val text = node.text
            val textLength = text?.length ?: 0
            return PhoneTypeEngine.LiveView(
                rawNodeId = target.rawNodeId,
                path = target.path,
                editable = node.isEditable,
                focused = node.isFocused,
                enabled = node.isEnabled,
                textLength = textLength,
                textDigest = if (redactText) "<redacted>" else PhoneTypeEngine.digest(text ?: ""),
                actions = accessibilityActionNames(node),
                password = node.isPassword,
            )
        }

        override fun focus(handle: Any): Boolean {
            val target = handle as? AccessibilityTypeHandle ?: return false
            return target.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        override fun click(handle: Any): Boolean {
            val target = handle as? AccessibilityTypeHandle ?: return false
            return target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        override fun setText(handle: Any, value: CharSequence): Boolean {
            val target = handle as? AccessibilityTypeHandle ?: return false
            if (displayId == 0) {
                val rect = Rect().also { target.node.getBoundsInScreen(it) }
                TraceField.acted(TraceActKind.TYPE, rect.exactCenterX(), rect.exactCenterY())
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            return target.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }

        override fun matchesText(handle: Any, value: CharSequence): Boolean {
            val target = handle as? AccessibilityTypeHandle ?: return false
            val observed = target.node.text ?: return false
            if (observed.length != value.length) return false
            for (index in 0 until value.length) {
                if (observed[index] != value[index]) return false
            }
            return true
        }

        override fun refresh(handle: Any): Any? {
            val target = handle as? AccessibilityTypeHandle ?: return null
            val node = nodeAtTaskPath(target.path, displayId, targetPackage) ?: return null
            return if (node.isEditable) AccessibilityTypeHandle(target.path, node, target.rawNodeId) else null
        }

        override fun settle() {
            Thread.sleep(TYPE_SETTLE_MS)
        }

        override fun readText(handle: Any): CharSequence? {
            val target = handle as? AccessibilityTypeHandle ?: return null
            if (target.node.isPassword) return null
            return if (target.node.isShowingHintText) "" else target.node.text ?: ""
        }

        /**
         * Plan 21 (Hands): clipboard + ACTION_PASTE over the field's whole text. The clip is marked sensitive (no
         * preview), and afterwards the owner's previous clip is put back, or ours is cleared. Never for passwords.
         */
        override fun paste(handle: Any, value: CharSequence): Boolean {
            val target = handle as? AccessibilityTypeHandle ?: return false
            val node = target.node
            if (node.isPassword) return false
            val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return false
            val previous = runCatching { clipboard.primaryClip }.getOrNull()
            val clip = android.content.ClipData.newPlainText("Cyclone", value).apply {
                description.extras = android.os.PersistableBundle().apply {
                    putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            return try {
                clipboard.setPrimaryClip(clip)
                if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val length = if (node.isShowingHintText) 0 else node.text?.length ?: 0
                if (length > 0) {
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
                    })
                }
                if (displayId == 0) {
                    val rect = Rect().also { node.getBoundsInScreen(it) }
                    TraceField.acted(TraceActKind.TYPE, rect.exactCenterX(), rect.exactCenterY())
                }
                node.performAction(AccessibilityNodeInfo.ACTION_PASTE).also {
                    // Give the app a moment to read the clip before it is replaced.
                    Thread.sleep(PASTE_SETTLE_MS)
                }
            } catch (_: Exception) {
                false
            } finally {
                runCatching { if (previous != null) clipboard.setPrimaryClip(previous) else clipboard.clearPrimaryClip() }
            }
        }
    }

    fun scroll(selector: ElementSelector?, forward: Boolean = true): Boolean {
        if (!agentCanAct()) return false
        val node = if (selector != null) resolveLiveTarget(selector)?.second else findScrollable(preferredForegroundRoot())
        node ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val rect = Rect().also { node.getBoundsInScreen(it) }
        TraceField.acted(TraceActKind.SCROLL, rect.exactCenterX(), rect.exactCenterY(), 0f, if (forward) -1f else 1f)
        return node.performAction(action)
    }

    fun tap(
        x: Float,
        y: Float,
        humanize: HumanizePreference = HumanizePreference.AUTO,
        commandId: String? = null,
    ): Boolean {
        if (!agentCanAct()) return false
        TraceField.acted(TraceActKind.TAP, x, y)
        return HumanGestureDispatch.tap(
            service = this,
            x = x,
            y = y,
            preference = humanize,
            kind = RuntimeGestureKind.COORDINATE_TAP,
            commandId = commandId,
        )
    }

    /**
     * Coordinate tap for what accessibility does not expose, with the same GATE check a labelled click gets:
     * whatever sits under the point is classified before anything is dispatched.
     */
    fun tapPoint(x: Float, y: Float, humanize: HumanizePreference = HumanizePreference.AUTO, commandId: String? = null): Boolean {
        if (!agentCanAct()) return false
        guardPoint("phone.tap_point", x, y)
        return tap(x, y, humanize, commandId)
    }

    /**
     * GATE check for a gesture by position: whatever sits under ([x], [y]) is classified exactly like a labelled
     * click. Throws [GateBlockedException] (and raises the approval card) when the owner must approve first.
     */
    fun guardPoint(action: String, x: Float, y: Float) {
        val snapshot = observe(markFresh = false)
        val labels = ClickGateIntercept.labelsAtPoint(snapshot.nodes, x.toInt(), y.toInt())
        val decision = ClickGateIntercept.decide(action, labels, OverlayChromeRuntime.snapshot().state)
        if (!decision.performClick) {
            if (decision.enterGate && decision.gateClass != null) OverlayChromeRuntime.enterGate(decision.gateClass)
            throw GateBlockedException(decision.gateClass)
        }
    }

    fun longPress(
        x: Float,
        y: Float,
        durationMs: Long = 650,
        humanize: HumanizePreference = HumanizePreference.AUTO,
        commandId: String? = null,
    ): Boolean {
        if (!agentCanAct()) return false
        TraceField.acted(TraceActKind.LONG_PRESS, x, y)
        return HumanGestureDispatch.longPress(
            service = this,
            x = x,
            y = y,
            durationMs = durationMs,
            preference = humanize,
            kind = RuntimeGestureKind.LONG_PRESS,
            commandId = commandId,
        )
    }

    fun swipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long = 350,
        humanize: HumanizePreference = HumanizePreference.AUTO,
        commandId: String? = null,
    ): Boolean {
        if (!agentCanAct()) return false
        TraceField.acted(TraceActKind.SCROLL, (x1 + x2) / 2f, (y1 + y2) / 2f, x2 - x1, y2 - y1)
        return HumanGestureDispatch.swipe(
            service = this,
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            durationMs = durationMs,
            preference = humanize,
            kind = RuntimeGestureKind.SWIPE,
            commandId = commandId,
        )
    }

    fun goBack(): Boolean = agentCanAct() && performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = agentCanAct() && performGlobalAction(GLOBAL_ACTION_HOME)

    /** Guided gestures are direct user instructions and bypass the AGENT lock. */
    fun guidedTap(x: Float, y: Float): Boolean = HumanGestureDispatch.tap(
        service = this,
        x = x,
        y = y,
        preference = HumanizePreference.AUTO,
        kind = RuntimeGestureKind.GUIDED_TAP,
    )
    fun guidedLongPress(x: Float, y: Float, durationMs: Long = 750): Boolean = HumanGestureDispatch.longPress(
        service = this,
        x = x,
        y = y,
        durationMs = durationMs,
        preference = HumanizePreference.AUTO,
        kind = RuntimeGestureKind.GUIDED_LONG_PRESS,
    )
    fun guidedSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean = HumanGestureDispatch.swipe(
        service = this,
        x1 = x1,
        y1 = y1,
        x2 = x2,
        y2 = y2,
        durationMs = durationMs,
        preference = HumanizePreference.AUTO,
        kind = RuntimeGestureKind.GUIDED_SWIPE,
    )
    fun guidedBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun guidedHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private fun rawTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun rawLongPress(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(450, 3000))).build()
        return dispatchGesture(gesture, null, null)
    }

    private fun rawSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(100, 3000))).build()
        return dispatchGesture(gesture, null, null)
    }

    fun takeScreenshot(crop: UiBounds? = null, callback: (Result<ScreenshotArtifact>) -> Unit) {
        // The Trace Field is a non-secure full-screen layer: hide it before any full-display capture.
        TraceFieldCaptureGate.hold { release ->
            val done: (Result<ScreenshotArtifact>) -> Unit = { result -> release(); callback(result) }
            try {
                takeDisplayScreenshot(crop, done)
            } catch (failure: Throwable) {
                done(Result.failure(failure))
            }
        }
    }

    private fun takeDisplayScreenshot(crop: UiBounds?, callback: (Result<ScreenshotArtifact>) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val outcome = runCatching {
                    try {
                        val wrapped = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB))
                            ?: error("Unable to map screenshot buffer")
                        val bitmap = wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: wrapped
                        if (crop == null) com.cyclone.mobile.ui.overlay.tracefield.TraceFieldBackdrop.ingest(bitmap)
                        val boundedCrop = crop?.let { bounds ->
                            UiBounds(
                                bounds.left.coerceIn(0, bitmap.width), bounds.top.coerceIn(0, bitmap.height),
                                bounds.right.coerceIn(0, bitmap.width), bounds.bottom.coerceIn(0, bitmap.height),
                            ).takeIf { it.width > 0 && it.height > 0 }
                        }
                        val outputBitmap = boundedCrop?.let { Bitmap.createBitmap(bitmap, it.left, it.top, it.width, it.height) } ?: bitmap
                        val file = File(cacheDir, "cyclone-${System.currentTimeMillis()}.png")
                        FileOutputStream(file).use { output -> outputBitmap.compress(Bitmap.CompressFormat.PNG, 95, output) }
                        DeviceState.lastScreenshotPath = file.absolutePath
                        ScreenshotArtifact(file, outputBitmap.width, outputBitmap.height, boundedCrop, System.currentTimeMillis(), capturedAtMonotonicMs = result.timestamp)
                    } finally { result.hardwareBuffer.close() }
                }
                callback(outcome)
            }

            override fun onFailure(errorCode: Int) {
                callback(Result.failure(IllegalStateException("Screenshot failed: $errorCode")))
            }
        })
    }

    private fun agentCanAct(): Boolean =
        (DeviceState.controller == DeviceState.Controller.AGENT && !DeviceState.requireFreshObservation) ||
            PhoneToolExecutor.humanDesktopControlActive()

    private fun resolveLiveTarget(selector: ElementSelector): Pair<UiNodeSnapshot, AccessibilityNodeInfo>? {
        val snapshot = observe(markFresh = false)
        val match = SelectorEngine.resolve(snapshot, selector, 1).firstOrNull() ?: return null
        val live = liveNodeAtSnapshotPath(match.node) ?: return null
        return match.node to live
    }

    private fun liveNodeAtSnapshotPath(snapshotNode: UiNodeSnapshot): AccessibilityNodeInfo? =
        nodeAtTaskPath(snapshotNode.path)?.takeIf { sameNode(snapshotNode, it) }

    private fun nodeAtTaskPath(
        path: String,
        displayId: Int = 0,
        targetPackage: String? = null,
    ): AccessibilityNodeInfo? {
        val parsed = TaskSurfaceWindows.parseNodePath(path) ?: return null
        val root = if (displayId == 0) {
            val primary = preferredForegroundRoot() ?: return null
            if (parsed.windowId == null || parsed.windowId == primary.windowId) {
                primary
            } else {
                val window = windowsOnAllDisplays.get(0).orEmpty()
                    .firstOrNull { it.id == parsed.windowId } ?: return null
                val candidate = window.root ?: return null
                if (!TaskSurfaceWindows.includeSibling(
                        window.type,
                        candidate.packageName?.toString().orEmpty(),
                        primary.packageName?.toString().orEmpty(),
                    )
                ) return null
                candidate
            }
        } else {
            val packageName = targetPackage ?: return null
            val windows = windowsOnAllDisplays.get(displayId).orEmpty()
            val window = parsed.windowId?.let { id -> windows.firstOrNull { it.id == id } }
                ?: windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .sortedByDescending { it.layer }
                    .firstOrNull { it.root?.packageName?.toString() == packageName }
                ?: return null
            val candidate = window.root ?: return null
            if (candidate.packageName?.toString() != packageName) return null
            candidate
        }
        var node = root
        for (index in parsed.children) node = node.getChild(index) ?: return null
        return node
    }

    private fun sameNode(snapshot: UiNodeSnapshot, node: AccessibilityNodeInfo): Boolean {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        return snapshot.className == node.className?.toString().orEmpty() &&
            snapshot.resourceId == node.viewIdResourceName.orEmpty() &&
            snapshot.bounds == UiBounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun collectNode(node: AccessibilityNodeInfo, path: String, parentId: String?, depth: Int, out: MutableList<UiNodeSnapshot>, parentClassName: String = "") {
        if (depth > 40 || out.size >= 2500) return
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom)
        val id = stableNodeId(path, node, bounds)
        val childIds = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childRect = Rect().also { child.getBoundsInScreen(it) }
            childIds += stableNodeId("$path/$i", child, UiBounds(childRect.left, childRect.top, childRect.right, childRect.bottom))
        }
        val password = node.isPassword
        val sensitive = isSensitiveEditable(node)
        val safeText = if (sensitive) "" else node.text?.toString().orEmpty()
        val safeDescription = if (sensitive) "" else node.contentDescription?.toString().orEmpty()
        out += UiNodeSnapshot(
            id = id, path = path, parentId = parentId, childIds = childIds, depth = depth, windowId = node.windowId,
            className = node.className?.toString().orEmpty(),
            role = inferRole(node, parentClassName, safeText, safeDescription),
            text = safeText,
            contentDescription = safeDescription,
            resourceId = node.viewIdResourceName.orEmpty(), bounds = bounds,
            clickable = node.isClickable, longClickable = node.isLongClickable, editable = node.isEditable, scrollable = node.isScrollable,
            enabled = node.isEnabled, selected = node.isSelected, checked = node.isChecked, checkable = node.isCheckable,
            focused = node.isFocused, focusable = node.isFocusable, visibleToUser = node.isVisibleToUser,
            actions = accessibilityActionNames(node),
            password = password,
        )
        val selfClass = node.className?.toString().orEmpty()
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectNode(it, "$path/$i", id, depth + 1, out, selfClass) }
    }

    private fun accessibilityActionNames(node: AccessibilityNodeInfo): List<String> = node.actionList.orEmpty().map { action ->
        when (action.id) {
            AccessibilityNodeInfo.ACTION_CLICK -> "ACTION_CLICK"
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> "ACTION_LONG_CLICK"
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "ACTION_SCROLL_FORWARD"
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "ACTION_SCROLL_BACKWARD"
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id -> "ACTION_SCROLL_TO_POSITION"
            AccessibilityNodeInfo.ACTION_SET_TEXT -> "ACTION_SET_TEXT"
            AccessibilityNodeInfo.ACTION_EXPAND -> "ACTION_EXPAND"
            AccessibilityNodeInfo.ACTION_COLLAPSE -> "ACTION_COLLAPSE"
            AccessibilityNodeInfo.ACTION_FOCUS -> "ACTION_FOCUS"
            AccessibilityNodeInfo.ACTION_SELECT -> "ACTION_SELECT"
            else -> action.label?.toString()?.takeIf { it.isNotBlank() } ?: "ACTION_${action.id}"
        }
    }.distinct()

    private fun automationSelector(node: AccessibilityNodeInfo): AutomationSelector = AutomationSelector(
        resourceId = node.viewIdResourceName?.takeIf { it.isNotBlank() },
        text = if (isSensitiveEditable(node)) null else node.text?.toString()?.takeIf { it.isNotBlank() },
        contentDescription = if (isSensitiveEditable(node)) null else node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
        role = inferRole(node, ""),
        className = node.className?.toString()?.takeIf { it.isNotBlank() },
        requireClickable = node.isClickable.takeIf { it },
        requireEditable = node.isEditable.takeIf { it },
        requireScrollable = node.isScrollable.takeIf { it },
    )

    private fun stableNodeId(path: String, node: AccessibilityNodeInfo, bounds: UiBounds): String {
        val raw = listOf(path, node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(),
            "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}").joinToString("|")
        return sha256(raw).take(16)
    }

    private fun inferRole(
        node: AccessibilityNodeInfo,
        parentClassName: String,
        safeText: String = if (isSensitiveEditable(node)) "" else node.text?.toString().orEmpty(),
        safeDescription: String = if (isSensitiveEditable(node)) "" else node.contentDescription?.toString().orEmpty(),
    ): String {
        return AccessibilityRoles.inferRole(
            className = node.className?.toString().orEmpty(),
            clickable = node.isClickable,
            editable = node.isEditable,
            checkable = node.isCheckable,
            scrollable = node.isScrollable,
            selected = node.isSelected,
            text = safeText,
            contentDescription = safeDescription,
            resourceId = node.viewIdResourceName.orEmpty(),
            parentClassName = parentClassName,
            actions = accessibilityActionNames(node),
        )
    }

    private fun isSensitiveEditable(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        if (!node.isEditable) return false
        val hints = listOf(
            node.viewIdResourceName.orEmpty(),
            node.contentDescription?.toString().orEmpty(),
            node.hintText?.toString().orEmpty(),
            node.className?.toString().orEmpty(),
        ).joinToString(" ")
        return sensitiveEditableHint.containsMatchIn(hints)
    }

    private fun screenFingerprint(packageName: String?, nodes: List<UiNodeSnapshot>): String {
        val normalized = buildString {
            append(packageName.orEmpty())
            nodes.filter { it.visibleToUser && it.windowId != OverlayChromeObservation.OVERLAY_WINDOW_ID }.take(800).forEach {
                append('|').append(it.resourceId).append('|').append(it.text.take(120)).append('|').append(it.contentDescription.take(120))
                    .append('|').append(it.className).append('|').append(it.bounds.left).append(',').append(it.bounds.top)
                    .append(',').append(it.bounds.right).append(',').append(it.bounds.bottom)
            }
        }
        return sha256(normalized).take(20)
    }

    private fun findScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) findScrollable(node.getChild(i))?.let { return it }
        return null
    }

    /** Waits until no accessibility event has touched [displayId] for [quietMs] (at most [maxWaitMs]). */
    fun waitForRevisionQuiet(displayId: Int, quietMs: Long, maxWaitMs: Long) {
        val counter = observationRevisions.computeIfAbsent(displayId) { java.util.concurrent.atomic.AtomicLong() }
        val started = android.os.SystemClock.uptimeMillis()
        var seen = counter.get()
        var quietSince = started
        while (android.os.SystemClock.uptimeMillis() - started < maxWaitMs) {
            Thread.sleep(20)
            val now = counter.get()
            if (now != seen) {
                seen = now
                quietSince = android.os.SystemClock.uptimeMillis()
            } else if (android.os.SystemClock.uptimeMillis() - quietSince >= quietMs) return
        }
    }

    fun waitForUiQuiet(quietMs: Long = 90L, maxWaitMs: Long = 300L) {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < maxWaitMs) {
            val lastEvent = DeviceState.lastUiEventAtMs
            if (lastEvent == 0L || System.currentTimeMillis() - lastEvent >= quietMs) return
            Thread.sleep(20)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
private data class AccessibilityTypeHandle(
    val path: String,
    val node: AccessibilityNodeInfo,
    val rawNodeId: String,
)
