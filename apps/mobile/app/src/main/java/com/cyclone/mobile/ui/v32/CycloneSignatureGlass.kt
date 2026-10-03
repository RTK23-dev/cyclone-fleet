package com.cyclone.mobile.ui.v32

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.cyclone.mobile.ui.v32.ask.askGlass
import com.cyclone.mobile.ui.v32.ask.askWhorls
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal val SignatureInk = Color(0xFFE0F5F3)
internal val SignatureMuted = Color(0xFFA6CCCA)
internal val SignatureTeal = Color(0xFF83DBD7)
internal val SignatureScheme = darkColorScheme(
    primary = SignatureTeal, onPrimary = Color(0xFF052528),
    primaryContainer = Color(0xFF17494E), onPrimaryContainer = SignatureInk,
    secondary = Color(0xFF92DBC4), onSecondary = Color(0xFF07382F),
    secondaryContainer = Color(0xFF153D36), onSecondaryContainer = Color(0xFFBBF4DE),
    tertiary = Color(0xFFE9C78B), onTertiary = Color(0xFF3F3015),
    tertiaryContainer = Color(0xFF3C3325), onTertiaryContainer = Color(0xFFFFE1AD),
    error = Color(0xFFFFB4AB), onError = Color(0xFF5A1E1C),
    errorContainer = Color(0xFF442C30), onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF061A20), onBackground = SignatureInk,
    outline = Color(0xFF527B7D), outlineVariant = Color(0xFF315257),
    surface = Color(0xFF08282D), surfaceVariant = Color(0xFF123B40),
    onSurface = SignatureInk, onSurfaceVariant = SignatureMuted,
)

internal val LocalCycloneSignatureTheme = compositionLocalOf { false }

/** Scoped to Ask and task surfaces; light device settings cannot leak into overlay text. */
@Composable
internal fun CycloneSignatureTheme(enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    if (com.cyclone.mobile.ui.v32.ask.inAskGlass()) {
        // R5: on the rain Material parts take the neutral glass scheme (white and graphite), never teal.
        CompositionLocalProvider(
            LocalCycloneSignatureTheme provides true,
            LocalCycloneOverlayChrome provides true,
            LocalCycloneLiquidBackdrop provides null,
            LocalTealMatrixField provides false,
            LocalContentColor provides com.cyclone.mobile.ui.v32.ask.AskGlass.Ink,
        ) {
            MaterialTheme(colorScheme = com.cyclone.mobile.ui.v32.ask.AskGlassScheme, content = content)
        }
        return
    }
    CompositionLocalProvider(
        LocalCycloneSignatureTheme provides true,
        LocalCycloneOverlayChrome provides true,
        LocalCycloneLiquidBackdrop provides null,
        // Surfaces with their own canvas (the Ask page) keep painted glass.
        LocalTealMatrixField provides false,
        LocalContentColor provides SignatureInk,
    ) {
        MaterialTheme(colorScheme = SignatureScheme, content = content)
    }
}

/** Readable companion card: an opaque teal backing stops launcher icons bleeding into copy. */
@Composable
internal fun CycloneSignatureCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    accent: Color = SignatureTeal,
    content: @Composable BoxScope.() -> Unit,
) {
    CycloneSignatureGlass(modifier, textured = false, cornerRadius = cornerRadius,
        solidBacking = true, accent = accent, content = content)
}

/**
 * The ask capsule is drawn at its actual size, never stretched from a concept bitmap.
 * The optical rim and contour dots are cached until size/density changes. Listening only
 * changes a local halo: idle capsules have no animation loop or per-frame dot generation.
 * This translucent material also works in an accessibility window without sampling or
 * capturing the app underneath (Compose's backdrop cannot blur another app's pixels).
 */
@Composable
internal fun CycloneSignatureGlass(
    modifier: Modifier = Modifier,
    listening: Boolean = false,
    textured: Boolean = true,
    cornerRadius: Dp = 33.dp,
    solidBacking: Boolean = false,
    accent: Color = SignatureTeal,
    focused: Boolean = false,
    refract: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    if (com.cyclone.mobile.ui.v32.ask.inAskGlass()) {
        // R5: every card, tray and capsule built on this material is smoked glass over the app's rain.
        val shape = com.kyant.capsule.ContinuousRoundedRectangle(cornerRadius)
        CompositionLocalProvider(
            LocalCycloneInsideLiquidHost provides true,
            LocalCycloneOverlayChrome provides true,
        ) {
            CycloneSignatureTheme {
                Box(
                    modifier
                        .askGlass(cornerRadius, shineOffset = 0.4f, shape = shape)
                        .then(if (textured) Modifier.askWhorls() else Modifier)
                        .then(if (focused) Modifier.border(1.2.dp, Color.White.copy(alpha = .35f), shape) else Modifier)
                        .clip(shape),
                    contentAlignment = Alignment.Center,
                    content = content,
                )
            }
        }
        return
    }
    // Teal glass (controls layer only): the surface renders the living canvas itself, frosted and
    // lensed, so no layer capture or blur pass runs. Off the canvas (floating overlay) it stays painted.
    val live = refract && !solidBacking && LocalTealMatrixField.current
    val voiceLight = animateFloatAsState(if (listening) 1f else 0f, label = "Voice glass light")
    val focusLight = animateFloatAsState(if (focused) 1f else 0f, label = "Focus glass light")
    CompositionLocalProvider(
        LocalCycloneInsideLiquidHost provides true,
        LocalCycloneOverlayChrome provides true,
    ) {
        CycloneSignatureTheme {
            val optics = if (live) Modifier.tealGlass(cornerRadius, press = { focusLight.value * 0.7f }) else Modifier
            Box(
                modifier.then(optics).drawWithCache {
                    val radius = minOf(cornerRadius.toPx(), size.height / 2f)
                    val silhouette = Path().apply {
                        addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius)))
                    }
                    // Refracting glass keeps the scene visible through a thinner teal body; text on it
                    // still clears WCAG AA with margin (see docs/design/CYCLONE_TEAL_MATRIX.md).
                    val fill = Brush.verticalGradient(listOf(
                        Color(0xE0225259), Color(0xEE08272D), Color(0xF0052027), Color(0xE01B5057),
                    ))
                    val rim = Brush.verticalGradient(
                        0f to Color(0xFFB4EEEA).copy(alpha = .56f),
                        .22f to accent.copy(alpha = .14f),
                        .55f to accent.copy(alpha = .035f),
                        1f to accent.copy(alpha = .57f),
                    )
                    // Teal Matrix upgrade: a thin specular sweep across the upper edge and a soft
                    // bloom along the lower edge make the capsule read as lit glass, not a flat pill.
                    val specular = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        .30f to Color.White.copy(alpha = .30f),
                        .55f to Color.White.copy(alpha = .12f),
                        1f to Color.Transparent,
                    )
                    val bloom = Brush.verticalGradient(
                        .55f to Color.Transparent,
                        1f to accent.copy(alpha = .20f),
                    )
                    val focusRim = Brush.horizontalGradient(
                        listOf(accent.copy(alpha = .15f), Color(0xFF41D7CB), accent.copy(alpha = .15f)),
                    )
                    // Concentric dotted ridges bend into each capsule end, then fade before
                    // the text field. The asymmetric whorls are a material signature, not a
                    // fingerprint button. All geometry is in dp, including on narrow devices.
                    data class Dot(val center: Offset, val radius: Float, val alpha: Float)
                    val canvasSize = size
                    val dots = buildList {
                        if (textured && !live) {
                            val unit = 1.dp.toPx()
                            val reach = minOf(canvasSize.width * .28f, 100.dp.toPx())
                            for (side in 0..1) {
                                val originX = if (side == 0) -15f * unit else canvasSize.width + 12f * unit
                                val originY = canvasSize.height * if (side == 0) .95f else .72f
                                for (ridge in 1..25) {
                                    val r = ridge * 4.1f * unit
                                    val count = (2 * PI * r / (3.8f * unit)).toInt().coerceAtLeast(12)
                                    for (i in 0 until count) {
                                        val angle = i * 2.0 * PI / count + ridge * .037
                                        val x = originX + cos(angle).toFloat() * r
                                        val y = originY + sin(angle).toFloat() * r * .83f
                                        val edge = if (side == 0) x else canvasSize.width - x
                                        if (x < 0 || x > canvasSize.width || y < 0 || y > canvasSize.height || edge !in 0f..reach) continue
                                        val fade = (1f - edge / reach).coerceIn(0f, 1f)
                                        val lower = (.24f + .76f * y / canvasSize.height)
                                        add(Dot(Offset(x, y), (.35f + .40f * fade) * unit, fade * lower * .52f))
                                    }
                                }
                            }
                        }
                    }
                    val leftMist = Brush.radialGradient(
                        listOf(accent.copy(alpha = .19f), Color.Transparent),
                        center = Offset(radius * .6f, size.height * .78f), radius = radius * 1.9f,
                    )
                    val rightMist = Brush.radialGradient(
                        listOf(accent.copy(alpha = .12f), Color.Transparent),
                        center = Offset(size.width - radius * .65f, size.height * .75f), radius = radius * 1.9f,
                    )
                    val voiceHalo = Brush.radialGradient(
                        listOf(Color(0xFF41D7CB).copy(alpha = .48f), Color.Transparent),
                        center = Offset(size.width - 81.dp.toPx(), size.height / 2), radius = 31.dp.toPx(),
                    )
                    onDrawBehind {
                        clipPath(silhouette) {
                            if (live) {
                                // The glass shader already drew body, lensing and rim.
                                drawRect(voiceHalo, alpha = voiceLight.value)
                                if (focusLight.value > 0f) {
                                    drawRoundRect(
                                        focusRim, cornerRadius = CornerRadius(radius),
                                        style = Stroke(1.2.dp.toPx()), alpha = focusLight.value * .6f,
                                    )
                                }
                                return@clipPath
                            }
                            if (solidBacking) drawRect(Color(0xFF08282D))
                            drawRect(fill)
                            drawRect(leftMist)
                            drawRect(rightMist)
                            // Multiple translucent inward strokes form a soft optical edge,
                            // rather than a saturated neon outline or expensive live blur.
                            for (layer in 9 downTo 1) {
                                val inset = layer * .65.dp.toPx()
                                drawRoundRect(
                                    rim, topLeft = Offset(inset, inset),
                                    size = Size(size.width - 2 * inset, size.height - 2 * inset),
                                    cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)),
                                    alpha = .045f + (10 - layer) * .014f,
                                    style = Stroke(1.5.dp.toPx()),
                                )
                            }
                            dots.forEach { drawCircle(accent.copy(alpha = it.alpha), it.radius, it.center) }
                            drawRect(bloom)
                            drawLine(
                                specular,
                                Offset(radius * .6f, 1.2.dp.toPx()),
                                Offset(size.width - radius * .6f, 1.2.dp.toPx()),
                                strokeWidth = 1.dp.toPx(),
                                cap = StrokeCap.Round,
                            )
                            drawRect(voiceHalo, alpha = voiceLight.value)
                            drawRoundRect(rim, cornerRadius = CornerRadius(radius), style = Stroke(.7.dp.toPx()))
                            if (focusLight.value > 0f) {
                                drawRoundRect(
                                    focusRim, cornerRadius = CornerRadius(radius),
                                    style = Stroke(1.2.dp.toPx()), alpha = focusLight.value * .85f,
                                )
                            }
                        }
                    }
                }.clip(RoundedCornerShape(cornerRadius)),
                contentAlignment = Alignment.Center,
                content = content,
            )
        }
    }
}

internal enum class SignatureGlyph { ADD, MIC, SEND, PAUSE, PLAY }

/** Thin, consistent line icons from the reference, with independent 48dp hit targets. */
@Composable
internal fun SignatureIcon(glyph: SignatureGlyph, modifier: Modifier = Modifier, color: Color = SignatureInk) {
    Canvas(modifier.size(26.dp)) {
        val u = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, point(x1, y1), point(x2, y2), 1.45f * u, StrokeCap.Round)
        when (glyph) {
            SignatureGlyph.ADD -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            SignatureGlyph.SEND -> { line(12f, 21f, 12f, 3f); line(4f, 11f, 12f, 3f); line(12f, 3f, 20f, 11f) }
            SignatureGlyph.PAUSE -> { line(8f, 5f, 8f, 19f); line(16f, 5f, 16f, 19f) }
            SignatureGlyph.PLAY -> {
                val path = Path().apply { moveTo(8f*u, 4f*u); lineTo(19f*u, 12f*u); lineTo(8f*u, 20f*u); close() }
                drawPath(path, color, style = Stroke(1.45f*u))
            }
            SignatureGlyph.MIC -> {
                drawRoundRect(color, point(9f, 2f), Size(6f*u, 13f*u), CornerRadius(3f*u), style = Stroke(1.45f*u))
                drawArc(color, 0f, 180f, false, point(5.5f, 6f), Size(13f*u, 13f*u), style = Stroke(1.45f*u, cap = StrokeCap.Round))
                line(5.5f, 11f, 5.5f, 12.5f); line(18.5f, 11f, 18.5f, 12.5f)
                line(12f, 19f, 12f, 22f); line(9f, 22f, 15f, 22f)
            }
        }
    }
}

@Composable
internal fun SignatureAction(
    glyph: SignatureGlyph,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
) {
    Box(
        modifier.size(48.dp).clip(CircleShape)
            .background(
                when {
                    !selected -> Color.Transparent
                    // On the R3/R4 rain the selected action is a white veil, not teal.
                    com.cyclone.mobile.ui.v32.ask.LocalAskBackdrop.current != null -> Color.White.copy(alpha = .14f)
                    else -> SignatureTeal.copy(alpha = .12f)
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { SignatureIcon(glyph, color = SignatureInk.copy(alpha = if (enabled) 1f else .45f)) }
}

/** Match system-bar contrast while this destination is visible, then restore the host state. */
@Composable
internal fun CycloneSignatureSystemBars(enabled: Boolean = true, glass: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()
    androidx.compose.runtime.DisposableEffect(activity, enabled, glass) {
        val window = activity?.window
        if (window == null || !enabled) return@DisposableEffect onDispose {}
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        val statusLight = controller.isAppearanceLightStatusBars
        val navigationLight = controller.isAppearanceLightNavigationBars
        val statusColor = window.statusBarColor
        val navigationColor = window.navigationBarColor
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        // R5: the glass world's graphite canvas; the teal canvas elsewhere.
        val bars = if (glass) android.graphics.Color.rgb(5, 6, 8) else android.graphics.Color.rgb(6, 26, 32)
        window.statusBarColor = bars
        window.navigationBarColor = bars
        onDispose {
            controller.isAppearanceLightStatusBars = statusLight
            controller.isAppearanceLightNavigationBars = navigationLight
            window.statusBarColor = statusColor
            window.navigationBarColor = navigationColor
        }
    }
}
