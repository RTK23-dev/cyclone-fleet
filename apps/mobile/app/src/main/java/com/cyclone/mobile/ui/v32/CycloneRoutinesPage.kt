package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.RunState
import com.cyclone.mobile.automation.TriggerType

@Composable
fun CycloneRoutinesPage(context: Context, refreshTick: Int, onAi: () -> Unit, onMarketplace: () -> Unit = {}, refresh: () -> Unit) {
    val all = remember(refreshTick) { AutomationRuntime.store.listAutomations() }
    val task by com.cyclone.mobile.runtime.background.WorkspaceTasks.state.collectAsState()
    val runs = remember(refreshTick) { AutomationRuntime.store.listRuns() }
    var query by rememberSaveable { mutableStateOf("") }
    var grouping by rememberSaveable { mutableIntStateOf(0) }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // R6: a routine picked in the smart search opens straight to its detail.
    LaunchedEffect(RoutinesNav.pending) {
        RoutinesNav.pending?.let { selected = it; RoutinesNav.pending = null }
    }
    var create by remember { mutableStateOf(false) }
    var mode by rememberSaveable { mutableStateOf("") }

    BackHandler(selected != null || group != null || mode.isNotEmpty() || create) {
        when {
            create -> create = false
            selected != null -> selected = null
            group != null -> group = null
            else -> mode = ""
        }
    }

    when {
        mode == "teach" -> CycloneFollowMePage(context, refreshTick) { mode = "" }
        mode == "manual" -> Column {
            CycloneBackRow("Advanced") { mode = "advanced" }
            V32TeachPage(context, refreshTick)
        }
        mode == "advanced" -> LazyColumn(
            contentPadding = cyclonePageInsets(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CycloneBackRow("Routines") { mode = "" } }
            item { CyclonePageIntro("Optional", "Advanced", "Build or inspect a routine when you need precise control.") }
            item {
                CycloneSimpleCard(Modifier.fillMaxWidth()) {
                    RoutineCreateRow(Icons.Rounded.Tune, "Routine builder", "Build triggers and steps manually") { mode = "builder" }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                    RoutineCreateRow(Icons.Rounded.School, "Manual teacher", "Inspect teaching history and low-level captures") { mode = "manual" }
                }
            }
        }
        mode == "builder" -> V32RoutineBuilder(
            onBack = { mode = "" },
            onSave = { draft ->
                val routine = draft.toAutomationForDevice(notificationAccess = v32NotificationListenerEnabled(context))
                AutomationRuntime.store.saveAutomation(routine)
                if (routine.trigger.type == TriggerType.SCHEDULE) AutomationRuntime.registerSchedule(context, routine)
                refresh()
                mode = ""
            },
        )
        selected != null && all.any { it.id == selected } -> {
            V32RoutineDetail(context, all.first { it.id == selected }, { selected = null }, refresh)
        }
        else -> {
            val normalizedQuery = query.trim()
            val filtered = all.filter { routine ->
                normalizedQuery.isBlank() ||
                    routine.name.contains(normalizedQuery, true) ||
                    routine.description.contains(normalizedQuery, true) ||
                    routine.categories.any { it.contains(normalizedQuery, true) } ||
                    routine.appPackages.any { pkg -> appLabel(context, pkg).contains(normalizedQuery, true) }
            }

            fun keys(routine: AutomationDefinition): List<String> = when (grouping) {
                0 -> routine.appPackages.ifEmpty { listOf("Other") }
                1 -> routine.categories.ifEmpty { listOf("Uncategorized") }
                else -> emptyList()
            }

            val groups = if (grouping == 2) emptyMap() else {
                filtered.flatMap { routine -> keys(routine).map { key -> key to routine } }
                    .groupBy({ it.first }, { it.second })
            }
            val showGrouped = grouping != 2 && group == null
            val visibleRoutines = if (group == null) filtered else groups[group].orEmpty()

            LazyColumn(
                contentPadding = cyclonePageInsets(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    CyclonePageHeader(
                        title = "Routines",
                        subtitle = "${all.size} ${if (all.size == 1) "routine" else "routines"}",
                        trailing = {
                            FilledIconButton(onClick = { create = !create }, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Rounded.Add, if (create) "Close create menu" else "Create routine", modifier = Modifier.size(22.dp))
                            }
                        },
                    )
                }

                item {
                    CycloneSimpleCard(Modifier.fillMaxWidth()) {
                        RoutineCreateRow(Icons.Rounded.Storefront, "Marketplace", "Recipes and connections, ready to add") { onMarketplace() }
                    }
                }

                if (create) {
                    item {
                        CycloneLiquidPanel(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 24.dp,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Create a routine", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Choose how you want to start.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                                RoutineCreateRow(Icons.Rounded.AutoAwesome, "Describe it to Cyclone", "Tell Cyclone what you want in plain language") {
                                    create = false
                                    onAi()
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                                RoutineCreateRow(Icons.Rounded.School, "Teach by doing", "Use Follow Me while you perform the routine") {
                                    create = false
                                    mode = "teach"
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .35f))
                                RoutineCreateRow(Icons.Rounded.Tune, "Advanced", "Build manually or inspect teaching tools") {
                                    create = false
                                    mode = "advanced"
                                }
                            }
                        }
                    }
                }

                item {
                    CycloneLiquidSearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search routines",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    CycloneSegmentedControl(
                        listOf("Apps", "Categories", "All"),
                        grouping,
                        onSelect = {
                            grouping = it
                            group = null
                        },
                    )
                }

                group?.let { activeGroup ->
                    item {
                        CycloneBackRow(
                            if (grouping == 0 && activeGroup != "Other") appLabel(context, activeGroup) else activeGroup,
                        ) { group = null }
                    }
                }

                task?.takeIf { grouping == 0 && group == it.packageName && UiTask(it).active }?.let { current ->
                    item { CycloneTaskProgress(current) }
                }

                if (filtered.isEmpty()) {
                    item {
                        CycloneSimpleCard(Modifier.fillMaxWidth()) {
                            Text(
                                if (query.isNotBlank()) "No routines match your search" else "No routines yet",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                if (query.isNotBlank()) "Try a different app, category or routine name." else "Describe one to Cyclone or teach it by doing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (showGrouped) {
                    val orderedKeys = groups.keys.sortedWith(compareBy<String> {
                        when {
                            it == "Other" || it == "Uncategorized" -> 1
                            else -> 0
                        }
                    }.thenBy { key ->
                        if (grouping == 0 && key != "Other") appLabel(context, key).lowercase() else key.lowercase()
                    })
                    items(orderedKeys) { key ->
                        val routines = groups.getValue(key)
                        val active = runs.count { run -> run.state == RunState.RUNNING && routines.any { it.id == run.automationId } }
                        RoutineGroupCard(
                            context = context,
                            key = key,
                            grouping = grouping,
                            count = routines.size,
                            active = active,
                            onClick = { group = key },
                        )
                    }
                } else {
                    items(visibleRoutines, key = { it.id }) { routine ->
                        RoutineListCard(
                            routine = routine,
                            onOpen = { selected = routine.id },
                            onRun = { AutomationRuntime.router.runManual(routine.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineGroupCard(
    context: Context,
    key: String,
    grouping: Int,
    count: Int,
    active: Int,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    if (grouping == 0 && key != "Other") {
                        CycloneAppIcon(key, Modifier.size(30.dp))
                    } else {
                        Icon(Icons.Rounded.Bolt, null, Modifier.size(21.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (grouping == 0 && key != "Other") appLabel(context, key) else key,
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$count ${if (count == 1) "routine" else "routines"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (active > 0) CycloneStatus("$active active")
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f))
        }
    }
}

@Composable
private fun RoutineListCard(
    routine: AutomationDefinition,
    onOpen: () -> Unit,
    onRun: () -> Unit,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                CycloneAppIcon(routine.appPackages.firstOrNull(), Modifier.padding(7.dp).size(29.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(routine.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    routine.v32TriggerSummary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                routine.categories.firstOrNull()?.let { category ->
                    Text(category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                val ground = com.cyclone.mobile.automation.RoutineGrounding.of(routine)
                if (ground != com.cyclone.mobile.automation.RoutineGround.NO_PHONE) {
                    Text(ground.label, style = MaterialTheme.typography.labelSmall,
                        color = if (ground == com.cyclone.mobile.automation.RoutineGround.GROUNDED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (routine.enabled) {
                TextButton(onClick = onRun) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(17.dp))
                    Spacer(Modifier.size(3.dp))
                    Text("Run", style = MaterialTheme.typography.labelMedium)
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f))
        }
    }
}

@Composable
private fun RoutineCreateRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f))
    }
}

/** R6: where the smart search leaves the routine to open. */
internal object RoutinesNav {
    var pending by androidx.compose.runtime.mutableStateOf<String?>(null)
}
