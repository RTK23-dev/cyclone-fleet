package com.cyclone.mobile.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.runtime.plane.MissionPlanes
import com.cyclone.mobile.runtime.plane.PlaneKind
import com.cyclone.mobile.runtime.plane.PlaneUi
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskCommands
import com.cyclone.mobile.task.TaskEngines
import com.cyclone.mobile.ui.v32.CycloneSignatureGlass
import com.cyclone.mobile.ui.v32.SignatureInk
import com.cyclone.mobile.ui.v32.SignatureMuted

private val PillTeal = Color(0xFF83DBD7)

/** What the pill's symbol shows; pure so the states can be tested without Compose. */
enum class PlanePillLook { SCREEN, BACKGROUND, SWITCHING, UNAVAILABLE, WAITING }

object PlanePillModel {
    fun look(ui: PlaneUi): PlanePillLook = when {
        ui.switching -> PlanePillLook.SWITCHING
        ui.waitingFor != null -> PlanePillLook.WAITING
        ui.kind == PlaneKind.BACKGROUND -> PlanePillLook.BACKGROUND
        !ui.available -> PlanePillLook.UNAVAILABLE
        else -> PlanePillLook.SCREEN
    }

    /** What a tap does; null when a tap cannot do anything useful right now (the long-press explains). */
    fun tap(ui: PlaneUi): TaskCommand? = when (look(ui)) {
        PlanePillLook.SWITCHING, PlanePillLook.UNAVAILABLE -> null
        PlanePillLook.WAITING -> TaskCommand.StartNow
        PlanePillLook.SCREEN -> TaskCommand.MoveToBackground
        PlanePillLook.BACKGROUND -> TaskCommand.MoveToForeground
    }

    fun describe(ui: PlaneUi): String = when (look(ui)) {
        PlanePillLook.SCREEN -> "Cyclone works on your screen"
        PlanePillLook.BACKGROUND -> "Cyclone works in the background" + (ui.appLabel?.let { " in $it" } ?: "")
        PlanePillLook.SWITCHING -> "Moving the task"
        PlanePillLook.UNAVAILABLE -> "Background work is unavailable"
        PlanePillLook.WAITING -> "Waiting for you to finish with ${ui.waitingFor}"
    }

    fun action(ui: PlaneUi): String = when (look(ui)) {
        PlanePillLook.SCREEN -> "Move to background"
        PlanePillLook.BACKGROUND -> "Show on screen"
        PlanePillLook.SWITCHING -> "Please wait"
        PlanePillLook.UNAVAILABLE -> "Shows why"
        PlanePillLook.WAITING -> "Start now"
    }
}

/**
 * The planes pill (plan 25 §4.5): a small glass capsule with one symbol, two stacked screens. The front screen lit
 * means Cyclone works on your screen; the back screen lit means it works behind it. Tap switches; long-press explains
 * (and offers "Always in background" when an app was held back). Every press goes through Task Kit.
 */
@Composable
internal fun PlanePill(modifier: Modifier = Modifier) {
    val state by MissionPlanes.ui.collectAsState()
    val ui = state ?: return
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var explained by remember(ui.missionId) { mutableStateOf(false) }
    val look = PlanePillModel.look(ui)
    val taskId = TaskEngines.MIND_TASK_PREFIX + ui.missionId
    val onTap = {
        PlanePillModel.tap(ui)?.let { command ->
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            TaskCommands.send(context, taskId, command)
        } ?: run { explained = true }
        Unit
    }
    val onLong = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        explained = !explained
    }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CycloneSignatureGlass(
            modifier = Modifier.size(width = 58.dp, height = 40.dp)
                .semantics {
                    role = Role.Switch
                    contentDescription = PlanePillModel.action(ui)
                    stateDescription = PlanePillModel.describe(ui)
                    onClick(label = PlanePillModel.action(ui)) { onTap(); true }
                    onLongClick(label = "Explain") { onLong(); true }
                }
                .pointerInput(ui) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLong() }) },
            textured = false,
            solidBacking = true,
            cornerRadius = 20.dp,
        ) {
            Box(Modifier.align(Alignment.Center)) { PlaneSymbol(look) }
        }
        AnimatedVisibility(explained, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
            OverlayAppleGlass(Modifier.widthIn(max = 280.dp), cornerRadius = 18.dp) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(PlanePillModel.describe(ui), color = SignatureInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    ui.note?.let { Text(it, color = SignatureMuted, fontSize = 12.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (look == PlanePillLook.SCREEN && ui.note?.contains("Long-press to allow") == true) {
                            Text("Always in background", color = PillTeal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable(role = Role.Button) {
                                    TaskCommands.send(context, taskId, TaskCommand.AllowBackground)
                                    TaskCommands.send(context, taskId, TaskCommand.MoveToBackground)
                                    explained = false
                                })
                        }
                        Text("Close", color = SignatureMuted, fontSize = 13.sp,
                            modifier = Modifier.clickable(role = Role.Button) { explained = false })
                    }
                }
            }
        }
    }
}

/** Two stacked screens. The lit one is where Cyclone works; while moving, the light slides between them. */
@Composable
internal fun PlaneSymbol(look: PlanePillLook) {
    val target = when (look) {
        PlanePillLook.BACKGROUND -> 1f
        PlanePillLook.WAITING -> 0.5f
        else -> 0f
    }
    val settled by animateFloatAsState(target, tween(320, easing = FastOutSlowInEasing), label = "Plane light")
    val moving = rememberInfiniteTransition(label = "Plane switching")
    val sweep by moving.animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "Plane sweep")
    val light = if (look == PlanePillLook.SWITCHING) sweep else settled
    val dim = if (look == PlanePillLook.UNAVAILABLE) .38f else 1f
    Canvas(Modifier.size(width = 26.dp, height = 22.dp)) {
        val w = size.width * .62f
        val h = size.height * .72f
        val back = Offset(size.width - w, 0f)
        val front = Offset(0f, size.height - h)
        screen(back, Size(w, h), light * dim)
        screen(front, Size(w, h), (1f - light) * dim)
        if (look == PlanePillLook.UNAVAILABLE) {
            drawLine(SignatureMuted, Offset(size.width * .1f, size.height * .95f), Offset(size.width * .9f, size.height * .05f),
                strokeWidth = 1.6.dp.toPx())
        }
    }
}

private fun DrawScope.screen(topLeft: Offset, size: Size, lit: Float) {
    val radius = CornerRadius(3.dp.toPx())
    // The unlit screen is an outline; the lit one fills with the Ask capsule's teal.
    drawRoundRect(Color(0xFF0E2A2E), topLeft, size, radius)
    if (lit > 0.02f) drawRoundRect(PillTeal.copy(alpha = .85f * lit), topLeft, size, radius)
    drawRoundRect(SignatureInk.copy(alpha = .55f + .45f * lit), topLeft, size, radius, style = Stroke(1.3.dp.toPx()))
}

/**
 * Plan 26 (A42-7): an approval for a task working in the background shows what is being approved: a live glimpse of
 * the background screen, and Show on my screen (a Task Kit move). Nothing shows for tasks on the owner's screen.
 */
@Composable
fun BackgroundGlimpse(taskId: String, modifier: Modifier = Modifier) {
    val state by MissionPlanes.ui.collectAsState()
    val ui = state?.takeIf { TaskEngines.MIND_TASK_PREFIX + it.missionId == taskId && it.kind == PlaneKind.BACKGROUND } ?: return
    val sessionId = ui.backgroundSessionId ?: return
    val context = LocalContext.current
    var image by remember(sessionId) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(sessionId) {
        while (true) {
            com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.glimpse(sessionId)?.let { bitmap ->
                image = bitmap.asImageBitmap()
            }
            kotlinx.coroutines.delay(1_000)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("On the background screen" + (ui.appLabel?.let { " · $it" } ?: ""), color = SignatureMuted, fontSize = 12.sp)
        image?.let { bitmap ->
            androidx.compose.foundation.Image(bitmap, contentDescription = "What Cyclone sees on the background screen",
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        }
        Text("Show on my screen", color = PillTeal, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(role = Role.Button) { TaskCommands.send(context, taskId, TaskCommand.MoveToForeground) }
                .padding(vertical = 6.dp))
    }
}
