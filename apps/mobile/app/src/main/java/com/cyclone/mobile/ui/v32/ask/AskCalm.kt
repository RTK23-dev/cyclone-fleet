package com.cyclone.mobile.ui.v32.ask

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * R6 (docs/design/redesign/rounds/R6-calm.md): the calm backdrop every page but the AI page sits on. A still blue
 * that deepens to night towards the bottom, with a soft light from the top, like a banking app's home: nothing
 * moves, so the eye rests on the content. The AI page keeps the Cyclone rain.
 *
 * It is drawn once per size and never animates, so the glass above it blurs a still picture: the cheapest backdrop
 * the app has, in Full and in Lite.
 */
object AskCalm {
    val Top = Color(0xFF2B3BF2)
    val Upper = Color(0xFF1B28C9)
    val Middle = Color(0xFF111A86)
    val Lower = Color(0xFF0C1150)
    val Bottom = Color(0xFF090C2B)
    /** The light from the top: a soft, wide glow that lifts the header and the profile slider. */
    val Glow = Color(0xFF5A6CFF)

    /** Content glass on the calm blue: lighter than on the rain, so panels read as frosted indigo, not black. */
    const val SMOKE = 0.40f
    /** Navigation glass stays clearly darker than content (R5's rule), tuned for the blue. */
    const val CHROME_SMOKE = 0.58f
    /** The round quick-action buttons and the header pills: a white veil on the blue. */
    val Veil = Color(0x26FFFFFF)
    val VeilRim = Color(0x33FFFFFF)

    /** Pure: the gradient stops from top (0) to bottom (1). Tested for order and contrast. */
    val STOPS: List<Pair<Float, Color>> = listOf(0f to Top, 0.22f to Upper, 0.48f to Middle, 0.74f to Lower, 1f to Bottom)
}

/** True where the page sits on the calm backdrop (every page but the AI page, inside the glass world). */
val LocalAskCalm = staticCompositionLocalOf { false }

/** The calm backdrop itself. Recorded as the glass world's backdrop, exactly like the rain. */
@Composable
fun AskCalmField(modifier: Modifier = Modifier) {
    Box(
        modifier.drawWithCache {
            val base = Brush.verticalGradient(*AskCalm.STOPS.toTypedArray())
            val glow = Brush.radialGradient(
                listOf(AskCalm.Glow.copy(alpha = 0.45f), Color.Transparent),
                center = Offset(size.width * 0.5f, -size.height * 0.02f),
                radius = size.maxDimension * 0.62f,
            )
            val side = Brush.radialGradient(
                listOf(Color(0xFF3A1FB8).copy(alpha = 0.22f), Color.Transparent),
                center = Offset(size.width * 1.05f, size.height * 0.55f),
                radius = size.maxDimension * 0.5f,
            )
            onDrawBehind {
                drawRect(base)
                drawRect(glow)
                drawRect(side)
            }
        },
    )
}
