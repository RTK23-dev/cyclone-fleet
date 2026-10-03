package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.market.MarketError
import com.cyclone.mobile.market.Marketplace
import com.cyclone.mobile.mind.learn.LearnOutcome
import com.cyclone.mobile.mind.learn.MissionLearning
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.mind.mission.Mission
import com.cyclone.mobile.mind.mission.MissionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a finished run offers: Learn (everything it saw and did) and Save skill (its goal as the owner's recipe). */
private data class RunLearnState(
    val canLearn: Boolean,
    val learned: String?,
    val savedSkill: Boolean,
)

private fun runLearnState(context: Context, mission: Mission) = RunLearnState(
    canLearn = MissionLearning.canLearn(context, mission),
    learned = mission.learned?.sentence(),
    savedSkill = Marketplace.savedSkillFor(context, mission.goal) != null,
)

/**
 * Learn and Save skill for one finished run. Learn is one press per run: every screen the run saw, every control on
 * it and every move it made become app knowledge, so the next run on that app starts knowing where things are.
 */
@Composable
fun CycloneRunLearnActions(mission: Mission, modifier: Modifier = Modifier) {
    if (mission.status.live) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(mission.id, mission.learned) { mutableStateOf<RunLearnState?>(null) }
    var busy by remember(mission.id) { mutableStateOf(false) }
    var note by remember(mission.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(mission.id, mission.learned) { state = withContext(Dispatchers.IO) { runCatching { runLearnState(context, mission) }.getOrNull() } }
    val current = state ?: return
    val canSave = mission.status == MissionStatus.COMPLETED
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (current.learned ?: note)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                onClick = {
                    busy = true
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { runCatching { MissionLearning.learn(context, mission.id) }.getOrNull() }
                        note = when (outcome) {
                            is LearnOutcome.Learned -> outcome.report.sentence()
                            is LearnOutcome.Refused -> outcome.message
                            null -> "Learning did not finish. Try again."
                        }
                        state = withContext(Dispatchers.IO) { runCatching { runLearnState(context, MindMissions.store(context).load(mission.id) ?: mission) }.getOrNull() } ?: state
                        busy = false
                    }
                },
                enabled = current.canLearn && !busy,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { contentDescription = "Learn from this run" },
            ) { Text(when { busy -> "Learning…"; current.learned != null -> "Learned"; else -> "Learn" }) }
            if (canSave) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            note = withContext(Dispatchers.IO) {
                                try {
                                    val skill = Marketplace.saveSkillFromRun(context, mission.id, mission.goal)
                                    val grounded = Marketplace.ownerSkills(context).anchor(skill.id)
                                    if (grounded != null) "Saved “${skill.name}” to Your skills, grounded on “${grounded.destination.title}” in the app's map."
                                    else "Saved “${skill.name}” to Your skills in the Marketplace."
                                } catch (error: MarketError) {
                                    error.message
                                }
                            }
                            state = state?.copy(savedSkill = Marketplace.savedSkillFor(context, mission.goal) != null)
                        }
                    },
                    enabled = !current.savedSkill,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).semantics { contentDescription = "Save this run as a skill" },
                ) { Text(if (current.savedSkill) "Skill saved" else "Save skill") }
            }
        }
    }
}

/** The same actions for a Brain outcome, which is a run trace; shown only when a Mind mission is behind it. */
@Composable
fun CycloneRunLearnActionsForRun(runId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val history by MindMissions.history.collectAsState()
    var mission by remember(runId) { mutableStateOf<Mission?>(null) }
    LaunchedEffect(runId, history) { mission = withContext(Dispatchers.IO) { runCatching { MissionLearning.missionForRun(context, runId) }.getOrNull() } }
    mission?.let { CycloneRunLearnActions(it, modifier) }
}
