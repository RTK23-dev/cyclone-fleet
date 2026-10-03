package com.cyclone.mobile.ui.v32

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

private enum class BuilderStage(val title: String, val eyebrow: String) {
    TRIGGER("When should it start?", "1 of 4 · When"),
    DETAILS("Add the details", "2 of 4 · Details"),
    ACTIONS("What should Cyclone do?", "3 of 4 · Then"),
    REVIEW("Name and review", "4 of 4 · Check"),
}

private val repeatOptions = listOf(
    "Once" to null,
    "Daily" to 86_400_000L,
    "Weekly" to 604_800_000L,
)

@Composable
fun V32RoutineBuilder(onBack: () -> Unit, onSave: (V32AutomationDraft) -> Unit) {
    val context = LocalContext.current
    var stage by rememberSaveable { mutableStateOf(BuilderStage.TRIGGER) }
    var trigger by rememberSaveable { mutableStateOf(V32TriggerChoice.ONE_TAP) }
    var sourcePackage by rememberSaveable { mutableStateOf("") }
    var containsText by rememberSaveable { mutableStateOf("") }
    var scheduledAt by rememberSaveable { mutableStateOf<Long?>(null) }
    var repeatMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var calendarName by rememberSaveable { mutableStateOf("") }
    var remoteKey by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val actions = remember { mutableStateListOf<V32ActionDraft>() }
    var actionDialog by remember { mutableStateOf<V32ActionChoice?>(null) }
    var editActionId by remember { mutableStateOf<String?>(null) }
    var triggerAppPickerOpen by remember { mutableStateOf(false) }

    fun draft() = V32AutomationDraft(name, trigger, sourcePackage, containsText, scheduledAt, repeatMillis, calendarName, remoteKey, actions.toList())
    fun back() {
        stage = when (stage) {
            BuilderStage.TRIGGER -> { onBack(); return }
            BuilderStage.DETAILS -> BuilderStage.TRIGGER
            BuilderStage.ACTIONS -> if (trigger == V32TriggerChoice.ONE_TAP) BuilderStage.TRIGGER else BuilderStage.DETAILS
            BuilderStage.REVIEW -> BuilderStage.ACTIONS
        }
    }

    actionDialog?.let { choice ->
        val existing = editActionId?.let { id -> actions.firstOrNull { it.id == id } }
        V32ActionEditorDialog(
            context = context,
            choice = choice,
            existing = existing,
            onDismiss = { actionDialog = null; editActionId = null },
            onSave = { value ->
                val updated = existing?.copy(value = value) ?: V32ActionDraft(choice = choice, value = value)
                val index = actions.indexOfFirst { it.id == updated.id }
                if (index >= 0) actions[index] = updated else actions.add(updated)
                actionDialog = null
                editActionId = null
            },
        )
    }

    if (triggerAppPickerOpen) {
        V32AppPickerDialog(
            context = context,
            onDismiss = { triggerAppPickerOpen = false },
            onSelect = { packageName ->
                sourcePackage = packageName
                triggerAppPickerOpen = false
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(stage.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stage.eyebrow, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onBack) { Icon(Icons.Rounded.Close, "Close") }
            }
        },
    ) { padding ->
        when (stage) {
            BuilderStage.TRIGGER -> V32TriggerStep(Modifier.padding(padding), trigger) { selected ->
                trigger = selected
                stage = if (selected == V32TriggerChoice.ONE_TAP) BuilderStage.ACTIONS else BuilderStage.DETAILS
            }
            BuilderStage.DETAILS -> V32TriggerDetailsStep(
                modifier = Modifier.padding(padding),
                context = context,
                trigger = trigger,
                sourcePackage = sourcePackage,
                onSourcePackage = { sourcePackage = it },
                containsText = containsText,
                onContainsText = { containsText = it },
                scheduledAt = scheduledAt,
                onScheduledAt = { scheduledAt = it },
                repeatMillis = repeatMillis,
                onRepeatMillis = { repeatMillis = it },
                calendarName = calendarName,
                onCalendarName = { calendarName = it },
                remoteKey = remoteKey,
                onRemoteKey = { remoteKey = it },
                onChooseApp = { triggerAppPickerOpen = true },
                onContinue = { stage = BuilderStage.ACTIONS },
            )
            BuilderStage.ACTIONS -> V32ActionsStep(
                modifier = Modifier.padding(padding),
                actions = actions,
                onAdd = { choice -> actionDialog = choice },
                onEdit = { action -> editActionId = action.id; actionDialog = action.choice },
                onRemove = { action -> actions.remove(action) },
                onContinue = { stage = BuilderStage.REVIEW },
            )
            BuilderStage.REVIEW -> V32ReviewStep(
                modifier = Modifier.padding(padding),
                draft = draft(),
                name = name,
                onName = { name = it },
                onSave = { onSave(draft()) },
            )
        }
    }
}

@Composable
private fun V32TriggerStep(modifier: Modifier, selected: V32TriggerChoice, onSelect: (V32TriggerChoice) -> Unit) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Pick one clear starting point. You can add conditions after the routine exists.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(V32TriggerChoice.entries.filter { it != V32TriggerChoice.CALENDAR }, key = { it.name }) { choice ->
            val chosen = selected == choice
            Card(
                onClick = { onSelect(choice) },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
            ) {
                Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Surface(shape = CircleShape, color = triggerTone(choice)) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(triggerIcon(choice), null) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(choice.label, fontWeight = FontWeight.Bold)
                        Text(choice.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (chosen) Icons.Rounded.Check else Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun V32TriggerDetailsStep(
    modifier: Modifier,
    context: Context,
    trigger: V32TriggerChoice,
    sourcePackage: String,
    onSourcePackage: (String) -> Unit,
    containsText: String,
    onContainsText: (String) -> Unit,
    scheduledAt: Long?,
    onScheduledAt: (Long) -> Unit,
    repeatMillis: Long?,
    onRepeatMillis: (Long?) -> Unit,
    calendarName: String,
    onCalendarName: (String) -> Unit,
    remoteKey: String,
    onRemoteKey: (String) -> Unit,
    onChooseApp: () -> Unit,
    onContinue: () -> Unit,
) {
    val detailsValid = when (trigger) {
        V32TriggerChoice.NOTIFICATION, V32TriggerChoice.APP_OPENED -> sourcePackage.isNotBlank()
        V32TriggerChoice.SCHEDULE -> scheduledAt != null && scheduledAt > System.currentTimeMillis()
        V32TriggerChoice.CALENDAR -> calendarName.isNotBlank()
        V32TriggerChoice.CYCLONE -> remoteKey.isNotBlank()
        V32TriggerChoice.ONE_TAP -> true
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { CycloneHeroCard(trigger.label, trigger.description, triggerIcon(trigger), tone = CyclonePastel.SKY) }
        when (trigger) {
            V32TriggerChoice.NOTIFICATION, V32TriggerChoice.APP_OPENED -> {
                item {
                    CycloneSimpleCard {
                        Text("Source app", fontWeight = FontWeight.Bold)
                        OutlinedTextField(sourcePackage, onSourcePackage, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Android package") }, placeholder = { Text("com.example.app") })
                        OutlinedButton(onClick = onChooseApp, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Apps, null); Spacer(Modifier.size(6.dp)); Text("Choose an installed app") }
                        if (trigger == V32TriggerChoice.NOTIFICATION) OutlinedTextField(containsText, onContainsText, Modifier.fillMaxWidth(), label = { Text("Notification contains (optional)") }, maxLines = 2)
                    }
                }
            }
            V32TriggerChoice.SCHEDULE -> item {
                CycloneSimpleCard {
                    Text("Time", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            val initial = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
                            TimePickerDialog(context, { _, hour, minute ->
                                val next = Calendar.getInstance().apply {
                                    set(Calendar.HOUR_OF_DAY, hour)
                                    set(Calendar.MINUTE, minute)
                                    set(Calendar.SECOND, 0)
                                    set(Calendar.MILLISECOND, 0)
                                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                                }
                                onScheduledAt(next.timeInMillis)
                            }, initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE), true).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.Schedule, null)
                        Spacer(Modifier.size(6.dp))
                        Text(scheduledAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Choose time")
                    }
                    Text("Repeat", fontWeight = FontWeight.Bold)
                    CycloneLiquidChoiceBar(
                        options = repeatOptions.map { it.first },
                        selectedIndex = repeatOptions.indexOfFirst { it.second == repeatMillis }.coerceAtLeast(0),
                        onSelect = { index -> onRepeatMillis(repeatOptions[index].second) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            V32TriggerChoice.CALENDAR -> item {
                CycloneSimpleCard {
                    Text("Calendar", fontWeight = FontWeight.Bold)
                    OutlinedTextField(calendarName, onCalendarName, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Calendar name") })
                    Text("Calendar permission and event matching remain under Android control.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            V32TriggerChoice.CYCLONE -> item {
                CycloneSimpleCard {
                    Text("Connection event", fontWeight = FontWeight.Bold)
                    OutlinedTextField(remoteKey, onRemoteKey, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Event key") }, placeholder = { Text("morning-ready") })
                    Text("This identifies the event. It never becomes phone-action authority.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            V32TriggerChoice.ONE_TAP -> Unit
        }
        item { Button(onClick = onContinue, enabled = detailsValid, modifier = Modifier.fillMaxWidth()) { Text("Continue") } }
    }
}

@Composable
private fun V32ActionsStep(
    modifier: Modifier,
    actions: List<V32ActionDraft>,
    onAdd: (V32ActionChoice) -> Unit,
    onEdit: (V32ActionDraft) -> Unit,
    onRemove: (V32ActionDraft) -> Unit,
    onContinue: () -> Unit,
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (actions.isEmpty()) item { CycloneHeroCard("Add the first action", "Start small. You can teach or ask AI for selector-rich steps later.", Icons.Rounded.TouchApp, tone = CyclonePastel.MINT) }
        else {
            item { Text("Cyclone runs these from top to bottom.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(actions, key = { it.id }) { action ->
                CycloneSimpleCard(modifier = Modifier.clickable { onEdit(action) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                                Icon(actionIcon(action.choice), null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(action.choice.label, fontWeight = FontWeight.Bold)
                            if (action.value.isNotBlank()) Text(if (action.choice == V32ActionChoice.RUN_SKILL) skillName(action.value) else action.value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onRemove(action) }) { Icon(Icons.Rounded.Close, "Remove") }
                    }
                }
            }
        }
        item { CycloneSectionTitle("Add an action") }
        items(V32ActionChoice.entries, key = { it.name }) { choice ->
            OutlinedButton(onClick = { onAdd(choice) }, modifier = Modifier.fillMaxWidth()) {
                Icon(actionIcon(choice), null)
                Spacer(Modifier.size(8.dp))
                Text(choice.label, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.Add, null)
            }
        }
        item { Button(onClick = onContinue, enabled = actions.isNotEmpty() && actions.none { it.validationIssue() != null }, modifier = Modifier.fillMaxWidth()) { Text("Review routine") } }
    }
}

@Composable
private fun V32ReviewStep(modifier: Modifier, draft: V32AutomationDraft, name: String, onName: (String) -> Unit, onSave: () -> Unit) {
    val current = draft.copy(name = name)
    val issues = current.validationIssues()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { OutlinedTextField(name, onName, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Routine name") }, placeholder = { Text("Morning check-in") }) }
        item {
            CycloneHeroCard(current.triggerSummary(), "Then ${current.actions.size} ${if (current.actions.size == 1) "action" else "actions"}. Cyclone will still apply policy and verify phone changes.", Icons.Rounded.Visibility, tone = CyclonePastel.LEMON)
        }
        item {
            CycloneSimpleCard {
                Text("Then", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                current.actions.forEachIndexed { index, action ->
                    val shown = if (action.choice == V32ActionChoice.RUN_SKILL) skillName(action.value) else action.value
                    Text("${index + 1}. ${action.choice.label}${shown.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}")
                }
            }
        }
        item {
            CycloneSimpleCard {
                Text("Check", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Phone-changing actions use Cyclone’s existing after-state verification. Add a custom final assertion in the advanced editor in a later phase.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (issues.isNotEmpty()) item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { issues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
            }
        }
        item { Button(onClick = onSave, enabled = issues.isEmpty(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.size(6.dp)); Text("Save routine") } }
    }
}

@Composable
private fun V32ActionEditorDialog(
    context: Context,
    choice: V32ActionChoice,
    existing: V32ActionDraft?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(existing?.id, choice) { mutableStateOf(existing?.value ?: if (choice == V32ActionChoice.WAIT) "1000" else "") }
    var appPickerOpen by remember { mutableStateOf(false) }
    if (appPickerOpen) {
        V32AppPickerDialog(
            context = context,
            onDismiss = { appPickerOpen = false },
            onSelect = { value = it; appPickerOpen = false },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(actionIcon(choice), null) },
        title = { Text(choice.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(choice.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (choice) {
                    V32ActionChoice.OPEN_APP -> {
                        OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("App package") }, placeholder = { Text("com.example.app") })
                        OutlinedButton(onClick = { appPickerOpen = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Apps, null); Spacer(Modifier.size(6.dp)); Text("Choose an installed app") }
                    }
                    V32ActionChoice.WAIT -> OutlinedTextField(value, { value = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Milliseconds") })
                    V32ActionChoice.HUMAN -> OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), maxLines = 2, label = { Text("What should Cyclone ask?") })
                    V32ActionChoice.HOME, V32ActionChoice.BACK -> Text("No extra details needed.")
                    V32ActionChoice.RUN_SKILL -> V32SkillPicker(context, value) { value = it }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(value) }, enabled = V32ActionDraft(choice = choice, value = value).validationIssue() == null) { Text("Add") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The owner's saved skills with how well each is grounded on its app's map; pick one to run. */
@Composable
private fun V32SkillPicker(context: Context, selected: String, onSelect: (String) -> Unit) {
    val skills = remember(context) { runCatching { com.cyclone.mobile.market.Marketplace.skillsWithHealth(context) }.getOrDefault(emptyList()) }
    if (skills.isEmpty()) {
        Text("You have no saved skills yet. Finish a run and press Save skill on it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(skills, key = { it.first.id }) { (listing, _, health) ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(listing.id) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(if (listing.id == selected) Icons.Rounded.Check else Icons.Rounded.Star, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(listing.name, fontWeight = FontWeight.SemiBold)
                    Text("${health.state.label} · ${health.detail}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun skillName(id: String): String = com.cyclone.mobile.market.Marketplace.listing(id)?.name ?: id

private data class V32InstalledApp(val label: String, val packageName: String)

@Composable
private fun V32AppPickerDialog(context: Context, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val apps = remember(context) { launchableApps(context) }
    val visible = remember(apps, query) { apps.filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }.take(60) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CycloneLiquidSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search apps",
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(visible, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(app.packageName) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                                Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Apps, null) }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontWeight = FontWeight.SemiBold)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun launchableApps(context: Context): List<V32InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        .map { V32InstalledApp(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

@Composable
private fun triggerTone(choice: V32TriggerChoice) = when (choice) {
    V32TriggerChoice.ONE_TAP -> cyclonePastel(CyclonePastel.LILAC).container
    V32TriggerChoice.NOTIFICATION -> cyclonePastel(CyclonePastel.PEACH).container
    V32TriggerChoice.SCHEDULE -> cyclonePastel(CyclonePastel.MINT).container
    V32TriggerChoice.APP_OPENED -> cyclonePastel(CyclonePastel.SKY).container
    V32TriggerChoice.CALENDAR -> cyclonePastel(CyclonePastel.LEMON).container
    V32TriggerChoice.CYCLONE -> MaterialTheme.colorScheme.primaryContainer
}

private fun triggerIcon(choice: V32TriggerChoice): ImageVector = when (choice) {
    V32TriggerChoice.ONE_TAP -> Icons.Rounded.TouchApp
    V32TriggerChoice.NOTIFICATION -> Icons.Rounded.Notifications
    V32TriggerChoice.SCHEDULE -> Icons.Rounded.Schedule
    V32TriggerChoice.APP_OPENED -> Icons.Rounded.Smartphone
    V32TriggerChoice.CALENDAR -> Icons.Rounded.CalendarMonth
    V32TriggerChoice.CYCLONE -> Icons.Rounded.Link
}

private fun actionIcon(choice: V32ActionChoice): ImageVector = when (choice) {
    V32ActionChoice.RUN_SKILL -> Icons.Rounded.Star
    V32ActionChoice.OPEN_APP -> Icons.Rounded.Apps
    V32ActionChoice.HOME -> Icons.Rounded.Home
    V32ActionChoice.BACK -> Icons.AutoMirrored.Rounded.ArrowBack
    V32ActionChoice.WAIT -> Icons.Rounded.Schedule
    V32ActionChoice.HUMAN -> Icons.Rounded.AutoAwesome
}
