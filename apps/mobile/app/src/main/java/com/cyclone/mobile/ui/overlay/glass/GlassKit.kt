package com.cyclone.mobile.ui.overlay.glass

import android.content.Context
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription

private val Teal = Color(0xFF83DBD7)
private val Ink = Color(0xFFE0F5F3)
private val Muted = Color(0xFFA6CCCA)
private val Dim = Color(0xFF6F9896)

/**
 * True inside a Tilt Glass surface (the tools drawer, its model page). Shared controls that also live on Teal Matrix
 * screens read it and draw their glass version: lit capsules for what you press, veils for what you read.
 */
val LocalTiltGlass = compositionLocalOf { false }

/** The glass palette for Material text and icons inside a tilt-glass surface, plus [LocalTiltGlass]. */
@Composable
fun TiltGlassTheme(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme.copy(
        primary = Teal,
        onPrimary = Color(0xFF052528),
        onSurface = Ink,
        onSurfaceVariant = Muted,
        outline = Dim,
        outlineVariant = Dim,
    )
    MaterialTheme(colorScheme = colors, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalTiltGlass provides true, LocalContentColor provides Ink, content = content)
    }
}

/**
 * A pressable capsule on the glass: a soft fill, the lit rim and the press glow (principles 6 and 7). [fill] is the
 * dark-soft default; pass the teal for a primary or selected capsule.
 */
@Composable
fun Modifier.glassCapsule(
    cornerRadius: Dp,
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role = Role.Button,
    fill: Color = Color.White.copy(alpha = 0.08f),
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
): Modifier = this
    .pressGlow(interaction, cornerRadius)
    .clip(RoundedCornerShape(cornerRadius))
    .background(fill)
    .litRim(cornerRadius = cornerRadius)
    .clickable(interaction, indication = null, enabled = enabled, role = role, onClick = onClick)

/**
 * A thin rim whose brightest point faces the light (and a weaker one opposite), for round controls; with
 * [cornerRadius] it follows a capsule instead of a circle.
 */
fun Modifier.litRim(width: Dp = 1.1.dp, cornerRadius: Dp? = null): Modifier = drawWithCache {
    val stroke = Stroke(width.toPx())
    val inset = width.toPx() / 2f
    val radius = size.minDimension / 2f - inset
    val circleBrush = Brush.sweepGradient(
        0f to Color(0xF2F2FFFD), 0.16f to Color.Transparent, 0.34f to Color.Transparent,
        0.5f to Color(0x99BEF5F0), 0.66f to Color.Transparent, 0.84f to Color.Transparent, 1f to Color(0xF2F2FFFD),
    )
    onDrawWithContent {
        drawContent()
        val degrees = GlassOptics.rimRotationDegrees(GlassLight.x, GlassLight.y)
        if (cornerRadius == null) {
            rotate(degrees) { drawCircle(circleBrush, radius = radius, style = stroke) }
        } else {
            // A capsule cannot be rotated, so the gradient's bright points move to the light instead.
            val a = (((degrees / 360f) % 1f) + 1f) % 1f
            val stops = listOf(a to Color(0xF2F2FFFD), (a + 0.5f) % 1f to Color(0x99BEF5F0))
            val colors = buildList {
                stops.forEach { (at, color) ->
                    add((at - 0.16f + 1f) % 1f to Color.Transparent); add(at to color); add((at + 0.16f) % 1f to Color.Transparent)
                }
            }.sortedBy { it.first }.toTypedArray()
            drawRoundRect(Brush.sweepGradient(*colors), topLeft = Offset(inset, inset),
                size = Size(size.width - 2 * inset, size.height - 2 * inset),
                cornerRadius = CornerRadius(cornerRadius.toPx() - inset), style = stroke)
        }
    }
}

/**
 * Every press lights the control up: the rim flashes fully bright and a soft teal glow blooms and fades (about
 * 0.7 s). [cornerRadius] null means a circle.
 */
@Composable
fun Modifier.pressGlow(interaction: MutableInteractionSource, cornerRadius: Dp? = null): Modifier {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { event ->
            if (event is PressInteraction.Press) launch {
                flash.snapTo(0f)
                flash.animateTo(1f, tween(130))
                flash.animateTo(0f, tween(620))
            }
        }
    }
    return drawWithContent {
        drawContent()
        val f = flash.value
        if (f <= 0f) return@drawWithContent
        val glow = 10.dp.toPx()
        if (cornerRadius == null) {
            val r = size.minDimension / 2f
            drawCircle(Brush.radialGradient(
                listOf(Teal.copy(alpha = 0.55f * f), Teal.copy(alpha = 0f)), center = center, radius = r + glow,
            ), radius = r + glow)
            drawCircle(Color(0xFFF2FFFD).copy(alpha = 0.95f * f), radius = r - 0.6.dp.toPx(), style = Stroke(1.3.dp.toPx()))
        } else {
            val cr = CornerRadius(cornerRadius.toPx())
            drawRoundRect(Teal.copy(alpha = 0.28f * f), Offset(-glow / 2, -glow / 2),
                Size(size.width + glow, size.height + glow), CornerRadius(cr.x + glow / 2))
            drawRoundRect(Color(0xFFF2FFFD).copy(alpha = 0.95f * f), cornerRadius = cr, style = Stroke(1.3.dp.toPx()))
        }
    }
}

/** A soft round control: dim glass, a lit rim and the press glow. */
@Composable
fun GlassRoundButton(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 46.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.size(size)
            .pressGlow(interaction)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.02f))))
            .litRim()
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** The soft pill text sits on: no edge, fully rounded, so the dots show faintly through it. */
fun Modifier.veilPill(): Modifier = background(Color(0x4D04181D), RoundedCornerShape(percent = 50))

/**
 * The voice button (plan 27): a normal round glass button until the owner talks, then a living teal orb. The orb is a
 * lit sphere (its highlight faces the shared light), a slow swirl inside it (7 s), and a soft breath of light (2.4 s)
 * that rises into the bar above it and fades out before the bar's edge; below the orb it stays tight.
 *
 * It is one control in both states, so a press is never cut short by the button changing under the finger. Voice
 * starts as the finger goes down; see [VoicePress] for tap to talk, push to talk ([pushToTalk]) and the guard that
 * keeps a bounce or a quick second touch from stopping it.
 */
@Composable
fun VoiceOrbButton(
    listening: Boolean,
    enabled: Boolean,
    description: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    pushToTalk: Boolean = true,
    content: @Composable BoxScope.(tint: Color) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isListening by rememberUpdatedState(listening)
    val canStart by rememberUpdatedState(enabled)
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val press = remember { longArrayOf(0L, 0L) } // [0] listening since, [1] last press that did something
    LaunchedEffect(listening) { press[0] = if (listening) android.os.SystemClock.uptimeMillis() else 0L }
    fun act(action: VoicePress.Action, now: Long) {
        when (action) {
            VoicePress.Action.START -> if (canStart) { press[1] = now; press[0] = now; start() }
            VoicePress.Action.STOP -> { press[1] = now; stop() }
            VoicePress.Action.NONE -> Unit
        }
    }
    val motion = if (listening) rememberInfiniteTransition(label = "Voice orb") else null
    val swirl = motion?.animateFloat(0f, 360f, infiniteRepeatable(tween(7_000, easing = LinearEasing)), label = "Swirl")
    val breath = motion?.animateFloat(0f, 1f, infiniteRepeatable(tween(2_400), RepeatMode.Reverse), label = "Breath")
    Box(
        Modifier.size(46.dp)
            .pressGlow(interaction)
            .drawBehind { if (listening) drawVoiceOrb(swirl?.value ?: 0f, breath?.value ?: 0f) }
            .clip(CircleShape)
            .then(
                if (listening) Modifier
                else Modifier.background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.02f))))
            )
            .litRim()
            .pointerInput(pushToTalk) {
                detectTapGestures(onPress = voicePress@{
                    val down = android.os.SystemClock.uptimeMillis()
                    val action = VoicePress.down(down, isListening, press[0], press[1])
                    if (action == VoicePress.Action.NONE || (action == VoicePress.Action.START && !canStart)) return@voicePress
                    val pressed = PressInteraction.Press(it)
                    interaction.emit(pressed)
                    act(action, down)
                    val released = tryAwaitRelease()
                    interaction.emit(PressInteraction.Release(pressed))
                    if (released && pushToTalk && action == VoicePress.Action.START) {
                        val up = android.os.SystemClock.uptimeMillis()
                        if (VoicePress.upAfterStart(up - down) == VoicePress.Action.STOP) { press[1] = up; stop() }
                    }
                })
            }
            .semantics {
                role = Role.Button
                contentDescription = description
                stateDescription = if (listening) "Listening" else "Not listening"
                onClick(label = if (listening) "Stop" else "Talk") {
                    if (isListening) stop() else if (canStart) start()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        content(if (listening) Color(0xFF052528) else Ink.copy(alpha = if (enabled) 1f else 0.45f))
    }
}

/**
 * The listening orb, drawn behind the button (not clipped by it, so the breath can rise into the bar). Every glow is a
 * radial gradient that reaches zero inside what is drawn, so nothing has an edge.
 */
private fun DrawScope.drawVoiceOrb(swirl: Float, breath: Float) {
    val c = center
    val r = size.minDimension / 2f
    // The breath: an upright soft ellipse above the orb, tight below it.
    val pivot = Offset(c.x, c.y - r * 0.42f)
    scale(scaleX = 1f, scaleY = 1.12f + 0.08f * breath, pivot = pivot) {
        val glow = r * (1.22f + 0.06f * breath)
        drawCircle(
            Brush.radialGradient(
                0f to Teal.copy(alpha = 0.48f + 0.12f * breath),
                0.5f to Teal.copy(alpha = 0.20f + 0.06f * breath),
                1f to Teal.copy(alpha = 0f),
                center = pivot, radius = glow,
            ),
            radius = glow, center = pivot,
        )
    }
    // The sphere, lit from the shared light.
    val lx = GlassLight.x
    val ly = GlassLight.y
    val len = kotlin.math.hypot(lx, ly).coerceAtLeast(0.001f)
    val hx = lx / len
    val hy = ly / len
    val orb = Path().apply { addOval(Rect(c, r)) }
    clipPath(orb) {
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFF8FF3E8), 0.45f to Color(0xFF3CC4B8), 0.85f to Color(0xFF1B8C84), 1f to Color(0xFF116560),
                center = Offset(c.x + hx * r * 0.35f, c.y + hy * r * 0.35f), radius = r * 1.35f,
            ),
            radius = r, center = c,
        )
        rotate(swirl, pivot = c) {
            drawCircle(Brush.radialGradient(listOf(Color(0x59E6FFFB), Color(0x00E6FFFB)),
                center = Offset(c.x - r * 0.32f, c.y - r * 0.28f), radius = r * 0.62f), radius = r, center = c)
            drawCircle(Brush.radialGradient(listOf(Color(0x66074A46), Color(0x00074A46)),
                center = Offset(c.x + r * 0.38f, c.y + r * 0.40f), radius = r * 0.7f), radius = r, center = c)
        }
        // A small soft highlight towards the light.
        drawCircle(Brush.radialGradient(listOf(Color(0x6BFFFFFF), Color(0x00FFFFFF)),
            center = Offset(c.x + hx * r * 0.45f, c.y + hy * r * 0.45f), radius = r * 0.42f), radius = r, center = c)
    }
}

private val iconCache = LruCache<String, ImageBitmap>(24)

/** The app's real launcher icon, cached; null when the app is unknown. */
fun appIconBitmap(context: Context, packageName: String?): ImageBitmap? {
    if (packageName.isNullOrBlank()) return null
    iconCache.get(packageName)?.let { return it }
    return runCatching {
        val size = (48 * context.resources.displayMetrics.density).toInt().coerceIn(64, 192)
        context.packageManager.getApplicationIcon(packageName).toBitmap(size, size).asImageBitmap()
    }.getOrNull()?.also { iconCache.put(packageName, it) }
}

/** One app logo: round, with a thin dark ring so it sits cleanly on the glass. [current] adds the teal ring. */
@Composable
fun AppLogo(packageName: String?, size: Dp, current: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(packageName) { appIconBitmap(context, packageName) } ?: return
    Box(modifier.size(size)
        .drawWithContent {
            drawContent()
            val r = this.size.minDimension / 2f
            drawCircle(Color(0xF2062026), radius = r + 1.dp.toPx(), style = Stroke(2.dp.toPx()))
            if (current) drawCircle(Teal.copy(alpha = 0.55f), radius = r + 2.6.dp.toPx(), style = Stroke(1.4.dp.toPx()))
        }) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    }
}

/**
 * The apps a task has worked in, the current one in front and larger, earlier ones tucked behind it, slightly smaller
 * and quieter (plan 27, the card header).
 */
@Composable
fun AppLogoStack(packages: List<String>, modifier: Modifier = Modifier) {
    if (packages.isEmpty()) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy((-9).dp)) {
        packages.forEachIndexed { index, pkg ->
            val current = index == packages.lastIndex
            AppLogo(
                pkg, if (current) 34.dp else 28.dp, current = current,
                modifier = Modifier.graphicsLayer { alpha = if (current) 1f else 0.82f }
                    .zIndex(if (current) 1f else 0f),
            )
        }
    }
}
