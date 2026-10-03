package com.cyclone.mobile.ui.overlay.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFFE0F5F3)
private val DiscFill = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0.02f)))

/**
 * A large tool in the tools drawer (Photos, Camera): a lit capsule like the working card's action buttons, the icon on
 * a soft disc, the label below. The whole tile is the press target, so only its edge is lit.
 */
@Composable
fun GlassToolTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, minHeight: Dp = 76.dp) {
    Column(
        modifier.heightIn(min = minHeight)
            .glassCapsule(22.dp, onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(DiscFill), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(21.dp), tint = Ink)
        }
        Text(label, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A row in the tools drawer (Files, Share screen, …): the icon is a round glass button with the lit rim, the label
 * sits beside it. Pressing anywhere on the row flashes the round button, as the working card's buttons do.
 */
@Composable
fun GlassToolRow(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val alpha = if (enabled) 1f else 0.42f
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).graphicsLayer { this.alpha = alpha }
                .pressGlow(interaction)
                .clip(CircleShape)
                .background(DiscFill)
                .litRim(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(21.dp), tint = Ink)
        }
        Text(label, color = Ink.copy(alpha = alpha), fontSize = 15.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
