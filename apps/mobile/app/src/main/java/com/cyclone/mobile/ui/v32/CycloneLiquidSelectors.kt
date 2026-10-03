package com.cyclone.mobile.ui.v32

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.overlay.glass.LocalTiltGlass
import com.cyclone.mobile.ui.overlay.glass.glassCapsule

/**
 * Discrete choice control for 2-4 mutually-exclusive options.
 *
 * A standalone bar owns one refractive tray. When already inside a liquid panel the tray is
 * omitted so the selected option is only the moving chromatic lens — never a second darkened box.
 */
@Composable
internal fun CycloneLiquidChoiceBar(
    options: List<String>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (options.isEmpty()) return
    val height = if (compact) 42.dp else 48.dp
    val lensHeight = if (compact) 30.dp else 36.dp
    val embedded = LocalCycloneInsideLiquidHost.current

    val track: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
            selectedIndex?.takeIf { it in options.indices }?.let { index ->
                CycloneLiquidSelectionLens(
                    selectedIndex = index,
                    itemCount = options.size,
                    totalWidth = maxWidth,
                    modifier = Modifier.align(Alignment.CenterStart),
                    height = lensHeight,
                    horizontalInset = 4.dp,
                )
            }
            Row(
                Modifier.fillMaxWidth().fillMaxHeight().selectableGroup(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                options.forEachIndexed { index, label ->
                    val active = selectedIndex == index
                    val color by animateColorAsState(
                        targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(170),
                        label = "Cyclone liquid choice tint",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .selectable(
                                selected = active,
                                role = Role.RadioButton,
                                onClick = { onSelect(index) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 6.dp),
                            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                            color = color,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    if (embedded) {
        Box(modifier.height(height).fillMaxWidth(), contentAlignment = Alignment.Center) { track() }
    } else {
        CycloneLiquidTray(modifier = modifier, height = height, contentPadding = 3.dp) { track() }
    }
}

/**
 * Compact binary liquid control. The moving lens is visibly smaller than the tray and therefore
 * reads as a thumb travelling through one physical object rather than another box drawn inside it.
 */
@Composable
internal fun CycloneLiquidToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    CycloneLiquidTray(
        modifier = modifier.width(62.dp),
        height = 38.dp,
        contentPadding = 3.dp,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                ),
        ) {
            CycloneLiquidSelectionLens(
                selectedIndex = if (checked) 1 else 0,
                itemCount = 2,
                totalWidth = maxWidth,
                modifier = Modifier.align(Alignment.CenterStart),
                height = 28.dp,
                horizontalInset = 3.dp,
            )
            if (checked) {
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .width(maxWidth / 2)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * One liquid trigger used by in-layout selectors. The expanded content is owned by the caller in
 * the same Compose hierarchy; no Material Popup is required for backdrop-dependent controls.
 */
@Composable
internal fun CycloneLiquidMenuTrigger(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
) {
    val height = if (compact) 46.dp else 54.dp
    // Tilt Glass: the trigger is pressed, so it is a lit capsule with the press glow rather than a veil tray.
    val glass = LocalTiltGlass.current
    val frame: @Composable (@Composable BoxScope.() -> Unit) -> Unit = { inner ->
        if (glass) Box(
            modifier.height(height).fillMaxWidth().glassCapsule(height / 2, onClick, enabled = enabled),
            contentAlignment = Alignment.Center,
            content = inner,
        ) else CycloneLiquidTray(modifier = modifier, height = height, contentPadding = 3.dp, content = inner)
    }
    frame {
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .then(if (glass) Modifier else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick))
                .padding(horizontal = if (glass) 16.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (compact) {
                    Text(
                        value,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Choose $title",
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
