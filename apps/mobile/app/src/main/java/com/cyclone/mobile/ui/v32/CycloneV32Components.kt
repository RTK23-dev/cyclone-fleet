package com.cyclone.mobile.ui.v32

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.automation.AutomationDefinition

private const val LEGACY_ENHANCED_CONTROL_ROW = "Enhanced control engine"

enum class V32Destination(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    PROFILES("Profiles", Icons.Rounded.Person),
    AI("AI", Icons.Rounded.AutoAwesome),
    ROUTINES("Routines", Icons.Rounded.Bolt),
    BRAIN("Brain", Icons.Rounded.AccountTree),
}

@Composable
fun CycloneV32TopBar(
    title: String,
    settingsOpen: Boolean,
    ready: Boolean,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onSearch: (() -> Unit)? = null,
) {
    Surface(color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CycloneSpacing.Page, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (settingsOpen && com.cyclone.mobile.ui.v32.ask.inAskGlass()) {
                // R5: back is a chrome glass chip, like the header chips on Home and the AI page.
                com.cyclone.mobile.ui.v32.ask.AskRoundChip("Back", onBack, shineOffset = 0.1f) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(20.dp), tint = com.cyclone.mobile.ui.v32.ask.AskGlass.Ink)
                }
            } else if (settingsOpen) {
                IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                }
            } else {
                Surface(
                    modifier = Modifier.size(40.dp).clickable(onClick = onSettings),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) { CycloneOrbitMark(Modifier.size(24.dp)) }
                }
            }
            Text(
                if (settingsOpen) "Settings" else title,
                style = MaterialTheme.typography.titleLarge.copy(
                    shadow = if (com.cyclone.mobile.ui.v32.ask.inAskGlass()) com.cyclone.mobile.ui.v32.ask.AskTextShadow else null,
                ),
                modifier = Modifier.weight(1f),
            )
            if (settingsOpen && onSearch != null && com.cyclone.mobile.ui.v32.ask.inAskGlass()) {
                // R6: the smart search from Settings, finding any setting (or anything else) by its words.
                com.cyclone.mobile.ui.v32.ask.AskRoundChip("Search", onSearch, shineOffset = 0.2f) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = com.cyclone.mobile.ui.v32.ask.AskGlass.Ink)
                }
            }
            if (!settingsOpen) {
                Surface(
                    modifier = Modifier.clickable(onClick = onSettings),
                    shape = RoundedCornerShape(999.dp),
                    color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = if (ready) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier.size(7.dp).background(
                                if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                                CircleShape,
                            ),
                        )
                        Text(if (ready) "Ready" else "Setup", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun CycloneV32BottomBar(selected: V32Destination, onSelect: (V32Destination) -> Unit) {
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    if (imeVisible) return

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 5.dp, bottom = 8.dp),
    ) {
        CycloneLiquidTray(height = 62.dp, contentPadding = 4.dp) {
            BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
                CycloneLiquidSelectionLens(
                    selectedIndex = selected.ordinal,
                    itemCount = V32Destination.entries.size,
                    totalWidth = maxWidth,
                    modifier = Modifier.align(Alignment.CenterStart),
                    height = 48.dp,
                    horizontalInset = 4.dp,
                )
                Row(
                    Modifier.fillMaxWidth().fillMaxHeight().selectableGroup(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    V32Destination.entries.forEach { destination ->
                        val isSelected = selected == destination
                        val tint by animateColorAsState(
                            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = tween(180),
                            label = "Cyclone nav tint",
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(destination) }),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(
                                    when (destination) {
                                        V32Destination.HOME -> com.cyclone.mobile.R.drawable.ic_cyclone_home_42
                                        V32Destination.PROFILES -> com.cyclone.mobile.R.drawable.ic_cyclone_profiles_42
                                        V32Destination.AI -> com.cyclone.mobile.R.drawable.ic_cyclone_ai_42
                                        V32Destination.ROUTINES -> com.cyclone.mobile.R.drawable.ic_cyclone_routines_42
                                        V32Destination.BRAIN -> com.cyclone.mobile.R.drawable.ic_cyclone_brain_42
                                    },
                                ),
                                contentDescription = destination.label,
                                modifier = Modifier.size(if (destination == V32Destination.AI) 23.dp else 21.dp),
                                tint = tint,
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
                            Text(
                                destination.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = tint,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CycloneSegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    CycloneLiquidTray(modifier = modifier.fillMaxWidth(), height = 48.dp, contentPadding = 4.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
            CycloneLiquidSelectionLens(
                selectedIndex = selected,
                itemCount = options.size,
                totalWidth = maxWidth,
                modifier = Modifier.align(Alignment.CenterStart),
                height = 38.dp,
                horizontalInset = 4.dp,
            )
            Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
                options.forEachIndexed { index, label ->
                    val active = selected == index
                    val textColor by animateColorAsState(
                        targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(170),
                        label = "Cyclone segment text",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            color = textColor,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CycloneRoutineCard(
    automation: AutomationDefinition,
    tone: CyclonePastel,
    onOpen: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        CycloneAppIcon(automation.appPackages.firstOrNull(), Modifier.size(30.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        automation.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        automation.v32TriggerSummary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CycloneLiquidToggle(checked = automation.enabled, onCheckedChange = onEnabledChange)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append("${automation.steps.size} ${if (automation.steps.size == 1) "action" else "actions"}")
                        automation.categories.firstOrNull()?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (automation.enabled) "On" else "Off",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (automation.enabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun CyclonePermissionRow(
    icon: ImageVector,
    title: String,
    body: String,
    ready: Boolean,
    actionLabel: String = if (ready) "Ready" else "Enable",
    onClick: () -> Unit,
) {
    if (title == LEGACY_ENHANCED_CONTROL_ROW) return

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(13.dp),
            color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                Icon(
                    if (ready) Icons.Rounded.Check else icon,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(actionLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}
