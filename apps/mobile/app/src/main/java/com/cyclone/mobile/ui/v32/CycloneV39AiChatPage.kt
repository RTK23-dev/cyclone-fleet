package com.cyclone.mobile.ui.v32

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.OpenRouterModelPreset
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.QuickAgentConfig
import com.cyclone.mobile.ai.QuickAgentResult
import com.cyclone.mobile.ai.RequestDispatch
import com.cyclone.mobile.ai.RequestIntent
import com.cyclone.mobile.ai.RequestIntentRouter
import com.cyclone.mobile.ai.model.ModelRegistry
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.overlay.glass.GlassPalette
import com.cyclone.mobile.ui.overlay.glass.LocalGlassPalette
import com.cyclone.mobile.ui.v32.ask.AskBackground
import com.cyclone.mobile.ui.v32.ask.AskCopy
import com.cyclone.mobile.ui.v32.ask.AskDim
import com.cyclone.mobile.ui.v32.ask.AskGlass
import com.cyclone.mobile.ui.v32.ask.AskHeader
import com.cyclone.mobile.ui.v32.ask.AskHome
import com.cyclone.mobile.ui.v32.ask.AskLogoPanel
import com.cyclone.mobile.ui.v32.ask.AskMenuDrawer
import com.cyclone.mobile.ui.v32.ask.AskModelSheet
import com.cyclone.mobile.ui.v32.ask.AskRainField
import com.cyclone.mobile.ui.v32.ask.AskScrim
import com.cyclone.mobile.ui.v32.ask.AskVideoField
import com.cyclone.mobile.ui.v32.ask.AskGreetingPool
import com.cyclone.mobile.ui.v32.ask.LocalAskBackdrop
import com.cyclone.mobile.ui.v32.ask.LocalAskWorld
import com.cyclone.mobile.ui.v32.ask.LocalAskShine
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import com.cyclone.mobile.ui.overlay.PendingTaskAttachment
import com.cyclone.mobile.ui.overlay.TaskAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class V39ChatRole { USER, CYCLONE }
internal data class V39ChatMessage(val id: Long, val role: V39ChatRole, val text: String, val ok: Boolean? = null)

/** Process-session chat only. Brain owns persistent run diagnostics. The Ask page is the live Gemini-style canvas. Plus stays a short sheet. */
internal object V39AiChatSessionRuntime {
    private val nextId = AtomicLong(1L)
    val messages = mutableStateListOf<V39ChatMessage>()
    val submitGate = V39AiSubmitGate()
    var pendingRequest by mutableStateOf("")
    /** R6: a run picked in the smart search; the page opens its menu on that run. */
    var pendingOpenRun by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")

    fun append(role: V39ChatRole, text: String, ok: Boolean? = null) {
        text.trim().takeIf(String::isNotBlank)?.let {
            messages += V39ChatMessage(nextId.getAndIncrement(), role, it, ok)
        }
    }
}

internal object V39AiChatContract {
    const val PREFS = "cyclone_ai"
    const val MODEL_KEY = "openrouter_model"
    const val PLACEHOLDER = "Ask Cyclone…"

    fun normalizedRequest(value: String) = value.trim()
    fun modelForStored(stored: String?): OpenRouterModelPreset =
        OpenRouterModelPresets.byId(stored.orEmpty())
    fun storageId(model: OpenRouterModelPreset): String = ModelRegistry.profileForPreset(model)?.cycloneId ?: model.id

    fun config(modelId: String, accessProfile: CycloneAiAccessProfile): QuickAgentConfig {
        val model = modelForStored(modelId)
        return QuickAgentConfig(
            model = model,
            visionModel = model,
            safeMode = accessProfile != CycloneAiAccessProfile.FULL,
            accessProfile = accessProfile,
        )
    }

    fun finalStatus(result: QuickAgentResult) = if (result.ok) "Completed and checked" else "Stopped safely"
}

internal class V39AiSubmitGate {
    private val active = AtomicBoolean(false)

    fun tryAccept(rawRequest: String, hasKey: Boolean): String? {
        val request = V39AiChatContract.normalizedRequest(rawRequest)
        if (request.isBlank() || !hasKey || !active.compareAndSet(false, true)) return null
        return request
    }

    fun complete() = active.set(false)
}

@Composable
internal fun V39AiChatPage(
    context: Context,
    refreshTick: Int,
    onSettingsSection: (String) -> Unit = {},
    onRoutines: () -> Unit = {},
    onBrain: () -> Unit = {},
    onSettings: () -> Unit,
) {
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val task by WorkspaceTasks.state.collectAsState()
    val liveMission by com.cyclone.mobile.mind.mission.MindMissions.live.collectAsState()
    var askOptions by remember { mutableStateOf<List<com.cyclone.mobile.task.AskWhileWorking.Option>?>(null) }
    LaunchedEffect(liveMission?.id) { if (liveMission == null) askOptions = null }
    val queuedRequests by WorkspaceTasks.requests.state.collectAsState()
    val foregroundActivity by OverlayChromeRuntime.activity.collectAsState()
    val foregroundSnapshot = remember(foregroundActivity) { OverlayChromeRuntime.snapshot() }
    val foregroundWorking = task == null && foregroundActivity in setOf(OverlayChromeState.WORKING, OverlayChromeState.LIVE)
    val attached by PendingTaskAttachment.present.collectAsState()
    val prefs = context.getSharedPreferences(V39AiChatContract.PREFS, Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val session = V39AiChatSessionRuntime
    var chatJob by remember { mutableStateOf<Job?>(null) }
    var composer by rememberSaveable { mutableStateOf("") }
    var toolsOpen by remember { mutableStateOf(false) }
    var voiceOpen by remember { mutableStateOf(false) }
    var modelMenuOpen by remember { mutableStateOf(false) }
    // R3: the burger opens the menu drawer, the mark opens the logo panel; a run tapped on home opens in the drawer.
    var menuOpen by remember { mutableStateOf(false) }
    var logoOpen by remember { mutableStateOf(false) }
    var openRun by remember { mutableStateOf<String?>(null) }
    var drawerCollapsed by rememberSaveable { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val catalogRevision by com.cyclone.mobile.ai.OpenRouterCatalogStore.revision.collectAsState()
    var selectedModelId by rememberSaveable(catalogRevision, refreshTick) {
        mutableStateOf(com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context))
    }
    var reasoningEffort by rememberSaveable {
        mutableStateOf(prefs.getString("openrouter_reasoning_effort", "medium") ?: "medium")
    }
    val hasKey = remember(refreshTick) { OpenRouterSecretStore.hasKey(context) }
    val previewRoute = remember(composer, attached) { RequestIntentRouter.route(composer, hasAttachment = attached) }
    val emptyCanvas = session.messages.isEmpty() && !session.busy && task == null && !foregroundWorking

    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        voiceOpen = false
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                composer = listOf(composer, it).filter(String::isNotBlank).joinToString(" ")
            }
        }
    }

    fun restoreAttachmentAfterChatFailure(attachment: TaskAttachment?) {
        if (attachment != null && !PendingTaskAttachment.present.value) PendingTaskAttachment.set(attachment)
    }

    fun persistAiControls(modelId: String, effort: String) {
        selectedModelId = modelId
        reasoningEffort = effort
        prefs.edit()
            .putString(V39AiChatContract.MODEL_KEY, modelId)
            .putString("openrouter_reasoning_effort", effort)
            .apply()
    }

    fun chooseAsk(choice: com.cyclone.mobile.task.AskWhileWorking.Choice) {
        askOptions = null
        val text = V39AiChatContract.normalizedRequest(composer)
        val target = com.cyclone.mobile.mind.mission.MindMissions.viewedTaskId(com.cyclone.mobile.task.AskWhileWorking.viewed.value)
        if (text.isBlank() || target == null) return
        val result = com.cyclone.mobile.task.TaskCommands.send(context, target, com.cyclone.mobile.task.AskWhileWorking.command(choice, text))
        if (result.handled) {
            session.append(V39ChatRole.USER, text)
            session.append(V39ChatRole.CYCLONE, result.detail)
            composer = ""
        } else message = result.detail
    }

    fun submit(raw: String = composer) {
        message = ""
        val normalized = V39AiChatContract.normalizedRequest(raw)
        if (normalized.isBlank()) return
        // Plan 38: while a mission runs, the owner chooses what the text is: a change to the task they are viewing
        // (Steer, or Answer to its question), the next task (Queue) or a task at the same time (Parallel).
        if (com.cyclone.mobile.mind.mission.MindMissions.isLive()) {
            drawerCollapsed = false
            askOptions = com.cyclone.mobile.mind.mission.MindMissions.askOptions(context,
                com.cyclone.mobile.task.AskWhileWorking.viewed.value, normalized)
            return
        }

        if (com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context).isBlank()) {
            message = "Choose models in Settings → Model & API first."
            return
        }
        val route = RequestIntentRouter.route(normalized, hasAttachment = attached)
        val dispatch = if (route.intent == RequestIntent.CHAT) RequestDispatch.CHAT else
            RequestIntentRouter.dispatch(route, canStartPhoneTask = WorkspaceTasks.canStartRequest())

        when (dispatch) {
            RequestDispatch.START_PHONE_TASK -> runCatching {
                check(OverlayChromeRuntime.isAttached()) { "Phone control needs repair. Open Phone control in Settings." }
                OverlayChromeRuntime.submitRequest(normalized)
            }.onSuccess {
                session.append(V39ChatRole.USER, normalized)
                session.append(V39ChatRole.CYCLONE, "Got it. I'll work on that on your phone.")
                composer = ""
            }.onFailure { message = it.message ?: "Couldn't open the phone-task setup." }

            RequestDispatch.QUEUE_PHONE_TASK -> runCatching { WorkspaceTasks.queueRequest(normalized) }
                .onSuccess {
                    session.append(V39ChatRole.USER, normalized)
                    session.append(V39ChatRole.CYCLONE, "I've saved that to Up next. Your current task can keep going.")
                    composer = ""
                    message = "Saved to Up next. Your current task continues."
                }
                .onFailure { message = it.message ?: "Couldn't save this task." }

            RequestDispatch.CHAT -> {
                val request = session.submitGate.tryAccept(normalized, hasKey) ?: run {
                    if (!hasKey) message = "Add an OpenRouter key in Settings to chat."
                    return
                }
                val history = session.messages.map { (if (it.role == V39ChatRole.USER) "user" else "assistant") to it.text }
                val attachment = PendingTaskAttachment.take()
                val model = OpenRouterModelPresets.byId(com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context)).copy(reasoningEffort = reasoningEffort)
                composer = ""
                session.busy = true
                session.status = "Answering…"
                session.append(V39ChatRole.USER, request)
                chatJob = scope.launch {
                    try {
                        val answer = com.cyclone.mobile.ai.CycloneTextChat.answer(context, model, history, request, attachment)
                        session.append(V39ChatRole.CYCLONE, answer)
                        session.status = ""
                    } catch (cancelled: CancellationException) {
                        restoreAttachmentAfterChatFailure(attachment)
                        session.status = "Reply stopped"
                        throw cancelled
                    } catch (error: Exception) {
                        restoreAttachmentAfterChatFailure(attachment)
                        session.status = "Couldn't get a reply"
                        session.append(V39ChatRole.CYCLONE, error.message ?: "Chat failed. Try again.")
                    } finally {
                        session.submitGate.complete()
                        session.busy = false
                        chatJob = null
                    }
                }
            }
        }
    }

    fun startVoice() {
        toolsOpen = false
        modelMenuOpen = false
        voiceOpen = true
        runCatching {
            dictation.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
            )
        }.onFailure {
            voiceOpen = false
            message = "Dictation isn't available. You can type your request."
        }
    }

    fun openCamera() {
        toolsOpen = false
        context.startActivity(
            Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java)
                .putExtra("camera", true),
        )
    }

    fun openFiles() {
        toolsOpen = false
        context.startActivity(Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java))
    }

    fun openPhotos() {
        toolsOpen = false
        context.startActivity(
            Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java)
                .putExtra("photos", true),
        )
    }

    fun shareScreen() {
        toolsOpen = false
        com.cyclone.mobile.capture.LiveScreenShare.start(context)
    }

    LaunchedEffect(keyboardOpen, toolsOpen, modelMenuOpen) {
        if (keyboardOpen) {
            modelMenuOpen = false
            toolsOpen = false
        } else if (toolsOpen || modelMenuOpen) {
            drawerCollapsed = false
        }
    }

    LaunchedEffect(session.pendingOpenRun) {
        session.pendingOpenRun?.let {
            session.pendingOpenRun = null
            openRun = it
            menuOpen = true
        }
    }

    LaunchedEffect(Unit) {
        session.pendingRequest.takeIf(String::isNotBlank)?.let {
            session.pendingRequest = ""
            composer = it
            submit(it)
        }
    }

    // R3 (docs/design/redesign/rounds/R3-ai-screen.md): the Cyclone rain behind smoked glass. The rain is recorded
    // once as the backdrop every glass surface on the page blurs; the shine crosses them every six seconds.
    val backdrop = rememberLayerBackdrop()
    val shine = rememberInfiniteTransition(label = "Ask shine").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(AskGlass.SHINE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "Ask shine phase",
    )
    val modelLabel = remember(catalogRevision, selectedModelId) {
        AskCopy.modelLabel(com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context).takeIf(String::isNotBlank)
            ?.let { com.cyclone.mobile.ai.OpenRouterCatalogStore.preset(context, it).label })
    }
    androidx.activity.compose.BackHandler(menuOpen || logoOpen || modelMenuOpen) {
        menuOpen = false
        logoOpen = false
        modelMenuOpen = false
    }
    val homeCanvas = emptyCanvas && composer.isBlank()
    val backgroundVideo by AskBackground.video.collectAsState()
    LaunchedEffect(Unit) { AskBackground.load(context) }

    // R5: inside the app the glass world owns the rain (with the scene while this page is in front), the backdrop,
    // the shine and the quality; the page only adds its greeting pool. Standing alone it still draws its own.
    val world = LocalAskWorld.current
    CompositionLocalProvider(
        LocalAskBackdrop provides if (world) LocalAskBackdrop.current else backdrop,
        LocalAskShine provides if (world) LocalAskShine.current else shine,
        LocalGlassPalette provides GlassPalette.SMOKE,
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .then(if (world) Modifier else Modifier.background(Color(0xFF050608))),
    ) {
        if (world) {
            if (homeCanvas) AskGreetingPool(Modifier.matchParentSize())
        } else {
            // The owner may pick a video from their phone instead (logo panel › Background video); the rain is the default.
            val video = backgroundVideo
            if (video != null) AskVideoField(video, Modifier.matchParentSize().layerBackdrop(backdrop))
            else AskRainField(Modifier.matchParentSize().layerBackdrop(backdrop))
            AskScrim(Modifier.matchParentSize(), greeting = homeCanvas)
        }
        com.cyclone.mobile.ui.overlay.glass.FollowPhoneLight()

        Column(
            Modifier.fillMaxSize().padding(
                horizontal = CycloneConversationTokens.space16,
                vertical = CycloneConversationTokens.space8,
            ),
            verticalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8),
        ) {
            AskHeader(
                modelLabel = modelLabel,
                modelOpen = modelMenuOpen,
                onMenu = {
                    modelMenuOpen = false
                    logoOpen = false
                    openRun = null
                    menuOpen = true
                },
                onModel = {
                    toolsOpen = false
                    logoOpen = false
                    modelMenuOpen = !modelMenuOpen
                },
                onLogo = {
                    modelMenuOpen = false
                    menuOpen = false
                    logoOpen = !logoOpen
                },
            )

            if (homeCanvas) {
                AskHome(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onSuggestion = { composer = it },
                    onSeeAll = {
                        openRun = null
                        menuOpen = true
                    },
                    onRun = {
                        openRun = it
                        menuOpen = true
                    },
                )
            } else {
                CycloneConversationPanel(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space12),
                        contentPadding = PaddingValues(top = if (keyboardOpen) 2.dp else 4.dp, bottom = 8.dp),
                    ) {
                        if (session.messages.isNotEmpty()) {
                            items(session.messages, key = { it.id }) { V39ChatBubble(it) }
                        }

                        // Plan 27: the task on the same glass as the overlay (pill above, card, island, moments).
                        val mission = liveMission
                        val missionTask = mission?.let { m -> task?.takeIf { it.taskId == "mission-${m.id}" } }
                        if (mission != null && missionTask == null) {
                            item(key = "mission-${mission.id}") { CycloneLiveMissionCard(mission) }
                            item(key = "behind-missions") { CycloneBehindMissions() }
                        } else (missionTask ?: task)?.let { current ->
                            item(key = "current-${current.taskId}") {
                                InAppTaskStack(current)
                            }
                        }
                        if (foregroundWorking) {
                            item(key = "foreground-${foregroundSnapshot.sessionId}") {
                                InAppForegroundCard(foregroundSnapshot)
                            }
                        }
                        if (queuedRequests.isNotEmpty()) {
                            item(key = "queued") { CyclonePendingRequests() }
                        }

                        if (session.busy || session.status.isNotBlank()) {
                            item {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = CycloneConversationTokens.space4,
                                            end = CycloneConversationTokens.space24,
                                            top = CycloneConversationTokens.space4,
                                            bottom = CycloneConversationTokens.space4,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8),
                                ) {
                                    if (session.busy) {
                                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp)
                                    }
                                    Text(
                                        if (session.busy) "Thinking…" else session.status,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            AnimatedContent(
                targetState = drawerCollapsed,
                transitionSpec = {
                    (fadeIn(tween(CycloneConversationTokens.stateTransitionMs)) +
                        slideInVertically(
                            animationSpec = tween(
                                CycloneConversationTokens.stateTransitionMs,
                                easing = FastOutSlowInEasing,
                            ),
                        ) { it / 6 }
                    ).togetherWith(
                        fadeOut(tween(CycloneConversationTokens.fastTransitionMs)) +
                            slideOutVertically(
                                animationSpec = tween(CycloneConversationTokens.fastTransitionMs),
                            ) { it / 8 },
                    )
                },
                label = "Ask Cyclone retraction",
            ) { minimized ->
                if (minimized) {
                val minimizedSendEnabled = composer.isNotBlank() && liveMission != null || composer.isNotBlank() && when (previewRoute.intent) {
                    RequestIntent.PHONE_TASK -> true
                    RequestIntent.CHAT -> hasKey && !session.busy
                }
                CycloneMinimizedComposerBar(
                    text = composer,
                    onTextChanged = { composer = it },
                    onExpand = { drawerCollapsed = false },
                    onAdd = {
                        drawerCollapsed = false
                        modelMenuOpen = false
                        toolsOpen = true
                    },
                    onVoice = { startVoice() },
                    onVoiceStop = { voiceOpen = false },
                    onSubmit = { submit() },
                    sendEnabled = minimizedSendEnabled,
                    busy = session.busy,
                    voiceActive = voiceOpen,
                    modifier = Modifier.padding(bottom = CycloneConversationTokens.space8),
                )
            } else {
                CycloneChatDrawerSurface(
                    containerColor = Color.Transparent,
                    outlineColor = Color.Transparent,
                    onCollapse = {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                        toolsOpen = false
                        modelMenuOpen = false
                        drawerCollapsed = true
                    },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
                ) {
            if (!hasKey) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .90f),
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Key, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("OpenRouter key required for chat", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onSettings) { Text("Settings") }
                    }
                }
            }

            if (message.isNotBlank()) {
                Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (attached) {
                Text("Attachment ready", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }

            if (session.busy) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Answering",
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    TextButton(onClick = { chatJob?.cancel() }) { Text("Stop reply") }
                }
            }
            val sendEnabled = composer.isNotBlank() && liveMission != null || composer.isNotBlank() && when (previewRoute.intent) {
                RequestIntent.PHONE_TASK -> true
                RequestIntent.CHAT -> hasKey && !session.busy
            }
            askOptions?.takeIf { composer.isNotBlank() }?.let { options -> CycloneAskOptions(options, ::chooseAsk) }
            // Plan 27: the same glass Ask bar as the overlay.
            GlassComposerBar(
                text = composer,
                onTextChanged = { composer = it },
                placeholder = V39AiChatContract.PLACEHOLDER,
                onAdd = {
                    toolsOpen = !toolsOpen
                    if (toolsOpen) modelMenuOpen = false
                },
                onVoice = { startVoice() },
                onVoiceStop = { voiceOpen = false },
                onSend = { submit() },
                sendEnabled = sendEnabled,
                busy = session.busy,
                voiceActive = voiceOpen,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
                }
            }
            }

        AnimatedVisibility(
            visible = toolsOpen,
            modifier = Modifier.matchParentSize().zIndex(3f),
            enter = fadeIn() + slideInVertically { it / 5 },
            exit = fadeOut() + slideOutVertically { it / 5 },
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = .28f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { toolsOpen = false },
                        ),
                )
                // Tilt Glass: the + drawer is a working card, like the overlay's.
                InAppGlassSheet(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .heightIn(max = 460.dp)
                        .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                    handle = { CycloneSheetDismissHandle(onDismiss = { toolsOpen = false }, handleColor = GlassMutedHandle) },
                ) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                        CycloneAttachmentTools(
                            onCamera = { openCamera() },
                            onPhotos = { openPhotos() },
                            onFiles = { openFiles() },
                            onShareScreen = { shareScreen() },
                            filesLabel = "Files",
                            extras = listOf(
                                Icons.Rounded.Bolt to "Create a routine",
                                Icons.Rounded.Tune to "Model & intelligence",
                            ),
                            onExtra = { label ->
                                toolsOpen = false
                                when (label) {
                                    "Create a routine" -> composer = "Create a routine"
                                    "Model & intelligence" -> modelMenuOpen = true
                                }
                            },
                        )
                    }
                }
            }
        }

        // R3 state 2: the model selector drops from the header pill.
        if (modelMenuOpen && !keyboardOpen) {
            Box(Modifier.matchParentSize().zIndex(4f)) {
                AskDim { modelMenuOpen = false }
                AskModelSheet(
                    modifier = Modifier.align(Alignment.TopCenter).padding(
                        start = CycloneConversationTokens.space16,
                        end = CycloneConversationTokens.space16,
                        top = 66.dp,
                    ),
                    onChanged = ::persistAiControls,
                    onDismiss = { modelMenuOpen = false },
                )
            }
        }

        // R3 state 3: the menu drawer from the left.
        AnimatedVisibility(
            visible = menuOpen,
            modifier = Modifier.matchParentSize().zIndex(5f),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(160)),
        ) {
            Box(Modifier.fillMaxSize()) {
                AskDim { menuOpen = false }
                AskMenuDrawer(
                    openRun = openRun,
                    newChatEnabled = !session.busy && (session.messages.isNotEmpty() || session.status.isNotBlank()),
                    onNewChat = {
                        session.messages.clear()
                        session.status = ""
                        composer = ""
                        message = ""
                        menuOpen = false
                    },
                    onRoutines = {
                        menuOpen = false
                        onRoutines()
                    },
                    onBrain = {
                        menuOpen = false
                        onBrain()
                    },
                    onSettings = {
                        menuOpen = false
                        onSettings()
                    },
                    onDismiss = { menuOpen = false },
                )
            }
        }

        // R3 state 4: the logo panel from the top right.
        if (logoOpen) {
            Box(Modifier.matchParentSize().zIndex(5f)) {
                AskDim { logoOpen = false }
                AskLogoPanel(
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = CycloneConversationTokens.space16, top = 66.dp),
                    onSettings = { section ->
                        logoOpen = false
                        if (section.isBlank()) onSettings() else onSettingsSection(section)
                    },
                )
            }
        }

        if (voiceOpen) {
            AskCycloneVoiceMode(onClose = { voiceOpen = false })
        }
    }
    }
}

@Composable
private fun AskCycloneVoiceMode(onClose: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "voice")
    val scales = listOf(0.35f, 0.62f, 1f, 0.62f, 0.35f)
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07101F), Color(0xFF123D72), Color(0xFF07101F)),
                ),
            )
            .semantics { contentDescription = "Listening…" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                scales.forEachIndexed { index, base ->
                    val amount by pulse.animateFloat(
                        initialValue = base * 0.45f,
                        targetValue = base,
                        animationSpec = infiniteRepeatable(
                            animation = tween(700 + index * 90, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "bar$index",
                    )
                    Canvas(Modifier.size(width = 6.dp, height = 56.dp)) {
                        val half = size.height / 2f * amount
                        drawLine(
                            color = Color(0xFF9EC5FF),
                            start = Offset(size.width / 2f, size.height / 2f - half),
                            end = Offset(size.width / 2f, size.height / 2f + half),
                            strokeWidth = size.width,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
            Text("Listening…", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("Speak naturally", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .72f))
            Spacer(Modifier.height(24.dp))
            Surface(
                modifier = Modifier
                    .size(64.dp)
                    .clickable(role = Role.Button, onClick = onClose),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 0.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Stop, "Stop listening", Modifier.size(28.dp))
                }
            }
        }
        Icon(
            Icons.Rounded.Close,
            "Close voice",
            Modifier
                .align(Alignment.TopStart)
                .padding(18.dp)
                .size(28.dp)
                .clickable(onClick = onClose),
            tint = Color.White,
        )
    }
}

@Composable
private fun V39ChatBubble(message: V39ChatMessage) {
    CycloneConversationBubble(
        text = message.text,
        speaker = if (message.role == V39ChatRole.USER) {
            CycloneConversationSpeaker.USER
        } else {
            CycloneConversationSpeaker.CYCLONE
        },
    )
    if (message.role == V39ChatRole.CYCLONE && message.ok != null) {
        Text(
            if (message.ok) "Checked" else "Stopped",
            modifier = Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (message.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
