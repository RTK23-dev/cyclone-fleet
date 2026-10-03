package com.cyclone.mobile.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.R
import com.cyclone.mobile.owner.MomentKind
import com.cyclone.mobile.owner.OwnerMoment
import com.cyclone.mobile.runtime.background.SemanticStepState
import com.cyclone.mobile.runtime.background.TaskFollowUpAction
import com.cyclone.mobile.runtime.background.TaskPresentationProjector
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.runtime.plane.MissionPlanes
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskCommands
import com.cyclone.mobile.task.TaskEngines
import com.cyclone.mobile.ui.overlay.glass.AppLogo
import com.cyclone.mobile.ui.overlay.glass.AppLogoStack
import com.cyclone.mobile.ui.overlay.glass.GlassRoundButton
import com.cyclone.mobile.ui.overlay.glass.litRim
import com.cyclone.mobile.ui.overlay.glass.pressGlow
import com.cyclone.mobile.ui.overlay.glass.LocalGlassPalette
import com.cyclone.mobile.ui.overlay.glass.tiltGlass
import com.cyclone.mobile.ui.v32.CycloneTaskVisualState
import com.cyclone.mobile.ui.v32.appLabel
import com.cyclone.mobile.ui.v32.taskVisualState
import kotlinx.coroutines.delay

internal val GlassInk = Color(0xFFE0F5F3)
internal val GlassMuted = Color(0xFFA6CCCA)
internal val GlassDim = Color(0xFF6F9896)
internal val GlassTeal = Color(0xFF83DBD7)
internal val GlassWarm = Color(0xFFE9C78B)
private val VeilTint = Color(0x4D04181D)

/** Plan 27 geometry: the gaps between the stacked capsules. */
object OverlayStackGeometry {
    const val PILL_GAP_DP = 10
    const val CARD_GAP_DP = 14
    const val PILL_HEIGHT_DP = 34
    const val CARD_RADIUS_DP = 30
    const val BAR_RADIUS_DP = 33
    const val BAR_HEIGHT_DP = 66
}

// ---- the plane pill: a third, smaller capsule on top of the stack ----------------------------------------------

/**
 * Where the mission works, in one word, on a small pill above the card (or above the island), with the reason beside
 * it for four seconds whenever a move did not happen. Tap moves the task, long-press explains. Task Kit only.
 */
@Composable
internal fun PlaneRow(taskId: String, modifier: Modifier = Modifier) {
    val state by MissionPlanes.ui.collectAsState()
    val ui = state?.takeIf { TaskEngines.MIND_TASK_PREFIX + it.missionId == taskId } ?: return
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val look = PlanePillModel.look(ui)
    var shown by remember(ui.missionId) { mutableLongStateOf(ui.outcomeSeq) }
    var line by remember(ui.missionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.outcomeSeq) {
        if (ui.outcomeSeq != shown) {
            shown = ui.outcomeSeq
            line = OverlayGlassCopy.outcome(ui)
            if (line != null) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(4_000)
            line = null
        }
    }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().height(OverlayStackGeometry.PILL_HEIGHT_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(line != null, Modifier.weight(1f, fill = false), enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Row(
                Modifier.height(OverlayStackGeometry.PILL_HEIGHT_DP.dp)
                    .tiltGlass(17.dp, dots = false, thin = true, palette = LocalGlassPalette.current)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(line.orEmpty(), color = GlassInk, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            Modifier.height(OverlayStackGeometry.PILL_HEIGHT_DP.dp).widthIn(min = 92.dp)
                .pressGlow(interaction, 17.dp)
                .tiltGlass(17.dp, dots = false, thin = true, palette = LocalGlassPalette.current)
                .clip(RoundedCornerShape(17.dp))
                .combinedClickable(
                    interactionSource = interaction, indication = null, role = Role.Switch,
                    onClickLabel = PlanePillModel.action(ui),
                    onLongClickLabel = "Explain",
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        line = ui.note ?: PlanePillModel.describe(ui)
                    },
                ) {
                    val command = PlanePillModel.tap(ui)
                    if (command == null) line = ui.note ?: PlanePillModel.describe(ui)
                    else {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        TaskCommands.send(context, taskId, command)
                    }
                }
                .semantics { stateDescription = PlanePillModel.describe(ui); contentDescription = PlanePillModel.action(ui) }
                .padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaneSymbol(look)
            Text(OverlayGlassCopy.planeWord(look), color = GlassInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- small shared pieces ---------------------------------------------------------------------------------------

@Composable
internal fun GlassSpinner(size: Int = 14, color: Color = GlassTeal) {
    val motion = rememberInfiniteTransition(label = "Spinner")
    val turn by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "Turn")
    Canvas(Modifier.size(size.dp)) {
        rotate(turn) {
            drawCircle(color, radius = this.size.minDimension / 2f - 1.dp.toPx(),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(1f, 3.4.dp.toPx()))))
        }
    }
}

@Composable
private fun StatusChip(state: CycloneTaskVisualState) {
    Row(
        Modifier.height(30.dp).background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(15.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        val color = if (state == CycloneTaskVisualState.ACTION_NEEDED || state == CycloneTaskVisualState.FAILED) GlassWarm else GlassTeal
        when (state) {
            CycloneTaskVisualState.WORKING -> GlassSpinner()
            CycloneTaskVisualState.DONE -> Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = color)
            else -> Unit
        }
        Text(OverlayGlassCopy.chip(state), color = color, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** A thin progress line with a soft glow at its end; without a known fraction a short light slides along it. */
@Composable
internal fun GlassProgress(fraction: Float?, modifier: Modifier = Modifier, height: Int = 3) {
    val motion = rememberInfiniteTransition(label = "Progress")
    val slide by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "Slide")
    Canvas(modifier.fillMaxWidth().height(height.dp)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(Color.White.copy(alpha = 0.10f), cornerRadius = r)
        val (start, end) = if (fraction != null) 0f to fraction.coerceIn(0.02f, 1f) else (slide * 1.3f - 0.3f).coerceAtLeast(0f) to (slide * 1.3f).coerceAtMost(1f)
        if (end <= start) return@Canvas
        drawRoundRect(
            Brush.horizontalGradient(listOf(GlassTeal.copy(alpha = 0.35f), GlassTeal), startX = size.width * start, endX = size.width * end),
            topLeft = Offset(size.width * start, 0f), size = Size(size.width * (end - start), size.height), cornerRadius = r,
        )
        drawCircle(GlassTeal.copy(alpha = 0.45f), radius = size.height * 2.2f, center = Offset(size.width * end, size.height / 2f))
    }
}

/** A capsule button on the glass: teal when primary, soft dark otherwise; lit edge and press glow like the rest. */
@Composable
internal fun GlassCapsuleButton(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 44.dp)
            .pressGlow(interaction, 22.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (primary) GlassTeal.copy(alpha = if (enabled) 1f else 0.45f) else Color.White.copy(alpha = 0.08f))
            .litRim(cornerRadius = 22.dp)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (primary) Color(0xFF052528) else GlassInk, fontSize = 15.sp,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun CycloneMark(size: Int) {
    Icon(painterResource(R.drawable.ic_cyclone_status), null, Modifier.size(size.dp), tint = GlassInk)
}

/** The header's left side: the apps the task has worked in, or Cyclone's mark before it has opened one. */
@Composable
private fun HeaderApps(apps: List<String>) {
    if (apps.isEmpty()) CycloneMark(28) else AppLogoStack(apps)
}

@Composable
private fun CollapseButton(onMinimize: () -> Unit) {
    GlassRoundButton("Make smaller", onMinimize, size = 40.dp) {
        Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(22.dp), tint = GlassInk)
    }
}

/** The card's frame: the glass, the grabber inside its top edge, and one soft pill the words sit on. */
@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().tiltGlass(OverlayStackGeometry.CARD_RADIUS_DP.dp, palette = LocalGlassPalette.current)) {
        Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 14.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp)
                .size(32.dp, 3.dp).background(GlassMuted.copy(alpha = 0.38f), CircleShape))
            Column(
                Modifier.fillMaxWidth().background(VeilTint, RoundedCornerShape(22.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) { content() }
        }
    }
}

// ---- the working card ------------------------------------------------------------------------------------------

/**
 * The expanded card (plan 27): the task's app logos, its status and the collapse button; the title; a thin progress
 * line; the last few steps; and one quiet line with the step count and apps.
 */
@Composable
internal fun OverlayWorkCard(task: WorkspaceTaskUi, onMinimize: () -> Unit) {
    val context = LocalContext.current
    val snapshot = remember(task) { TaskPresentationProjector.project(task) }
    val apps = remember(task.taskId, task.packageName) { TaskAppTrail.record(task.taskId, task.packageName) }
    val labels = remember(apps) { apps.map { appLabel(context, it) }.filter { it != "Other" } }
    val state = task.taskVisualState()
    GlassCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeaderApps(apps)
            Spacer(Modifier.weight(1f))
            StatusChip(state)
            CollapseButton(onMinimize)
        }
        Text(snapshot.title, color = GlassInk, fontSize = 23.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        when (state) {
            CycloneTaskVisualState.WORKING -> {
                GlassProgress(snapshot.progressFraction)
                Spacer(Modifier.height(14.dp))
                val steps = OverlayGlassCopy.steps(snapshot.milestones)
                if (steps.isEmpty()) StepRow(snapshot.currentMilestone ?: task.message, current = true)
                else steps.forEach { StepRow(it.label, current = it.state == SemanticStepState.ACTIVE || it.state == SemanticStepState.ACTION_NEEDED) }
                MetaLine(OverlayGlassCopy.meta(snapshot.completedCount, snapshot.totalCount, labels))
            }
            CycloneTaskVisualState.ACTION_NEEDED -> {
                (snapshot.supportingCopy ?: task.message).takeIf { it.isNotBlank() }?.let {
                    Text(it, color = GlassMuted, fontSize = 15.sp, lineHeight = 21.sp)
                }
                val takeOver = TaskFollowUpAction.TAKE_OVER in snapshot.followUps
                val done = TaskFollowUpAction.CONTINUE in snapshot.followUps
                val autofill = TaskFollowUpAction.AUTOFILL in snapshot.followUps
                if (takeOver || done || autofill) Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (autofill) GlassCapsuleButton("Autofill", true, { TaskCommands.send(context, task.taskId, TaskCommand.Autofill) })
                    if (done) GlassCapsuleButton("I'm done", !autofill, { TaskCommands.send(context, task.taskId, TaskCommand.Done) })
                    if (takeOver) GlassCapsuleButton("Take over", false, { TaskCommands.send(context, task.taskId, TaskCommand.TakeOver) })
                }
            }
            else -> {
                (snapshot.outcomeCopy ?: snapshot.supportingCopy)?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = GlassMuted, fontSize = 15.sp, lineHeight = 21.sp)
                }
                if (labels.isNotEmpty()) MetaLine((if (state == CycloneTaskVisualState.DONE) "Done in " else "In ") + labels.joinToString(" → "))
            }
        }
    }
}

/** The on-screen (classic) task, which has no task record: its status and a stop. */
@Composable
internal fun OverlayForegroundCard(snapshot: OverlayChromeSnapshot, onMinimize: () -> Unit, onStop: () -> Unit) {
    val status = snapshot.statusMessage?.trim()?.takeIf { it.isNotBlank() }
        ?: snapshot.bullets.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: if (snapshot.state == OverlayChromeState.LIVE) "Watching the phone and continuing…" else "Using your phone…"
    val app by WorkingApp.foreground.collectAsState()
    // The apps this on-screen task has worked in (the last three, the current one in front), like a recorded task's
    // card; Cyclone's mark only before any app is known.
    val apps = remember(snapshot.sessionId, app) { TaskAppTrail.record("screen:${snapshot.sessionId}", app) }
    GlassCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeaderApps(apps)
            Spacer(Modifier.weight(1f))
            StatusChip(CycloneTaskVisualState.WORKING)
            CollapseButton(onMinimize)
        }
        Spacer(Modifier.height(14.dp))
        GlassProgress(null)
        Spacer(Modifier.height(14.dp))
        StepRow(status, current = true)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            GlassCapsuleButton("Stop", false, onStop)
        }
    }
}

@Composable
private fun StepRow(label: String, current: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (current) GlassSpinner(12) else Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = GlassTeal)
        }
        Text(label, color = if (current) GlassInk else GlassMuted, fontSize = 14.5.sp,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MetaLine(text: String) {
    if (text.isBlank()) return
    Text(text.uppercase(), color = GlassDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.3.sp,
        modifier = Modifier.padding(top = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// ---- the island ------------------------------------------------------------------------------------------------

/**
 * The working card folded into the Ask bar's size (plan 27): the current app's logo inside a turning progress ring,
 * what Cyclone does now (centred, on a soft pill), and the same pause / hold-to-stop control as the bar. Tap opens
 * the card again.
 */
@Composable
internal fun WorkIsland(
    appPackage: String?,
    lines: Pair<String, String>,
    fraction: Float?,
    working: Boolean,
    paused: Boolean,
    taskKey: String,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(OverlayStackGeometry.BAR_HEIGHT_DP.dp).tiltGlass(OverlayStackGeometry.BAR_RADIUS_DP.dp, palette = LocalGlassPalette.current)) {
        Row(
            Modifier.fillMaxWidth().height(OverlayStackGeometry.BAR_HEIGHT_DP.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RingedLogo(appPackage, working)
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(percent = 50)).background(VeilTint)
                    .clickable(onClickLabel = "Open the task", onClick = onOpen)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(lines.first, color = GlassInk, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(lines.second, color = GlassMuted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center)
            }
            OverlayRequestAction(working = working, paused = paused, taskKey = taskKey, canSend = false,
                onSend = {}, onPauseOrResume = onPause, onStop = onStop)
        }
        Canvas(Modifier.align(Alignment.BottomCenter).padding(horizontal = 30.dp, vertical = 5.dp).fillMaxWidth().height(2.dp)) {
            drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = CornerRadius(1.dp.toPx()))
            fraction?.let {
                drawRoundRect(GlassTeal, size = Size(size.width * it.coerceIn(0.02f, 1f), size.height), cornerRadius = CornerRadius(1.dp.toPx()))
            }
        }
    }
}

@Composable
private fun RingedLogo(appPackage: String?, working: Boolean) {
    val motion = rememberInfiniteTransition(label = "Island ring")
    val turn by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "Ring")
    Box(Modifier.size(42.dp).drawBehind {
        if (!working) return@drawBehind
        val stroke = 2.dp.toPx()
        rotate(turn) {
            drawArc(Brush.sweepGradient(listOf(GlassTeal.copy(alpha = 0f), GlassTeal.copy(alpha = 0.35f), GlassTeal)),
                startAngle = 0f, sweepAngle = 150f, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round),
                topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke))
        }
    }, contentAlignment = Alignment.Center) {
        if (appPackage != null) AppLogo(appPackage, 32.dp) else CycloneMark(22)
    }
}

// ---- Needs you -------------------------------------------------------------------------------------------------

/**
 * An Owner Moment on the glass (plan 27). An approval leads with what will be sent: a small line saying where it goes,
 * the message itself large and bright, then Send / Change / Not now. Other moments keep the owner card's own bodies.
 */
@Composable
internal fun OverlayOwnerCard(moment: OwnerMoment) {
    val context = LocalContext.current
    val apps = remember(moment.taskId) { TaskAppTrail.of(moment.taskId) }
    GlassCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeaderApps(apps)
            Spacer(Modifier.weight(1f))
            StatusChip(CycloneTaskVisualState.ACTION_NEEDED)
            moment.dismissal?.let { dismissal ->
                GlassRoundButton(dismissal.label, { TaskCommands.send(context, moment.taskId, dismissal.command) }, size = 40.dp) {
                    Icon(Icons.Rounded.Close, null, Modifier.size(20.dp), tint = GlassInk)
                }
            }
        }
        if (moment.kind == MomentKind.APPROVAL) {
            val (where, message) = remember(moment.text, moment.title) { OverlayGlassCopy.approval(moment.text, moment.title) }
            Text(where, color = GlassMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp, start = 2.dp))
            Box(
                Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.075f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) { Text(message, color = Color(0xFFF2FFFD), fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 27.sp) }
            BackgroundGlimpse(moment.taskId, Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                moment.actions.forEach { action ->
                    GlassCapsuleButton(action.label, action.primary, { TaskCommands.send(context, moment.taskId, action.command) })
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))
            com.cyclone.mobile.ui.v32.CycloneOwnerCard(moment, framed = false)
        }
    }
}
