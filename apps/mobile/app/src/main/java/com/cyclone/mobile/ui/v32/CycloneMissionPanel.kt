package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.mind.mission.Mission
import com.cyclone.mobile.mind.mission.MissionStatus
import com.cyclone.mobile.mind.mission.OwnerRequest
import com.cyclone.mobile.mind.mission.OwnerRequestKind
import com.cyclone.mobile.mind.mission.OwnerResponse

/** The running mission: its plan, what it just did, what it needs from the owner, and Stop. */
@Composable
fun CycloneLiveMissionCard(mission: Mission) {
    val context = LocalContext.current
    val moment = com.cyclone.mobile.owner.rememberOwnerMoment()
    CycloneSignatureCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Cyclone Mind · ${statusLabel(mission)}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(mission.goal, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            // Plan 38: the plan with its branch after a diversion (struck dropped steps, the new route, "See v1").
            CyclonePlanView(mission)
            mission.events.takeLast(3).forEach { event ->
                Text((if (event.ok) "· " else "! ") + event.text, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            moment?.takeIf { it.taskId == "mission-${mission.id}" }?.let { CycloneOwnerCard(it, framed = false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                // Planes (plan 25): the same pill as on the overlay.
                com.cyclone.mobile.ui.overlay.PlanePill()
                // Plan 38: a real pause (the mission holds at its next step) and Resume, through Task Kit.
                TextButton(onClick = { com.cyclone.mobile.task.TaskCommands.send(context, "mission-${mission.id}",
                        if (mission.paused) com.cyclone.mobile.task.TaskCommand.Unpause else com.cyclone.mobile.task.TaskCommand.Pause) },
                    shape = RoundedCornerShape(18.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text(if (mission.paused) "Resume" else "Pause") }
                TextButton(onClick = { com.cyclone.mobile.task.TaskCommands.send(context, "mission-${mission.id}", com.cyclone.mobile.task.TaskCommand.Stop) }, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Stop the mission" }) { Text("Stop") }
            }
        }
    }
}

/**
 * Plan 26 §6: missions working behind the front one, each on a background screen of its own. The owner can stop
 * one (through Task Kit, like every task button); their questions come up one at a time on the owner card.
 */
@Composable
fun CycloneBehindMissions() {
    val context = LocalContext.current
    val behind by MindMissions.behind.collectAsState()
    val viewed by com.cyclone.mobile.task.AskWhileWorking.viewed.collectAsState()
    if (behind.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Behind your screen", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp))
        behind.forEach { mission ->
            // Plan 38 (D7): opening a row makes it the task the Ask bar steers; tapping it again goes back to the front task.
            val open = viewed == "mission-${mission.id}"
            CycloneSignatureCard(modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        com.cyclone.mobile.task.AskWhileWorking.view(if (open) null else "mission-${mission.id}")
                    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(mission.goal, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(statusLabel(mission) + if (open) " · the Ask bar changes this task" else "", style = MaterialTheme.typography.bodySmall,
                            color = if (open) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (open) CyclonePlanView(mission)
                    }
                    TextButton(onClick = { com.cyclone.mobile.task.TaskCommands.send(context, "mission-${mission.id}", com.cyclone.mobile.task.TaskCommand.Stop) },
                        shape = RoundedCornerShape(18.dp), modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Stop this task" }) { Text("Stop") }
                }
            }
        }
    }
}

/** Missions that ended recently, with Resume where the conversation can continue. */
@Composable
fun CycloneRecentMissions(limit: Int = 4) {
    val context = LocalContext.current
    val history by MindMissions.history.collectAsState()
    val live by MindMissions.live.collectAsState()
    LaunchedEffect(Unit) { MindMissions.refresh(context) }
    val behind by MindMissions.behind.collectAsState()
    val running = behind.map { it.id }.toSet() + listOfNotNull(live?.id)
    val recent = history.filter { it.id !in running }.take(limit)
    if (recent.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Missions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp))
        recent.forEach { mission ->
            CycloneSignatureCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(mission.goal, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(statusLabel(mission) + (mission.summary.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    CycloneRunLearnActions(mission)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = { MindMissions.delete(context, mission.id) }, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                            Text("Remove")
                        }
                        if (mission.status.resumable) {
                            TextButton(onClick = { MindMissions.resume(context, mission.id) }, enabled = live == null,
                                modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("Resume") }
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(mission: Mission): String = when (mission.status) {
    MissionStatus.RUNNING -> "working · ${mission.turns} steps"
    MissionStatus.WAITING -> "waiting for you"
    MissionStatus.COMPLETED -> "done"
    MissionStatus.GAVE_UP -> "not possible"
    MissionStatus.FAILED -> "failed"
    MissionStatus.CANCELLED -> "stopped"
    MissionStatus.PAUSED -> "paused (time used up)"
    MissionStatus.INTERRUPTED -> "interrupted"
}
