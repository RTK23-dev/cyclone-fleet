package com.cyclone.mobile.ai

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.PowerManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import com.cyclone.mobile.ui.overlay.OverlayExternalInteraction
import com.cyclone.mobile.ui.overlay.OverlayGesturePassthrough
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.capture.LiveCaptureSessionManager
import com.cyclone.mobile.capture.LiveCaptureService
import com.cyclone.mobile.ui.overlay.ScreenSharePill
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.MainActivity
import com.cyclone.mobile.ui.overlay.LocalOverlayImeBottomPx
import com.cyclone.mobile.ui.overlay.OverlayChrome
import com.cyclone.mobile.ui.overlay.OverlayChromeContract
import com.cyclone.mobile.ui.overlay.OverlayImeLift
import com.cyclone.mobile.ui.overlay.OverlayAiSettings
import com.cyclone.mobile.ui.overlay.OverlayIdleActivationTracker
import com.cyclone.mobile.ui.overlay.OverlayIdleHalo
import com.cyclone.mobile.ui.overlay.OverlayIdleTapResult
import com.cyclone.mobile.ui.overlay.OverlayIdleVisualState
import com.cyclone.mobile.ui.overlay.OverlayChromeSnapshot
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import com.cyclone.mobile.ui.overlay.OverlayCopy
import com.cyclone.mobile.ui.overlay.OverlayUserAction

internal object OverlayLockScreenPolicy {
    fun blocked(screenOff: Boolean, keyguardLocked: Boolean, interactive: Boolean): Boolean =
        screenOff || keyguardLocked || !interactive
}

internal data class OverlayWindowContract(
    val matchParentWidth: Boolean,
    val widthDp: Int?,
    val heightDp: Int?,
    val bottomCenter: Boolean,
    val notFocusable: Boolean,
    val notTouchModal: Boolean,
    val notTouchable: Boolean,
    val bottomMarginDp: Int,
)

internal object OverlayChromeWindowPolicy {
    fun main(compact: Boolean): OverlayWindowContract = if (compact) {
        OverlayWindowContract(
            matchParentWidth = false,
            widthDp = OverlayChromeContract.IDLE_TOUCH_SIZE_DP,
            heightDp = OverlayChromeContract.IDLE_TOUCH_SIZE_DP,
            bottomCenter = true,
            notFocusable = true,
            notTouchModal = true,
            notTouchable = false,
            bottomMarginDp = OverlayChromeContract.IDLE_TOUCH_BOTTOM_MARGIN_DP,
        )
    } else {
        OverlayWindowContract(
            matchParentWidth = true,
            widthDp = null,
            heightDp = null,
            bottomCenter = true,
            notFocusable = false,
            notTouchModal = true,
            notTouchable = false,
            bottomMarginDp = 0,
        )
    }

    fun minimizedComposer(): OverlayWindowContract = OverlayWindowContract(
        matchParentWidth = true,
        widthDp = null,
        heightDp = null,
        bottomCenter = true,
        notFocusable = false,
        notTouchModal = true,
        notTouchable = false,
        bottomMarginDp = 0,
    )

    fun glass(): OverlayWindowContract = main(compact = false).copy(notFocusable = true)

    val halo: OverlayWindowContract = OverlayWindowContract(
        matchParentWidth = false,
        widthDp = OverlayChromeContract.IDLE_VISUAL_WIDTH_DP,
        heightDp = OverlayChromeContract.IDLE_VISUAL_HEIGHT_DP,
        bottomCenter = true,
        notFocusable = true,
        notTouchModal = true,
        notTouchable = true,
        bottomMarginDp = OverlayChromeContract.IDLE_VISUAL_BOTTOM_MARGIN_DP,
    )

    fun flags(spec: OverlayWindowContract, secretVisible: Boolean = false): Int {
        // Android replaces an entire secure window with black pixels in USB screen capture.
        // The ordinary Ask/task surface is part of the phone UI and must mirror normally.
        // Keep the Secrets Card protected; it is never exposed to the desktop stream.
        var flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (secretVisible) flags = flags or WindowManager.LayoutParams.FLAG_SECURE
        if (spec.notTouchModal) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (spec.notFocusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (spec.notTouchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    fun withHostGesturePassthrough(flags: Int): Int =
        flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

    fun gravity(spec: OverlayWindowContract): Int =
        Gravity.BOTTOM or if (spec.bottomCenter) Gravity.CENTER_HORIZONTAL else Gravity.END
}

/**
 * Sibling of [AiTraceOverlayController]. Hosts the V4 Compose overlay on
 * TYPE_ACCESSIBILITY_OVERLAY so Cyclone chrome is not a new Activity.
 *
 * Expanded chrome is one touchable drawer window. Idle mode deliberately splits the logical
 * overlay into a non-touchable 144x72dp visual halo and a centered 48x48dp activation window.
 * A minimized current run uses a separate content-height Ask Cyclone pill; it never turns the
 * rest of the host app into a touch target.
 */
class OverlayChromeController(
    private val service: CycloneAccessibilityService,
    private val onAction: (OverlayUserAction) -> Unit,
    private val onComposerChanged: (String) -> Unit,
    private val onRequestSubmitted: (String) -> Unit,
    private val onVoiceStateChanged: (Boolean, String?, String?) -> Unit,
    private val getAiSettings: () -> OverlayAiSettings,
    private val onAiSettingsChanged: (OverlayAiSettings) -> Unit,
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val keyguard = service.getSystemService(KeyguardManager::class.java)
    private val power = service.getSystemService(PowerManager::class.java)
    private var screenOff = false
    private var lockReceiverRegistered = false
    private var lastLockBlocked: Boolean? = null
    private fun lockScreenBlocked() = OverlayLockScreenPolicy.blocked(screenOff, keyguard.isKeyguardLocked, power.isInteractive)
    private fun hideLockedWindows(): Boolean {
        if (!lockScreenBlocked()) return false
        val attached = listOfNotNull(root, haloRoot, shareRoot, sheetRoot)
        if (attached.any { it.visibility == View.VISIBLE }) { speechRecognizer?.cancel(); voiceSession = false }
        attached.forEach { it.visibility = View.GONE }
        return true
    }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOff = when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> true
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> false
                else -> screenOff
            }
            render(latest)
        }
    }
    private val lockCheck = object : Runnable {
        override fun run() {
            if (!lockReceiverRegistered) return
            val blocked = lockScreenBlocked()
            if (blocked != lastLockBlocked) {
                lastLockBlocked = blocked
                render(latest)
            }
            main.postDelayed(this, 500)
        }
    }
    private fun watchLockScreen() {
        if (lockReceiverRegistered) return
        ContextCompat.registerReceiver(service, lockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        lockReceiverRegistered = true
        main.postDelayed(lockCheck, 500)
    }
    private var lifecycle = OverlayComposeLifecycle()
    @Volatile private var generation = 0L
    private var backgroundTask by mutableStateOf<com.cyclone.mobile.runtime.background.WorkspaceTaskUi?>(null)
    private val windows = com.cyclone.mobile.ui.overlay.OverlayWindowRegistry<View> { view ->
        try { wm.removeViewImmediate(view) } catch (_: IllegalArgumentException) { /* Already removed by Android. */ }
        if (view is ComposeView) view.disposeComposition()
    }
    /** Instrumentation hook: service detach has zero windows; task cleanup retains chrome. */
    fun attachedWindowCount(): Int = windows.size
    fun attachedLauncherCount(): Int = if (root?.isAttachedToWindow == true) 1 else 0
    private fun addWindow(view: View, layout: WindowManager.LayoutParams) {
        if (lockScreenBlocked()) view.visibility = View.GONE
        wm.addView(view, layout)
        windows.attached(view)
    }
    fun background(task: com.cyclone.mobile.runtime.background.WorkspaceTaskUi?) {
        onMain {
            backgroundTask = task
            if (com.cyclone.mobile.ui.overlay.BackgroundGlassPolicy.tearDown(task)) {
                backgroundTask = null
                render(latest.copy(idleChipVisible = true))
            }
            else if (com.cyclone.mobile.ui.overlay.BackgroundGlassPolicy.visible(task)) {
                if (root == null) show(latest) else applyLayout(latest)
            }
        }
    }
    private fun glass() =
        latest.state == OverlayChromeState.IDLE &&
            com.cyclone.mobile.ui.overlay.BackgroundGlassPolicy.visible(backgroundTask)
    private val idleActivation = OverlayIdleActivationTracker()
    private var root: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var shareRoot: ComposeView? = null
    private var haloRoot: ComposeView? = null
    private var haloParams: WindowManager.LayoutParams? = null
    private var sheetRoot: android.widget.FrameLayout? = null
    private var sheetCompose: ComposeView? = null
    private var sheetParams: WindowManager.LayoutParams? = null
    private var sheetCloseRequests by mutableStateOf(0)
    private var sheetLastPage = com.cyclone.mobile.ui.overlay.ComposerAccessory.ATTACHMENTS
    private var latest by mutableStateOf(OverlayChromeSnapshot())
    private var aiSettings by mutableStateOf(OverlayAiSettings())
    private var idleVisualState by mutableStateOf(OverlayIdleVisualState())
    private var imeBottomPx by mutableStateOf(0)
    private var navigationBottomPx by mutableStateOf(0)
    private var reportedImeBottomPx = 0
    private var speechRecognizer: SpeechRecognizer? = null
    /** A recognition session is running; a second start must not restart it (a restart ends it with an error). */
    private var voiceSession = false
    private var voiceSessionAt = 0L
    @Volatile private var hostGestureYielded = false

    fun show(snapshot: OverlayChromeSnapshot) {
        onMain {
            latest = snapshot
            watchLockScreen()
            if (hideLockedWindows()) return@onMain
            aiSettings = getAiSettings()
            if (root != null) {
                applyLayout(snapshot)
                return@onMain
            }
            lifecycle = OverlayComposeLifecycle()
            lifecycle.start()
            val halo = ComposeView(service).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setViewTreeLifecycleOwner(lifecycle)
                setViewTreeViewModelStoreOwner(lifecycle)
                setViewTreeSavedStateRegistryOwner(lifecycle)
                setContent {
                    OverlayIdleHalo(state = idleVisualState)
                }
            }
            val view = ComposeView(service).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setViewTreeLifecycleOwner(lifecycle)
                setViewTreeViewModelStoreOwner(lifecycle)
                setViewTreeSavedStateRegistryOwner(lifecycle)
                setContent {
                    val externalActive by OverlayExternalInteraction.active.collectAsState()
                    val secretCardState by com.cyclone.mobile.secrets.SecretsCardRuntime.state.collectAsState()
                    val ownerMoment = com.cyclone.mobile.owner.rememberOwnerMoment()
                    val sheetPage by com.cyclone.mobile.ui.overlay.OverlayToolsSheetState.page.collectAsState()
                    LaunchedEffect(sheetPage) { syncToolsSheet(sheetPage) }
                    LaunchedEffect(externalActive, secretCardState?.visible, ownerMoment?.kind, ownerMoment?.requestId) {
                        aiSettings = getAiSettings()
                        applyLayout(latest)
                    }
                    if (glass() && secretCardState?.visible != true && !com.cyclone.mobile.owner.OwnerMoments.overlayCard(ownerMoment)) {
                        com.cyclone.mobile.ui.v32.CycloneV32Theme(drawBackground = false) {
                            com.cyclone.mobile.ui.overlay.BackgroundTaskGlass(backgroundTask!!) { onAction(OverlayUserAction.ASK_CYCLONE) }
                        }
                    } else {
                        CompositionLocalProvider(LocalOverlayImeBottomPx provides imeBottomPx) {
                            OverlayChrome(
                                snapshot = latest,
                                onAction = onAction,
                                onComposerChanged = onComposerChanged,
                                onRequestSubmitted = onRequestSubmitted,
                                onVoiceInput = ::beginVoiceInput,
                                onVoiceStop = ::stopVoiceInput,
                                aiSettings = aiSettings,
                                onAiSettingsChanged = { next ->
                                    aiSettings = next
                                    onAiSettingsChanged(next)
                                },
                                idleVisualState = idleVisualState,
                                secretCardState = secretCardState,
                                ownerMoment = ownerMoment,
                                onIdleTap = ::recordIdleTap,
                                onIdleSemanticActivate = ::recordSemanticActivation,
                            )
                        }
                    }
                }
            }
            val haloLayout = windowParams(OverlayChromeWindowPolicy.halo)
            val layout = overlayParams(snapshot)
            haloParams = haloLayout
            params = layout
            haloRoot = halo
            root = view
            applyLayout(snapshot)
            // Add decoration first so the small semantic/touch hotspot stays above it.
            try {
                addWindow(halo, haloLayout)
                addWindow(view, layout)
                trackIme(view)
            } catch (_: Exception) { dismiss(); return@onMain }
            val shareView = ComposeView(service).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setViewTreeLifecycleOwner(lifecycle)
                setViewTreeViewModelStoreOwner(lifecycle)
                setViewTreeSavedStateRegistryOwner(lifecycle)
                setContent {
                    val sharing by LiveCaptureSessionManager.state.collectAsState()
                    val externalActive by OverlayExternalInteraction.active.collectAsState()
                    if (!externalActive && isCompact(latest) && sharing.active &&
                        sharing.phase != com.cyclone.mobile.capture.ScreenSharePhase.REQUESTING_PERMISSION
                    ) {
                        com.cyclone.mobile.ui.v32.CycloneV32Theme {
                            androidx.compose.foundation.layout.Box(Modifier.padding(8.dp)) {
                                ScreenSharePill(sharing) { LiveCaptureService.stop(service) }
                            }
                        }
                    }
                }
            }
            val shareLayout = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
            shareRoot = shareView
            try { addWindow(shareView, shareLayout) } catch (_: Exception) { dismiss() }
        }
    }

    /** A card that needs a full, focusable window: the Secrets Card or a mission's check-in card. */
    private fun cardVisible(): Boolean = com.cyclone.mobile.secrets.SecretsCardRuntime.state.value?.visible == true ||
        com.cyclone.mobile.owner.OwnerMoments.overlayCard(com.cyclone.mobile.owner.OwnerMomentsRuntime.current())

    fun render(snapshot: OverlayChromeSnapshot) {
        onMain {
            latest = snapshot
            if (hideLockedWindows()) return@onMain
            if (root != null && root?.isAttachedToWindow != true) {
                dismiss()
                show(snapshot)
                return@onMain
            }
            if (root == null) {
                val secretVisible = cardVisible()
                if (snapshot.state != OverlayChromeState.IDLE || snapshot.idleChipVisible || glass() || secretVisible) show(snapshot)
                return@onMain
            }
            if (!isCompact(snapshot)) resetIdleActivation()
            applyLayout(snapshot)
        }
    }

    fun dismiss() {
        onMain {
            generation++
            if (lockReceiverRegistered) service.unregisterReceiver(lockReceiver)
            lockReceiverRegistered = false
            main.removeCallbacksAndMessages(null)
            latest = OverlayChromeSnapshot(idleChipVisible = false)
            backgroundTask = null
            windows.clear()
            listOfNotNull(root, haloRoot, shareRoot, sheetCompose).forEach { it.disposeComposition() }
            sheetCompose = null
            com.cyclone.mobile.ui.overlay.OverlayToolsSheetState.close()
            sheetRoot = null
            sheetParams = null
            shareRoot = null
            root = null
            params = null
            haloRoot = null
            haloParams = null
            hostGestureYielded = false
            resetIdleActivation()
            speechRecognizer?.destroy()
            voiceSession = false
            speechRecognizer = null
            lifecycle.destroy()
        }
    }

    fun keyboardClosed() {
        onMain {
            reportedImeBottomPx = 0
            imeBottomPx = 0
            applyLayout(latest)
        }
    }

    private fun applyLayout(snapshot: OverlayChromeSnapshot) {
        if (hideLockedWindows()) return
        val yieldHost = OverlayGesturePassthrough.active()
        shareRoot?.let { share ->
            share.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            share.visibility = if (yieldHost || OverlayExternalInteraction.active.value) View.GONE else View.VISIBLE
        }
        sheetRoot?.visibility = if (yieldHost) View.GONE else View.VISIBLE
        val view = root ?: return
        val layout = params ?: return
        val secretVisible = cardVisible()
        val compact = !secretVisible && isCompact(snapshot)
        val minimizedComposer = !secretVisible && isMinimizedComposer(snapshot)
        // Driver mode (plan 32): the AI button replaces the idle bubble; everything else (task, cards) stays.
        val driverIdle = com.cyclone.mobile.voice.DriverMode.settings.value.enabled && compact && !minimizedComposer &&
            (snapshot.state == OverlayChromeState.IDLE || snapshot.launcherCollapsed) && !glass()
        val visible = (secretVisible || !compact || snapshot.idleChipVisible || glass()) &&
            !OverlayExternalInteraction.active.value &&
            !yieldHost && !driverIdle

        view.importantForAccessibility =
            if (yieldHost) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_YES
        view.contentDescription = when {
            secretVisible -> "Secret needed"
            snapshot.state == OverlayChromeState.GATE && !snapshot.minimized -> OverlayCopy.GATE
            else -> null
        }
        view.visibility = if (visible) View.VISIBLE else View.GONE

        val spec = when {
            secretVisible -> OverlayChromeWindowPolicy.main(compact = false)
            glass() -> OverlayChromeWindowPolicy.glass()
            minimizedComposer -> OverlayChromeWindowPolicy.minimizedComposer()
            else -> OverlayChromeWindowPolicy.main(compact)
        }
        val changed = applyWindowContract(
            layout,
            spec,
            followKeyboard = secretVisible || (!compact && !glass()),
        )
        if (changed || yieldHost != hostGestureYielded) {
            runCatching { wm.updateViewLayout(view, layout) }
        }

        haloRoot?.let { halo ->
            halo.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            halo.visibility = if (
                visible &&
                !secretVisible &&
                (snapshot.state == OverlayChromeState.IDLE || snapshot.launcherCollapsed) &&
                !glass() &&
                snapshot.idleChipVisible
            ) View.VISIBLE else View.GONE
        }
        haloParams?.let { hp ->
            val haloChanged = applyWindowContract(hp, OverlayChromeWindowPolicy.halo, followKeyboard = false)
            if (haloChanged || yieldHost != hostGestureYielded) {
                haloRoot?.let { halo -> runCatching { wm.updateViewLayout(halo, hp) } }
            }
        }
        hostGestureYielded = yieldHost
    }

    /**
     * The + tools drawer: a full-screen window above the Ask overlay. It blurs everything behind it
     * (cross-window blur, animated with the sheet) and owns focus only so Back closes it.
     */
    private fun syncToolsSheet(page: com.cyclone.mobile.ui.overlay.ComposerAccessory) {
        if (page == com.cyclone.mobile.ui.overlay.ComposerAccessory.NONE) {
            sheetRoot?.let { windows.remove(it) }
            sheetCompose?.disposeComposition()
            sheetCompose = null
            sheetRoot = null
            sheetParams = null
            return
        }
        sheetLastPage = page
        if (sheetRoot != null || root == null || lockScreenBlocked()) return
        val blurAvailable = runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
        val maxBlurPx = com.cyclone.mobile.ui.overlay.OverlayToolsSheetPhysics.BLUR_DP * service.resources.displayMetrics.density
        val content = ComposeView(service).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val current by com.cyclone.mobile.ui.overlay.OverlayToolsSheetState.page.collectAsState()
                val sharing by LiveCaptureSessionManager.state.collectAsState()
                com.cyclone.mobile.ui.v32.CycloneV32Theme(drawBackground = false) {
                    com.cyclone.mobile.ui.overlay.OverlayToolsSheet(
                        page = if (current == com.cyclone.mobile.ui.overlay.ComposerAccessory.NONE) sheetLastPage else current,
                        sharingActive = sharing.active,
                        aiSettings = aiSettings,
                        onAiSettingsChanged = { next ->
                            aiSettings = next
                            onAiSettingsChanged(next)
                        },
                        actions = com.cyclone.mobile.ui.overlay.OverlayToolsSheetActions(
                            onCamera = { launchFromSheet(Intent(service, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java).putExtra("camera", true)) },
                            onPhotos = { launchFromSheet(Intent(service, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java).putExtra("photos", true)) },
                            onFiles = { launchFromSheet(Intent(service, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java)) },
                            onShareScreen = { launchFromSheet(Intent(service, com.cyclone.mobile.capture.LiveCaptureConsentActivity::class.java).putExtra("wholeDisplay", true)) },
                            onCrossAppShare = { launchFromSheet(Intent(service, com.cyclone.mobile.capture.LiveCaptureConsentActivity::class.java).putExtra("wholeDisplay", true)) },
                        ),
                        blurAvailable = blurAvailable,
                        onBlurFraction = { fraction -> setSheetBlur((fraction * maxBlurPx).toInt(), blurAvailable) },
                        closeRequests = sheetCloseRequests,
                        onClosed = { com.cyclone.mobile.ui.overlay.OverlayToolsSheetState.close() },
                    )
                }
            }
        }
        // ComposeView is final; a thin host catches Back so it runs the same smooth close as a drag.
        val sheet = object : android.widget.FrameLayout(service) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) sheetCloseRequests++
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setViewTreeLifecycleOwner(lifecycle)
            setViewTreeViewModelStoreOwner(lifecycle)
            setViewTreeSavedStateRegistryOwner(lifecycle)
            addView(content)
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (blurAvailable) WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0),
            PixelFormat.TRANSLUCENT,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            fitInsetsTypes = 0
            if (blurAvailable) blurBehindRadius = 0
        }
        sheetCloseRequests = 0
        sheetParams = layout
        sheetRoot = sheet
        sheetCompose = content
        runCatching { addWindow(sheet, layout) }.onFailure {
            content.disposeComposition()
            sheetCompose = null
            sheetRoot = null
            sheetParams = null
            com.cyclone.mobile.ui.overlay.OverlayToolsSheetState.close()
        }
    }

    private fun setSheetBlur(radiusPx: Int, blurAvailable: Boolean) {
        if (!blurAvailable) return
        val view = sheetRoot ?: return
        val layout = sheetParams ?: return
        if (kotlin.math.abs(layout.blurBehindRadius - radiusPx) < 2 && radiusPx != 0) return
        layout.blurBehindRadius = radiusPx.coerceAtLeast(0)
        runCatching { wm.updateViewLayout(view, layout) }
    }

    /** Opening a picker/consent screen closes the drawer at once and yields the Ask overlay. */
    private fun launchFromSheet(intent: Intent) {
        com.cyclone.mobile.ui.overlay.OverlayToolsSheetState.close()
        OverlayExternalInteraction.active.value = true
        runCatching { service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                OverlayExternalInteraction.active.value = false
                Toast.makeText(service, "This action is unavailable.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun overlayParams(snapshot: OverlayChromeSnapshot): WindowManager.LayoutParams =
        windowParams(
            when {
                cardVisible() ->
                    OverlayChromeWindowPolicy.main(compact = false)
                glass() -> OverlayChromeWindowPolicy.glass()
                isMinimizedComposer(snapshot) -> OverlayChromeWindowPolicy.minimizedComposer()
                else -> OverlayChromeWindowPolicy.main(isCompact(snapshot))
            },
        )

    private fun windowParams(spec: OverlayWindowContract): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            widthFor(spec),
            heightFor(spec),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flagsFor(spec),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = gravityFor(spec)
            y = liftedY(spec, followKeyboard = spec.heightDp == null && !spec.notFocusable)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            fitInsetsTypes = 0
        }

    private fun applyWindowContract(
        layout: WindowManager.LayoutParams,
        spec: OverlayWindowContract,
        followKeyboard: Boolean,
    ): Boolean {
        var changed = false
        val width = widthFor(spec)
        val height = heightFor(spec)
        val flags = flagsFor(spec)
        val gravity = gravityFor(spec)
        val y = liftedY(spec, followKeyboard)
        if (layout.width != width) {
            layout.width = width
            changed = true
        }
        if (layout.height != height) {
            layout.height = height
            changed = true
        }
        if (layout.flags != flags) {
            layout.flags = flags
            changed = true
        }
        if (layout.gravity != gravity) {
            layout.gravity = gravity
            changed = true
        }
        if (layout.y != y) {
            layout.y = y
            changed = true
        }
        if (layout.softInputMode != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) {
            layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            changed = true
        }
        if (layout.fitInsetsTypes != 0) {
            layout.fitInsetsTypes = 0
            changed = true
        }
        return changed
    }

    private fun liftedY(spec: OverlayWindowContract, followKeyboard: Boolean): Int =
        OverlayImeLift.windowY(
            followKeyboard = followKeyboard,
            specBottomMarginPx = dp(spec.bottomMarginDp),
            imeBottomPx = imeBottomPx,
            navigationBottomPx = navigationBottomPx,
            restGapPx = dp(OverlayChromeContract.COMPOSER_BOTTOM_GAP_DP),
            keyboardGapPx = dp(OverlayImeLift.KEYBOARD_GAP_DP),
        )

    private fun trackIme(view: View) {
        val apply = { insets: WindowInsetsCompat ->
            reportedImeBottomPx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            imeBottomPx = reportedImeBottomPx
            navigationBottomPx = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            applyLayout(latest)
        }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            apply(insets)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            view,
            object : WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
            ) {
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>,
                ): WindowInsetsCompat {
                    apply(insets)
                    return insets
                }
            },
        )
        ViewCompat.requestApplyInsets(view)
        // Accessibility overlays can receive frame-relative (zero) IME insets even with a docked
        // keyboard. Resolve its absolute window bounds; never inspect keyboard nodes or text.
        val keyboardTracker = object : Runnable {
            override fun run() {
                if (root !== view || !view.isAttachedToWindow) return
                if (view.visibility == View.VISIBLE && !isCompact(latest) && !glass()) {
                    val screen = wm.maximumWindowMetrics.bounds
                    val docked = if (view.hasWindowFocus()) runCatching {
                        service.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                            .maxOfOrNull { window ->
                                val bounds = android.graphics.Rect()
                                window.getBoundsInScreen(bounds)
                                OverlayImeLift.dockedHeight(screen.width(), screen.bottom, bounds.left,
                                    bounds.top, bounds.right, bounds.bottom, navigationBottomPx)
                            } ?: 0
                    }.getOrDefault(0) else 0
                    val next = OverlayImeLift.resolvedHeight(reportedImeBottomPx, docked)
                    if (imeBottomPx != next) {
                        imeBottomPx = next
                        applyLayout(latest)
                    }
                }
                main.postDelayed(this, 80L)
            }
        }
        main.post(keyboardTracker)
    }

    private fun widthFor(spec: OverlayWindowContract): Int =
        if (spec.matchParentWidth) WindowManager.LayoutParams.MATCH_PARENT else dp(requireNotNull(spec.widthDp))

    private fun heightFor(spec: OverlayWindowContract): Int =
        spec.heightDp?.let(::dp) ?: WindowManager.LayoutParams.WRAP_CONTENT

    private fun gravityFor(spec: OverlayWindowContract): Int = OverlayChromeWindowPolicy.gravity(spec)

    private fun flagsFor(spec: OverlayWindowContract): Int = OverlayChromeWindowPolicy.flags(
        spec,
        secretVisible = com.cyclone.mobile.secrets.SecretsCardRuntime.state.value?.visible == true,
    ).let {
        if (OverlayExternalInteraction.active.value || OverlayGesturePassthrough.active()) {
            OverlayChromeWindowPolicy.withHostGesturePassthrough(it)
        } else it
    }

    /**
     * Commit overlay yield before dispatchGesture. FLAG_NOT_TOUCHABLE is not enough:
     * the idle ball and Ask card must be GONE and not important-for-accessibility, then
     * WindowManager must process that frame or the stroke still hits Cyclone chrome.
     */
    fun syncHostGesturePassthrough() {
        val latch = CountDownLatch(1)
        val task = Runnable {
            try {
                if (root != null) applyLayout(latest)
                val attached = sequenceOf(root, haloRoot, shareRoot).firstOrNull { it?.isAttachedToWindow == true }
                if (attached != null) {
                    Choreographer.getInstance().postFrameCallback { latch.countDown() }
                } else {
                    latch.countDown()
                }
            } catch (_: RuntimeException) {
                latch.countDown()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
            return
        }
        if (!main.post(task)) {
            latch.countDown()
            return
        }
        // Alpha 91: a busy main thread (0.5 s stalls were measured) must not let a stroke land on Cyclone's own card.
        latch.await(1_200, TimeUnit.MILLISECONDS)
    }

    private fun recordIdleTap() {
        applyIdleTapResult(idleActivation.onTap(SystemClock.elapsedRealtime()))
    }

    private fun recordSemanticActivation() {
        applyIdleTapResult(idleActivation.semanticActivate())
    }

    private fun applyIdleTapResult(result: OverlayIdleTapResult) {
        if (result.ignored) return
        idleVisualState = OverlayIdleVisualState(
            pulseSerial = result.pulseSerial,
            pulseLevel = result.pulseLevel,
            activating = result.activate,
        )
        if (!result.activate) return
        main.postDelayed(
            {
                if (isCompact(latest) && latest.idleChipVisible && idleVisualState.activating) {
                    onAction(OverlayUserAction.ASK_CYCLONE)
                }
            },
            OverlayChromeContract.IDLE_ACTIVATION_DELAY_MS,
        )
    }

    private fun resetIdleActivation() {
        idleActivation.reset()
        idleVisualState = OverlayIdleVisualState()
    }

    private fun isCompact(snapshot: OverlayChromeSnapshot): Boolean =
        snapshot.state == OverlayChromeState.IDLE || snapshot.launcherCollapsed

    private fun isMinimizedComposer(snapshot: OverlayChromeSnapshot): Boolean =
        snapshot.state != OverlayChromeState.IDLE && snapshot.minimized && !snapshot.launcherCollapsed

    fun beginVoiceInput() {
        onMain {
            if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                onVoiceStateChanged(false, null, "Allow microphone access once, then tap the voice button again.")
                Toast.makeText(service, "Cyclone needs microphone access for voice requests.", Toast.LENGTH_LONG).show()
                service.startActivity(
                    Intent(service, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_REQUEST_OVERLAY_VOICE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                return@onMain
            }
            if (!SpeechRecognizer.isRecognitionAvailable(service)) {
                onVoiceStateChanged(false, null, "Voice recognition is not available on this phone.")
                return@onMain
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (voiceSession && now - voiceSessionAt < 60_000L) return@onMain
            val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(service).also {
                it.setRecognitionListener(OverlayRecognitionListener())
                speechRecognizer = it
            }
            // Clear any session the recognizer still holds, so starting never fails as busy.
            recognizer.cancel()
            voiceSession = true
            voiceSessionAt = now
            onVoiceStateChanged(true, null, OverlayCopy.LISTENING)
            recognizer.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                },
            )
        }
    }

    /**
     * The owner stopped talking (tap on the orb, or let go after holding it). The recognizer finishes what it heard,
     * so the words land in the Ask field instead of being thrown away.
     */
    fun stopVoiceInput() {
        onMain {
            if (!voiceSession) {
                // Nothing is running (it was cancelled elsewhere): just put the button back.
                onVoiceStateChanged(false, null, null)
                return@onMain
            }
            speechRecognizer?.stopListening() ?: run {
                voiceSession = false
                onVoiceStateChanged(false, null, null)
            }
        }
    }

    private inner class OverlayRecognitionListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            voiceSession = false
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that. Try speaking again."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Tap the microphone to retry."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone access is required for voice requests."
                else -> "Voice input paused. Tap the microphone to retry."
            }
            onVoiceStateChanged(false, null, message)
        }

        override fun onResults(results: Bundle?) {
            voiceSession = false
            val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            onVoiceStateChanged(false, transcript, if (transcript.isNullOrBlank()) "I didn't catch that." else null)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val transcript = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!transcript.isNullOrBlank()) onVoiceStateChanged(true, transcript, OverlayCopy.LISTENING)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun onMain(block: () -> Unit) {
        val expectedGeneration = generation
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else main.post { if (generation == expectedGeneration) block() }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
}

internal class OverlayComposeLifecycle : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun start() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        store.clear()
    }
}
