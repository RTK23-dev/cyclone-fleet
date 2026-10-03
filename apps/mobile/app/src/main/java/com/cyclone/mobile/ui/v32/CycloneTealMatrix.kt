package com.cyclone.mobile.ui.v32

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Teal Matrix: Cyclone's single app-wide visual language.
 *
 * Deep teal canvas, a quiet dot-matrix wave field, and translucent teal glass cards with a soft
 * optical rim. The Ask Cyclone capsule ([CycloneSignatureGlass]) is the reference object; every
 * card, tray and tile here is the same material at a calmer intensity. Everything is static and
 * cached per size, so no screen owns an animation loop just to look alive.
 */
internal object TealMatrix {
    val Canvas = Color(0xFF041519)
    val CanvasMid = Color(0xFF07262C)
    val Deep = Color(0xFF061A20)
    val Ink = SignatureInk
    val Muted = SignatureMuted
    val Teal = SignatureTeal
    val Bright = Color(0xFF41D7CB)
    val Glow = Color(0xFF2FB8B0)
    val Success = Color(0xFF4FD9B4)
    val Attention = Color(0xFFFF7A74)
    val Caution = Color(0xFFE9C78B)
    val Tile = Color(0xFF0E3B41)
    val Hairline = Color(0xFF2A5A5F)
}

/** The accent for icons and actions: teal on the teal canvas, white on the R5 rain. */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun matrixAccent(): Color =
    if (com.cyclone.mobile.ui.v32.ask.inAskGlass()) com.cyclone.mobile.ui.v32.ask.AskGlass.Ink else TealMatrix.Teal

/** Semantic glass tint. Only the rim, mists and backing change; copy stays equally readable. */
internal enum class MatrixTone(val accent: Color) {
    NEUTRAL(SignatureTeal),
    ACTIVE(TealMatrix.Bright),
    SUCCESS(TealMatrix.Success),
    ATTENTION(TealMatrix.Attention),
}

/**
 * Full-canvas backdrop: layered teal gradient, two aurora blooms and diagonal bands of dots that
 * swell and fade like the reference's flowing matrix wave. Drawn once per size into the cache.
 */
@Composable
internal fun TealMatrixStaticBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier.drawWithCache {
            val base = Brush.verticalGradient(
                0f to TealMatrix.Canvas,
                .38f to TealMatrix.CanvasMid,
                .72f to TealMatrix.Deep,
                1f to TealMatrix.Canvas,
            )
            val auroraTop = Brush.radialGradient(
                listOf(TealMatrix.Glow.copy(alpha = .22f), Color.Transparent),
                center = Offset(size.width * .82f, size.height * .06f),
                radius = size.maxDimension * .55f,
            )
            val auroraLow = Brush.radialGradient(
                listOf(TealMatrix.Teal.copy(alpha = .14f), Color.Transparent),
                center = Offset(size.width * .08f, size.height * .92f),
                radius = size.maxDimension * .6f,
            )
            data class Dot(val center: Offset, val radius: Float, val alpha: Float)
            val unit = 1.dp.toPx()
            val spacing = 11f * unit
            val canvas = size
            val dots = buildList {
                if (canvas.width > 0f && canvas.height > 0f) {
                    val columns = (canvas.width / spacing).toInt() + 1
                    val rows = (canvas.height / spacing).toInt() + 1
                    for (row in 0..rows) {
                        for (column in 0..columns) {
                            val x = column * spacing + if (row % 2 == 0) 0f else spacing / 2f
                            val y = row * spacing
                            val nx = x / canvas.width
                            val ny = y / canvas.height
                            // Two soft ribbons sweep diagonally; dots only exist inside them.
                            val ribbonA = sin((nx * 2.1f - ny * 3.4f + .35f) * PI).toFloat()
                            val ribbonB = sin((nx * 1.4f + ny * 2.6f - 1.9f) * PI).toFloat()
                            val strength = maxOf(
                                ((ribbonA - .72f) / .28f).coerceIn(0f, 1f),
                                ((ribbonB - .80f) / .20f).coerceIn(0f, 1f) * .7f,
                            )
                            if (strength < .04f) continue
                            val edge = (1f - ny * .35f)
                            add(Dot(Offset(x, y), (.4f + 1.05f * strength) * unit, strength * edge * .30f))
                        }
                    }
                }
            }
            onDrawBehind {
                drawRect(base)
                drawRect(auroraTop)
                drawRect(auroraLow)
                dots.forEach { drawCircle(TealMatrix.Teal.copy(alpha = it.alpha), it.radius, it.center) }
            }
        },
    )
}

/** Readable teal glass card. [tone] tints the rim for state (needs attention, done, working). */
@Composable
internal fun CycloneMatrixCard(
    modifier: Modifier = Modifier,
    tone: MatrixTone = MatrixTone.NEUTRAL,
    cornerRadius: Dp = 22.dp,
    onClick: (() -> Unit)? = null,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    CycloneSignatureGlass(
        modifier = modifier
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        textured = false,
        solidBacking = true,
        cornerRadius = cornerRadius,
        accent = tone.accent,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** Rounded glass tile that hosts an icon, like the reference's quick-action and app badges. */
@Composable
internal fun CycloneMatrixIconTile(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = TealMatrix.Teal,
    /** R5: a coloured tile (Settings' groups); null keeps the soft white glass square. */
    fill: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    if (com.cyclone.mobile.ui.v32.ask.inAskGlass()) {
        // R5: a soft white glass square like the AI page's list tiles, or a colour tile where a group has one.
        Box(
            modifier.size(size)
                .clip(com.kyant.capsule.ContinuousRoundedRectangle(size * .3f))
                .background(fill?.copy(alpha = .88f) ?: Color.White.copy(alpha = .12f))
                .border(.6.dp, Color.White.copy(alpha = .14f), com.kyant.capsule.ContinuousRoundedRectangle(size * .3f)),
            contentAlignment = Alignment.Center,
            content = content,
        )
        return
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * .32f))
            .background(
                Brush.verticalGradient(listOf(tint.copy(alpha = .20f), TealMatrix.Tile.copy(alpha = .75f))),
            )
            .border(.7.dp, tint.copy(alpha = .30f), RoundedCornerShape(size * .32f)),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** Quick action chip: icon tile plus label inside one glass pill. */
@Composable
internal fun CycloneMatrixQuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Glass answers touch with light: a short spring-in and a lit rim while pressed.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.965f else 1f,
        spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "Quick action press",
    )
    CycloneSignatureGlass(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
        textured = false,
        cornerRadius = 18.dp,
        focused = pressed,
        refract = true,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CycloneMatrixIconTile(size = 32.dp) {
                Icon(icon, null, Modifier.size(18.dp), tint = matrixAccent())
            }
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = TealMatrix.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Filled round check used for completed items. */
@Composable
internal fun CycloneMatrixCheck(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(TealMatrix.Success.copy(alpha = .92f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, null, Modifier.size(size * .66f), tint = TealMatrix.Deep)
    }
}

/** Soft red ring with an exclamation for items that need the user. */
@Composable
internal fun CycloneMatrixAttention(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(TealMatrix.Attention.copy(alpha = .16f))
            .border(1.2.dp, TealMatrix.Attention, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PriorityHigh, null, Modifier.size(size * .6f), tint = TealMatrix.Attention)
    }
}

/**
 * Static open ring for running items. It shows [fraction] when known and a fixed 3/4 arc otherwise;
 * the live task card owns motion, list rows stay still.
 */
@Composable
internal fun CycloneMatrixRing(fraction: Float?, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val accent = matrixAccent()
    val bright = if (com.cyclone.mobile.ui.v32.ask.inAskGlass()) Color.White else TealMatrix.Bright
    Canvas(modifier.size(size)) {
        val stroke = 2.4.dp.toPx()
        val inset = stroke / 2f
        val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
        drawArc(accent.copy(alpha = .18f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        val sweep = 360f * (fraction?.coerceIn(.06f, 1f) ?: .72f)
        drawArc(
            Brush.sweepGradient(listOf(accent.copy(alpha = .35f), bright, accent)),
            -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * Centered brand bar from the reference: a leading menu/back action, the Cyclone word mark and the
 * spiral mark. Actions keep 44dp targets.
 */
@Composable
internal fun CycloneMatrixAppBar(
    onLeading: () -> Unit,
    leadingDescription: String,
    back: Boolean = false,
    title: String = "Cyclone",
    onMark: (() -> Unit)? = null,
    markDescription: String = "Cyclone",
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClick = onLeading)
                .semantics { contentDescription = leadingDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (back) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Menu,
                null,
                Modifier.size(22.dp),
                tint = TealMatrix.Ink,
            )
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = TealMatrix.Ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Box(
            Modifier.size(44.dp).clip(CircleShape)
                .then(if (onMark != null) Modifier.clickable(role = Role.Button, onClick = onMark) else Modifier)
                .semantics { contentDescription = markDescription },
            contentAlignment = Alignment.Center,
        ) {
            CycloneOrbitMark(Modifier.size(28.dp))
        }
    }
}

/** Section label row: calm title, optional teal trailing action ("See all"). */
@Composable
internal fun CycloneMatrixSectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = TealMatrix.Ink,
        )
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button, onClick = onAction)
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 6.dp, vertical = 13.dp),
                style = MaterialTheme.typography.labelLarge,
                color = matrixAccent(),
            )
        }
    }
}
