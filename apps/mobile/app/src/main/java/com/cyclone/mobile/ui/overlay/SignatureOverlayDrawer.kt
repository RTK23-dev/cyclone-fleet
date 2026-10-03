package com.cyclone.mobile.ui.overlay

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.cyclone.mobile.ui.v32.CycloneConversationPanel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.v32.SignatureMuted
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal object SignatureDrawerGeometry {
    /** Reserve the system edges and keyboard before measuring the persistent composer. */
    fun availableHeight(screenHeight: Int, keyboardHeight: Int, topClearance: Int = 48,
        restingBottom: Int = 50, keyboardGap: Int = 8): Int =
        (screenHeight - topClearance - maxOf(restingBottom, keyboardHeight + keyboardGap)).coerceAtLeast(0)

    fun visibleHeight(fullHeight: Int, reveal: Float): Int =
        (fullHeight.coerceAtLeast(0) * reveal.coerceIn(0f, 1f)).roundToInt()

    fun revealAfterDrag(start: Float, dragPx: Float, heightPx: Float): Float =
        (start - dragPx / heightPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
}

/**
 * Plan 27: one bottom-anchored stack. From the top: the plane pill ([top], shown in every height while a mission has
 * one), 10 dp, the working card (revealed or folded away), 14 dp, then the Ask bar or the island. Drag down anywhere
 * on the stack folds one height lower (card → island → notification only); drag up opens it again. The composer is
 * never translated, faded, reparented or clipped by the card's animated bounds.
 */
@Composable
internal fun SignatureOverlayDrawer(
    expanded: Boolean,
    minimized: Boolean,
    onCollapse: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    /** Cap for the card above the composer. */
    upperMaxHeight: Dp = Dp.Unspecified,
    top: (@Composable () -> Unit)? = null,
    composer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val collapse by rememberUpdatedState(onCollapse)
    val expand by rememberUpdatedState(onExpand)
    var reveal by remember { mutableFloatStateOf(if (expanded) 1f else 0f) }
    var fullHeight by remember { mutableFloatStateOf(1f) }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragStart by remember { mutableFloatStateOf(0f) }
    var follow by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val density = LocalDensity.current
    val threshold = with(density) { 48.dp.toPx() }

    fun settle(target: Float, after: () -> Unit = {}) {
        settleJob?.cancel()
        settleJob = scope.launch {
            val fromFollow = follow
            animate(0f, 1f, animationSpec = spring(dampingRatio = 0.86f, stiffness = 380f)) { t, _ ->
                follow = fromFollow * (1f - t)
            }
        }
        scope.launch {
            animate(reveal, target, animationSpec = spring(dampingRatio = 0.86f, stiffness = 380f)) { value, _ ->
                reveal = value.coerceIn(0f, 1f)
            }
            after()
        }
    }
    LaunchedEffect(expanded) { settle(if (expanded) 1f else 0f) }

    Column(
        modifier.fillMaxWidth()
            .graphicsLayer { translationY = follow }
            .semantics {
                contentDescription = if (minimized) "Cyclone. Drag up to open, down to hide." else "Cyclone. Drag down to make smaller."
            }
            .pointerInput(minimized, expanded, threshold) {
                detectVerticalDragGestures(
                    onDragStart = {
                        settleJob?.cancel()
                        drag = 0f
                        dragStart = reveal
                    },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        drag += amount
                        // The stack follows the finger: freely downwards, a little resistance upwards.
                        follow = if (drag > 0f) drag * 0.55f else maxOf(-36f * density.density, drag * 0.3f)
                        if (!minimized && expanded) reveal = SignatureDrawerGeometry.revealAfterDrag(dragStart, drag, fullHeight)
                    },
                    onDragCancel = { settle(if (expanded) 1f else 0f) },
                    onDragEnd = {
                        when {
                            drag >= threshold -> settle(0f) { collapse() }
                            minimized && drag <= -threshold * .6f -> { settle(0f); expand() }
                            else -> settle(if (expanded) 1f else 0f)
                        }
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (top != null) {
            Box(Modifier.fillMaxWidth()) { top() }
            Spacer(Modifier.height(OverlayStackGeometry.PILL_GAP_DP.dp))
        }
        // Clip only the card. The composer below is never translated, faded, reparented, or clipped by it.
        Box(
            Modifier.weight(1f, fill = false).fillMaxWidth()
                .then(if (upperMaxHeight != Dp.Unspecified) Modifier.heightIn(max = upperMaxHeight) else Modifier)
                .clip(RoundedCornerShape(OverlayStackGeometry.CARD_RADIUS_DP.dp))
                .then(if (reveal == 0f) Modifier.clearAndSetSemantics {} else Modifier)
                .graphicsLayer { alpha = (reveal * 1.6f).coerceIn(0f, 1f) }
                .layout { measurable, constraints ->
                    val measured = measurable.measure(constraints.copy(minHeight = 0))
                    fullHeight = measured.height.toFloat().coerceAtLeast(1f)
                    val visible = SignatureDrawerGeometry.visibleHeight(measured.height, reveal)
                    layout(measured.width, visible) {
                        measured.placeRelative(0, visible - measured.height)
                    }
                },
        ) {
            // Bottom-anchored. It scrolls only when a large font makes the card taller than its room, so the scroll
            // never takes the fold gesture from the stack otherwise.
            val scroll = rememberScrollState()
            Column(
                Modifier.fillMaxWidth().verticalScroll(scroll, enabled = scroll.maxValue > 0, reverseScrolling = true),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
        Spacer(Modifier.height((OverlayStackGeometry.CARD_GAP_DP * reveal).dp))
        composer()
    }
}
