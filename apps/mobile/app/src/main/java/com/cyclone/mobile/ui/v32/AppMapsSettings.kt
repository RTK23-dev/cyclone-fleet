package com.cyclone.mobile.ui.v32

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.graphv2.AtlasRuntime
import com.cyclone.mobile.brain.graphv2.AtlasMapStatus
import com.cyclone.mobile.brain.graphv2.AtlasPlaceSummary
import com.cyclone.mobile.brain.graphv2.GraphEdgeType
import com.cyclone.mobile.mapping.run.MappingDriverRuntime
import com.cyclone.mobile.mapping.run.MappingNotificationService
import com.cyclone.mobile.mapping.run.MappingRunUi
import com.cyclone.mobile.mapping.session.MappingSessionException
import com.cyclone.mobile.mapping.session.MappingSessionState
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * App Maps on the phone: start a mapping pass, watch/stop it, and see the rooms each place has.
 *
 * Compact phone catalog. A Glass action is shown only when a real navigation callback is supplied.
 */
@Composable
internal fun AppMapsSettingsSection(
    context: Context,
    refreshTick: Int,
    onOpenOnGlass: ((String, String) -> Unit)? = null,
) {
    var run by remember { mutableStateOf(MappingDriverRuntime.current(context)) }
    var picking by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            run = MappingDriverRuntime.current(context)
        }
    }
    val places = remember(refreshTick, run?.rooms, run?.state) {
        AppLearnerRuntime.initialize(context)
        AtlasRuntime.catalog.all()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("App Maps", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Map coverage stored on this phone from Follow Me and mapping sessions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        MappingControlCard(
            context = context,
            run = run,
            onMapApp = { picking = !picking; startError = null },
            onChanged = { run = MappingDriverRuntime.current(context) },
        )
        startError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (picking) {
            LauncherAppPicker(context) { packageName ->
                picking = false
                startError = try {
                    MappingDriverRuntime.startForPackage(context, packageName)
                    run = MappingDriverRuntime.current(context)
                    null
                } catch (error: MappingSessionException) {
                    MappingCopy.startError(error.code)
                } catch (error: IllegalArgumentException) {
                    MappingCopy.startError("INVALID_REQUEST")
                }
            }
        }

        if (places.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "No app maps yet. Use Follow Me or a supported mapping session to teach Cyclone an app's rooms and doors.",
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            places.forEach { summary ->
                AppMapPlaceCard(summary, onOpenOnGlass)
            }
        }
    }
}

@Composable
private fun MappingControlCard(
    context: Context,
    run: MappingRunUi?,
    onMapApp: () -> Unit,
    onChanged: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val active = run != null && !run.state.terminal
            if (run == null || !active) {
                Text("Map an app", style = MaterialTheme.typography.titleMedium)
                Text(
                    MappingCopy.HOW_IT_WORKS,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                run?.let {
                    Text(
                        "Last pass: ${it.label} · ${MappingNotificationService.stateLine(it)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onMapApp) { Text("Choose an app") }
                    if (run != null) {
                        OutlinedButton(onClick = { shareReport(context) }) { Text("Share report") }
                    }
                }
            } else {
                Text("Mapping ${run.label}", style = MaterialTheme.typography.titleMedium)
                Text(MappingNotificationService.stateLine(run), style = MaterialTheme.typography.bodyMedium)
                if (run.darkDoors > 0) {
                    Text(
                        "${run.darkDoors} doors left dark (pay, send, delete, sign-out or permission).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (run.state) {
                        MappingSessionState.RUNNING -> OutlinedButton(onClick = {
                            MappingDriverRuntime.pause(context, run.jobId); onChanged()
                        }) { Text("Pause") }
                        MappingSessionState.PAUSED,
                        MappingSessionState.HUMAN_CONTROL,
                        MappingSessionState.NEEDS_SECRET -> OutlinedButton(onClick = {
                            MappingDriverRuntime.resume(context, run.jobId); onChanged()
                        }) { Text("Resume") }
                        else -> Unit
                    }
                    Button(onClick = { MappingDriverRuntime.stop(context, run.jobId); onChanged() }) { Text("Stop") }
                }
            }
        }
    }
}

@Composable
private fun LauncherAppPicker(context: Context, onPick: (String) -> Unit) {
    val apps = remember { MappingCopy.launcherApps(context) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                "Pick one app. Start with something simple like Clock or Calculator.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            apps.forEach { (packageName, label) ->
                Text(
                    label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(packageName) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun AppMapPlaceCard(
    summary: AtlasPlaceSummary,
    onOpenOnGlass: ((String, String) -> Unit)?,
) {
    val place = summary.place
    val source = place.packageName ?: place.origin.orEmpty()
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(place.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    place.mapStatus.settingsLabel(),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                "${MappingCopy.personaLabel(place.persona.wireValue)} · ${summary.screenCount} screens · ${summary.edgeCount} edges",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Observed ${formatTime(place.lastObservedAtEpochMillis)} · verified ${formatTime(place.lastVerifiedAtEpochMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary.screenCount > 0) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide rooms" else "Show rooms")
                }
                if (expanded) {
                    roomLines(summary).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (onOpenOnGlass != null) {
                OutlinedButton(
                    onClick = { onOpenOnGlass(place.id, place.persona.wireValue) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open on Glass")
                }
            }
        }
    }
}

/** The phone's small view of the house: one line per room with its outgoing door count. */
private fun roomLines(summary: AtlasPlaceSummary): List<String> {
    val snapshot = AtlasRuntime.store.snapshot(summary.place.key) ?: return emptyList()
    val navigation = setOf(GraphEdgeType.NAVIGATES_TO, GraphEdgeType.OPENS, GraphEdgeType.SUBMITS)
    val outgoing = snapshot.edges
        .filter { it.key.type in navigation }
        .groupingBy { it.key.from }
        .eachCount()
    return snapshot.screens.take(40).map { screen ->
        val doors = outgoing[screen.screenId] ?: 0
        val danger = if (screen.danger.wireValue != "none") " · ${screen.danger.wireValue}" else ""
        "${screen.purpose} · $doors ${if (doors == 1) "door" else "doors"}$danger"
    }
}

private fun shareReport(context: Context) {
    val report = MappingDriverRuntime.report(context) ?: return
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Cyclone mapping report")
        .putExtra(Intent.EXTRA_TEXT, report)
    context.startActivity(Intent.createChooser(send, "Share mapping report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal object MappingCopy {
    const val HOW_IT_WORKS =
        "Cyclone opens the app and walks its tabs, menus and settings on this phone, one tap at a time. " +
            "It never pays, sends, deletes, signs out or grants permissions. Keep your hands off the phone; " +
            "touching the screen pauses mapping."

    fun personaLabel(persona: String): String = when (persona) {
        "mapping" -> "Mapping pass"
        else -> "Your map (Follow Me)"
    }

    fun startError(code: String): String = when (code) {
        "MAPPING_PLANE_BUSY" -> "A mapping pass is already running. Stop it first."
        "HUMAN_HAS_CONTROL" -> "You have control of the phone right now. Give control back to Cyclone, then try again."
        "SESSION_REQUIRED", "SESSION_DISPLAY_MISMATCH" ->
            "Cyclone can't see the screen yet. Turn on Cyclone's accessibility service, then try again."
        else -> "Mapping couldn't start ($code)."
    }

    fun launcherApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == context.packageName) return@mapNotNull null
                pkg to info.loadLabel(pm).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
}

private fun AtlasMapStatus.settingsLabel(): String = when (this) {
    AtlasMapStatus.UNMAPPED -> "Unmapped"
    AtlasMapStatus.PARTIAL -> "Partial"
    AtlasMapStatus.MAPPED -> "Mapped"
    AtlasMapStatus.STALE -> "Stale"
}

private fun formatTime(epochMillis: Long?): String =
    epochMillis?.let { runCatching { Instant.ofEpochMilli(it).toString() }.getOrNull() } ?: "never"
