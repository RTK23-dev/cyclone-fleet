package com.cyclone.mobile.ui.v32

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.runtime.background.TaskPresentationProjector
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskCommands
import com.cyclone.mobile.task.TaskEngines
import com.cyclone.mobile.ui.overlay.OverlayChromeSnapshot
import com.cyclone.mobile.ui.overlay.OverlayForegroundCard
import com.cyclone.mobile.ui.overlay.OverlayGlassCopy
import com.cyclone.mobile.ui.overlay.OverlayOwnerCard
import com.cyclone.mobile.ui.overlay.OverlayStackGeometry
import com.cyclone.mobile.ui.overlay.OverlayWorkCard
import com.cyclone.mobile.ui.overlay.PlaneRow
import com.cyclone.mobile.ui.overlay.WorkIsland
import com.cyclone.mobile.ui.overlay.glass.GlassRoundButton
import com.cyclone.mobile.ui.overlay.glass.TiltGlassTheme
import com.cyclone.mobile.ui.overlay.glass.VoiceOrbButton
import com.cyclone.mobile.ui.overlay.glass.LocalGlassPalette
import com.cyclone.mobile.ui.overlay.glass.tiltGlass
import com.cyclone.mobile.ui.v32.ask.askWhorls
import com.cyclone.mobile.ui.v32.ask.GlassTier
import com.cyclone.mobile.ui.v32.ask.askGlass

/**
 * Plan 27 inside the app: the same glass stack as the overlay. The plane pill sits above the task, the card folds into
 * an island (its collapse button) and opens again with a tap, and a moment that needs the owner takes the card's
 * place. Every button goes through Task Kit.
 */
@Composable
internal fun InAppTaskStack(task: WorkspaceTaskUi) {
    val context = LocalContext.current
    val moment = com.cyclone.mobile.owner.rememberOwnerMoment()?.takeIf { it.taskId == task.taskId }
    var folded by rememberSaveable(task.taskId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (task.taskId.startsWith(TaskEngines.MIND_TASK_PREFIX)) {
            PlaneRow(task.taskId)
            Spacer(Modifier.height(OverlayStackGeometry.PILL_GAP_DP.dp))
        }
        AnimatedContent(
            targetState = when {
                moment != null -> 0
                folded -> 2
                else -> 1
            },
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
            label = "In-app task glass",
        ) { shape ->
            when (shape) {
                0 -> moment?.let { OverlayOwnerCard(it) }
                2 -> {
                    val snapshot = TaskPresentationProjector.project(task)
                    WorkIsland(
                        appPackage = com.cyclone.mobile.ui.overlay.WorkingApp.forTask(task.taskId, task.packageName),
                        lines = OverlayGlassCopy.island(snapshot.currentMilestone, snapshot.title, snapshot.completedCount, snapshot.totalCount),
                        fraction = snapshot.progressFraction,
                        working = task.working,
                        paused = false,
                        taskKey = task.taskId,
                        onOpen = { folded = false },
                        onPause = { TaskCommands.send(context, task.taskId, TaskCommand.Pause) },
                        onStop = { TaskCommands.send(context, task.taskId, TaskCommand.Stop) },
                    )
                }
                else -> OverlayWorkCard(task) { folded = true }
            }
        }
    }
}

/** The grabber colour of a glass sheet: the working card's grabber. */
internal val GlassMutedHandle = Color(0xFFA6CCCA).copy(alpha = 0.38f)

/**
 * A bottom sheet on the working card's glass (the in-app + drawer and its model page): the card's glass and radius,
 * [handle] on top, then one inner veil the content sits on, in the glass palette.
 */
@Composable
internal fun InAppGlassSheet(
    modifier: Modifier = Modifier,
    handle: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val radius = OverlayStackGeometry.CARD_RADIUS_DP.dp
    Box(modifier.fillMaxWidth().tiltGlass(radius, palette = LocalGlassPalette.current).clip(RoundedCornerShape(radius))) {
        TiltGlassTheme {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                handle()
                Box(Modifier.fillMaxWidth().background(Color(0x4D04181D), RoundedCornerShape(22.dp)).padding(12.dp)) {
                    content()
                }
            }
        }
    }
}

/** The on-screen (classic) task in the app, on the same glass. */
@Composable
internal fun InAppForegroundCard(snapshot: OverlayChromeSnapshot) {
    OverlayForegroundCard(snapshot, onMinimize = {}) {
        com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.dispatch(com.cyclone.mobile.ui.overlay.OverlayUserAction.STOP_TASK)
    }
}

/**
 * The in-app Ask bar on the overlay's glass: + and send as lit round buttons, the words on a soft pill that fills the
 * space between them, and the voice button that becomes the glowing orb while voice mode is open. [onExpand] adds the
 * small open button of the folded composer.
 */
@Composable
internal fun GlassComposerBar(
    text: String,
    onTextChanged: (String) -> Unit,
    placeholder: String,
    onAdd: () -> Unit,
    onVoice: () -> Unit,
    onSend: () -> Unit,
    onVoiceStop: () -> Unit = {},
    sendEnabled: Boolean,
    busy: Boolean,
    voiceActive: Boolean,
    modifier: Modifier = Modifier,
    fieldDescription: String = "Ask Cyclone composer",
    maxLines: Int = 4,
    onExpand: (() -> Unit)? = null,
) {
    // R3: on the AI screen the bar is smoked glass over the rain, with the fingerprint dots in silver at both ends.
    val askBackdrop = com.cyclone.mobile.ui.v32.ask.LocalAskBackdrop.current
    val material = if (askBackdrop != null) {
        // R5: the Ask bar is navigation glass (the darker chrome tier), above every content panel.
        Modifier.askGlass(33.dp, GlassTier.CHROME, 0.7f, com.kyant.capsule.ContinuousCapsule).askWhorls()
    } else {
        Modifier.tiltGlass(33.dp)
    }
    val veil = if (askBackdrop != null) com.cyclone.mobile.ui.v32.ask.AskGlass.Veil else Color(0x4D04181D)
    Box(modifier.fillMaxWidth().heightIn(min = 66.dp).then(material)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 66.dp).clip(RoundedCornerShape(33.dp)).padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GlassRoundButton("Add attachment", onAdd, enabled = !busy) { SignatureIcon(SignatureGlyph.ADD) }
            BasicTextField(
                value = text,
                onValueChange = onTextChanged,
                modifier = Modifier.weight(1f).heightIn(min = 46.dp, max = 104.dp)
                    .clip(RoundedCornerShape(23.dp)).background(veil)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
                    .semantics { contentDescription = fieldDescription },
                maxLines = maxLines,
                textStyle = TextStyle(color = if (askBackdrop != null) Color.White else SignatureInk, fontSize = 17.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(if (askBackdrop != null) Color.White else Color(0xFF83DBD7)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (sendEnabled) onSend() }),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(placeholder, color = SignatureInk, fontSize = 17.sp, maxLines = 1)
                        field()
                    }
                },
            )
            // Voice mode is its own screen here, so the button opens it with a tap (no push to talk).
            VoiceOrbButton(
                listening = voiceActive, enabled = !busy, description = "Voice mode",
                onStart = onVoice, onStop = onVoiceStop, pushToTalk = false,
            ) { tint -> SignatureIcon(SignatureGlyph.MIC, color = tint) }
            GlassRoundButton("Send request", onSend, enabled = sendEnabled) {
                SignatureIcon(SignatureGlyph.SEND, color = SignatureInk.copy(alpha = if (sendEnabled) 1f else .45f))
            }
            onExpand?.let { expand ->
                GlassRoundButton("Expand chat", expand, size = 36.dp) {
                    Icon(Icons.Rounded.KeyboardArrowUp, null, Modifier.size(20.dp), tint = SignatureInk)
                }
            }
        }
    }
}

