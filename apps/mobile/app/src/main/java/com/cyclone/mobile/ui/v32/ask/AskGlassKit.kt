package com.cyclone.mobile.ui.v32.ask

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.ui.overlay.glass.GlassPalette
import com.cyclone.mobile.ui.overlay.glass.LocalGlassPalette
import com.cyclone.mobile.ui.v32.CycloneOrbitMark
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle

/**
 * One page on the R3 material (the AI screen's style, used by Home in R4): the Cyclone rain recorded as the backdrop
 * every smoked-glass surface blurs, the scrim that keeps words readable, the shared shine, and the neutral glass
 * palette for task cards. [withScene] false draws the rain alone (the drawn dome, no video).
 */
@Composable
fun AskGlassPage(
    modifier: Modifier = Modifier,
    withScene: Boolean = false,
    greetingShade: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    // R5: under the app's glass world the page adds only its greeting pool; the rain, backdrop and shine are shared.
    if (LocalAskWorld.current) {
        Box(modifier.fillMaxSize()) {
            if (greetingShade) AskGreetingPool(Modifier.matchParentSize())
            content()
        }
        return
    }
    val backdrop = rememberLayerBackdrop()
    val shine = rememberInfiniteTransition(label = "Glass page shine").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(AskGlass.SHINE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "Glass page shine phase",
    )
    CompositionLocalProvider(
        LocalAskBackdrop provides backdrop,
        LocalAskShine provides shine,
        LocalGlassPalette provides GlassPalette.SMOKE,
    ) {
        Box(modifier.fillMaxSize().background(Color(0xFF050608))) {
            AskRainField(Modifier.matchParentSize().layerBackdrop(backdrop), withScene = withScene)
            AskScrim(Modifier.matchParentSize(), greeting = greetingShade)
            content()
        }
    }
}

/** A glass chip with an icon, a title and a detail line: suggestions on the AI page, quick actions on Home. */
@Composable
fun AskChip(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    shineOffset: Float = 0.3f,
    onClickLabel: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .heightIn(min = 58.dp)
            .askGlass(18.dp, shineOffset = shineOffset)
            .clip(ContinuousRoundedRectangle(18.dp))
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .semantics { contentDescription = "$title. $detail" }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(17.dp), tint = AskGlass.Ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = AskGlass.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, color = AskGlass.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A section title on the rain, with an optional action on the right ("See all", "Open chat"). */
@Composable
fun AskSectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AskSectionLabel(title, Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                Modifier.clip(ContinuousCapsule).clickable(role = Role.Button, onClick = onAction)
                    .heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 12.dp),
                color = AskGlass.Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(shadow = AskTextShadow),
            )
        }
    }
}

/** One smoked-glass card holding a list of rows, with hairlines between them. */
@Composable
fun AskGlassList(modifier: Modifier = Modifier, shineOffset: Float = 0.45f, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .askGlass(24.dp, shineOffset = shineOffset)
            .clip(ContinuousRoundedRectangle(24.dp)),
        content = content,
    )
}

/** A row in an [AskGlassList]: a leading tile, a title, a status line and a trailing mark. */
@Composable
fun AskListRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    subtitleColor: Color = AskGlass.Muted,
    leading: @Composable () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = AskGlass.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = subtitleColor, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** The leading tile of a list row: a soft glass square that holds an app icon or a symbol. */
@Composable
fun AskTile(content: @Composable () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(ContinuousRoundedRectangle(11.dp)).background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

enum class AskPipState { DONE, WORKING, NEEDS_YOU }

/** A small state mark at the end of a row: done (green tick), working (white ring), needs you (red !). */
@Composable
fun AskStatePip(state: AskPipState, progress: Float? = null) {
    when (state) {
        AskPipState.WORKING -> if (progress != null) {
            CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.size(22.dp), color = AskGlass.Ink,
                trackColor = AskGlass.Hairline, strokeWidth = 2.4.dp)
        } else {
            CircularProgressIndicator(Modifier.size(22.dp), color = AskGlass.Ink, trackColor = AskGlass.Hairline, strokeWidth = 2.4.dp)
        }
        AskPipState.DONE -> Box(Modifier.size(24.dp).clip(CircleShape).background(AskGlass.Done.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = Color.White)
        }
        AskPipState.NEEDS_YOU -> Box(Modifier.size(24.dp).clip(CircleShape).background(AskGlass.Problem.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PriorityHigh, null, Modifier.size(15.dp), tint = Color.White)
        }
    }
}

/** A small glass status capsule with a coloured dot ("Ready", "Setup", "Repair"). */
@Composable
fun AskStatusChip(label: String, positive: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 36.dp)
            .askGlass(18.dp, GlassTier.CHROME, shineOffset = 0.25f, shape = ContinuousCapsule)
            .clip(ContinuousCapsule)
            .clickable(role = Role.Button, onClickLabel = "Open settings", onClick = onClick)
            .semantics { contentDescription = "Settings, $label" }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (positive) AskGlass.Done else AskGlass.Waiting))
        Text(label, color = AskGlass.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Home's header (R4): the burger (Settings) top left, the Cyclone word in the middle, and the Cyclone mark top right,
 * which opens Ask Cyclone. The same glass chips as the AI page's header.
 */
@Composable
fun AskHomeHeader(onSettings: () -> Unit, onAi: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AskRoundChip("Settings", onSettings, shineOffset = 0.1f) {
            Icon(Icons.Rounded.Menu, null, Modifier.size(22.dp), tint = AskGlass.Ink)
        }
        Text(
            "Cyclone",
            Modifier.weight(1f),
            color = AskGlass.Ink,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = TextStyle(shadow = AskTextShadow),
        )
        AskRoundChip("Open Ask Cyclone", onAi, shineOffset = 0.15f) { CycloneOrbitMark(Modifier.size(22.dp)) }
    }
}

/** The greeting on the rain: a large line and a quieter one under it, both with the text shadow. */
@Composable
fun AskGreeting(title: String, subtitle: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Column(
        modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp).semantics { contentDescription = "$title. $subtitle" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = AskGlass.Ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(shadow = AskTextShadow))
        Text(subtitle, color = AskGlass.Muted, fontSize = 18.sp, style = TextStyle(shadow = AskTextShadow))
        trailing()
    }
}
