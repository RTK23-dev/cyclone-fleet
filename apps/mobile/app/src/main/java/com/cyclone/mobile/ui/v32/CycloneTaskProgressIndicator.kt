package com.cyclone.mobile.ui.v32

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.ProgressBarRangeInfo

/**
 * One task progress language for Ask Cyclone.
 *
 * Null means the backend has no stable denominator and therefore renders indeterminate progress.
 * A concrete value is always grounded in TaskPresentationSnapshot and animates only visually.
 */
@Composable
fun CycloneTaskProgressIndicator(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val palette = cycloneConversationPalette()
    if (progress == null) {
        LinearProgressIndicator(
            modifier = modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = palette.active,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        return
    }

    val target = progress.coerceIn(0f, 1f)
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(CycloneConversationTokens.stateTransitionMs),
        label = "task-progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = target,
                    range = 0f..1f,
                    steps = 0,
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(4.dp)
                .background(palette.active),
        )
    }
}
