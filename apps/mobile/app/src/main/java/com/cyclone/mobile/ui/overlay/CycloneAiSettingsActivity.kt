package com.cyclone.mobile.ui.overlay

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.model.ModelQualificationOutcome
import com.cyclone.mobile.ai.model.ModelQualificationRunner
import com.cyclone.mobile.ui.v32.CycloneOpenRouterCatalog
import com.cyclone.mobile.ui.v32.CycloneReasoningSelector
import com.cyclone.mobile.ui.v32.CycloneV32Theme
import com.cyclone.mobile.ui.overlay.tracefield.TraceFieldMode
import com.cyclone.mobile.ui.overlay.tracefield.TraceFieldPrefs
import com.cyclone.mobile.ui.overlay.tracefield.TraceFieldRuntime
import com.cyclone.mobile.ui.overlay.tracefield.TraceFieldStyle
import kotlinx.coroutines.launch

/** Full AI configuration intentionally lives in the Cyclone app, never in the floating composer. */
class CycloneAiSettingsActivity : ComponentActivity() {
    override fun onDestroy() {
        if (!isChangingConfigurations) com.cyclone.mobile.ui.overlay.OverlayExternalInteraction.active.value = false
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CycloneV32Theme {
                AiSettingsContent(context = this, onBack = { finish() })
            }
        }
    }
}

@Composable
private fun AiSettingsContent(context: Context, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val catalogRevision by OpenRouterCatalogStore.revision.collectAsState()
    val pickerModels = remember(catalogRevision) { OpenRouterCatalogStore.picker(context) }
    var selectedModelId by rememberSaveable(catalogRevision) { mutableStateOf(OpenRouterCatalogStore.activeId(context)) }
    var backupModelId by rememberSaveable(catalogRevision) { mutableStateOf(OpenRouterCatalogStore.backupId(context)) }
    var checking by remember { mutableStateOf(false) }
    var accessResult by remember { mutableStateOf<String?>(null) }
    var roleRefresh by remember { mutableStateOf(0) }
    val selectedModel = remember(catalogRevision, selectedModelId) { OpenRouterCatalogStore.preset(context, selectedModelId) }
    val roleManager = remember(roleRefresh) { context.getSystemService(RoleManager::class.java) }
    val roleAvailable = roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)
    val assistantHeld = roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    val requestAssistant = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        roleRefresh += 1
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Back")
                }
                Spacer(Modifier.weight(1f))
                Text("AI settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }

        item {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Model", fontWeight = FontWeight.Bold)
                        Text(
                            "Model choice and compatibility checks live here so the floating bar stays only about asking.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { SettingsCard { CycloneOpenRouterCatalog(context) } }
        item { Text("Choose model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        items(pickerModels, key = { it.id }) { model ->
            FilterChip(
                selected = selectedModelId == model.id,
                onClick = {
                    try {
                        OpenRouterCatalogStore.setActive(context, model.id)
                        selectedModelId = OpenRouterCatalogStore.activeId(context)
                        accessResult = null
                    } catch (failure: Exception) {
                        accessResult = failure.message
                    }
                },
                label = { Text(model.label) },
            )
        }

        item {
            SettingsCard {
                Text("When the main model is busy", fontWeight = FontWeight.Bold)
                Text(
                    "If the main model is rate-limited or down during a task, Cyclone can continue with a backup you choose. It never switches on its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                FilterChip(
                    selected = backupModelId.isBlank(),
                    onClick = {
                        runCatching { OpenRouterCatalogStore.setBackup(context, "") }
                        backupModelId = OpenRouterCatalogStore.backupId(context)
                    },
                    label = { Text("Stop and tell me") },
                )
                pickerModels.filter { it.id != selectedModelId }.forEach { model ->
                    FilterChip(
                        selected = backupModelId == model.id,
                        onClick = {
                            try {
                                OpenRouterCatalogStore.setBackup(context, model.id)
                                backupModelId = OpenRouterCatalogStore.backupId(context)
                            } catch (failure: Exception) {
                                accessResult = failure.message
                            }
                        },
                        label = { Text("Backup: ${model.label}") },
                    )
                }
            }
        }

        item {
            SettingsCard {
                var mindOn by remember { mutableStateOf(com.cyclone.mobile.mind.mission.MindMissions.enabled(context)) }
                var minutes by remember { mutableStateOf(com.cyclone.mobile.mind.mission.MindMissions.workingMinutes(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Cyclone Mind", fontWeight = FontWeight.Bold)
                        Text(
                            "One model works each request as a mission: it keeps the whole conversation, uses the phone's tools itself and asks you only when it needs you. Off uses the classic step agent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = mindOn, onCheckedChange = {
                        mindOn = it
                        com.cyclone.mobile.mind.mission.MindMissions.setEnabled(context, it)
                    })
                }
                if (mindOn) {
                    Spacer(Modifier.size(8.dp))
                    Text("Working time per mission: $minutes min", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 30, 60).forEach { value ->
                            FilterChip(
                                selected = minutes == value,
                                onClick = {
                                    minutes = value
                                    com.cyclone.mobile.mind.mission.MindMissions.setWorkingMinutes(context, value)
                                },
                                label = { Text("$value min") },
                            )
                        }
                    }
                    Text(
                        "Time you spend answering Cyclone does not count. A paused mission can be resumed from Ask.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            SettingsCard {
                // Plan 42: Speed. Which mode takes a request: Instant (one obvious action), Flash (a few routine steps) or the Mind.
                var modes by remember { mutableStateOf(com.cyclone.mobile.mind.modes.CycloneModes.settings(context)) }
                fun save(next: com.cyclone.mobile.mind.modes.ModeSettings) {
                    modes = next
                    com.cyclone.mobile.mind.modes.CycloneModes.save(context, next)
                }
                Text("Speed", fontWeight = FontWeight.Bold)
                Text(
                    when (modes.speed) {
                        com.cyclone.mobile.mind.modes.Speed.AUTO -> "Auto: clear commands happen instantly. For anything else JEV decides in one quick " +
                            "call: Instant, Flash (a few routine steps) or the full Mind. The phone model learns from JEV and takes over the actions " +
                            "it has proven it gets right. Unsure always goes to the smarter mode."
                        com.cyclone.mobile.mind.modes.Speed.COMMANDS -> "Clear commands like \"swipe up\", \"open my camera\", \"take a selfie\" or " +
                            "\"call Mam\" happen instantly, with no model. Everything else goes to the Mind."
                        com.cyclone.mobile.mind.modes.Speed.MIND -> "Every request is a Mind mission, as before."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.cyclone.mobile.mind.modes.Speed.entries.forEach { speed ->
                        FilterChip(selected = modes.speed == speed, onClick = { save(modes.copy(speed = speed)) }, label = { Text(speed.label) })
                    }
                }
                if (modes.speed == com.cyclone.mobile.mind.modes.Speed.AUTO) {
                    // Alpha 89: the phone model, taught by JEV. It acts only on actions it has earned (98% agreement with JEV).
                    val bar = remember { com.cyclone.mobile.mind.pilot.FastMode.settings(context).sureness.bar }
                    val stats = remember(modes.phoneModel) {
                        runCatching { com.cyclone.mobile.mind.decide.PhoneBrain.stats(context, bar, modes.phoneModel) }.getOrNull()
                    }
                    Text("Phone model", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.cyclone.mobile.mind.decide.PhoneModelUse.entries.forEach { use ->
                            FilterChip(selected = modes.phoneModel == use, onClick = { save(modes.copy(phoneModel = use)) }, label = { Text(use.label) })
                        }
                    }
                    Text(
                        phoneModelSummary(stats),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Instant never types, sends, pays, deletes or posts, and never acts on a password, code or card screen: those go " +
                        "to the Mind, which asks you. A call waits 2 seconds so you can stop it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Live voice: keep listening", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "After a quick action the mic stays open for ${modes.keepListeningSeconds} s, for the rest of your sentence " +
                                "(\"… and take a selfie\") or the next command.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = modes.keepListeningSeconds > 0, onCheckedChange = { save(modes.copy(keepListeningSeconds = if (it) 8 else 0)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Live voice: quiet when it worked", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "A quick action that worked is confirmed with a sound, not words.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = modes.silentSuccess, onCheckedChange = { save(modes.copy(silentSuccess = it)) })
                }
            }
        }

        item {
            SettingsCard {
                // Plan 41: Fast mode, the Pilot. Off by default; the full Mind always takes over when the fast model is unsure.
                var fast by remember { mutableStateOf(com.cyclone.mobile.mind.pilot.FastMode.settings(context)) }
                fun save(next: com.cyclone.mobile.mind.pilot.FastModeSettings) {
                    fast = next
                    com.cyclone.mobile.mind.pilot.FastMode.save(context, next)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Fast mode", fontWeight = FontWeight.Bold)
                        Text(
                            "The smart model plans the whole run; a fast model carries it out, a second per move, doing the low-risk moves " +
                                "itself and asking the smart model when the screen doesn't match. Anything that can't be undone still asks you. " +
                                "Banking, payment and authenticator apps are never done fast.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = fast.enabled, onCheckedChange = { save(fast.copy(enabled = it)) })
                }
                if (fast.enabled) {
                    Spacer(Modifier.size(8.dp))
                    Text("Fast decisions by", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.cyclone.mobile.mind.pilot.FastRoute.entries.forEach { route ->
                            FilterChip(selected = fast.route == route, onClick = { save(fast.copy(route = route)) }, label = { Text(route.label) })
                        }
                    }
                    if (fast.route == com.cyclone.mobile.mind.pilot.FastRoute.DECISIONS) {
                        // Alpha.78: the decision model is Cyclone's decision provider, not a free choice. JEV now (text only);
                        // OpenAI Decisions takes over in an update once it is available.
                        Text(
                            "${com.cyclone.mobile.mind.decide.Decisions.active().label}, text only. OpenAI Decisions replaces it once it's available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        var model by remember(fast.route) { mutableStateOf(fast.model) }
                        androidx.compose.material3.OutlinedTextField(
                            value = model,
                            onValueChange = { value -> model = value.take(120); save(fast.copy(model = model)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Fast model (OpenRouter)") },
                        )
                    }
                    Text("How sure before acting", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.cyclone.mobile.mind.pilot.FastSureness.entries.forEach { level ->
                            FilterChip(selected = fast.sureness == level, onClick = { save(fast.copy(sureness = level)) }, label = { Text(level.label) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Screenshots when needed", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (fast.route == com.cyclone.mobile.mind.pilot.FastRoute.DECISIONS) "JEV reads text only: no screenshots are taken for it."
                                else "Only when the screen's text isn't enough, never with a password, code or card field on screen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = fast.images, onCheckedChange = { save(fast.copy(images = it)) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Smart model checks ahead", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "While the fast model works, the smart model reviews the rest of the plan, and it always has before " +
                                    "anything that can't be undone. Faster and safer; it uses a few more smart-model calls.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = fast.lookahead, onCheckedChange = { save(fast.copy(lookahead = it)) })
                    }
                }
            }
        }

        item {
            SettingsCard {
                // Planes (plan 25): where missions work. The pill on a running task switches either way.
                var planeMode by remember { mutableStateOf(com.cyclone.mobile.runtime.plane.MissionPlanes.mode(context)) }
                val blocker = remember { com.cyclone.mobile.runtime.plane.MissionPlanes.blocker(context) }
                Text("Where Cyclone works", fontWeight = FontWeight.Bold)
                Text(
                    when (planeMode) {
                        com.cyclone.mobile.runtime.plane.PlaneMode.AUTOMATIC -> "Automatic: when you are using your phone, Cyclone works on a background screen behind it; otherwise on your screen, where you can watch. Protected screens and apps that need you always come to your screen."
                        com.cyclone.mobile.runtime.plane.PlaneMode.SCREEN -> "Always on your screen, where you can watch every step."
                        com.cyclone.mobile.runtime.plane.PlaneMode.BACKGROUND -> "In the background whenever the app allows it. Steps that need you or your screen still come to it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.cyclone.mobile.runtime.plane.PlaneMode.entries.forEach { mode ->
                        FilterChip(
                            selected = planeMode == mode,
                            onClick = {
                                planeMode = mode
                                com.cyclone.mobile.runtime.plane.MissionPlanes.setMode(context, mode)
                            },
                            label = { Text(mode.label) },
                        )
                    }
                }
                blocker?.let {
                    Text("Background work is not available yet: $it", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Plan 26 (A42-4): what happens when background is wanted but not possible right now.
                if (planeMode != com.cyclone.mobile.runtime.plane.PlaneMode.SCREEN) {
                    var fallback by remember { mutableStateOf(com.cyclone.mobile.runtime.plane.MissionPlanes.fallback(context)) }
                    Text("When background isn't possible", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.cyclone.mobile.runtime.plane.PlaneFallback.entries.forEach { choice ->
                            FilterChip(selected = fallback == choice, onClick = {
                                fallback = choice
                                com.cyclone.mobile.runtime.plane.MissionPlanes.setFallback(context, choice)
                            }, label = { Text(choice.label) })
                        }
                    }
                }
            }
        }

        item {
            SettingsCard {
                // Plan 37: the mission workspace, off until the Lab shows it wins every suite.
                var workspace by remember { mutableStateOf(com.cyclone.mobile.mind.mission.MindMissions.workspaceEnabled(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mission workspace (new)", fontWeight = FontWeight.Bold)
                        Text(
                            "Cyclone keeps a live plan, what it collected and where it is in view for the AI, folds apps it has left into " +
                                "a short journal, and checks each step against what it expected. Faster, cheaper long tasks across apps. " +
                                "Off by default while it is being measured.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = workspace,
                        onCheckedChange = { on ->
                            workspace = on
                            com.cyclone.mobile.mind.mission.MindMissions.setWorkspaceEnabled(context, on)
                        },
                    )
                }
            }
        }

        item {
            SettingsCard {
                // Plan 26 §6: tasks at the same time, behind the one on your screen.
                var parallel by remember { mutableStateOf(com.cyclone.mobile.mind.mission.MindMissions.parallelEnabled(context)) }
                val slots = remember { com.cyclone.mobile.mind.mission.MindMissions.behindSlots(context) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Tasks at the same time", fontWeight = FontWeight.Bold)
                        Text(
                            when {
                                slots <= 0 -> "This phone has too little memory; tasks run one after another."
                                else -> "While one task runs, up to $slots more can work behind your screen, each in its own background screen. " +
                                    "They never use your screen; one that needs it waits for its turn."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = parallel && slots > 0,
                        enabled = slots > 0,
                        onCheckedChange = { on ->
                            parallel = on
                            com.cyclone.mobile.mind.mission.MindMissions.setParallelEnabled(context, on)
                        },
                    )
                }
            }
        }

        item {
            SettingsCard {
                // Plan 26 (A42-1): one switch, one answer, one next step.
                var capability by remember { mutableStateOf(com.cyclone.mobile.runtime.plane.MissionPlanes.capability(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Background work", fontWeight = FontWeight.Bold)
                        Text(capability.headline, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = capability.level != com.cyclone.mobile.runtime.plane.CapabilityLevel.OFF &&
                            capability.level != com.cyclone.mobile.runtime.plane.CapabilityLevel.UNSUPPORTED,
                        enabled = capability.level != com.cyclone.mobile.runtime.plane.CapabilityLevel.UNSUPPORTED,
                        onCheckedChange = { on ->
                            com.cyclone.mobile.runtime.plane.MissionPlanes.setBackgroundOn(context, on)
                            capability = com.cyclone.mobile.runtime.plane.MissionPlanes.capability(context)
                        },
                    )
                }
                capability.action?.takeIf { it != com.cyclone.mobile.runtime.plane.CapabilityAction.TURN_ON }?.let { action ->
                    TextButton(onClick = {
                        runCatching { context.startActivity(com.cyclone.mobile.runtime.plane.BackgroundWatch.fixIntent(context, action)) }
                    }) { Text(action.label) }
                }
                Text("Also in Quick Settings: add the Cyclone background tile. Glass on your PC can keep it on after restarts.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BackgroundCheckSection(onDone = { capability = com.cyclone.mobile.runtime.plane.MissionPlanes.capability(context) })
                // Per app: seeded (banking, camera, games on your screen) and learned; the owner's choice wins.
                val compat = remember { com.cyclone.mobile.runtime.plane.MissionPlanes.compat(context) }
                var choices by remember { mutableStateOf(compat.choices()) }
                if (choices.isNotEmpty()) {
                    Text("Per app", style = MaterialTheme.typography.bodyMedium)
                    choices.entries.sortedBy { it.key }.take(30).forEach { (pkg, choice) ->
                        val label = remember(pkg) { runCatching { context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                val next = com.cyclone.mobile.runtime.plane.PlaneOverride.entries.let { it[(it.indexOf(choice) + 1) % it.size] }
                                compat.setOverride(pkg, next)
                                choices = compat.choices()
                            }) { Text(choice.label) }
                        }
                    }
                }
            }
        }

        item {
            SettingsCard {
                val memory = remember { com.cyclone.mobile.mind.mission.MindMissions.memory(context) }
                var facts by remember { mutableStateOf(memory.all()) }
                Text("Memory", fontWeight = FontWeight.Bold)
                Text(
                    "What Cyclone keeps for future tasks: people you told it about, your preferences, how you use apps. Say \"remember…\" " +
                        "in a task to add something. It never keeps passwords, codes, keys or card numbers, and it is encrypted on this phone. " +
                        "Your profiles share it when you switch between them, each memory labelled with the profile it came from; " +
                        "forgetting one here forgets it in every profile.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (facts.isEmpty()) {
                    Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    // Plan 40 P2: memory is shared by every profile and grouped by the profile it came from (this one
                    // first); plan 37 W3: within each, grouped like a small profile, newest first.
                    val names = remember {
                        runCatching { com.cyclone.mobile.runtime.workspaces.ProfileRegistryStore.records(context).associate { it.id to it.label } }
                            .getOrDefault(emptyMap()) + ("main" to "Profile A")
                    }
                    val byProfile = com.cyclone.mobile.mind.MemoryCarry.byProfile(facts)
                    byProfile.forEach { (profile, profileFacts) ->
                        if (byProfile.size > 1 || profile != null) {
                            Text(if (profile == null) "This profile" else "From ${names[profile] ?: profileFacts.first().profileLabel ?: "another profile"}",
                                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        }
                        listOf(
                            com.cyclone.mobile.mind.MindMemory.PERSON to "People",
                            com.cyclone.mobile.mind.MindMemory.PREFERENCE to "Your preferences",
                            com.cyclone.mobile.mind.MindMemory.APP to "Apps",
                            com.cyclone.mobile.mind.MindMemory.FACT to "Other",
                        ).forEach { (kind, title) ->
                            val group = profileFacts.filter { it.kind == kind }
                            if (group.isNotEmpty()) {
                                Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                                group.take(50).forEach { fact ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(fact.text, style = MaterialTheme.typography.bodyMedium)
                                            Text(if (fact.source == com.cyclone.mobile.mind.MindMemory.OWNER) "You asked Cyclone to remember this"
                                                else "Cyclone kept this", style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        TextButton(onClick = { memory.forget(fact.id); facts = memory.all() }) { Text("Forget") }
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { memory.forgetAll(); facts = memory.all() }) { Text("Forget everything") }
                }
            }
        }

        item {
            SettingsCard {
                Text("Intelligence", fontWeight = FontWeight.Bold)
                if (selectedModelId.isBlank()) {
                    Text("Choose an available model first.", style = MaterialTheme.typography.bodySmall)
                } else {
                    CycloneReasoningSelector(selectedModelId)
                }
                Text(
                    "Cyclone uses only the exact reasoning efforts advertised by this model in OpenRouter. Changing intelligence never changes the model or provider route.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !checking && selectedModelId.isNotBlank() && OpenRouterSecretStore.hasKey(context),
                    onClick = {
                        checking = true
                        accessResult = null
                        scope.launch {
                            accessResult = try {
                                when (val result = ModelQualificationRunner(context).qualify(selectedModel)) {
                                    is ModelQualificationOutcome.Passed ->
                                        "${selectedModel.label}: ${if (result.cached) "recently verified" else "verified"} for this OpenRouter account."
                                    is ModelQualificationOutcome.Failed -> buildString {
                                        append(selectedModel.label)
                                        append(": ")
                                        append(result.failure.userMessage)
                                        if (result.failure.httpStatus > 0) append(" (HTTP ").append(result.failure.httpStatus).append(')')
                                        result.failure.providerMessage?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                                    }
                                }
                            } catch (_: Exception) {
                                "Could not check model access. Try again."
                            }
                            checking = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.size(7.dp))
                    Text(if (checking) "Checking model…" else "Check model access")
                }
                if (!OpenRouterSecretStore.hasKey(context)) {
                    Text(
                        "Add your OpenRouter API key in Cyclone Settings before checking model access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                accessResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        item { SettingsCard { WorkingIndicatorSettings(context) } }

        item {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PowerSettingsNew, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Power button → Cyclone", fontWeight = FontWeight.Bold)
                        Text(
                            if (assistantHeld) "Cyclone is your selected Android assistant."
                            else "Choose Cyclone as Android's assistant, then set Press & hold power button to Digital assistant in system gesture settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (assistantHeld) Icon(Icons.Rounded.CheckCircle, "Enabled", tint = MaterialTheme.colorScheme.primary)
                }
                if (roleAvailable && !assistantHeld) {
                    Button(
                        onClick = { requestAssistant.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Make Cyclone my assistant") }
                }
                OutlinedButton(
                    onClick = { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open Android settings") }
                Text(
                    "Cyclone never intercepts the raw Power key. Android invokes the selected assistant through the system-owned assistant gesture.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Shared by the AI settings screen and the main Settings → Appearance → Working indicator page. */
@Composable
internal fun ColumnScope.WorkingIndicatorSettings(context: Context) {
    var mode by remember { mutableStateOf(TraceFieldPrefs.mode(context)) }
    var style by remember { mutableStateOf(TraceFieldPrefs.style(context)) }
    var previewMessage by remember { mutableStateOf<String?>(null) }
    Text("Working indicator", fontWeight = FontWeight.Bold)
    Text(
        "Trace Field shows tiny digits under a soft lens that follows what Cyclone is looking at and tapping. It never covers your screen and is hidden from Cyclone's own screenshots.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            TraceFieldMode.FIELD to "Trace Field",
            TraceFieldMode.EDGE to "Edge only",
            TraceFieldMode.OFF to "Off",
        ).forEach { (option, label) ->
            FilterChip(
                selected = mode == option,
                onClick = {
                    TraceFieldPrefs.setMode(context, option)
                    mode = option
                    previewMessage = null
                },
                label = { Text(label) },
            )
        }
    }
    if (mode == TraceFieldMode.FIELD) {
        Text("Style", fontWeight = FontWeight.Bold)
        TraceFieldStyle.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { option ->
                    FilterChip(
                        selected = style == option,
                        onClick = {
                            TraceFieldPrefs.setStyle(context, option)
                            style = option
                            previewMessage = null
                        },
                        label = { Text(option.label) },
                    )
                }
            }
        }
        Text(style.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedButton(
        enabled = mode != TraceFieldMode.OFF,
        onClick = {
            previewMessage = if (TraceFieldRuntime.preview()) {
                "Playing preview over this screen."
            } else {
                "Turn on Cyclone's accessibility service to preview."
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Preview") }
    previewMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    Text(
        "Battery Saver switches Trace Field to Edge only. Remove animations shows a still field.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Plan 28: the Background Check. One tap runs the real background path on a hidden screen and shows each step; a
 * failed step names what to do. The same result decides whether Automatic uses the background.
 */
@Composable
private fun ColumnScope.BackgroundCheckSection(onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val check = com.cyclone.mobile.runtime.plane.BackgroundCheck
    remember { check.last(context) }
    val report by check.report.collectAsState()
    var running by remember { mutableStateOf(check.running) }
    androidx.compose.runtime.LaunchedEffect(report, running) {
        if (running && !check.running) { running = false; onDone() }
        if (running) { kotlinx.coroutines.delay(400); running = check.running; if (!running) onDone() }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Background check", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(report?.headline ?: "Opens a harmless app on a hidden screen, reads it and scrolls it once. About 10 seconds.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(enabled = !running, onClick = { if (check.start(context)) running = true }) {
            Text(if (running) "Checking…" else "Check")
        }
    }
    report?.steps?.forEach { step ->
        val mark = when (step.result) {
            com.cyclone.mobile.runtime.plane.CheckResult.PASSED -> "✓"
            com.cyclone.mobile.runtime.plane.CheckResult.FAILED -> "✗"
            com.cyclone.mobile.runtime.plane.CheckResult.SKIPPED -> "–"
        }
        Text("$mark ${step.step.label}: ${step.detail}", style = MaterialTheme.typography.bodySmall,
            color = if (step.result == com.cyclone.mobile.runtime.plane.CheckResult.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** One line about how requests are decided on this phone (alpha 89). */
private fun phoneModelSummary(stats: org.json.JSONObject?): String {
    if (stats == null || stats.optInt("lessons") == 0) {
        return "It learns on this phone from the requests JEV decides. Nothing it learns leaves the phone."
    }
    val parts = mutableListOf<String>()
    stats.optDouble("onPhoneShare").takeIf { !it.isNaN() }?.let { parts += "${(it * 100).toInt()}% decided on this phone" }
    stats.optJSONObject("decisionsMs")?.optLong("p50", -1)?.takeIf { it > 0 }?.let { parts += "JEV answers in ${it} ms (median)" }
    val earned = stats.optJSONArray("earned")?.length() ?: 0
    parts += if (earned == 0) "no actions earned yet" else "$earned ${if (earned == 1) "action" else "actions"} earned"
    return parts.joinToString(" · ") + ". Nothing it learns leaves the phone."
}
