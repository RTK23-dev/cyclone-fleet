package com.cyclone.mobile.ui.v32

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.ui.v32.ask.askGlass

/**
 * Teal Matrix is the only in-app palette. Light device settings no longer produce a white/blue
 * variant: every screen, activity and overlay shares the Ask Cyclone teal material.
 */
private val CycloneTealMatrixColors = SignatureScheme.copy(
    surfaceTint = SignatureTeal,
    inverseSurface = SignatureInk,
    inverseOnSurface = TealMatrix.Deep,
    inversePrimary = Color(0xFF1B6E70),
    scrim = Color(0xFF010809),
)

private val CycloneV32Shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

object CycloneConversationTokens {
    val space4 = 4.dp
    val space8 = 8.dp
    val space12 = 12.dp
    val space16 = 16.dp
    val space24 = 24.dp

    val bubbleRadius = 18.dp
    val taskRadius = 22.dp
    val sheetRadius = 28.dp
    val composerRadius = 30.dp

    const val stateTransitionMs = 200
    const val fastTransitionMs = 140
    const val drawerDampingRatio = .90f
    const val drawerStiffness = 560f
}

@Immutable
data class CycloneConversationPalette(
    val active: Color,
    val activeSoft: Color,
    val success: Color,
    val successSoft: Color,
    val attention: Color,
    val attentionSoft: Color,
    val failure: Color,
    val failureSoft: Color,
    val cardOutline: Color,
    val secondaryText: Color,
)

@Composable
fun cycloneConversationPalette(): CycloneConversationPalette {
    val colors = MaterialTheme.colorScheme
    return CycloneConversationPalette(
        active = colors.primary,
        activeSoft = colors.primaryContainer.copy(alpha = .34f),
        success = colors.secondary,
        successSoft = colors.secondaryContainer.copy(alpha = .34f),
        attention = colors.tertiary,
        attentionSoft = colors.tertiaryContainer.copy(alpha = .42f),
        failure = colors.error,
        failureSoft = colors.errorContainer.copy(alpha = .30f),
        cardOutline = colors.outlineVariant.copy(alpha = .62f),
        secondaryText = colors.onSurfaceVariant,
    )
}

val CycloneTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    headlineLarge = TextStyle(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

/**
 * Shared Cyclone theme: Teal Matrix.
 *
 * Normal in-app screens own one full-canvas optical backdrop (the teal dot-matrix field), which the
 * liquid chrome may refract. A floating accessibility overlay is a different window and cannot sample
 * pixels owned by the host app, so transparent mode deliberately owns no backdrop layer at all and
 * stays content-sized (WRAP_CONTENT) over the app underneath it.
 */
@Composable
fun CycloneTheme(
    drawBackground: Boolean = true,
    glass: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (glass) {
        // R5 (docs/design/redesign/rounds/R5-app.md): the app's glass world draws the canvas (the Cyclone rain), so
        // the theme adds no teal backdrop; Material parts take the neutral glass scheme.
        MaterialTheme(
            colorScheme = com.cyclone.mobile.ui.v32.ask.AskGlassScheme,
            shapes = CycloneV32Shapes,
            typography = CycloneTypography,
        ) {
            CompositionLocalProvider(
                LocalCycloneSignatureTheme provides true,
                LocalContentColor provides com.cyclone.mobile.ui.v32.ask.AskGlass.Ink,
                LocalCycloneLiquidBackdrop provides null,
                LocalTealMatrixField provides false,
            ) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
        return
    }
    MaterialTheme(
        colorScheme = CycloneTealMatrixColors,
        shapes = CycloneV32Shapes,
        typography = CycloneTypography,
    ) {
        CompositionLocalProvider(
            LocalCycloneSignatureTheme provides true,
            LocalContentColor provides SignatureInk,
        ) {
            if (drawBackground) {
                // No captured backdrop layer: nothing re-blurs the moving canvas every frame.
                // Controls get teal glass from the canvas itself (Modifier.tealGlass); Material
                // overrides use their plain fallbacks.
                CompositionLocalProvider(
                    LocalCycloneLiquidBackdrop provides null,
                    LocalTealMatrixField provides true,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        TealMatrixBackdrop(Modifier.fillMaxSize())
                        content()
                    }
                }
            } else {
                CompositionLocalProvider(LocalCycloneLiquidBackdrop provides null) {
                    Box(Modifier.wrapContentSize()) { content() }
                }
            }
        }
    }
}

enum class CyclonePastel { PRIMARY, LILAC, MINT, LEMON, PEACH, SKY }

@Immutable
data class CyclonePastelColors(val container: Color, val content: Color)

@Composable
fun cyclonePastel(tone: CyclonePastel): CyclonePastelColors {
    return CyclonePastelColors(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
}

@Composable
fun CycloneHeroCard(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: CyclonePastel = CyclonePastel.PRIMARY,
    action: (@Composable () -> Unit)? = null,
) {
    CycloneMatrixCard(modifier = modifier.fillMaxWidth(), cornerRadius = 24.dp, contentPadding = 18.dp) {
        CycloneMatrixIconTile(size = 44.dp) {
            Icon(icon, null, modifier = Modifier.size(22.dp), tint = matrixAccent())
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        action?.invoke()
    }
}

@Composable
fun CycloneSectionTitle(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (com.cyclone.mobile.ui.v32.ask.inAskGlass()) {
            // R5: a quiet section label on the rain, like a grouped list's header.
            Text(
                title,
                style = MaterialTheme.typography.labelLarge.copy(shadow = com.cyclone.mobile.ui.v32.ask.AskTextShadow),
                color = com.cyclone.mobile.ui.v32.ask.AskGlass.Muted,
                letterSpacing = 0.3.sp,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        } else {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        action?.invoke()
    }
}

@Composable
fun CycloneStatusPill(label: String, positive: Boolean = true) {
    val glass = com.cyclone.mobile.ui.v32.ask.inAskGlass()
    val accent = when {
        glass && positive -> com.cyclone.mobile.ui.v32.ask.AskGlass.Done
        glass -> com.cyclone.mobile.ui.v32.ask.AskGlass.Waiting
        positive -> TealMatrix.Success
        else -> TealMatrix.Caution
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (glass) Color.White.copy(alpha = .10f) else accent.copy(alpha = .12f))
            .border(.7.dp, if (glass) Color.White.copy(alpha = .16f) else accent.copy(alpha = .38f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).background(accent, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (glass) com.cyclone.mobile.ui.v32.ask.AskGlass.Ink else SignatureInk)
    }
}

@Composable
fun CycloneSimpleCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    CycloneMatrixCard(modifier = modifier, cornerRadius = 20.dp, content = content)
}

@Composable
fun CyclonePageIntro(eyebrow: String, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = if (com.cyclone.mobile.ui.v32.ask.inAskGlass()) com.cyclone.mobile.ui.v32.ask.AskGlass.Muted else SignatureTeal)
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
fun CyclonePageHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (centered) {
        Column(
            modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            trailing?.invoke()
        }
        return
    }
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // R5: on the rain the large title and its line keep the words' soft shadow.
        val shade = if (com.cyclone.mobile.ui.v32.ask.inAskGlass()) com.cyclone.mobile.ui.v32.ask.AskTextShadow else null
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge.copy(shadow = shade))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(shadow = shade), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun CycloneBackRow(label: String, onClick: () -> Unit) {
    if (com.cyclone.mobile.ui.v32.ask.inAskGlass()) {
        // R5: back is navigation, so it is a chrome glass chip.
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .askGlass(20.dp, com.cyclone.mobile.ui.v32.ask.GlassTier.CHROME, 0.15f, com.kyant.capsule.ContinuousCapsule)
                .clip(com.kyant.capsule.ContinuousCapsule)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp), tint = com.cyclone.mobile.ui.v32.ask.AskGlass.Ink)
            Text(label, style = MaterialTheme.typography.titleSmall, color = com.cyclone.mobile.ui.v32.ask.AskGlass.Ink)
        }
        return
    }
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun CycloneHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (com.cyclone.mobile.ui.v32.ask.inAskGlass()) com.cyclone.mobile.ui.v32.ask.AskGlass.Hairline else TealMatrix.Hairline.copy(alpha = .55f)),
    )
}

@Composable
fun CycloneV32Theme(
    drawBackground: Boolean = true,
    glass: Boolean = false,
    content: @Composable () -> Unit,
) = CycloneTheme(drawBackground = drawBackground, glass = glass, content = content)

object CycloneColors {
    val Blue = SignatureTeal
    val Cyan = Color(0xFF41D7CB)
    val Success = Color(0xFF4FD9B4)
}

object CycloneSpacing {
    val Tiny = 4.dp
    val Small = 8.dp
    val Content = 16.dp
    val Page = 20.dp
    val Section = 28.dp
    val ComposerLift = 30.dp
    /** Extra list breathing room after Scaffold already inset the tab bar. */
    val ScreenBottom = 24.dp
}

fun cyclonePageInsets(top: androidx.compose.ui.unit.Dp = 14.dp) = PaddingValues(
    start = CycloneSpacing.Page,
    top = top,
    end = CycloneSpacing.Page,
    bottom = CycloneSpacing.ScreenBottom,
)

@Composable
fun CycloneSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    CycloneSignatureGlass(modifier = modifier, textured = false, solidBacking = true, cornerRadius = 20.dp) {
        Box { content() }
    }
}

/**
 * Calm content surface. Interactive navigation/action chrome must use CycloneLiquid* components;
 * this intentionally stays a restrained teal glass container.
 */
@Composable
fun CycloneGlassSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    CycloneSignatureGlass(
        modifier = modifier.animateContentSize(),
        textured = false,
        solidBacking = true,
        cornerRadius = 24.dp,
    ) {
        Box { content() }
    }
}

@Composable
fun CycloneStatus(label: String, positive: Boolean = true) = CycloneStatusPill(label, positive)
