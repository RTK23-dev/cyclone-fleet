package com.cyclone.mobile.ui.v32

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private object TaskCardSwipeSelection { var id by mutableStateOf<String?>(null) }

/** A short horizontal drag reveals a glass action; a deliberate full drag performs it. */
@Composable
internal fun CycloneSwipeTaskCard(
    id: String,
    canClear: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val cleared = TaskCardDismissals.cleared(context)
    if (canClear && id in cleared) return
    val scope = rememberCoroutineScope()
    val open by rememberUpdatedState(onOpen)
    var offset by remember(id) { mutableFloatStateOf(0f) }
    var width by remember(id) { mutableFloatStateOf(0f) }
    var motion by remember(id) { mutableStateOf<Job?>(null) }
    val actionWidth = with(LocalDensity.current) { 100.dp.toPx() }
    fun close() {
        motion?.cancel()
        motion = scope.launch { animate(offset, 0f, animationSpec = spring()) { value, _ -> offset = value } }
    }
    fun performOpen() { close(); open() }
    fun performClear() {
        if (canClear) { motion?.cancel(); TaskCardDismissals.clear(context, id) }
    }
    fun settle() {
        val target = TaskCardSwipePolicy.settle(offset, width, actionWidth, canClear)
        when (target) {
            TaskCardSwipeTarget.OPEN -> performOpen()
            TaskCardSwipeTarget.CLEAR -> performClear()
            else -> {
                val destination = when (target) {
                    TaskCardSwipeTarget.OPEN_BUTTON -> actionWidth
                    TaskCardSwipeTarget.CLEAR_BUTTON -> -actionWidth
                    else -> 0f
                }
                motion = scope.launch { animate(offset, destination, animationSpec = spring()) { value, _ -> offset = value } }
            }
        }
    }
    LaunchedEffect(TaskCardSwipeSelection.id, canClear) {
        if (TaskCardSwipeSelection.id != id || (!canClear && offset < 0f)) close()
    }
    Box(modifier.fillMaxWidth().clipToBounds().onSizeChanged { width = it.width.toFloat() }
        .semantics {
            customActions = buildList {
                add(CustomAccessibilityAction("Open") { performOpen(); true })
                if (canClear) add(CustomAccessibilityAction("Clear result card") { performClear(); true })
            }
        }) {
        if (offset != 0f) {
            val opening = offset > 0f
            CycloneLiquidPanel(
                modifier = Modifier.matchParentSize().wrapContentWidth(if (opening) Alignment.Start else Alignment.End)
                    .width(92.dp).padding(vertical = 1.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .18f), RoundedCornerShape(24.dp))
                    .clickable(role = Role.Button) { if (opening) performOpen() else performClear() },
                cornerRadius = 24.dp,
            ) { Text(if (opening) "Open" else "Clear", color = MaterialTheme.colorScheme.onSurface) }
        }
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }
            .pointerInput(id, canClear, actionWidth) {
                detectHorizontalDragGestures(
                    onDragStart = { motion?.cancel(); TaskCardSwipeSelection.id = id },
                    onDragCancel = { close() },
                    onDragEnd = { settle() },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        offset = (offset + amount).coerceIn(if (canClear) -width else 0f, width)
                    },
                )
            }) { content() }
    }
}
