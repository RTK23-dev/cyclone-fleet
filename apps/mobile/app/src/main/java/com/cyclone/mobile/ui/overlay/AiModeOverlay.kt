package com.cyclone.mobile.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.ai.OverlayComposeLifecycle
import com.cyclone.mobile.ui.overlay.glass.TiltGlassTheme
import com.cyclone.mobile.ui.overlay.glass.tiltGlass
import com.cyclone.mobile.ui.overlay.glass.veilPill
import com.cyclone.mobile.voice.ButtonSpot
import com.cyclone.mobile.voice.DriverMode
import com.cyclone.mobile.voice.VoiceFace
import com.cyclone.mobile.voice.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Driver mode's windows (plan 32), next to the normal chrome:
 * - the AI button: the orb on idle-bubble glass, where the idle bubble was. Tap to talk; hold one second, then drag
 *   it to an edge, where it stays (per orientation);
 * - AI mode: a Tilt Glass panel rising from the bottom while a request is live, with the owner's words, Cyclone's line,
 *   the big orb, one big Stop, and "Not now" when Cyclone asks;
 * - the dim: the screen darkens only while a request is live; it never takes a touch.
 *
 * The button and panel only call the [VoiceSession]; the session hands work to Cyclone through the overlay runtime
 * and Task Kit.
 */
object DriverOverlay {
    private var service: CycloneAccessibilityService? = null
    private var scope: CoroutineScope? = null
    private var session: VoiceSession? = null
    private var windows: Windows? = null

    /** Alpha 95: the keeper's state, see [heal]. */
    private var failures = 0
    private var lastRepairMs = 0L
    private var buttonDp = 0
    private var faceJob: kotlinx.coroutines.Job? = null

    /**
     * Attaches the Drive windows to [service]. Called by the accessibility service itself (alpha 95), so the orb no
     * longer depends on the main chrome attaching first, and safe to call again: the same live service is kept.
     */
    fun attach(service: CycloneAccessibilityService) {
        if (this.service === service && scope != null) {
            heal()
            return
        }
        detach()
        this.service = service
        DriverMode.load(service)
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            DriverMode.settings.map { it.enabled to it.buttonDp }.distinctUntilChanged().collect { (on, dp) ->
                // Alpha 95: one failure here used to end this watcher, and with it every later toggle or size change.
                guarded("drive.orb.settings") {
                    stop()
                    buttonDp = dp
                    failures = 0
                    if (on) start(service, dp)
                }
                // The idle bubble gives way to the AI button, or comes back.
                guarded("drive.orb.chrome") { OverlayChromeRuntime.refreshExternalSurface() }
            }
        }
        // Alpha 95: while Driver mode is on, the orb is always there. Whatever took it away, the keeper brings it back.
        s.launch {
            while (true) {
                kotlinx.coroutines.delay(com.cyclone.mobile.voice.OrbKeeper.CHECK_EVERY_MS)
                guarded("drive.orb.keeper") { heal() }
            }
        }
    }

    fun detach() {
        stop()
        scope?.cancel()
        scope = null
        service = null
    }

    private fun start(service: CycloneAccessibilityService, buttonDp: Int) {
        // Alpha.78: every voice run is written to the run history (Glass → Runs, diagnostics).
        val next = VoiceSession(service, VoiceTraceSink(service))
        session = next
        windows = runCatching { Windows(service, next, buttonDp) }
            .onFailure { record("drive.orb.windows", it) }
            .getOrNull()
        val w = windows ?: return
        faceJob = scope?.launch { next.turn.collect { turn -> guarded("drive.orb.face") { w.show(VoiceFace.of(turn)) } } }
    }

    private fun stop() {
        faceJob?.cancel()
        faceJob = null
        windows?.let { w -> guarded("drive.orb.remove") { w.remove() } }
        windows = null
        session?.let { s -> guarded("drive.orb.session") { s.close() } }
        session = null
    }

    /** One look at the orb: repairs whatever is missing while Driver mode is on ([com.cyclone.mobile.voice.OrbKeeper]). */
    private fun heal() {
        val svc = service ?: return
        val settings = DriverMode.settings.value
        val live = CycloneAccessibilityService.instance
        val w = windows
        val look = com.cyclone.mobile.voice.OrbKeeper.Look(
            enabled = settings.enabled,
            serviceCurrent = live == null || live === svc,
            windowsPresent = w != null,
            buttonAttached = w?.buttonAttached == true,
            buttonVisible = w?.buttonVisible == true,
            panelWanted = w?.panelWanted == true,
            panelAttached = w?.panelAttached == true,
            buttonOnScreen = w?.buttonOnScreen() != false,
        )
        val action = com.cyclone.mobile.voice.OrbKeeper.decide(look)
        if (action == com.cyclone.mobile.voice.OrbKeeper.Action.NONE) {
            if (settings.enabled && w != null) failures = 0
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!com.cyclone.mobile.voice.OrbKeeper.allowed(action, failures, lastRepairMs, now)) return
        com.cyclone.mobile.DeviceState.addLog("Drive orb: ${action.name.lowercase()}")
        when (action) {
            com.cyclone.mobile.voice.OrbKeeper.Action.REATTACH -> {
                lastRepairMs = now; failures++
                // Not from inside this scope's own coroutine: attach() cancels it.
                live?.let { next -> android.os.Handler(android.os.Looper.getMainLooper()).post { guarded("drive.orb.reattach") { attach(next) } } }
            }
            com.cyclone.mobile.voice.OrbKeeper.Action.REBUILD -> {
                lastRepairMs = now
                stop()
                start(svc, if (buttonDp > 0) buttonDp else settings.buttonDp)
                if (windows == null) failures++ else failures = 0
                guarded("drive.orb.chrome") { OverlayChromeRuntime.refreshExternalSurface() }
            }
            com.cyclone.mobile.voice.OrbKeeper.Action.SHOW_BUTTON -> w?.showButton()
            com.cyclone.mobile.voice.OrbKeeper.Action.PLACE -> w?.place()
            com.cyclone.mobile.voice.OrbKeeper.Action.NONE -> Unit
        }
    }

    private inline fun guarded(stage: String, block: () -> Unit) {
        try { block() } catch (error: kotlinx.coroutines.CancellationException) { throw error } catch (error: Throwable) { record(stage, error) }
    }

    private fun record(stage: String, error: Throwable) {
        val context = service ?: return
        runCatching { com.mobilerun.portal.diagnostics.CycloneProcessDiagnostics.recordNonFatal(context, stage, error) }
    }

    /** The three windows and their Compose content. */
    private class Windows(private val context: CycloneAccessibilityService, private val session: VoiceSession, private val buttonDp: Int) {
        private val wm = context.getSystemService(WindowManager::class.java)
        private val density = context.resources.displayMetrics.density
        private val lifecycles = mutableListOf<OverlayComposeLifecycle>()
        private val buttonPx = ((buttonDp + DRIVER_GLOW.value * 2) * density).toInt()

        private val button = ButtonHost(context).also { host -> host.addView(compose(host) { ButtonContent() }) }
        private val buttonParams = WindowManager.LayoutParams(buttonPx, buttonPx, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START }
        private val dim = compose { DimContent() }
        private val dimParams = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { fitInsetsTypes = 0 }
        private val panel = compose { PanelContent() }
        private val panelParams = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.BOTTOM }
        private var dimShown = false
        private var panelShown = false
        /** A gesture Cyclone injects is in progress: the voice windows let touches through (alpha.78). */
        private var yielding = false
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        private val unfollow = OverlayGesturePassthrough.follow { yieldNow -> onMainAndWait { yielding = yieldNow; applyTouch() } }

        init {
            place()
            wm.addView(button, buttonParams)
        }

        /** Touchable unless hidden or yielding to a gesture Cyclone is making. */
        private fun applyTouch() {
            val buttonOff = yielding || button.visibility == View.GONE
            val buttonFlags = if (buttonOff) buttonParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else buttonParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (buttonFlags != buttonParams.flags) { buttonParams.flags = buttonFlags; runCatching { wm.updateViewLayout(button, buttonParams) } }
            val panelFlags = if (yielding) panelParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else panelParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (panelFlags != panelParams.flags) { panelParams.flags = panelFlags; if (panelShown) runCatching { wm.updateViewLayout(panel, panelParams) } }
        }

        /** Runs [block] on the main thread and waits (at most 300 ms) until it ran and a frame passed. */
        private fun onMainAndWait(block: () -> Unit) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { block(); return }
            val latch = java.util.concurrent.CountDownLatch(1)
            val posted = main.post {
                try { block() } finally { android.view.Choreographer.getInstance().postFrameCallback { latch.countDown() } }
            }
            if (posted) latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        /** The last face shown: whether AI mode (the panel) is meant to be open. */
        var panelWanted = false
            private set
        val buttonAttached: Boolean get() = button.isAttachedToWindow
        val buttonVisible: Boolean get() = button.visibility == View.VISIBLE
        val panelAttached: Boolean get() = panelShown && panel.isAttachedToWindow

        fun buttonOnScreen(): Boolean {
            val bounds = wm.currentWindowMetrics.bounds
            return com.cyclone.mobile.voice.OrbKeeper.onScreen(buttonParams.x, buttonParams.y, buttonPx, bounds.width(), bounds.height())
        }

        /** The keeper found the button hidden while AI mode is closed. */
        fun showButton() {
            button.visibility = View.VISIBLE
            applyTouch()
        }

        fun show(face: VoiceFace) {
            panelWanted = face.panel
            // The orb moves into AI mode while it is up; the button comes back when it collapses.
            val hidden = face.panel
            if ((button.visibility == View.GONE) != hidden) {
                button.visibility = if (hidden) View.GONE else View.VISIBLE
                // A hidden button must not take touches meant for the app underneath.
                applyTouch()
            }
            button.contentDescription = face.description
            button.state = face.stateDescription
            if (face.dim != dimShown) {
                dimShown = face.dim
                if (face.dim) runCatching { wm.addView(dim, dimParams) } else runCatching { wm.removeView(dim) }
            }
            if (face.panel != panelShown) {
                // Alpha 95: shown only when the window really went up, so a failed add is noticed and repaired.
                panelShown = if (face.panel) runCatching { wm.addView(panel, panelParams) }.isSuccess
                    else { runCatching { wm.removeView(panel) }; false }
            }
        }

        fun remove() {
            unfollow()
            runCatching { wm.removeView(button) }
            if (dimShown) runCatching { wm.removeView(dim) }
            if (panelShown) runCatching { wm.removeView(panel) }
            lifecycles.forEach { it.destroy() }
        }

        /** Puts the button where the owner left it for this orientation. */
        fun place() {
            val bounds = wm.currentWindowMetrics.bounds
            val landscape = bounds.width() > bounds.height()
            val spot = DriverMode.spot(context, landscape)
            val (x, y) = spot.corner(bounds.width(), bounds.height(), buttonPx, (6 * density).toInt())
            buttonParams.x = x
            buttonParams.y = y
            if (button.isAttachedToWindow) runCatching { wm.updateViewLayout(button, buttonParams) }
        }

        fun moveBy(dx: Float, dy: Float, fromX: Int, fromY: Int) {
            val bounds = wm.currentWindowMetrics.bounds
            buttonParams.x = (fromX + dx).toInt().coerceIn(0, (bounds.width() - buttonPx).coerceAtLeast(0))
            buttonParams.y = (fromY + dy).toInt().coerceIn(0, (bounds.height() - buttonPx).coerceAtLeast(0))
            runCatching { wm.updateViewLayout(button, buttonParams) }
        }

        fun drop() {
            val bounds = wm.currentWindowMetrics.bounds
            val spot = ButtonSpot.at(buttonParams.x + buttonPx / 2f, buttonParams.y + buttonPx / 2f, bounds.width().toFloat(), bounds.height().toFloat())
            DriverMode.saveSpot(context, bounds.width() > bounds.height(), spot)
            place()
        }

        /**
         * A ComposeView for one overlay window. Compose looks up the lifecycle from the window's root view, so when the
         * ComposeView sits inside a host (the button's touch frame), the host carries the owners too; without them
         * the first frame throws and takes the whole app down.
         */
        private fun compose(host: View? = null, content: @Composable () -> Unit): ComposeView {
            val owner = OverlayComposeLifecycle().also { it.start(); lifecycles += it }
            host?.apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
            }
            return ComposeView(context).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setContent { TiltGlassTheme { content() } }
            }
        }

        @Composable
        private fun ButtonContent() {
            val turn by session.turn.collectAsState()
            val level by session.level.collectAsState()
            val face = remember(turn) { VoiceFace.of(turn) }
            DriverButtonFace(face, level, buttonDp.dp)
        }

        @Composable
        private fun DimContent() {
            val shade by animateFloatAsState(0.46f, tween(260), label = "Dim")
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = shade * 0.75f),
                0.7f to Color.Black.copy(alpha = shade),
                1f to Color(0xFF04181D).copy(alpha = shade + 0.2f),
            )))
        }

        @Composable
        private fun PanelContent() {
            val turn by session.turn.collectAsState()
            val level by session.level.collectAsState()
            val face = remember(turn) { VoiceFace.of(turn) }
            val enter = remember { MutableTransitionState(false).apply { targetState = true } }
            AnimatedVisibility(enter, enter = slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(200))) {
                AiModePanel(face, level, onOrb = session::tap, onStop = session::stop, onNotNow = session::notNow)
            }
        }

        /** The button's touch: tap talks; a one-second hold picks it up to move. Raw screen positions, so it follows the finger. */
        @SuppressLint("ViewConstructor")
        private inner class ButtonHost(context: Context) : FrameLayout(context) {
            var state: String = ""
            private val slop = ViewConfiguration.get(context).scaledTouchSlop
            private var downX = 0f
            private var downY = 0f
            private var fromX = 0
            private var fromY = 0
            private var held = false
            private var moved = false
            private val hold = Runnable {
                if (!moved) {
                    held = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
                }
            }

            init {
                isClickable = true
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        fromX = buttonParams.x; fromY = buttonParams.y
                        held = false; moved = false
                        postDelayed(hold, HOLD_TO_MOVE_MS)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (held) moveBy(dx, dy, fromX, fromY)
                        else if (kotlin.math.hypot(dx, dy) > slop) { moved = true; removeCallbacks(hold) }
                    }
                    MotionEvent.ACTION_UP -> {
                        removeCallbacks(hold)
                        if (held) { animate().scaleX(1f).scaleY(1f).setDuration(120).start(); drop() }
                        else if (!moved) performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        removeCallbacks(hold)
                        if (held) { animate().scaleX(1f).scaleY(1f).setDuration(120).start(); drop() }
                    }
                }
                return true
            }

            override fun performClick(): Boolean {
                super.performClick()
                session.tap()
                return true
            }

            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.className = "android.widget.Button"
                info.stateDescription = state
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, "Talk to Cyclone"))
            }

            override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean =
                if (action == AccessibilityNodeInfo.ACTION_CLICK) performClick() else super.performAccessibilityAction(action, arguments)

            override fun onConfigurationChanged(newConfig: Configuration?) {
                super.onConfigurationChanged(newConfig)
                place()
            }
        }
    }

    const val HOLD_TO_MOVE_MS = 1_000L
}

/** AI mode: status, captions on veil pills, the big orb, Stop and "Not now". Glanceable: large type, two lines at most. */
@Composable
internal fun AiModePanel(face: VoiceFace, level: Float, onOrb: () -> Unit, onStop: () -> Unit, onNotNow: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)
            .tiltGlass(30.dp)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The small status word shows only when no caption already says it (never "LISTENING" over "Listening…").
        if (face.showStatus) {
            Text(face.status.uppercase(), color = if (face.warm) GlassWarm else GlassMuted, fontSize = 12.sp, letterSpacing = 1.6.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
        }
        if (face.you.isNotBlank()) {
            Caption(face.you, GlassMuted, 20, FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
        }
        if (face.cyclone.isNotBlank()) Caption(face.cyclone, GlassInk, 23, FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier.size(136.dp)
                .clickable(interaction, indication = null, role = Role.Button, onClick = onOrb)
                .semantics {
                    role = Role.Button
                    contentDescription = "Cyclone voice"
                    stateDescription = face.stateDescription
                    onClick(label = "Talk") { onOrb(); true }
                },
            contentAlignment = Alignment.Center,
        ) {
            DriveOrb(face.motion, level, face.warm, Modifier.size(136.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            if (face.notNow) GlassCapsuleButton("Not now", primary = false, onClick = onNotNow, modifier = Modifier.weight(1f).height(56.dp))
            GlassCapsuleButton("Stop", primary = true, onClick = onStop, modifier = Modifier.weight(1f).height(56.dp))
        }
    }
}

@Composable
private fun Caption(text: String, color: Color, sizeSp: Int, weight: FontWeight) {
    Box(Modifier.widthIn(max = 560.dp).veilPill().padding(horizontal = 18.dp, vertical = 8.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(text, color = color, fontSize = sizeSp.sp, lineHeight = (sizeSp + 6).sp, fontWeight = weight, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
