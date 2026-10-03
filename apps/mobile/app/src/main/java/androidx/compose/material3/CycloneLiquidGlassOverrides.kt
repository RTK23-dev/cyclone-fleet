@file:Suppress("PackageDirectoryMismatch")

package androidx.compose.material3

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.v32.CycloneKyantLiquidButton
import com.cyclone.mobile.ui.v32.CycloneKyantLiquidIconButton
import com.cyclone.mobile.ui.v32.LocalCycloneLiquidBackdrop

/**
 * Cyclone-wide Material 3 compatibility layer.
 *
 * These deliberately narrower overloads win for the Button/IconButton call shapes used by the
 * product without forcing every feature page to carry a custom import. Rendering uses
 * Kyant0/AndroidLiquidGlass whenever a Cyclone backdrop is available. Accessibility overlays are
 * intentionally transparent/content-sized and therefore have no local backdrop; those windows use
 * compact Material-like fallbacks instead of crashing or inventing a full-screen sampling layer.
 */

@Composable
private fun neutralGlassSurface(lightAlpha: Float, darkAlpha: Float): Color =
    MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (isSystemInDarkTheme()) darkAlpha else lightAlpha,
    )

@Composable
private fun LiquidContent(contentColor: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalContentColor provides contentColor, content = content)
}

private val KyantDefaultPadding = PaddingValues(horizontal = 16.dp)

@Composable
private fun FallbackButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentPadding: PaddingValues,
    containerColor: Color,
    contentColor: Color,
    content: @Composable RowScope.() -> Unit,
) {
    val alpha = if (enabled) 1f else .46f
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor.copy(alpha = containerColor.alpha * alpha))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidContent(contentColor.copy(alpha = contentColor.alpha * if (enabled) 1f else .58f)) { content() }
    }
}

@Composable
private fun FallbackIconButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    containerColor: Color,
    contentColor: Color,
    content: @Composable () -> Unit,
) {
    val alpha = if (enabled) 1f else .46f
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .background(containerColor.copy(alpha = containerColor.alpha * alpha))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LiquidContent(contentColor.copy(alpha = contentColor.alpha * if (enabled) 1f else .58f), content)
    }
}

@Composable
private fun CyclonePrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = LocalCycloneLiquidBackdrop.current
    if (backdrop == null) {
        FallbackButton(
            onClick, modifier, enabled, contentPadding,
            MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, content,
        )
        return
    }
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        tint = MaterialTheme.colorScheme.primary,
        contentPadding = contentPadding,
    ) {
        LiquidContent(MaterialTheme.colorScheme.onPrimary) { content() }
    }
}

@Composable
private fun CycloneTonalButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val backdrop = LocalCycloneLiquidBackdrop.current
    if (backdrop == null) {
        FallbackButton(
            onClick, modifier, enabled, contentPadding,
            MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, content,
        )
        return
    }
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        tint = MaterialTheme.colorScheme.primaryContainer,
        contentPadding = contentPadding,
    ) {
        LiquidContent(MaterialTheme.colorScheme.onPrimaryContainer) { content() }
    }
}

@Composable
private fun CycloneElevatedButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val surface = neutralGlassSurface(lightAlpha = 0.11f, darkAlpha = 0.15f)
    val backdrop = LocalCycloneLiquidBackdrop.current
    if (backdrop == null) {
        FallbackButton(
            onClick, modifier, enabled, contentPadding,
            surface, MaterialTheme.colorScheme.onSurface, content,
        )
        return
    }
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        surfaceColor = surface,
        contentPadding = contentPadding,
    ) {
        LiquidContent(MaterialTheme.colorScheme.onSurface) { content() }
    }
}

@Composable
private fun CycloneOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val surface = neutralGlassSurface(lightAlpha = 0.09f, darkAlpha = 0.13f)
    val backdrop = LocalCycloneLiquidBackdrop.current
    if (backdrop == null) {
        FallbackButton(
            onClick, modifier, enabled, contentPadding,
            surface, MaterialTheme.colorScheme.primary, content,
        )
        return
    }
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        surfaceColor = surface,
        contentPadding = contentPadding,
    ) {
        LiquidContent(MaterialTheme.colorScheme.primary) { content() }
    }
}

@Composable
private fun CycloneTextButton(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val surface = neutralGlassSurface(lightAlpha = 0.055f, darkAlpha = 0.085f)
    val backdrop = LocalCycloneLiquidBackdrop.current
    if (backdrop == null) {
        FallbackButton(
            onClick, modifier, enabled, contentPadding,
            surface, MaterialTheme.colorScheme.primary, content,
        )
        return
    }
    CycloneKyantLiquidButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier,
        enabled = enabled,
        surfaceColor = surface,
        contentPadding = contentPadding,
    ) {
        LiquidContent(MaterialTheme.colorScheme.primary) { content() }
    }
}

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = CyclonePrimaryButton(onClick, modifier, enabled, KyantDefaultPadding, content)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) = CyclonePrimaryButton(onClick, modifier, enabled, contentPadding, content)

@Suppress("UNUSED_PARAMETER")
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape,
    contentPadding: PaddingValues = KyantDefaultPadding,
    content: @Composable RowScope.() -> Unit,
) = CyclonePrimaryButton(onClick, modifier, enabled, contentPadding, content)

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = CycloneTonalButton(onClick, modifier, enabled, KyantDefaultPadding, content)

@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) = CycloneTonalButton(onClick, modifier, enabled, contentPadding, content)

@Suppress("UNUSED_PARAMETER")
@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape,
    contentPadding: PaddingValues = KyantDefaultPadding,
    content: @Composable RowScope.() -> Unit,
) = CycloneTonalButton(onClick, modifier, enabled, contentPadding, content)

@Composable
fun ElevatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = CycloneElevatedButton(onClick, modifier, enabled, KyantDefaultPadding, content)

@Composable
fun ElevatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) = CycloneElevatedButton(onClick, modifier, enabled, contentPadding, content)

@Suppress("UNUSED_PARAMETER")
@Composable
fun ElevatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape,
    contentPadding: PaddingValues = KyantDefaultPadding,
    content: @Composable RowScope.() -> Unit,
) = CycloneElevatedButton(onClick, modifier, enabled, contentPadding, content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = CycloneOutlinedButton(onClick, modifier, enabled, KyantDefaultPadding, content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) = CycloneOutlinedButton(onClick, modifier, enabled, contentPadding, content)

@Suppress("UNUSED_PARAMETER")
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape,
    contentPadding: PaddingValues = KyantDefaultPadding,
    content: @Composable RowScope.() -> Unit,
) = CycloneOutlinedButton(onClick, modifier, enabled, contentPadding, content)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) = CycloneTextButton(onClick, modifier, enabled, KyantDefaultPadding, content)

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) = CycloneTextButton(onClick, modifier, enabled, contentPadding, content)

@Suppress("UNUSED_PARAMETER")
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape,
    contentPadding: PaddingValues = KyantDefaultPadding,
    content: @Composable RowScope.() -> Unit,
) = CycloneTextButton(onClick, modifier, enabled, contentPadding, content)

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val surface = neutralGlassSurface(lightAlpha = 0.07f, darkAlpha = 0.11f)
    val backdrop = LocalCycloneLiquidBackdrop.current
    if (backdrop == null) {
        FallbackIconButton(onClick, modifier, enabled, surface, MaterialTheme.colorScheme.onSurface, content)
        return
    }
    CycloneKyantLiquidIconButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        enabled = enabled,
        surfaceColor = surface,
    ) {
        LiquidContent(MaterialTheme.colorScheme.onSurface) { content() }
    }
}

@Composable
fun FilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalCycloneLiquidBackdrop.current
    if (backdrop == null) {
        FallbackIconButton(
            onClick, modifier, enabled,
            MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, content,
        )
        return
    }
    CycloneKyantLiquidIconButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        enabled = enabled,
        tint = MaterialTheme.colorScheme.primary,
    ) {
        LiquidContent(MaterialTheme.colorScheme.onPrimary) { content() }
    }
}

@Composable
fun FilledTonalIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalCycloneLiquidBackdrop.current
    if (backdrop == null) {
        FallbackIconButton(
            onClick, modifier, enabled,
            MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, content,
        )
        return
    }
    CycloneKyantLiquidIconButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        enabled = enabled,
        tint = MaterialTheme.colorScheme.primaryContainer,
    ) {
        LiquidContent(MaterialTheme.colorScheme.onPrimaryContainer) { content() }
    }
}

@Composable
fun OutlinedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val surface = neutralGlassSurface(lightAlpha = 0.09f, darkAlpha = 0.13f)
    val backdrop = LocalCycloneLiquidBackdrop.current
    if (backdrop == null) {
        FallbackIconButton(onClick, modifier, enabled, surface, MaterialTheme.colorScheme.primary, content)
        return
    }
    CycloneKyantLiquidIconButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
        enabled = enabled,
        surfaceColor = surface,
    ) {
        LiquidContent(MaterialTheme.colorScheme.primary) { content() }
    }
}
