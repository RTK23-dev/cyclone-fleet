package com.cyclone.mobile.ui.overlay

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LocalParking
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.cyclone.mobile.voice.DriveIntroFrame
import com.cyclone.mobile.voice.DriveIntroScript
import com.cyclone.mobile.voice.PillIcon
import com.cyclone.mobile.voice.VoiceFace
import kotlinx.coroutines.delay

/**
 * The Driver mode intro (plan 32): a nine-second film on a night road that plays when the owner turns Driver mode on,
 * from Settings, the Ask panel or the Quick Settings tile. It shows the real Drive button ([DriverButtonFace]); the
 * story and timing live in [DriveIntroScript] (tested). A tap moves to the next scene; Skip and Back close it. With
 * animations off in Android settings every scene holds still. At the end it asks for the microphone if Drive has not
 * got it yet (this replaced the permission prompt that used to open straight from the switch).
 */
class DriveIntroActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val needsMic = remember {
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            }
            val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finish() }
            DriveIntro(needsMic = needsMic, onDone = { allowMic ->
                if (allowMic && needsMic) askMic.launch(Manifest.permission.RECORD_AUDIO) else finish()
            })
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, DriveIntroActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        /** From the app or the Ask panel (which may be an overlay window, hence a new task). */
        fun start(context: Context) {
            runCatching { context.startActivity(intent(context)) }
        }

        /**
         * From the Quick Settings tile: collapses the shade and opens the film. Android 14+ takes a PendingIntent; the
         * Intent form is used only on Android 13, where it is the only one there is.
         */
        @SuppressLint("StartActivityAndCollapseDeprecated")
        fun startFromTile(tile: TileService) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    tile.startActivityAndCollapse(PendingIntent.getActivity(tile, 0, intent(tile),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                } else {
                    @Suppress("DEPRECATION") tile.startActivityAndCollapse(intent(tile))
                }
            }
        }
    }
}

private val NightTop = Color(0xFF020B10)
private val NightMid = Color(0xFF05191D)
private val NightLow = Color(0xFF082A2C)

@Composable
internal fun DriveIntro(needsMic: Boolean, onDone: (allowMic: Boolean) -> Unit) {
    val still = rememberReducedMotion()
    var elapsed by remember { mutableLongStateOf(if (still) DriveIntroScript.settled(0) else 0L) }
    // The clock: frame by frame, or scene by scene with animations off. A tap only moves [elapsed].
    LaunchedEffect(still) {
        if (still) {
            while (elapsed < DriveIntroScript.END_MS) {
                val scene = (elapsed / DriveIntroScript.SCENE_MS).toInt()
                elapsed = DriveIntroScript.settled(scene)
                delay(DriveIntroScript.STILL_SCENE_MS)
                if (elapsed == DriveIntroScript.settled(scene)) elapsed = DriveIntroScript.next(elapsed)
            }
            elapsed = DriveIntroScript.LAST_MS
        } else {
            var last = withFrameMillis { it }
            while (elapsed < DriveIntroScript.LAST_MS) {
                withFrameMillis { now ->
                    elapsed = (elapsed + (now - last).coerceIn(0L, 64L)).coerceAtMost(DriveIntroScript.LAST_MS)
                    last = now
                }
            }
        }
    }
    val frame = DriveIntroScript.frame(elapsed, still)
    BackHandler { onDone(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(NightTop, NightMid, NightLow)))
            .pointerInput(Unit) { detectTapGestures { if (elapsed < DriveIntroScript.END_MS) elapsed = DriveIntroScript.next(elapsed) } },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawNightRoad(frame, still) }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth > maxHeight
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { OrbAndPill(frame, orb = 132.dp) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Caption(frame, TextAlign.Start)
                        Controls(frame, needsMic, onDone, Alignment.Start)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.weight(0.5f))
                    OrbAndPill(frame, orb = 150.dp)
                    Spacer(Modifier.height(36.dp))
                    Caption(frame, TextAlign.Center)
                    Spacer(Modifier.weight(1f))
                    Controls(frame, needsMic, onDone, Alignment.CenterHorizontally)
                }
            }
        }
        if (!frame.done) {
            Text("Skip", color = GlassMuted, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp).clip(RoundedCornerShape(50))
                    .clickable(role = Role.Button, onClickLabel = "Skip the introduction") { onDone(false) }
                    .padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

/** The Drive button as it really looks, the tap's ring, and the one line under it. */
@Composable
private fun OrbAndPill(frame: DriveIntroFrame, orb: Dp) {
    val face = remember(frame.motion, frame.warm) {
        VoiceFace(motion = frame.motion, status = "", you = "", cyclone = "", panel = false, dim = false, warm = frame.warm,
            stop = false, notNow = false, description = "", stateDescription = "")
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(orb + DRIVER_GLOW * 2 + 24.dp), contentAlignment = Alignment.Center) {
            if (frame.ripple > 0f) {
                Canvas(Modifier.size(orb + DRIVER_GLOW * 2 + 24.dp)) {
                    val r = orb.toPx() / 2f * (1f + 0.55f * frame.ripple)
                    drawCircle(Color.White.copy(alpha = 0.55f * (1f - frame.ripple)), radius = r, style = Stroke(2.dp.toPx()))
                }
            }
            Box(Modifier.graphicsLayer { scaleX = frame.orbScale; scaleY = frame.orbScale }) {
                DriverButtonFace(face, frame.level, orb)
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
            if (frame.pill.isNotEmpty() && frame.pillAlpha > 0f) Pill(frame)
        }
    }
}

@Composable
private fun Pill(frame: DriveIntroFrame) {
    val lift = with(LocalDensity.current) { 14.dp.toPx() }
    val warm = frame.pillIcon == PillIcon.WAIT || frame.pillIcon == PillIcon.READY
    val (icon, tint) = pillIcon(frame.pillIcon)
    Row(
        Modifier
            .graphicsLayer { alpha = frame.pillAlpha; translationY = frame.pillLift * lift }
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(50))
            .background(if (warm) GlassWarm.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.09f))
            .border(1.dp, if (warm) GlassWarm.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(frame.pill, color = if (frame.pillIcon == PillIcon.YOU) GlassMuted else GlassInk, fontSize = 15.sp, maxLines = 2)
    }
}

private fun pillIcon(kind: PillIcon): Pair<ImageVector?, Color> = when (kind) {
    PillIcon.YOU -> Icons.Rounded.Mic to GlassMuted
    PillIcon.CYCLONE -> Icons.Rounded.GraphicEq to GlassTeal
    PillIcon.DONE -> Icons.Rounded.CheckCircle to GlassTeal
    PillIcon.WAIT -> Icons.Rounded.PauseCircle to GlassWarm
    PillIcon.READY -> Icons.Rounded.LocalParking to GlassWarm
    PillIcon.NONE -> null to GlassInk
}

@Composable
private fun Caption(frame: DriveIntroFrame, align: TextAlign) {
    Column(
        Modifier.fillMaxWidth().graphicsLayer { alpha = frame.captionAlpha }
            .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = frame.spoken },
        horizontalAlignment = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(frame.title, color = GlassInk, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, textAlign = align)
        Text(frame.detail, color = GlassMuted, fontSize = 16.sp, lineHeight = 22.sp, textAlign = align,
            modifier = Modifier.widthIn(max = 340.dp))
    }
}

/** Three dots while the film plays; at the close, Got it (and the microphone, if Drive still needs it). */
@Composable
private fun Controls(frame: DriveIntroFrame, needsMic: Boolean, onDone: (Boolean) -> Unit, align: Alignment.Horizontal) {
    Column(horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.heightIn(min = 96.dp)) {
        if (!frame.done) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 36.dp)) {
                repeat(DriveIntroScript.SCENES) { i ->
                    val on = i == frame.scene
                    Box(Modifier.size(width = if (on) 22.dp else 7.dp, height = 7.dp).clip(RoundedCornerShape(50))
                        .background(if (on) GlassTeal else Color.White.copy(alpha = 0.22f)))
                }
            }
        } else {
            if (needsMic) {
                Text("Cyclone needs the microphone to hear you. It opens only when you tap the orb.",
                    color = GlassMuted, fontSize = 14.sp, modifier = Modifier.widthIn(max = 340.dp))
            }
            Text(if (needsMic) "Allow the microphone" else "Got it", color = NightTop, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.graphicsLayer { alpha = frame.captionAlpha }.clip(RoundedCornerShape(50)).background(GlassTeal)
                    .clickable(role = Role.Button) { onDone(true) }.padding(horizontal = 30.dp, vertical = 14.dp))
            if (needsMic) {
                Text("Not now", color = GlassMuted, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onDone(false) }
                        .padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

/**
 * A night road seen from the driver's seat: a soft teal horizon below the words, the road's edges, and the centre
 * dashes flowing towards you on the left, so the middle stays clear for the captions and Got it. The dashes follow
 * [DriveIntroFrame.roadTravel], so the car visibly slows and stops in the third scene.
 */
private fun DrawScope.drawNightRoad(frame: DriveIntroFrame, still: Boolean) {
    val w = size.width
    val h = size.height
    val horizon = h * if (w > h) 0.58f else 0.64f
    val vx = w / 2f
    val span = h - horizon
    // The glow on the horizon: teal while driving, warmer when a step waits.
    val glow = if (frame.warm) GlassWarm else GlassTeal
    drawCircle(Brush.radialGradient(0f to glow.copy(alpha = 0.18f), 1f to glow.copy(alpha = 0f), center = Offset(vx, horizon), radius = w * 0.8f),
        radius = w * 0.8f, center = Offset(vx, horizon))
    // Where each line meets the bottom edge; everything else is on the ray from the vanishing point.
    val leftEdge = vx - w * 1.25f
    val lane = vx - w * 0.42f
    val rightEdge = vx + w * 0.62f
    val road = Path().apply { moveTo(vx, horizon); lineTo(rightEdge, h); lineTo(leftEdge, h); close() }
    drawPath(road, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.01f), Color.White.copy(alpha = 0.045f)), startY = horizon, endY = h))
    for (edge in listOf(leftEdge, rightEdge)) {
        drawLine(Brush.verticalGradient(listOf(GlassTeal.copy(alpha = 0f), GlassTeal.copy(alpha = 0.36f)), startY = horizon, endY = h),
            Offset(vx, horizon), Offset(edge, h), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
    // Centre dashes: depth z from 1 (the bottom edge) to far away; a point at depth z sits 1/z of the way down the ray.
    val frac = if (still) 0.35f else frame.roadTravel % 1f
    for (i in 0 until 16) {
        val near = i + 1f - frac
        if (near < 0.35f) continue
        val p1 = (1f / near).coerceAtMost(1.6f)
        val p2 = 1f / (near + 0.45f)
        val depth = p1.coerceIn(0f, 1f)
        drawLine(Color.White.copy(alpha = 0.08f + 0.42f * depth),
            Offset(vx + (lane - vx) * p1, horizon + span * p1), Offset(vx + (lane - vx) * p2, horizon + span * p2),
            strokeWidth = (1.2f + 6f * depth) * density, cap = StrokeCap.Round)
    }
}
