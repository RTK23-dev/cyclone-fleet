package com.cyclone.mobile.ui.v32.ask

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.ai.OpenRouterModelPreset
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.mind.mission.Mission
import com.cyclone.mobile.ui.v32.CycloneOrbitMark
import com.cyclone.mobile.ui.v32.reasoningEffortLabel
import com.cyclone.mobile.voice.DriverMode
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle

/** The page dims under an open sheet; a tap on the dim closes it. */
@Composable
internal fun AskDim(onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = "Close",
            onClick = onDismiss,
        ),
    )
}

/**
 * The model selector (R3 state 2): drops from the header pill. Every model the owner picked in Model & API, the current
 * one ticked, and the thinking level the provider offers for it (exact provider tokens, never invented).
 */
@Composable
internal fun AskModelSheet(
    modifier: Modifier = Modifier,
    onChanged: (modelId: String, effort: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val revision by OpenRouterCatalogStore.revision.collectAsState()
    val models = remember(revision) { OpenRouterCatalogStore.picker(context) }
    var activeId by remember(revision) { mutableStateOf(OpenRouterCatalogStore.activeId(context)) }
    val canonical = remember(revision, activeId) { OpenRouterCatalogStore.canonicalId(activeId) }
    val options = remember(revision, canonical) { OpenRouterCatalogStore.reasoningOptions(context, canonical) }
    val defaultEffort = remember(revision, canonical) { OpenRouterCatalogStore.defaultReasoningEffort(context, canonical) }
    var effort by remember(revision, canonical) { mutableStateOf(OpenRouterCatalogStore.reasoningSelection(context, canonical)) }

    fun choose(option: OpenRouterModelPreset) {
        OpenRouterCatalogStore.setActive(context, option.id)
        activeId = OpenRouterCatalogStore.activeId(context)
        onChanged(com.cyclone.mobile.ui.v32.V39AiChatContract.storageId(option),
            OpenRouterCatalogStore.reasoningSelection(context, option.id).orEmpty())
        onDismiss()
    }

    Column(
        modifier.fillMaxWidth()
            .askGlass(28.dp, GlassTier.CHROME, 0.25f, smoke = AskGlass.SHEET_SMOKE)
            .clip(ContinuousRoundedRectangle(28.dp))
            .semantics { contentDescription = "Model and thinking" }
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AskSheetLabel("Model")
        if (models.isEmpty()) {
            Text("Add models in Settings › Model & API.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = AskGlass.Muted, fontSize = 14.sp)
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
            items(models, key = { it.id }) { model ->
                val selected = OpenRouterCatalogStore.canonicalId(model.id) == canonical
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                        .clip(ContinuousRoundedRectangle(16.dp))
                        .then(if (selected) Modifier.background(Color.White.copy(alpha = 0.12f)).border(0.7.dp, Color.White.copy(alpha = 0.28f), ContinuousRoundedRectangle(16.dp)) else Modifier)
                        .clickable(role = Role.RadioButton, onClick = { choose(model) })
                        .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(model.label, color = AskGlass.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (model.vision) "Sees the screen" else "Text only", color = AskGlass.Muted, fontSize = 12.5.sp)
                    }
                    if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = AskGlass.Ink)
                }
            }
        }
        AskSheetLabel("Thinking", Modifier.padding(top = 8.dp))
        if (options.isEmpty()) {
            Text("Set by the model", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = AskGlass.Muted, fontSize = 14.sp)
        } else {
            val shown = effort ?: defaultEffort
            AskSegmented(options, shown, Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) { option ->
                OpenRouterCatalogStore.setReasoningEffort(context, canonical, option)
                effort = option
                onChanged(activeId, option)
            }
        }
    }
}

@Composable
private fun AskSheetLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = AskGlass.Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
}

/** Up to four levels share the width; more scroll sideways. The shown one is a lighter glass pill. */
@Composable
private fun AskSegmented(options: List<String>, selected: String?, modifier: Modifier = Modifier, onSelect: (String) -> Unit) {
    val fits = options.size <= 4
    Row(
        modifier.fillMaxWidth()
            .clip(ContinuousCapsule)
            .background(AskGlass.Veil)
            .then(if (fits) Modifier else Modifier.horizontalScroll(rememberScrollState()))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            Box(
                (if (fits) Modifier.weight(1f) else Modifier.widthIn(min = 76.dp))
                    .heightIn(min = 40.dp)
                    .clip(ContinuousCapsule)
                    .then(if (on) Modifier.background(Color.White.copy(alpha = 0.22f)).border(0.7.dp, Color.White.copy(alpha = 0.4f), ContinuousCapsule) else Modifier)
                    .clickable(role = Role.RadioButton, onClick = { onSelect(option) })
                    .semantics { stateDescription = if (on) "Selected" else "Not selected" },
                contentAlignment = Alignment.Center,
            ) {
                Text(reasoningEffortLabel(option), color = if (on) AskGlass.Ink else AskGlass.Muted, fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp))
            }
        }
    }
}

/**
 * The menu (R3 state 3): a glass drawer from the left. New chat, a search over runs, every run grouped by day (a run
 * opens in place with its summary, Resume when it can continue, and Remove), then Routines, Brain and Settings.
 */
@Composable
internal fun AskMenuDrawer(
    openRun: String?,
    newChatEnabled: Boolean,
    onNewChat: () -> Unit,
    onRoutines: () -> Unit,
    onBrain: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val history by MindMissions.history.collectAsState()
    val live by MindMissions.live.collectAsState()
    val behind by MindMissions.behind.collectAsState()
    LaunchedEffect(Unit) { MindMissions.refresh(context) }
    var query by remember { mutableStateOf("") }
    var expanded by remember(openRun) { mutableStateOf(openRun) }
    val running = behind.map { it.id }.toSet() + listOfNotNull(live?.id)
    val now = remember(history) { System.currentTimeMillis() }
    val runs = history.filter { it.id !in running && AskCopy.matches(query, it.goal, it.summary) }
    val groups = AskCopy.DAY_GROUPS.mapNotNull { group ->
        runs.filter { AskCopy.dayGroup(it.updatedAtMs, now) == group }.takeIf { it.isNotEmpty() }?.let { group to it }
    }
    val shape = ContinuousRoundedRectangle(topStart = 0.dp, topEnd = 32.dp, bottomEnd = 32.dp, bottomStart = 0.dp)

    Column(
        Modifier.fillMaxHeight().widthIn(max = 340.dp).fillMaxWidth(0.86f)
            .askGlass(32.dp, GlassTier.CHROME, 0.1f, shape, smoke = AskGlass.SHEET_SMOKE)
            .clip(shape)
            .semantics { contentDescription = "Menu" }
            .padding(top = 14.dp, bottom = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CycloneOrbitMark(Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Text("Cyclone", Modifier.weight(1f), color = AskGlass.Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = if (newChatEnabled) 0.14f else 0.06f))
                    .clickable(enabled = newChatEnabled, role = Role.Button, onClick = onNewChat)
                    .semantics { contentDescription = "New chat" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Edit, null, Modifier.size(20.dp), tint = AskGlass.Ink.copy(alpha = if (newChatEnabled) 1f else 0.45f)) }
        }
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth().heightIn(min = 44.dp)
                .clip(ContinuousCapsule).background(AskGlass.Veil).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = AskGlass.Muted)
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.weight(1f).semantics { contentDescription = AskCopy.SEARCH_RUNS },
                textStyle = TextStyle(color = AskGlass.Ink, fontSize = 15.sp),
                cursorBrush = SolidColor(Color.White),
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(AskCopy.SEARCH_RUNS, color = AskGlass.Muted, fontSize = 15.sp)
                        field()
                    }
                },
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (groups.isEmpty()) {
                item {
                    Text(if (query.isBlank()) AskCopy.NO_RUNS else AskCopy.NO_MATCH, Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        color = AskGlass.Muted, fontSize = 14.sp)
                }
            }
            groups.forEach { (group, missions) ->
                item(key = "group-$group") {
                    Text(group, Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp), color = AskGlass.Faint,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                items(missions, key = { it.id }) { mission ->
                    AskDrawerRun(
                        mission = mission,
                        nowMs = now,
                        open = expanded == mission.id,
                        canResume = live == null,
                        onToggle = { expanded = if (expanded == mission.id) null else mission.id },
                        onResume = {
                            MindMissions.resume(context, mission.id)
                            onDismiss()
                        },
                        onRemove = {
                            MindMissions.delete(context, mission.id)
                            expanded = null
                        },
                    )
                }
            }
        }
        AskDivider(Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
        AskMenuLink(Icons.Rounded.Bolt, "Routines", onRoutines)
        AskMenuLink(Icons.Rounded.Psychology, "Brain", onBrain)
        AskMenuLink(Icons.Rounded.Settings, "Settings", onSettings)
    }
}

@Composable
private fun AskDrawerRun(
    mission: Mission,
    nowMs: Long,
    open: Boolean,
    canResume: Boolean,
    onToggle: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            .clip(ContinuousRoundedRectangle(16.dp))
            .then(if (open) Modifier.background(Color.White.copy(alpha = 0.10f)) else Modifier),
    ) {
        AskRunRow(mission, nowMs, trailing = {
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(20.dp), tint = AskGlass.Faint)
        }, onClick = onToggle)
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 62.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                mission.summary.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = AskGlass.Muted, fontSize = 13.5.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mission.status.resumable) AskPillButton("Resume", enabled = canResume, primary = true, onClick = onResume)
                    AskPillButton("Remove", onClick = onRemove)
                }
            }
        }
    }
}

@Composable
private fun AskPillButton(label: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 40.dp).clip(ContinuousCapsule)
            .background(Color.White.copy(alpha = if (primary) 0.22f else 0.10f))
            .border(0.7.dp, Color.White.copy(alpha = if (primary) 0.4f else 0.2f), ContinuousCapsule)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = AskGlass.Ink.copy(alpha = if (enabled) 1f else 0.45f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AskMenuLink(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = AskGlass.Ink)
        Text(label, color = AskGlass.Ink, fontSize = 16.5.sp)
    }
}

/**
 * The logo panel (R3 state 4): Cyclone's own state on one card, from the top right. Phone control, Driver mode and User
 * notes switch here exactly as in Settings; Model & API and Settings open those pages.
 */
@Composable
internal fun AskLogoPanel(modifier: Modifier = Modifier, onSettings: (section: String) -> Unit) {
    val context = LocalContext.current
    val phone = remember { com.cyclone.mobile.permissions.CyclonePermissionSetup.phoneControlSnapshot(context) }
    val drive by DriverMode.settings.collectAsState()
    LaunchedEffect(Unit) { DriverMode.load(context) }
    var notes by remember { mutableStateOf(com.cyclone.mobile.brain.UserMdRuntime.enabled) }
    val video by AskBackground.video.collectAsState()
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) AskBackground.choose(context, uri)
    }
    Column(
        modifier.widthIn(max = 320.dp).fillMaxWidth(0.86f)
            .askGlass(28.dp, GlassTier.CHROME, 0.3f, smoke = AskGlass.SHEET_SMOKE)
            .clip(ContinuousRoundedRectangle(28.dp))
            .semantics { contentDescription = "Cyclone" }
            .padding(vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                CycloneOrbitMark(Modifier.size(26.dp))
            }
            Column {
                Text("Cyclone", color = AskGlass.Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(AskCopy.phoneLine(phone.ready, phone.needsRepair), color = AskGlass.Muted, fontSize = 13.sp)
            }
        }
        AskPanelRow(Icons.Rounded.Smartphone, "Phone control", onClick = { onSettings("Phone control") }) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (phone.ready) AskGlass.Done else AskGlass.Waiting))
            Text(AskCopy.phoneValue(phone.ready, phone.needsRepair), color = AskGlass.Muted, fontSize = 14.sp)
        }
        AskPanelRow(Icons.Rounded.DirectionsCar, "Driver mode", onClick = null) {
            AskSwitch(drive.enabled, "Driver mode") { on ->
                DriverMode.setEnabled(context, on)
                // Turning it on plays the short Drive film, which asks for the microphone at its end if needed.
                if (on) com.cyclone.mobile.ui.overlay.DriveIntroActivity.start(context)
            }
        }
        AskPanelRow(Icons.Rounded.Person, "User notes", onClick = null) {
            AskSwitch(notes, "User notes") { on ->
                com.cyclone.mobile.brain.UserMdRuntime.setEnabled(context, on)
                notes = on
            }
        }
        // The AI screen's background: the rain, or a video the owner picks from their phone (never copied).
        AskPanelRow(Icons.Rounded.Movie, "Background video", onClick = { pickVideo.launch(arrayOf("video/*")) }) {
            Text(if (video != null) "On" else "Off", color = AskGlass.Muted, fontSize = 14.sp)
        }
        if (video != null) {
            AskPanelRow(Icons.Rounded.Grain, "Use the rain", onClick = { AskBackground.useRain(context) }) {
                Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = AskGlass.Faint)
            }
        }
        AskDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        AskPanelRow(Icons.Rounded.Key, "Model & API", onClick = { onSettings("Model & API") }) {
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = AskGlass.Faint)
        }
        AskPanelRow(Icons.Rounded.Settings, "Settings", onClick = { onSettings("") }) {
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = AskGlass.Faint)
        }
    }
}

@Composable
private fun AskPanelRow(icon: ImageVector, label: String, onClick: (() -> Unit)?, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(21.dp), tint = AskGlass.Ink)
        Text(label, Modifier.weight(1f), color = AskGlass.Ink, fontSize = 16.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { trailing() }
    }
}

/** A switch on the glass: green when on (the one colour a switch is allowed), a pale track when off. */
@Composable
private fun AskSwitch(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    val knob by animateDpAsState(if (checked) 20.dp else 0.dp, label = "Ask switch")
    val track by animateColorAsState(if (checked) AskGlass.Done else Color.White.copy(alpha = 0.2f), label = "Ask switch track")
    Box(
        Modifier.width(50.dp).heightIn(min = 30.dp).clip(ContinuousCapsule).background(track)
            .clickable(role = Role.Switch, onClickLabel = label, onClick = { onChange(!checked) })
            .semantics { stateDescription = if (checked) "On" else "Off"; contentDescription = label }
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = knob).size(24.dp).clip(CircleShape).background(Color.White))
    }
}
