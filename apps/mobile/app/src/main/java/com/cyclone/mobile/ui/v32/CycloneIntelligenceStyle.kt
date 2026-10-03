package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/** Cyclone's own quiet aurora identity; shared by the composer and task surfaces. */
internal object CycloneIntelligenceStyle {
    val Ink = Color(0xFF101418)
    val Surface = Color(0xFF1C2228)
    val Raised = Color(0xFF252D35)
    val Text = Color(0xFFF1F4F8)
    val Muted = Color(0xFFAFBBC9)
    val Mint = Color(0xFF8DE3D2)
    val Blue = Color(0xFFA5C8FF)
    val Gradient = Brush.linearGradient(listOf(Blue, Color(0xFFC4B8FF), Mint))
}

@Composable
internal fun CycloneIntelligenceTheme(content: @Composable () -> Unit) {
    CycloneTheme(content = content)
}

/** The Cyclone mark in colour: seven petals spiralling into the void (brand/logo-wormhole). */
@Composable
internal fun CycloneOrbitMark(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(com.cyclone.mobile.R.drawable.ic_cyclone_mark_42),
        contentDescription = null,
        modifier = modifier,
    )
}
