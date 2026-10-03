package com.cyclone.mobile.ui.v32

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class DrawerSettle { OPEN, COLLAPSE, EXPAND }

internal object CycloneChatDrawerGesturePolicy {
    const val COLLAPSE_THRESHOLD_DP = 72f
    const val EXPAND_THRESHOLD_DP = 34f

    fun shouldCollapse(downwardDragDp: Float): Boolean =
        downwardDragDp >= COLLAPSE_THRESHOLD_DP

    fun shouldExpand(verticalDragDp: Float): Boolean =
        verticalDragDp <= -EXPAND_THRESHOLD_DP
}

@Composable
internal fun CycloneChatDrawerSurface(
    onCollapse: () -> Unit,
    onExpand: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = .98f),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    outlineColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f),
    handleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .40f),
    contentPadding: PaddingValues = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val collapseThresholdPx = with(density) { CycloneChatDrawerGesturePolicy.COLLAPSE_THRESHOLD_DP.dp.toPx() }
    val expandThresholdPx = with(density) { CycloneChatDrawerGesturePolicy.EXPAND_THRESHOLD_DP.dp.toPx() }


    fun settle(target: DrawerSettle) {
        settleJob?.cancel()
        settleJob = scope.launch {
            val targetOffset = when (target) {
                DrawerSettle.COLLAPSE -> collapseThresholdPx * 1.18f
                DrawerSettle.EXPAND -> -expandThresholdPx * 1.18f
                DrawerSettle.OPEN -> 0f
            }
            animate(
                initialValue = dragOffset,
                targetValue = targetOffset,
                animationSpec = spring(
                    dampingRatio = CycloneConversationTokens.drawerDampingRatio,
                    stiffness = CycloneConversationTokens.drawerStiffness,
                ),
            ) { value, _ -> dragOffset = value }
            when (target) {
                DrawerSettle.COLLAPSE -> onCollapse()
                DrawerSettle.EXPAND -> onExpand?.invoke()
                DrawerSettle.OPEN -> Unit
            }
            dragOffset = 0f
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = dragOffset },
        shape = RoundedCornerShape(CycloneConversationTokens.sheetRadius),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(.8.dp, outlineColor),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .semantics {
                        contentDescription = if (onExpand == null) {
                            "Drag down or tap to minimize Cyclone chat"
                        } else {
                            "Drag up to expand or down to hide Cyclone chat"
                        }
                    }
                    .clickable(role = Role.Button) {
                        if (onExpand == null) settle(DrawerSettle.COLLAPSE) else settle(DrawerSettle.EXPAND)
                    }
                    .pointerInput(collapseThresholdPx, expandThresholdPx, onExpand) {
                        detectVerticalDragGestures(
                            onDragStart = { settleJob?.cancel() },
                            onVerticalDrag = { change, amount ->
                                change.consume()
                                val next = dragOffset + amount
                                dragOffset = if (onExpand == null) next.coerceAtLeast(0f)
                                else next.coerceIn(-expandThresholdPx * 1.6f, collapseThresholdPx * 1.6f)
                            },
                            onDragCancel = { settle(DrawerSettle.OPEN) },
                            onDragEnd = {
                                when {
                                    dragOffset >= collapseThresholdPx -> settle(DrawerSettle.COLLAPSE)
                                    onExpand != null && dragOffset <= -expandThresholdPx -> settle(DrawerSettle.EXPAND)
                                    else -> settle(DrawerSettle.OPEN)
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = 42.dp, height = 4.dp)
                        .background(handleColor, CircleShape),
                )
            }

            Column(
                Modifier.fillMaxWidth().padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(CycloneConversationTokens.space8),
                content = content,
            )
        }
    }
}

@Composable
internal fun CycloneCollapsedAskPill(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    status: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = .98f),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    secondaryColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    outlineColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f),
) {
    var dragDp by remember { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    Box(
        modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragDp = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragDp += with(density) { amount.toDp().value }
                    },
                    onDragCancel = { dragDp = 0f },
                    onDragEnd = {
                        if (CycloneChatDrawerGesturePolicy.shouldExpand(dragDp)) onExpand()
                        dragDp = 0f
                    },
                )
            },
    ) {
        Surface(
            onClick = onExpand,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "Ask Cyclone. Open current chat."
            },
            shape = RoundedCornerShape(999.dp),
            color = containerColor,
            contentColor = contentColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(.8.dp, outlineColor),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = contentColor.copy(alpha = .08f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(25.dp), tint = contentColor)
                        if (active) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(3.dp)
                                    .size(8.dp)
                                    .background(accentColor, CircleShape),
                            )
                        }
                    }
                }

                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text("Ask Cyclone", style = MaterialTheme.typography.bodyLarge, color = contentColor)
                    status?.takeIf(String::isNotBlank)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = secondaryColor,
                            maxLines = 1,
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = accentColor.copy(alpha = .16f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.KeyboardArrowUp,
                            null,
                            Modifier.size(26.dp),
                            tint = accentColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CycloneSheetDismissHandle(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    handleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .40f),
) {
    var dragDp by remember { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics { contentDescription = "Drag down or tap to close" }
            .clickable(role = Role.Button, onClick = onDismiss)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragDp = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragDp += with(density) { amount.toDp().value }
                    },
                    onDragCancel = { dragDp = 0f },
                    onDragEnd = {
                        if (CycloneChatDrawerGesturePolicy.shouldCollapse(dragDp)) onDismiss()
                        dragDp = 0f
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 42.dp, height = 4.dp)
                .background(handleColor, CircleShape),
        )
    }
}
