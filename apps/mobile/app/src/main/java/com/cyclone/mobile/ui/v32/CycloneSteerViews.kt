package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.mind.MindPlanStep
import com.cyclone.mobile.mind.mission.Mission
import com.cyclone.mobile.task.AskWhileWorking

/**
 * Plan 38: the rows the Ask bar offers when the owner sends typed text while Cyclone works (overlay and Ask page).
 * One tap each; a greyed row shows why it can't run instead of running. The highlighted row is a hint, never a choice.
 */
@Composable
fun CycloneAskOptions(
    options: List<AskWhileWorking.Option>,
    onChoose: (AskWhileWorking.Choice) -> Unit,
    modifier: Modifier = Modifier,
    ink: Color = MaterialTheme.colorScheme.onSurface,
    accent: Color = MaterialTheme.colorScheme.primary,
    surface: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    var reason by remember(options) { mutableStateOf<String?>(null) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(surface)
            .border(1.dp, ink.copy(alpha = .10f), RoundedCornerShape(22.dp)).padding(vertical = 6.dp),
    ) {
        options.forEach { option ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clickable(role = Role.Button) { if (option.enabled) onChoose(option.choice) else reason = option.reason }
                    .semantics { contentDescription = "${option.title}: ${option.subtitle}" + if (!option.enabled) " (not available)" else "" }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val alpha = if (option.enabled) 1f else .40f
                Box(
                    Modifier.size(12.dp).clip(CircleShape)
                        .background(if (option.suggested && option.enabled) accent else Color.Transparent)
                        .border(1.5.dp, (if (option.suggested && option.enabled) accent else ink).copy(alpha = .6f * alpha), CircleShape),
                )
                Text(option.title, color = ink.copy(alpha = alpha), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(.36f))
                Text(option.subtitle, color = ink.copy(alpha = .66f * alpha), fontSize = 14.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(.64f))
            }
        }
        reason?.let {
            Text(it, color = ink.copy(alpha = .75f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
    }
}

/**
 * Plan 38 §4: the plan as it is now. After a diversion the header says "Plan v2 · Changed course" (or "You changed
 * this"), the dropped steps are struck through and the new ones hang off a branch line with the reason once.
 * [compact] shows only the header and the changed rows (the overlay); "See v1" opens the earlier plan.
 */
@Composable
fun CyclonePlanView(
    mission: Mission,
    compact: Boolean = false,
    ink: Color = MaterialTheme.colorScheme.onSurface,
    dim: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val plan = mission.plan
    if (plan.isEmpty()) return
    val diverted = mission.planVersion > 1
    if (compact && !diverted) return
    var showEarlier by remember(mission.id, mission.planVersion) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (diverted) {
            Text("Plan v${mission.planVersion}" + (mission.planLabel?.let { " · $it" } ?: ""), color = accent, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
        }
        val rows = if (compact) plan.filter { it.dropped || it.branch } else plan
        rows.forEach { step -> PlanRow(step, ink, dim, accent) }
        val earlier = mission.planHistory
        if (diverted && earlier.isNotEmpty()) {
            val label = "See v${mission.planVersion - 1}"
            Text(if (showEarlier) "Hide earlier plan" else "$label  ›", color = accent, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.End).clickable(role = Role.Button) { showEarlier = !showEarlier }
                    .padding(vertical = 6.dp, horizontal = 4.dp))
            if (showEarlier) {
                earlier.last().filterNot { it.dropped }.forEach { step ->
                    Text("${mark(step.status)}  ${step.text}", color = dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PlanRow(step: MindPlanStep, ink: Color, dim: Color, accent: Color) {
    when {
        step.dropped -> Text("✕  ${step.text}", color = dim.copy(alpha = .6f), fontSize = 13.sp,
            textDecoration = TextDecoration.LineThrough, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = "Dropped: ${step.text}" })
        step.branch -> Column {
            Text("└→ ${step.text}", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { contentDescription = "New step: ${step.text}" })
            step.note?.let { Text("ⓘ $it", color = dim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 22.dp)) }
        }
        else -> Text("${mark(step.status)}  ${step.text}", color = if (step.status == "done") dim else ink, fontSize = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun mark(status: String) = when (status) {
    "done" -> "✓"
    "doing" -> "›"
    "skipped" -> "–"
    else -> "○"
}
