package com.cyclone.mobile.ui.v32.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.mind.mission.Mission
import com.cyclone.mobile.ui.v32.CycloneOrbitMark
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle

/** Words on the rain keep a soft shadow, so they stay sharp over the brightest frames. */
internal val AskTextShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), blurRadius = 14f)

/** A round smoked-glass chip (the header's menu and mark): a 44 dp target. */
@Composable
internal fun AskRoundChip(
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    shineOffset: Float = 0f,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .askGlass(size / 2, GlassTier.CHROME, shineOffset = shineOffset, shape = ContinuousCapsule)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * R3 header: the burger (menu drawer) top left, the Cyclone mark (logo panel) top right, and the model selector pill
 * between them, where the word "Cyclone" used to be.
 */
@Composable
internal fun AskHeader(
    modelLabel: String,
    modelOpen: Boolean,
    onMenu: () -> Unit,
    onModel: () -> Unit,
    onLogo: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AskRoundChip("Menu", onMenu, shineOffset = 0.1f) {
            Icon(Icons.Rounded.Menu, null, Modifier.size(22.dp), tint = AskGlass.Ink)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .widthIn(max = 250.dp)
                    .askGlass(22.dp, GlassTier.CHROME, shineOffset = 0.2f, shape = ContinuousCapsule)
                    .clip(ContinuousCapsule)
                    .clickable(role = Role.Button, onClick = onModel)
                    .semantics { contentDescription = "Model: $modelLabel. Choose a model" }
                    .padding(start = 18.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    modelLabel,
                    Modifier.weight(1f, fill = false),
                    color = AskGlass.Ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    if (modelOpen) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    null,
                    Modifier.size(20.dp),
                    tint = AskGlass.Ink,
                )
            }
        }
        AskRoundChip("Cyclone", onLogo, shineOffset = 0.15f) { CycloneOrbitMark(Modifier.size(22.dp)) }
    }
}

/**
 * R3 home: the greeting just under the header, four suggestions that fill the Ask bar, and the last three runs on one
 * glass card ("See all" opens the menu, where every run lives).
 */
@Composable
internal fun AskHome(
    modifier: Modifier = Modifier,
    onSuggestion: (String) -> Unit,
    onSeeAll: () -> Unit,
    onRun: (String) -> Unit,
) {
    val context = LocalContext.current
    val history by MindMissions.history.collectAsState()
    val live by MindMissions.live.collectAsState()
    val behind by MindMissions.behind.collectAsState()
    LaunchedEffect(Unit) { MindMissions.refresh(context) }
    val running = behind.map { it.id }.toSet() + listOfNotNull(live?.id)
    val recent = history.filter { it.id !in running }.take(3)
    val now = remember(history) { System.currentTimeMillis() }
    val greeting = remember { AskCopy.greeting(java.time.LocalTime.now().hour) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)
                .semantics { contentDescription = "$greeting. ${AskCopy.QUESTION}" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(greeting, color = AskGlass.Ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                style = TextStyle(shadow = AskTextShadow))
            Text(AskCopy.QUESTION, color = AskGlass.Muted, fontSize = 20.sp, style = TextStyle(shadow = AskTextShadow))
        }

        AskSectionLabel(AskCopy.SUGGESTIONS_LABEL)
        AskCopy.SUGGESTIONS.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEachIndexed { column, suggestion ->
                    AskSuggestionChip(suggestion, Modifier.weight(1f), shineOffset = 0.3f + 0.05f * (row * 2 + column)) {
                        onSuggestion(suggestion.seed)
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AskSectionLabel(AskCopy.RUNS_LABEL, Modifier.weight(1f))
                Text(
                    AskCopy.SEE_ALL,
                    Modifier.clip(ContinuousCapsule).clickable(role = Role.Button, onClick = onSeeAll)
                        .heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 12.dp),
                    color = AskGlass.Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    style = TextStyle(shadow = AskTextShadow),
                )
            }
            val shape = ContinuousRoundedRectangle(24.dp)
            Column(
                Modifier.fillMaxWidth()
                    .askGlass(24.dp, shineOffset = 0.45f)
                    .clip(shape),
            ) {
                recent.forEachIndexed { index, mission ->
                    if (index > 0) AskDivider(Modifier.padding(start = 62.dp))
                    AskRunRow(mission, now) { onRun(mission.id) }
                }
            }
        }
    }
}

@Composable
internal fun AskSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(horizontal = 4.dp),
        color = AskGlass.Muted,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        style = TextStyle(shadow = AskTextShadow),
    )
}

@Composable
internal fun AskDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.7.dp).background(AskGlass.Hairline))
}

@Composable
private fun AskSuggestionChip(suggestion: AskCopy.Suggestion, modifier: Modifier, shineOffset: Float, onClick: () -> Unit) {
    val shape = ContinuousRoundedRectangle(18.dp)
    Row(
        modifier
            .heightIn(min = 58.dp)
            .askGlass(18.dp, shineOffset = shineOffset)
            .clip(shape)
            .clickable(role = Role.Button, onClickLabel = "Write in the Ask bar", onClick = onClick)
            .semantics { contentDescription = "${suggestion.title}. ${suggestion.detail}" }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            Icon(suggestionIcon(suggestion.icon), null, Modifier.size(17.dp), tint = AskGlass.Ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(suggestion.title, color = AskGlass.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(suggestion.detail, color = AskGlass.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun suggestionIcon(name: String): ImageVector = when (name) {
    "calendar" -> Icons.Rounded.CalendarMonth
    "messages" -> Icons.Rounded.Sms
    "search" -> Icons.Rounded.Search
    else -> Icons.Rounded.AutoAwesome
}

/** One run: a tile in its tone, the goal on one line, and how it ended. */
@Composable
internal fun AskRunRow(mission: Mission, nowMs: Long, trailing: @Composable RowScope.() -> Unit = {
    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = AskGlass.Faint)
}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AskRunTile(mission.status.name)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(mission.goal, color = AskGlass.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(AskCopy.runLine(mission.status.name, mission.updatedAtMs, nowMs), color = AskGlass.Muted, fontSize = 12.5.sp,
                maxLines = 1)
        }
        trailing()
    }
}

@Composable
internal fun AskRunTile(status: String, size: Dp = 36.dp) {
    val tone = AskCopy.tone(status)
    val (color, icon) = when (tone) {
        AskCopy.Tone.DONE -> AskGlass.Done to Icons.Rounded.Check
        AskCopy.Tone.WAITING -> AskGlass.Waiting to Icons.Rounded.Schedule
        AskCopy.Tone.PROBLEM -> AskGlass.Problem to Icons.Rounded.PriorityHigh
    }
    Box(
        Modifier.size(size).clip(ContinuousRoundedRectangle(size * 0.3f)).background(color.copy(alpha = 0.88f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.56f), tint = Color.White)
    }
}
