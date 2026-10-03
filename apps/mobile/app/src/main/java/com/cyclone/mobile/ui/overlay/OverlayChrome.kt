package com.cyclone.mobile.ui.overlay

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.capture.LiveCaptureConsentActivity
import com.cyclone.mobile.ui.overlay.glass.tiltGlass
import com.cyclone.mobile.capture.LiveCaptureService
import com.cyclone.mobile.capture.LiveCaptureSessionManager
import com.cyclone.mobile.capture.ScreenSharePhase
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.v32.CycloneAskTaskPanel
import com.cyclone.mobile.ui.v32.CycloneForegroundWorkCard
import com.cyclone.mobile.ui.v32.CyclonePendingRequests
import com.cyclone.mobile.ui.v32.CycloneTrayIconAction
import com.cyclone.mobile.ui.v32.CycloneV32Theme

private val AuroraBlue = Color(0xFF4A8DFF)
private val AuroraCyan = Color(0xFF80E9FF)
private val AuroraWhite = Color(0xFFE8F4FF)

data class OverlayAiSettings(
    val modelId: String = "",
    val reasoningEffort: String = "medium",
)

data class OverlayIdleVisualState(
    val pulseSerial: Int = 0,
    val pulseLevel: Int = 0,
    val activating: Boolean = false,
)

internal data class OverlayIdleTapResult(
    val pulseSerial: Int,
    val pulseLevel: Int,
    val tapCount: Int,
    val activate: Boolean,
    val ignored: Boolean,
)

internal object SheetDismissal {
    fun shouldDismiss(offsetPx: Float, heightPx: Float): Boolean =
        heightPx > 0f && offsetPx >= heightPx * 0.28f
}

/** Three deliberate taps; a hold is never treated as activation. */
internal class OverlayIdleActivationTracker(
    private val maxGapMs: Long = OverlayChromeContract.IDLE_TAP_MAX_GAP_MS,
    private val maxSequenceMs: Long = OverlayChromeContract.IDLE_TAP_MAX_SEQUENCE_MS,
) {
    private var firstTapAtMs = 0L
    private var lastTapAtMs = 0L
    private var tapCount = 0
    private var pulseSerial = 0
    private var activating = false

    fun onTap(atMs: Long): OverlayIdleTapResult {
        if (activating) return OverlayIdleTapResult(pulseSerial, 0, tapCount, false, true)
        val expired = tapCount == 0 || atMs - lastTapAtMs > maxGapMs || atMs - firstTapAtMs > maxSequenceMs
        if (expired) {
            firstTapAtMs = atMs
            tapCount = 1
        } else {
            tapCount += 1
        }
        lastTapAtMs = atMs
        pulseSerial += 1
        val level = tapCount.coerceIn(1, 3)
        val activate = tapCount >= 3
        if (activate) {
            activating = true
            tapCount = 0
        }
        return OverlayIdleTapResult(pulseSerial, level, tapCount, activate, false)
    }

    fun semanticActivate(): OverlayIdleTapResult {
        if (activating) return OverlayIdleTapResult(pulseSerial, 0, tapCount, false, true)
        pulseSerial += 1
        activating = true
        tapCount = 0
        return OverlayIdleTapResult(pulseSerial, 3, 0, true, false)
    }

    fun reset() {
        firstTapAtMs = 0L
        lastTapAtMs = 0L
        tapCount = 0
        activating = false
    }
}

@Composable
fun OverlayChrome(
    snapshot: OverlayChromeSnapshot,
    onAction: (OverlayUserAction) -> Unit,
    onComposerChanged: (String) -> Unit = {},
    onRequestSubmitted: (String) -> Unit = {},
    onVoiceInput: () -> Unit = {},
    onVoiceStop: () -> Unit = {},
    aiSettings: OverlayAiSettings = OverlayAiSettings(),
    onAiSettingsChanged: (OverlayAiSettings) -> Unit = {},
    idleVisualState: OverlayIdleVisualState = OverlayIdleVisualState(),
    secretCardState: com.cyclone.mobile.secrets.SecretsCardUiState? = null,
    ownerMoment: com.cyclone.mobile.owner.OwnerMoment? = null,
    onIdleTap: () -> Unit = {},
    onIdleSemanticActivate: () -> Unit = { onAction(OverlayUserAction.ASK_CYCLONE) },
    modifier: Modifier = Modifier,
) {
    com.cyclone.mobile.ui.v32.CycloneSignatureTheme {
        val presentation = when {
            secretCardState?.visible == true -> "secret"
            com.cyclone.mobile.owner.OwnerMoments.overlayCard(ownerMoment) -> "owner"
            snapshot.state == OverlayChromeState.IDLE -> "idle"
            snapshot.launcherCollapsed -> "launcher"
            snapshot.state == OverlayChromeState.GATE -> "gate"
            else -> "composer"
        }
        AnimatedContent(
            targetState = presentation,
            transitionSpec = {
                (fadeIn(tween(180)) + slideInVertically(tween(240, easing = FastOutSlowInEasing)) { it / 3 })
                    .togetherWith(fadeOut(tween(130)) + slideOutVertically(tween(160)) { it / 4 })
            },
            label = "Cyclone chat drawer",
        ) { mode ->
            when (mode) {
                "secret" -> com.cyclone.mobile.secrets.SecretsCardOverlay(secretCardState!!)
                "owner" -> ownerMoment?.let { moment ->
                    com.cyclone.mobile.ui.overlay.glass.FollowPhoneLight()
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        if (moment.taskId.startsWith(com.cyclone.mobile.task.TaskEngines.MIND_TASK_PREFIX)) {
                            PlaneRow(moment.taskId)
                            androidx.compose.foundation.layout.Spacer(Modifier.height(OverlayStackGeometry.PILL_GAP_DP.dp))
                        }
                        OverlayOwnerCard(moment)
                    }
                }
                "idle" -> if (snapshot.idleChipVisible) {
                    IdleActivationHotspot(
                        state = idleVisualState,
                        onTap = onIdleTap,
                        onSemanticActivate = onIdleSemanticActivate,
                        modifier = modifier,
                    )
                } else {
                    Box(Modifier.size(OverlayChromeContract.IDLE_TOUCH_SIZE_DP.dp))
                }
                "launcher" -> if (snapshot.idleChipVisible) {
                    IdleActivationHotspot(
                        state = idleVisualState,
                        onTap = onIdleTap,
                        onSemanticActivate = onIdleSemanticActivate,
                        modifier = modifier,
                    )
                } else {
                    Box(Modifier.size(OverlayChromeContract.IDLE_TOUCH_SIZE_DP.dp))
                }
                "gate" -> GatePanel(snapshot, onAction)
                else -> { com.cyclone.mobile.ui.overlay.glass.FollowPhoneLight(); ComposerPanel(
                    snapshot = snapshot,
                    minimized = snapshot.minimized,
                    onAction = onAction,
                    onComposerChanged = onComposerChanged,
                    onRequestSubmitted = onRequestSubmitted,
                    onVoiceInput = onVoiceInput,
                    onVoiceStop = onVoiceStop,
                    aiSettings = aiSettings,
                    onAiSettingsChanged = onAiSettingsChanged,
                ) }
            }
        }
    }
}

@Composable
private fun IdleActivationHotspot(
    state: OverlayIdleVisualState,
    onTap: () -> Unit,
    onSemanticActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(OverlayChromeContract.IDLE_TOUCH_SIZE_DP.dp)
            .semantics {
                contentDescription = "Cyclone AI. Triple tap to open."
                onClick("Open Cyclone") {
                    if (!state.activating) onSemanticActivate()
                    true
                }
            }
            .pointerInput(state.activating) {
                if (!state.activating) detectTapGestures(onTap = { onTap() })
            },
        contentAlignment = Alignment.Center,
    ) {}
}

/** Non-touchable ambient decoration hosted by the controller in its own window. */
@Composable
internal fun OverlayIdleHalo(
    state: OverlayIdleVisualState,
    modifier: Modifier = Modifier,
) {
    val pulseScale = remember { Animatable(1f) }
    val pulseAlpha = remember { Animatable(0f) }
    LaunchedEffect(state.pulseSerial) {
        if (state.pulseSerial <= 0) return@LaunchedEffect
        pulseScale.snapTo(1f)
        pulseAlpha.snapTo(0f)
        val peak = when {
            state.activating -> 1.15f
            state.pulseLevel >= 2 -> 1.11f
            else -> 1.08f
        }
        pulseAlpha.animateTo(if (state.activating) 1f else 0.72f, tween(90))
        pulseScale.animateTo(peak, tween(110, easing = FastOutSlowInEasing))
        pulseScale.animateTo(1f, tween(if (state.activating) 190 else 150, easing = FastOutSlowInEasing))
        pulseAlpha.animateTo(0f, tween(170))
    }

    // Plan 27: a small, slightly oval, see-through glass bubble with a thin shine and Cyclone's mark; taps light it up.
    Box(
        modifier.size(OverlayChromeContract.IDLE_VISUAL_WIDTH_DP.dp, OverlayChromeContract.IDLE_VISUAL_HEIGHT_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        val response = pulseAlpha.value
        Box(
            Modifier.size(58.dp, 50.dp)
                .graphicsLayer {
                    scaleX = pulseScale.value
                    scaleY = pulseScale.value
                }
                .tiltGlass(25.dp, dots = false, thin = true, seeThrough = 0.42f + 0.3f * response),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(com.cyclone.mobile.R.drawable.ic_cyclone_status), null,
                Modifier.size(if (state.activating) 22.dp else 20.dp),
                tint = Color(0xFFE0F5F3).copy(alpha = 0.82f + 0.18f * response),
            )
        }
    }
}

@Composable
private fun ComposerPanel(
    snapshot: OverlayChromeSnapshot,
    minimized: Boolean,
    onAction: (OverlayUserAction) -> Unit,
    onComposerChanged: (String) -> Unit,
    onRequestSubmitted: (String) -> Unit,
    onVoiceInput: () -> Unit,
    onVoiceStop: () -> Unit,
    aiSettings: OverlayAiSettings,
    onAiSettingsChanged: (OverlayAiSettings) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    // The + tools drawer lives in its own window (OverlayToolsSheet); this panel only reads it.
    val accessory by OverlayToolsSheetState.page.collectAsState()
    val sharing by LiveCaptureSessionManager.state.collectAsState()
    val attached by PendingTaskAttachment.present.collectAsState()
    val queued by WorkspaceTasks.requests.state.collectAsState()
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val workspace by WorkspaceTasks.state.collectAsState()
    val clearedCards = com.cyclone.mobile.ui.v32.TaskCardDismissals.cleared(context)
    val task = workspace?.takeIf { com.cyclone.mobile.ui.v32.taskCardVisible(it, clearedCards) }
    val foregroundWorking = snapshot.state == OverlayChromeState.WORKING || snapshot.state == OverlayChromeState.LIVE
    val foregroundApp by WorkingApp.foreground.collectAsState()
    val activeWork = task?.working == true || foregroundWorking
    val imePx = LocalOverlayImeBottomPx.current
    val keyboardHeightDp = with(LocalDensity.current) {
        maxOf(imePx, WindowInsets.ime.getBottom(this)).toDp().value.toInt()
    }
    val panelHeight = SignatureDrawerGeometry.availableHeight(
        LocalConfiguration.current.screenHeightDp, keyboardHeightDp,
    ).coerceAtMost(650)
    // The work panel is only a little taller than the live work card; the conversation above it
    // (the first prompt) stays one scroll away instead of filling half the screen.
    var workCardPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val upperMaxHeight = if (workCardPx > 0) {
        with(density) { workCardPx.toDp() } + OverlayChromeContract.WORK_PANEL_PEEK_DP.dp
    } else androidx.compose.ui.unit.Dp.Unspecified
    // Cyclone's own glass keeps the Trace Field underneath it: report where it is on screen.
    val hostView = LocalView.current
    DisposableEffect(Unit) {
        onDispose { com.cyclone.mobile.ui.overlay.tracefield.TraceFieldRuntime.chromeBounds(null) }
    }

    LaunchedEffect(task?.taskId, task?.working, foregroundWorking) {
        if (activeWork) {
            focusManager.clearFocus()
            OverlayToolsSheetState.close()
        }
    }

    val submit = {
        if (snapshot.composerText.isNotBlank()) {
            onRequestSubmitted(snapshot.composerText)
            focusManager.clearFocus()
        }
    }

    val upperVisible = !minimized && (task != null || foregroundWorking || queued.isNotEmpty() ||
        sharing.phase != ScreenSharePhase.OFF || attached)
    // Plan 27: folded while working, the Ask bar becomes the island with the live status.
    val island = minimized && activeWork
    val mindTaskId = task?.taskId?.takeIf { it.startsWith(com.cyclone.mobile.task.TaskEngines.MIND_TASK_PREFIX) }
    // Plan 38: while a Mind mission runs, typed text + send offers Steer / Queue / Parallel for the task on this card
    // (D7), and the empty bar's button is the real pause (the mission holds at its next step) and the two-tap stop.
    val mindLive by com.cyclone.mobile.mind.mission.MindMissions.live.collectAsState()
    val mindTask = mindTaskId ?: mindLive?.let { "mission-${it.id}" }
    val mindPaused = mindLive?.paused == true
    var askOptions by remember { mutableStateOf<List<com.cyclone.mobile.task.AskWhileWorking.Option>?>(null) }
    LaunchedEffect(mindTask) { if (mindTask == null) askOptions = null }
    val sendWhileWorking: (() -> Unit)? = mindTask?.let { id -> {
        focusManager.clearFocus()
        keyboard?.hide()
        askOptions = com.cyclone.mobile.mind.mission.MindMissions.askOptions(context, id, snapshot.composerText)
    } }
    val chooseAsk: (com.cyclone.mobile.task.AskWhileWorking.Choice) -> Unit = { choice ->
        val text = snapshot.composerText
        askOptions = null
        val target = com.cyclone.mobile.mind.mission.MindMissions.viewedTaskId(mindTask)
        if (target != null && text.isNotBlank()) {
            val result = com.cyclone.mobile.task.TaskCommands.send(context, target, com.cyclone.mobile.task.AskWhileWorking.command(choice, text))
            if (result.handled) onComposerChanged("")
            else android.widget.Toast.makeText(context, result.detail, android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val islandSnapshot = task?.let { remember(it) { com.cyclone.mobile.runtime.background.TaskPresentationProjector.project(it) } }
    // After the owner moves the task behind the screen, the stack settles into the island (their home screen shows).
    val planeState by com.cyclone.mobile.runtime.plane.MissionPlanes.ui.collectAsState()
    val moveSeq = planeState?.moveSeq ?: 0L
    var seenMove by remember { mutableStateOf(moveSeq) }
    LaunchedEffect(moveSeq) {
        if (moveSeq != seenMove) {
            seenMove = moveSeq
            when (planeState?.movedTo) {
                com.cyclone.mobile.runtime.plane.PlaneKind.BACKGROUND -> if (!minimized) onAction(OverlayUserAction.MINIMIZE)
                com.cyclone.mobile.runtime.plane.PlaneKind.SCREEN -> if (minimized) onAction(OverlayUserAction.ASK_CYCLONE)
                null -> Unit
            }
        }
    }
    SignatureOverlayDrawer(
        expanded = upperVisible,
        minimized = minimized,
        onCollapse = {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            OverlayToolsSheetState.close()
            onAction(OverlayUserAction.MINIMIZE)
        },
        onExpand = { onAction(OverlayUserAction.ASK_CYCLONE) },
        modifier = Modifier.fillMaxWidth().heightIn(max = panelHeight.dp).padding(horizontal = 16.dp)
            .onGloballyPositioned { coordinates ->
                val origin = IntArray(2).also { hostView.getLocationOnScreen(it) }
                val bounds = coordinates.boundsInWindow()
                com.cyclone.mobile.ui.overlay.tracefield.TraceFieldRuntime.chromeBounds(
                    android.graphics.RectF(
                        bounds.left + origin[0], bounds.top + origin[1],
                        bounds.right + origin[0], bounds.bottom + origin[1],
                    ),
                )
            },
        upperMaxHeight = upperMaxHeight,
        top = mindTaskId?.let { id -> { PlaneRow(id) } },
        composer = {
            AnimatedContent(
                targetState = island,
                transitionSpec = {
                    (fadeIn(tween(220)) + slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 5 })
                        .togetherWith(fadeOut(tween(140)))
                },
                label = "Ask bar or island",
            ) { showIsland ->
                if (showIsland) {
                    val lines = islandSnapshot?.let {
                        OverlayGlassCopy.island(it.currentMilestone, it.title, it.completedCount, it.totalCount)
                    } ?: OverlayGlassCopy.island(snapshot.statusMessage ?: snapshot.bullets.firstOrNull(), "Cyclone is working", 0, null)
                    WorkIsland(
                        // The app Cyclone works in: the task's own, or for an on-screen task the app on screen.
                        appPackage = if (task != null) WorkingApp.forTask(task.taskId, task.packageName) else foregroundApp,
                        lines = lines,
                        fraction = islandSnapshot?.progressFraction,
                        working = foregroundWorking || task?.working == true || snapshot.userPaused || mindTask != null,
                        paused = if (mindTask != null) mindPaused else snapshot.userPaused,
                        taskKey = mindTask ?: snapshot.sessionId,
                        onOpen = { onAction(OverlayUserAction.ASK_CYCLONE) },
                        onPause = {
                            // Alpha 91: a touch that is Cyclone's own injected gesture is never the owner's Pause.
                            if (OverlayGesturePassthrough.injectedRecently()) return@WorkIsland
                            if (mindTask != null) com.cyclone.mobile.task.TaskCommands.send(context, mindTask,
                                if (mindPaused) com.cyclone.mobile.task.TaskCommand.Unpause else com.cyclone.mobile.task.TaskCommand.Pause)
                            else onAction(OverlayUserAction.TAKE_CONTROL)
                        },
                        onStop = { if (!OverlayGesturePassthrough.injectedRecently()) onAction(OverlayUserAction.STOP_TASK) },
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    askOptions?.takeIf { snapshot.composerText.isNotBlank() }?.let { options ->
                        com.cyclone.mobile.ui.v32.CycloneAskOptions(options, chooseAsk, ink = com.cyclone.mobile.ui.v32.SignatureInk, accent = Color(0xFF83DBD7),
                            surface = Color(0xE6141A22))
                    }
                    OverlayAppleComposerBar(
                        text = snapshot.composerText,
                        onTextChanged = onComposerChanged,
                        focusRequester = focusRequester,
                        onFocusChanged = { focused -> if (focused) askOptions = null },
                        placeholder = when {
                            snapshot.voiceListening -> OverlayCopy.LISTENING
                            else -> OverlayCopy.COMPOSER
                        },
                        menuOpen = accessory != ComposerAccessory.NONE,
                        voiceListening = snapshot.voiceListening,
                        working = foregroundWorking || snapshot.userPaused || mindTask != null,
                        paused = if (mindTask != null) mindPaused else snapshot.userPaused,
                        taskKey = mindTask ?: snapshot.sessionId,
                        onPause = {
                            // Alpha 91: a touch that is Cyclone's own injected gesture is never the owner's Pause.
                            if (OverlayGesturePassthrough.injectedRecently()) return@OverlayAppleComposerBar
                            if (mindTask != null) com.cyclone.mobile.task.TaskCommands.send(context, mindTask,
                                if (mindPaused) com.cyclone.mobile.task.TaskCommand.Unpause else com.cyclone.mobile.task.TaskCommand.Pause)
                            else onAction(OverlayUserAction.TAKE_CONTROL)
                        },
                        onStop = { if (!OverlayGesturePassthrough.injectedRecently()) onAction(OverlayUserAction.STOP_TASK) },
                        onSendWhileWorking = sendWhileWorking,
                        onMenu = {
                            focusManager.clearFocus()
                            keyboard?.hide()
                            OverlayToolsSheetState.toggle(ComposerAccessory.ATTACHMENTS)
                        },
                        onDictate = onVoiceInput,
                        onStopDictation = onVoiceStop,
                        onPrimary = {
                            if (!foregroundWorking && !snapshot.userPaused && snapshot.composerText.isNotBlank()) submit()
                        },
                    )
                    }
                }
            }
        },
    ) {
        val minimize = { onAction(OverlayUserAction.MINIMIZE) }
        if (task != null || foregroundWorking) {
            Box(Modifier.fillMaxWidth().onSizeChanged { workCardPx = it.height }) {
                when {
                    task != null -> OverlayWorkCard(task, minimize)
                    else -> OverlayForegroundCard(snapshot, minimize) { onAction(OverlayUserAction.STOP_TASK) }
                }
            }
        }
        // Plan 38 §4: after a diversion the card shows the branch: "Plan v2 · Changed course", the dropped step struck
        // through and the new route with its reason. Nothing shows while the plan has not changed.
        mindLive?.takeIf { mindTaskId == "mission-${it.id}" && it.planVersion > 1 }?.let { mission ->
            Box(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(Color(0xB3141A22)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                com.cyclone.mobile.ui.v32.CyclonePlanView(mission, compact = true, ink = com.cyclone.mobile.ui.v32.SignatureInk,
                    dim = com.cyclone.mobile.ui.v32.SignatureInk.copy(alpha = .62f), accent = Color(0xFF83DBD7))
            }
        }
        if (queued.isNotEmpty()) CyclonePendingRequests { onAction(OverlayUserAction.MINIMIZE) }

        if (sharing.phase != ScreenSharePhase.OFF) {
            OverlayAppleStatusPill(
                text = when (sharing.phase) {
                    ScreenSharePhase.LIVE -> "Sharing screen"
                    ScreenSharePhase.REQUESTING_PERMISSION -> "Waiting for permission"
                    ScreenSharePhase.STARTING -> sharing.message ?: "Starting screen share"
                    ScreenSharePhase.STOPPING -> "Stopping screen share"
                    ScreenSharePhase.ERROR -> sharing.message ?: "Screen sharing failed"
                    ScreenSharePhase.REVOKED -> "Screen sharing ended"
                    ScreenSharePhase.OFF -> "Screen sharing off"
                },
                actionLabel = if (sharing.active) "Stop" else null,
                onAction = if (sharing.active) ({ LiveCaptureService.stop(context) }) else null,
            )
        }

        if (attached) {
            OverlayAppleStatusPill(
                text = "Reference attached",
                actionLabel = "Remove",
                onAction = { PendingTaskAttachment.take() },
            )
        }
    }
}

@Composable
private fun GatePanel(
    snapshot: OverlayChromeSnapshot,
    onAction: (OverlayUserAction) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 12.dp),
    ) {
        OverlayAppleGlass(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 28.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Confirmation needed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    CycloneTrayIconAction(onClick = { onAction(OverlayUserAction.EXIT) }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Close, "Cancel", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(OverlayCopy.GATE, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { onAction(OverlayUserAction.GATE_CONFIRM) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(OverlayCopy.CONFIRM) }
                if (snapshot.gateClass != null) {
                    Text(
                        "Cyclone will authorize only this exact ${snapshot.gateClass.wire} action.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

internal enum class ComposerAccessory {
    NONE, ATTACHMENTS, MODEL;
    fun toggle(next: ComposerAccessory): ComposerAccessory = if (this == next) NONE else next
}

/** Quiet inline status kept for callers/tests outside the Apple-style composer path. */
@Composable
internal fun ScreenSharePill(state: com.cyclone.mobile.capture.ScreenShareState, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (state.active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Text(
            when (state.phase) {
                ScreenSharePhase.LIVE -> "Sharing screen"
                ScreenSharePhase.REQUESTING_PERMISSION -> "Waiting for permission"
                ScreenSharePhase.STARTING -> state.message ?: "Starting screen share"
                ScreenSharePhase.STOPPING -> "Stopping screen share"
                ScreenSharePhase.ERROR -> state.message ?: "Screen sharing failed"
                ScreenSharePhase.REVOKED -> "Screen sharing ended"
                ScreenSharePhase.OFF -> "Screen sharing off"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
        )
        if (state.active) {
            TextButton(
                onClick = onStop,
                enabled = state.phase != ScreenSharePhase.STOPPING,
                modifier = Modifier.heightIn(min = 40.dp),
            ) { Text("Stop") }
        }
    }
}
