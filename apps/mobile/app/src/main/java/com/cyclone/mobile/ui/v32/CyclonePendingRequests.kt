package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.runtime.background.PendingWorkspaceRequest
import com.cyclone.mobile.runtime.background.WorkspaceDestinationHint
import com.cyclone.mobile.runtime.background.WorkspaceTasks

/**
 * FIFO presentation: queued work has Steer + Stop and starts automatically when the
 * current phone task finishes or fails. The queue sits under the live task so Ask Cyclone
 * stays put.
 */
@Composable
fun CyclonePendingRequests(onOpen: () -> Unit = {}) {
    val requests by WorkspaceTasks.requests.state.collectAsState()
    val context = LocalContext.current
    if (requests.isEmpty()) return
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var steering by remember { mutableStateOf<PendingWorkspaceRequest?>(null) }
    val visible = if (keyboardOpen) requests.take(KEYBOARD_VISIBLE_QUEUE_CARDS) else requests

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Up next",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                requests.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        visible.forEach { request ->
            QueuedTaskCard(
                request = request,
                compact = keyboardOpen,
                onSteer = { steering = if (steering?.id == request.id) null else request },
                onStop = {
                    if (steering?.id == request.id) steering = null
                    WorkspaceTasks.requests.remove(request.id)
                },
            )
            if (steering?.id == request.id) {
                SteerDestinationSheet(
                    request = request,
                    destinations = WorkspaceTasks.queueDestinations(context),
                    onSelected = { destination ->
                        WorkspaceTasks.requests.steer(request.id, destination)
                        WorkspaceTasks.tryPromoteNext(context)
                        steering = null
                    },
                )
            }
        }
        if (keyboardOpen && requests.size > visible.size) {
            Text(
                "+${requests.size - visible.size} more queued",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun QueuedTaskCard(
    request: PendingWorkspaceRequest,
    compact: Boolean,
    onSteer: () -> Unit,
    onStop: () -> Unit,
) {
    val context = LocalContext.current
    val target = remember(request) { WorkspaceTasks.resolveQueueTarget(context, request) }
    val status = remember(request) { WorkspaceTasks.queuePresentationStatus(context, request) }
    val model = TaskGlassPresentation.queued(
        request = request,
        appLabel = target?.appLabel ?: request.targetAppLabel,
        packageName = target?.packageName ?: request.targetPackageName,
        status = status,
    )
    CycloneSignatureCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(if (compact) 12.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CycloneAppIcon(model.packageName, Modifier.size(36.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        model.taskLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        model.status,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    onClick = onSteer,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = model.steerContentDescription },
                    shape = RoundedCornerShape(18.dp),
                ) { Text("Steer") }
                TextButton(
                    onClick = onStop,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = model.stopContentDescription },
                    shape = RoundedCornerShape(18.dp),
                ) { Text("Stop") }
            }
        }
    }
}

/** Inline by design: an AccessibilityService overlay cannot safely depend on an Activity dialog token. */
@Composable
private fun SteerDestinationSheet(
    request: PendingWorkspaceRequest,
    destinations: List<WorkspaceDestinationHint>,
    onSelected: (WorkspaceDestinationHint) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        destinations.forEach { destination ->
            CycloneLiquidFilterChip(
                selected = request.preferredDestination?.androidUserId == destination.androidUserId,
                onClick = { onSelected(destination) },
                label = destination.label,
                modifier = Modifier.semantics { contentDescription = "Steer to ${destination.label}" },
            )
        }
    }
}

private const val KEYBOARD_VISIBLE_QUEUE_CARDS = 2
