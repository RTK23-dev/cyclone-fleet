package com.cyclone.mobile.ui.v32

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.cyclone.mobile.CycloneRelease
import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.CycloneAiAccessProfileStore
import com.cyclone.mobile.gateway.GatewaySettingsActivity
import com.cyclone.mobile.permissions.CyclonePermissionSetup
import com.cyclone.mobile.runtime.background.BackgroundSetup
import com.cyclone.mobile.runtime.background.BackgroundSetupActivity
import com.cyclone.mobile.secrets.VaultSettingsPanel
import com.cyclone.mobile.ui.overlay.WorkingIndicatorSettings
import com.cyclone.mobile.ui.RootFeaturesCard

private data class Settings426Row(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val value: String? = null,
    /** Plan 30: the setup card the ⓘ button shows again. */
    val info: com.cyclone.mobile.setup.SetupCard? = null,
)

private val settingsAutonomyProfiles = listOf(
    CycloneAiAccessProfile.GUIDED,
    CycloneAiAccessProfile.BALANCED,
    CycloneAiAccessProfile.FULL,
)

private fun settingsAutonomyLabel(profile: CycloneAiAccessProfile): String = when (profile) {
    CycloneAiAccessProfile.GUIDED -> "Ask often"
    CycloneAiAccessProfile.BALANCED -> "Balanced"
    CycloneAiAccessProfile.FULL -> "Independent"
}

@Composable
internal fun CycloneSettingsPage426(
    context: Context,
    refreshTick: Int,
    refresh: () -> Unit,
    section: String,
    onSetup: (com.cyclone.mobile.setup.SetupCard?) -> Unit = {},
    onSection: (String) -> Unit,
) {
    val prefs = context.getSharedPreferences(V39AiChatContract.PREFS, Context.MODE_PRIVATE)
    com.cyclone.mobile.brain.UserMdRuntime.initialize(context)
    var selectedModel by rememberSaveable(refreshTick) {
        mutableStateOf(com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context))
    }
    var reasoningEffort by rememberSaveable(refreshTick) {
        mutableStateOf(prefs.getString("openrouter_reasoning_effort", "medium") ?: "medium")
    }
    var accessProfile by rememberSaveable(refreshTick) { mutableStateOf(CycloneAiAccessProfileStore.read(context)) }
    val phoneControl = CyclonePermissionSetup.phoneControlSnapshot(context)
    val notificationAccess = CyclonePermissionSetup.notificationAccessEnabled(context)
    val resultNotifications = CyclonePermissionSetup.resultNotificationsEnabled(context)
    val batteryUnrestricted = CyclonePermissionSetup.batteryUnrestricted(context)
    val background = remember(refreshTick) { BackgroundSetup.read(context) }
    val essentialsReady = listOf(phoneControl.ready, notificationAccess, resultNotifications, batteryUnrestricted).count { it }
    val setupCards = remember { com.cyclone.mobile.setup.SetupState.cards(context) }
    val setupDone = remember(refreshTick) { com.cyclone.mobile.setup.SetupState.done(context).size }
    LaunchedEffect(context) { com.cyclone.mobile.ui.v32.ask.VisualQuality.load(context) }
    val qualityMode by com.cyclone.mobile.ui.v32.ask.VisualQuality.mode.collectAsState()
    val resolvedQuality by com.cyclone.mobile.ui.v32.ask.VisualQuality.resolved.collectAsState()

    fun modelValue(): String = V39AiChatContract.modelForStored(selectedModel).label
    fun effortValue(): String = reasoningEffort.takeIf { it.isNotBlank() }?.let(::reasoningEffortLabel) ?: "Model default"
    fun phoneValue(): String = when {
        phoneControl.ready -> "Ready"
        phoneControl.needsRepair -> "Repair"
        else -> "Setup"
    }
    fun workingIndicatorValue(): String {
        val mode = com.cyclone.mobile.ui.overlay.tracefield.TraceFieldPrefs.mode(context)
        return when (mode) {
            com.cyclone.mobile.ui.overlay.tracefield.TraceFieldMode.OFF -> "Off"
            com.cyclone.mobile.ui.overlay.tracefield.TraceFieldMode.EDGE -> "Edge only"
            com.cyclone.mobile.ui.overlay.tracefield.TraceFieldMode.FIELD ->
                com.cyclone.mobile.ui.overlay.tracefield.TraceFieldPrefs.style(context).label
        }
    }
    fun visualQualityValue(): String = when (qualityMode) {
        com.cyclone.mobile.ui.v32.ask.QualityMode.AUTO ->
            "Auto · " + if (resolvedQuality == com.cyclone.mobile.ui.v32.ask.GlassQuality.LITE) "Lite" else "Full"
        else -> qualityMode.label
    }
    fun backgroundValue(): String = if (background.setupFailure == null) "Ready" else "Setup"

    if (section.isEmpty()) {
        Settings426Root(
            groups = listOf(
                "AI" to listOf(
                    Settings426Row("Model & API", "Model & API", Icons.Rounded.Psychology, modelValue()),
                    Settings426Row("Default intelligence", "Default intelligence", Icons.Rounded.Tune, effortValue()),
                    Settings426Row("Phone autonomy", "Phone autonomy", Icons.Rounded.PhoneAndroid, settingsAutonomyLabel(accessProfile)),
                    Settings426Row("User notes", "User notes", Icons.Rounded.Person, if (com.cyclone.mobile.brain.UserMdRuntime.enabled) "On" else "Off"),
                ),
                "Drive" to listOf(
                    Settings426Row("Driver mode", "Driver mode", Icons.Rounded.DirectionsCar,
                        if (com.cyclone.mobile.voice.DriverMode.enabled(context)) "On" else "Off", com.cyclone.mobile.setup.SetupCard.DRIVER),
                    Settings426Row("Voice", "Voice", Icons.Rounded.RecordVoiceOver, "OpenRouter"),
                ),
                "Appearance" to listOf(
                    Settings426Row(VISUAL_QUALITY, VISUAL_QUALITY, Icons.Rounded.AutoAwesome, visualQualityValue()),
                    Settings426Row("Working indicator", "Working indicator", Icons.Rounded.Tune, workingIndicatorValue()),
                ),
                "Phone" to listOf(
                    Settings426Row(SET_UP_CYCLONE, SET_UP_CYCLONE, Icons.Rounded.CheckCircle, "$setupDone/${setupCards.size}"),
                    Settings426Row("Quick setup", "Quick setup", Icons.Rounded.Bolt, "With root"),
                    Settings426Row("Phone control", "Phone control", Icons.Rounded.Smartphone, phoneValue(), com.cyclone.mobile.setup.SetupCard.PHONE_CONTROL),
                    Settings426Row("Notifications", "Notifications", Icons.Rounded.Notifications, if (resultNotifications) "On" else "Off", com.cyclone.mobile.setup.SetupCard.RESULTS),
                    Settings426Row("Background work", "Background work", Icons.Rounded.CloudQueue, backgroundValue(),
                        com.cyclone.mobile.setup.SetupCard.BACKGROUND.takeIf { it in setupCards }),
                    Settings426Row("Permissions", "Permissions", Icons.Rounded.Security, "$essentialsReady/4"),
                ),
                "Profiles" to listOf(
                    Settings426Row("Profile engine", "Profiles", Icons.Rounded.Layers, "Manage"),
                ),
                "Knowledge" to listOf(
                    Settings426Row("App Maps", "App Maps", Icons.Rounded.Layers, "On device"),
                    Settings426Row("Vault", "Vault", Icons.Rounded.Key, "Slots only"),
                    Settings426Row("Storage", "Storage", Icons.Rounded.Storage, "On device"),
                ),
                "Connections" to listOf(
                    Settings426Row("PC Gateway", "PC Gateway", Icons.Rounded.Smartphone, "Optional"),
                ),
                "Privacy & safety" to listOf(
                    Settings426Row("Privacy & safety", "Privacy & safety", Icons.Rounded.Security),
                ),
                "About" to listOf(
                    Settings426Row("About", "Cyclone", Icons.Rounded.Info, CycloneRelease.label),
                ),
            ),
            onOpen = { id -> if (id == SET_UP_CYCLONE) onSetup(null) else onSection(id) },
            onInfo = onSetup,
        )
        return
    }

    LazyColumn(
        contentPadding = cyclonePageInsets(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (section !in setOf("App Maps", "Vault")) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (section == "Profile engine") "Profiles" else section, style = MaterialTheme.typography.headlineSmall)
                    Settings426DetailSubtitle(section)?.let { subtitle ->
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        when (section) {
            "Working indicator" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        WorkingIndicatorSettings(context)
                    }
                }
            }

            VISUAL_QUALITY -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val modes = com.cyclone.mobile.ui.v32.ask.QualityMode.entries
                        CycloneLiquidChoiceBar(
                            options = modes.map { it.label },
                            selectedIndex = modes.indexOf(qualityMode).coerceAtLeast(0),
                            onSelect = { index -> com.cyclone.mobile.ui.v32.ask.VisualQuality.setMode(context, modes[index]) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            com.cyclone.mobile.ui.v32.ask.QualityPolicy.explain(
                                qualityMode, resolvedQuality,
                                com.cyclone.mobile.ui.v32.ask.VisualQuality.device,
                                com.cyclone.mobile.ui.v32.ask.VisualQuality.steppedDown,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Full blurs and bends the rain under every panel. Lite keeps the same look with less work: " +
                                "one shared blur, no cast shadows on cards and a calmer rain. Auto picks for this phone " +
                                "and moves to Lite if frames start to slow.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            "Driver mode" -> item { Settings426Surface { DriverModeSettings(context, refresh) } }
            "Voice" -> item { Settings426Surface { VoiceSettings(context) } }
            "Quick setup" -> item { CycloneQuickSetup(context, refresh) { onSection("Permissions") } }
            "Model & API" -> item {
                ModelApi426Card(
                    context = context,
                    modelId = selectedModel,
                    effort = reasoningEffort,
                    onChanged = { model, effort ->
                        selectedModel = model
                        reasoningEffort = effort
                        prefs.edit()
                            .putString(V39AiChatContract.MODEL_KEY, model)
                            .putString("openrouter_reasoning_effort", effort)
                            .apply()
                        refresh()
                    },
                )
            }

            "Default intelligence" -> item {
                Settings426Surface {
                    CycloneModelIntelligencePanel(selectedModel, reasoningEffort) { model, effort ->
                        selectedModel = model
                        reasoningEffort = effort
                        prefs.edit()
                            .putString(V39AiChatContract.MODEL_KEY, model)
                            .putString("openrouter_reasoning_effort", effort)
                            .apply()
                        refresh()
                    }
                }
            }

            "Phone autonomy" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CycloneLiquidChoiceBar(
                            options = settingsAutonomyProfiles.map(::settingsAutonomyLabel),
                            selectedIndex = settingsAutonomyProfiles.indexOf(accessProfile).coerceAtLeast(0),
                            onSelect = { index ->
                                accessProfile = settingsAutonomyProfiles[index]
                                CycloneAiAccessProfileStore.write(context, accessProfile)
                                refresh()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            accessProfile.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Payments, credentials, destructive changes and final send actions still require confirmation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            "User notes" -> item { UserNotes426Card(context, refresh) }

            "Phone control" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Settings426Readiness("Phone control", phoneControl.detail, phoneControl.ready)
                        CyclonePermissionRow(
                            Icons.Rounded.Security,
                            "Phone control",
                            phoneControl.detail,
                            phoneControl.ready,
                            phoneControl.actionLabel,
                        ) { open426(context, CyclonePermissionSetup.accessibilitySettings()) }
                        CyclonePermissionRow(
                            Icons.Rounded.Notifications,
                            "Notification triggers",
                            "React to selected app notifications.",
                            notificationAccess,
                            if (notificationAccess) "Manage" else "Enable",
                        ) { open426(context, CyclonePermissionSetup.notificationAccessSettings()) }
                        CyclonePermissionRow(
                            Icons.Rounded.BatteryChargingFull,
                            "Unrestricted battery",
                            "Keep scheduled work reliable while the phone is idle.",
                            batteryUnrestricted,
                            if (batteryUnrestricted) "Manage" else "Allow",
                        ) { open426(context, if (batteryUnrestricted) CyclonePermissionSetup.batteryOptimizationSettings() else CyclonePermissionSetup.batteryExemptionRequest(context)) }
                    }
                }
            }

            "Notifications" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CyclonePermissionRow(
                            Icons.Rounded.Notifications,
                            "Notification triggers",
                            "Let routines react to selected app notifications.",
                            notificationAccess,
                            if (notificationAccess) "Manage" else "Enable",
                        ) { open426(context, CyclonePermissionSetup.notificationAccessSettings()) }
                        CyclonePermissionRow(
                            Icons.Rounded.CheckCircle,
                            "Task results",
                            "Show a concise result after a task or routine.",
                            resultNotifications,
                            if (resultNotifications) "Manage" else "Allow",
                        ) {
                            if (!resultNotifications && Build.VERSION.SDK_INT >= 33) {
                                (context as? Activity)?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 320) }
                            } else open426(context, CyclonePermissionSetup.appDetails(context))
                        }
                    }
                }
            }

            "Background work" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Settings426Readiness(
                            title = if (background.setupFailure == null) "Background work is ready" else "Background work needs setup",
                            body = if (background.setupFailure == null) "Cyclone can keep a task running while you use your phone." else "Open setup to check the secure workspace requirements.",
                            ready = background.setupFailure == null,
                        )
                        CycloneLiquidTextAction(
                            label = if (background.setupFailure == null) "Review background setup" else "Set up background work",
                            onClick = { open426(context, Intent(context, BackgroundSetupActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            "Permissions" -> item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Permissions426Card(context)
                    Codes426Card(context)
                }
            }
            "Profile engine" -> item { Settings426Surface { RootFeaturesCard() } }
            "App Maps" -> item { AppMapsSettingsSection(context, refreshTick) }
            "Vault" -> item { VaultSettingsPanel() }
            "Storage" -> item {
                Settings426Surface {
                    Settings426InfoRow(
                        Icons.Rounded.Memory,
                        "Stored on this phone",
                        "Routines and learned app knowledge stay in Cyclone's local app storage. Manage routines in Routines and learned knowledge in Brain.",
                    )
                }
            }
            "PC Gateway" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Settings426InfoRow(
                            Icons.Rounded.Smartphone,
                            "Optional PC companion",
                            "Cyclone's phone experience works without PC pairing. Connect a PC only when you want desktop control or agent integration.",
                        )
                        CycloneLiquidTextAction(
                            label = "Open PC Gateway",
                            onClick = { open426(context, Intent(context, GatewaySettingsActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            "Privacy & safety" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Settings426InfoRow(Icons.Rounded.Security, "One action authority", "AI, routines and PC requests use the same Cyclone policy and phone executor.")
                        Settings426InfoRow(Icons.Rounded.Visibility, "Verified changes", "Cyclone checks the phone state instead of treating transport success as task success.")
                        Settings426InfoRow(Icons.Rounded.Key, "Sensitive text stays private", "Passwords, OTPs, tokens and typed values are excluded from learning reports.")
                    }
                }
            }
            "About" -> item {
                Settings426Surface {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Cyclone", style = MaterialTheme.typography.titleLarge)
                        Text(CycloneRelease.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("com.cyclone.mobile", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private const val SET_UP_CYCLONE = "Set up Cyclone"
private const val VISUAL_QUALITY = "Visual quality"

/** R5: each Settings group has its own tile colour on the rain, so a group is found by colour before words. */
internal fun settingsGroupTile(group: String): Color = when (group) {
    "AI" -> Color(0xFF7B61FF)
    "Drive" -> Color(0xFF30B85A)
    "Appearance" -> Color(0xFF3E7BFA)
    "Phone" -> Color(0xFFFF9F0A)
    "Profiles" -> Color(0xFF32ADE6)
    "Knowledge" -> Color(0xFF5E5CE6)
    "Connections" -> Color(0xFF64D2FF)
    "Privacy & safety" -> Color(0xFF0A84FF)
    else -> Color(0xFF8E8E93)
}

@Composable
private fun Settings426Root(
    groups: List<Pair<String, List<Settings426Row>>>,
    onOpen: (String) -> Unit,
    onInfo: (com.cyclone.mobile.setup.SetupCard) -> Unit,
) {
    LazyColumn(
        contentPadding = cyclonePageInsets(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        groups.forEach { (groupTitle, rows) ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        groupTitle,
                        modifier = Modifier.padding(start = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CycloneSurface(Modifier.fillMaxWidth()) {
                        Column {
                            rows.forEachIndexed { index, row ->
                                Settings426ListRow(row, settingsGroupTile(groupTitle), onClick = { onOpen(row.id) }, onInfo = onInfo)
                                if (index != rows.lastIndex) {
                                    CycloneHairline(Modifier.padding(start = 58.dp, end = 14.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Settings426ListRow(row: Settings426Row, tile: Color, onClick: () -> Unit, onInfo: (com.cyclone.mobile.setup.SetupCard) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CycloneMatrixIconTile(size = 38.dp, fill = tile) {
            Box(contentAlignment = Alignment.Center) {
                Icon(row.icon, null, Modifier.size(19.dp), tint = matrixAccent())
            }
        }
        Text(row.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        row.info?.let { card ->
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button) { onInfo(card) }
                    .semantics { contentDescription = "What is ${row.title}?" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        row.value?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f))
    }
}

@Composable
private fun ModelApi426Card(
    context: Context,
    modelId: String,
    effort: String,
    onChanged: (String, String) -> Unit,
) {
    Settings426Surface {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CycloneOpenRouterCatalog(context) {
                onChanged(com.cyclone.mobile.ai.OpenRouterCatalogStore.activeId(context), effort)
            }
            Text("Model", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CycloneModelPill(modelId, effort, modifier = Modifier.align(Alignment.Start), onChange = onChanged)
        }
    }
}

@Composable
private fun Permissions426Card(context: Context) {
    val agentKeyboard = CyclonePermissionSetup.agentKeyboardEnabled(context)
    val overlay = CyclonePermissionSetup.overlayEnabled(context)
    val exactTiming = CyclonePermissionSetup.exactTimingEnabled(context)
    val calendar = CyclonePermissionSetup.calendarEnabled(context)
    val calendarWrite = CyclonePermissionSetup.calendarWriteEnabled(context)
    val contacts = CyclonePermissionSetup.contactsEnabled(context)
    val microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    Settings426Surface {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            CyclonePermissionRow(Icons.Rounded.Keyboard, "Cyclone Agent Keyboard", "Optional reliable text entry for stubborn fields.", agentKeyboard, if (agentKeyboard) "Manage" else "Enable") {
                open426(context, CyclonePermissionSetup.keyboardSettings())
            }
            CyclonePermissionRow(Icons.Rounded.Layers, "Display over apps", "Show takeover and task controls above the current app.", overlay, if (overlay) "Manage" else "Allow") {
                open426(context, CyclonePermissionSetup.overlaySettings(context))
            }
            CyclonePermissionRow(Icons.Rounded.Schedule, "Precise timing", "Optional exact scheduling for strict routine times.", exactTiming, if (exactTiming) "Manage" else "Allow") {
                open426(context, CyclonePermissionSetup.exactTimingSettings(context))
            }
            CyclonePermissionRow(Icons.Rounded.CalendarMonth, "Calendar", "Read your events and add the ones you ask for, without opening an app.", calendar && calendarWrite, if (calendar && calendarWrite) "Manage" else "Allow") {
                if (!calendar || !calendarWrite) (context as? Activity)?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 321) }
                else open426(context, CyclonePermissionSetup.appDetails(context))
            }
            CyclonePermissionRow(Icons.Rounded.Contacts, "Contacts", "Look up a number or address when a task asks for someone. Cyclone never changes your contacts.", contacts, if (contacts) "Manage" else "Allow") {
                if (!contacts) (context as? Activity)?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.READ_CONTACTS), 323) }
                else open426(context, CyclonePermissionSetup.appDetails(context))
            }
            CyclonePermissionRow(Icons.Rounded.Mic, "Voice requests", "Speak directly into Ask Cyclone.", microphone, if (microphone) "Manage" else "Allow") {
                if (!microphone) (context as? Activity)?.let { ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.RECORD_AUDIO), 322) }
                else open426(context, CyclonePermissionSetup.appDetails(context))
            }
            Settings426InfoRow(Icons.Rounded.ScreenShare, "Screen sharing asks every session", "Android capture consent remains temporary and explicit.")
        }
    }
}

/**
 * Plan 49: Settings → Permissions → Codes. Cyclone fills a code sent by text to this phone's own number, without asking,
 * when the run plainly uses that number. Texts are read in memory only.
 */
@Composable
private fun Codes426Card(context: Context) {
    var tick by remember { mutableStateOf(0) }
    val canRead = remember(tick) { com.cyclone.mobile.codes.AndroidCodes.canReadTexts(context) }
    var on by remember(tick) { mutableStateOf(com.cyclone.mobile.codes.AndroidCodes.enabled(context)) }
    val sims = remember(tick) { com.cyclone.mobile.codes.AndroidCodes.simNumbers(context).map { it.second } }
    var draft by rememberSaveable { mutableStateOf(com.cyclone.mobile.codes.AndroidCodes.confirmed(context).joinToString(", ")) }
    LaunchedEffect(Unit) { tick++ }
    Settings426Surface {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Codes from this phone's texts", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "When a sign-up or task uses this phone's number, Cyclone reads the code from the text and fills it. " +
                            "Never for banking or payments. Texts are read only for that moment and never saved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CycloneLiquidToggle(on, { next ->
                    on = next
                    com.cyclone.mobile.codes.AndroidCodes.setEnabled(context, next)
                })
            }
            CyclonePermissionRow(Icons.Rounded.Key, "Read texts", "Needed to read the code. If Android says \"Restricted setting\": App info → ⋮ → Allow restricted settings, then try again.",
                canRead, if (canRead) "Manage" else "Allow") {
                if (!canRead) (context as? Activity)?.let {
                    ActivityCompat.requestPermissions(it, arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_PHONE_NUMBERS), 324)
                } else open426(context, CyclonePermissionSetup.appDetails(context))
                tick++
            }
            Text(
                if (sims.isEmpty()) "Your SIM doesn't tell Android its number. Add this phone's number below."
                else "This phone's number from the SIM: ${sims.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("This phone's numbers (comma separated)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            CycloneLiquidTextAction(
                label = "Save numbers",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    com.cyclone.mobile.codes.AndroidCodes.setConfirmed(context, draft.split(',', '\n'))
                    draft = com.cyclone.mobile.codes.AndroidCodes.confirmed(context).joinToString(", ")
                    tick++
                },
            )
        }
    }
}

@Composable
private fun Settings426Readiness(title: String, body: String, ready: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = if (ready) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Settings426InfoRow(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        CycloneMatrixIconTile(size = 40.dp) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Settings426Surface(content: @Composable () -> Unit) {
    CycloneSurface(Modifier.fillMaxWidth()) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

private fun Settings426DetailSubtitle(section: String): String? = when (section) {
    "Driver mode" -> "A large voice button for the car: talk, and Cyclone tells you when it is done."
    "Voice" -> "The voice, the models and how fast Cyclone answers on this phone."
    VISUAL_QUALITY -> "How much glass work the app does: Full, Lite, or Auto for this phone."
    "Working indicator" -> "The Trace Field overlay shown while Cyclone works: mode, style and a live preview."
    "Model & API" -> "Choose the model Cyclone uses and secure your API access."
    "Default intelligence" -> "Set the default reasoning level for new tasks."
    "Phone autonomy" -> "Choose how independently Cyclone may use phone tools."
    "User notes" -> "A short personal sheet Cyclone can use when you mention people or jobs without naming the app."
    "Phone control" -> "Core phone access and reliability."
    "Notifications" -> "Triggers and task-result alerts."
    "Background work" -> "Work while you keep using your main screen."
    "Permissions" -> "Optional capabilities for harder workflows."
    "Profile engine" -> "Create and manage separate app profiles."
    "Storage" -> "Where Cyclone keeps local knowledge."
    "PC Gateway" -> "Connect Cyclone to desktop agents when needed."
    else -> null
}

private fun open426(context: Context, intent: Intent) {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun UserNotes426Card(context: Context, refresh: () -> Unit) {
    var enabled by rememberSaveable { mutableStateOf(com.cyclone.mobile.brain.UserMdRuntime.enabled) }
    var draft by rememberSaveable { mutableStateOf(com.cyclone.mobile.brain.UserMdRuntime.markdown()) }
    Settings426Surface {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Attach to tasks", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Only matching lines are sent — never the whole page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CycloneLiquidToggle(enabled, { on ->
                    enabled = on
                    com.cyclone.mobile.brain.UserMdRuntime.save(context, draft)
                    com.cyclone.mobile.brain.UserMdRuntime.setEnabled(context, on)
                    refresh()
                })
            }
            Text(
                "Edit freely. Cyclone fills People and Apps after finished runs. It never changes # Me.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().height(280.dp),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            CycloneLiquidTextAction(
                label = "Save notes",
                prominent = true,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    com.cyclone.mobile.brain.UserMdRuntime.save(context, draft)
                    draft = com.cyclone.mobile.brain.UserMdRuntime.markdown()
                    refresh()
                },
            )
        }
    }
}
