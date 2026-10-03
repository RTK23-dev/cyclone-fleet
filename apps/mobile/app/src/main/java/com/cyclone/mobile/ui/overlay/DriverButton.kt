package com.cyclone.mobile.ui.overlay

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.overlay.glass.GlassLight
import com.cyclone.mobile.ui.overlay.glass.tiltGlass
import com.cyclone.mobile.voice.OrbMotion
import com.cyclone.mobile.voice.VoiceFace

/**
 * Drive's AI button (plan 32): the voice orb on idle-bubble glass. The window around it handles the touch (tap to
 * talk, hold one second to move; see DriverOverlay); this only draws. While a task works a thin ring turns around the
 * glass; when Cyclone needs the owner the glow turns warm.
 */
@Composable
internal fun DriverButtonFace(face: VoiceFace, level: Float, size: Dp) {
    Box(Modifier.size(size + DRIVER_GLOW * 2), contentAlignment = Alignment.Center) {
        if (face.warm || face.motion == OrbMotion.WORK) DriveHalo(face, Modifier.size(size + DRIVER_GLOW * 2))
        Box(Modifier.size(size).tiltGlass(size / 2, dots = false, thin = true, seeThrough = 0.78f), contentAlignment = Alignment.Center) {
            DriveOrb(face.motion, level, face.warm, Modifier.size(size * 0.8f))
        }
    }
}

/** The glow and the working ring around the button's glass. */
@Composable
private fun DriveHalo(face: VoiceFace, modifier: Modifier) {
    val still = rememberReducedMotion()
    val t = rememberInfiniteTransition(label = "Drive halo")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "Ring")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600), RepeatMode.Reverse), label = "Warm")
    val tint = if (face.warm) GlassWarm else GlassTeal
    Canvas(modifier) {
        val glass = (size.minDimension - DRIVER_GLOW.toPx() * 2) / 2f
        if (face.warm) {
            val glow = glass + DRIVER_GLOW.toPx() * (0.75f + 0.25f * if (still) 1f else breath)
            drawCircle(Brush.radialGradient(0f to tint.copy(alpha = 0f), (glass / glow) to tint.copy(alpha = 0.55f), 1f to tint.copy(alpha = 0f),
                center = center, radius = glow), radius = glow)
        }
        if (face.motion == OrbMotion.WORK) {
            val ring = glass + 4.dp.toPx()
            val start = if (still) -90f else spin - 90f
            drawArc(tint.copy(alpha = 0.18f), 0f, 360f, false, Offset(center.x - ring, center.y - ring), Size(ring * 2, ring * 2),
                style = Stroke(3.dp.toPx()))
            drawArc(tint.copy(alpha = 0.95f), start, if (still) 360f else 96f, false, Offset(center.x - ring, center.y - ring), Size(ring * 2, ring * 2),
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

/**
 * The orb: a lit teal sphere. It breathes calmly at rest, swells with the owner's voice while listening, swirls while
 * thinking and pulses with Cyclone's voice while speaking. With animations off in Android settings it holds still.
 */
@Composable
internal fun DriveOrb(motion: OrbMotion, level: Float, warm: Boolean, modifier: Modifier) {
    val still = rememberReducedMotion()
    val t = rememberInfiniteTransition(label = "Drive orb")
    val slow by t.animateFloat(0f, 360f, infiniteRepeatable(tween(7_000, easing = LinearEasing)), label = "Swirl slow")
    val fast by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1_300, easing = LinearEasing)), label = "Swirl fast")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2_800), RepeatMode.Reverse), label = "Breath")
    val voice by animateFloatAsState(if (motion == OrbMotion.LISTEN || motion == OrbMotion.SPEAK) level else 0f, tween(90), label = "Level")
    Canvas(modifier) {
        val b = if (still) 0.5f else breath
        val swirl = if (still) 0f else if (motion == OrbMotion.SWIRL) fast else slow
        val swell = when (motion) {
            OrbMotion.LISTEN -> 1f + 0.13f * voice + 0.02f * b
            OrbMotion.SPEAK -> 1f + 0.09f * voice
            OrbMotion.SWIRL -> 1f + 0.03f * b
            else -> 1f + 0.025f * b
        }
        drawDriveOrb(size.minDimension / 2f * 0.66f * swell, swirl, b, voice, motion, warm)
    }
}

private fun DrawScope.drawDriveOrb(r: Float, swirl: Float, breath: Float, voice: Float, motion: OrbMotion, warm: Boolean) {
    val c = center
    val tint = if (warm) GlassWarm else GlassTeal
    // The glow: soft all round, reaching zero inside the canvas so it has no edge.
    val glow = (r * (1.32f + 0.10f * breath + 0.30f * voice)).coerceAtMost(size.minDimension / 2f)
    drawCircle(Brush.radialGradient(0f to tint.copy(alpha = 0.50f + 0.15f * voice), 0.55f to tint.copy(alpha = 0.18f + 0.10f * voice),
        1f to tint.copy(alpha = 0f), center = c, radius = glow), radius = glow, center = c)
    // Listening: one ring rides out on the voice.
    if (motion == OrbMotion.LISTEN && voice > 0.05f) {
        val ring = r * (1.08f + 0.22f * voice)
        drawCircle(tint.copy(alpha = 0.55f * voice), radius = ring, center = c, style = Stroke(1.5.dp.toPx()))
    }
    val lx = GlassLight.x
    val ly = GlassLight.y
    val len = kotlin.math.hypot(lx, ly).coerceAtLeast(0.001f)
    val hx = lx / len
    val hy = ly / len
    val orb = Path().apply { addOval(Rect(c, r)) }
    val body = if (warm) listOf(Color(0xFFFFE3B0), Color(0xFFE0B066), Color(0xFF9C7336), Color(0xFF6E4E22))
        else listOf(Color(0xFF8FF3E8), Color(0xFF3CC4B8), Color(0xFF1B8C84), Color(0xFF116560))
    clipPath(orb) {
        drawCircle(Brush.radialGradient(0f to body[0], 0.45f to body[1], 0.85f to body[2], 1f to body[3],
            center = Offset(c.x + hx * r * 0.35f, c.y + hy * r * 0.35f), radius = r * 1.35f), radius = r, center = c)
        rotate(swirl, pivot = c) {
            drawCircle(Brush.radialGradient(listOf(Color(0x59E6FFFB), Color(0x00E6FFFB)),
                center = Offset(c.x - r * 0.32f, c.y - r * 0.28f), radius = r * 0.62f), radius = r, center = c)
            drawCircle(Brush.radialGradient(listOf(Color(0x66074A46), Color(0x00074A46)),
                center = Offset(c.x + r * 0.38f, c.y + r * 0.40f), radius = r * 0.7f), radius = r, center = c)
            if (motion == OrbMotion.SWIRL) drawCircle(Brush.radialGradient(listOf(Color(0x80FFFFFF), Color(0x00FFFFFF)),
                center = Offset(c.x, c.y - r * 0.55f), radius = r * 0.35f), radius = r, center = c)
        }
        drawCircle(Brush.radialGradient(listOf(Color(0x6BFFFFFF), Color(0x00FFFFFF)),
            center = Offset(c.x + hx * r * 0.45f, c.y + hy * r * 0.45f), radius = r * 0.42f), radius = r, center = c)
    }
}

/** Android's "remove animations" (animator duration scale 0): Drive's glass holds still. */
@Composable
internal fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** Room around the button's glass for its glow and working ring. */
internal val DRIVER_GLOW = 16.dp
