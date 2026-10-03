package com.cyclone.mobile.ui.v32.ask

import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.overlay.glass.GlassPalette
import com.cyclone.mobile.ui.overlay.glass.LocalGlassPalette
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur

/** True under [AskGlassWorld]: pages then take the backdrop, shine and quality from the app instead of their own. */
val LocalAskWorld = staticCompositionLocalOf { false }

/** True where surfaces draw as smoked glass over the rain (R3 AI page, R4 Home, R5 every in-app page). */
@Composable
@ReadOnlyComposable
fun inAskGlass(): Boolean = LocalAskBackdrop.current != null

/**
 * R5's colour scheme for Material parts on the rain: neutral graphite and white instead of teal. The accents are the
 * state colours of the smoked glass (done green, waiting amber, problem red).
 */
val AskGlassScheme = darkColorScheme(
    primary = Color(0xFFF5F7FA), onPrimary = Color(0xFF0B0C0F),
    primaryContainer = Color(0xFF2B2E34), onPrimaryContainer = Color(0xFFF5F7FA),
    secondary = Color(0xFF7EE2A8), onSecondary = Color(0xFF06291A),
    secondaryContainer = Color(0xFF1D3328), onSecondaryContainer = Color(0xFFC8F5DA),
    tertiary = Color(0xFFFFC46B), onTertiary = Color(0xFF3A2804),
    tertiaryContainer = Color(0xFF3A3122), onTertiaryContainer = Color(0xFFFFE2B3),
    error = Color(0xFFFF8A80), onError = Color(0xFF4A0F0B),
    errorContainer = Color(0xFF45282A), onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF050608), onBackground = Color(0xFFF5F7FA),
    surface = Color(0xFF15171B), onSurface = Color(0xFFF5F7FA),
    surfaceVariant = Color(0xFF24272D), onSurfaceVariant = Color(0xFFB3B7BF),
    surfaceTint = Color(0xFFF5F7FA),
    outline = Color(0xFF5E626A), outlineVariant = Color(0xFF34373D),
    inverseSurface = Color(0xFFF5F7FA), inverseOnSurface = Color(0xFF0B0C0F), inversePrimary = Color(0xFF2B2E34),
    scrim = Color(0xFF000000),
)

/**
 * The one glass world under every in-app page (R5, docs/design/redesign/rounds/R5-app.md). The rain is drawn once for
 * the whole app and recorded as the backdrop every smoked-glass surface blurs; pages no longer own a canvas, so moving
 * between tabs never restarts it.
 *
 * - [aiStage]: the AI page is in front, so the official scene (or the owner's video) plays; elsewhere the drawn dome.
 * - Quality: [VisualQuality] resolves Auto / Full / Lite. Lite blurs the rain once per frame into a shared layer that
 *   content glass samples, and the rain runs at 20 fps. The frame watch can step Auto down while it runs.
 * - While any list scrolls, the rain holds still ([AskMotion]).
 */
@Composable
fun AskGlassWorld(
    aiStage: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(context) {
        VisualQuality.load(context)
        AskBackground.load(context)
    }
    val quality by VisualQuality.resolved.collectAsState()
    val lite = quality == GlassQuality.LITE
    val backdrop = rememberLayerBackdrop()
    val shared = rememberLayerBackdrop()
    val shine = rememberInfiniteTransition(label = "Glass world shine").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(AskGlass.SHINE_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "Glass world shine phase",
    )
    val video by AskBackground.video.collectAsState()
    val scrollHold = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed != Offset.Zero) AskMotion.scrolled(SystemClock.uptimeMillis())
                return Offset.Zero
            }
        }
    }
    AskFrameWatch(quality)
    CompositionLocalProvider(
        LocalAskWorld provides true,
        LocalAskBackdrop provides backdrop,
        LocalAskBlurredBackdrop provides if (lite) shared else null,
        LocalAskShine provides shine,
        LocalGlassQuality provides quality,
        LocalGlassPalette provides GlassPalette.SMOKE,
        LocalAskCalm provides !aiStage,
    ) {
        Box(modifier.fillMaxSize().background(if (aiStage) AskWorld.Canvas else AskCalm.Bottom).nestedScroll(scrollHold)) {
            if (lite) {
                // The shared blur sits under the rain, so it is never seen itself: it only records the blurred rain
                // for the content panels above to sample. One blur a frame instead of one per panel.
                Box(
                    Modifier.matchParentSize()
                        .layerBackdrop(shared)
                        .drawPlainBackdrop(
                            backdrop = backdrop,
                            shape = { RectangleShape },
                            effects = { blur(AskGlass.LITE_SHARED_BLUR_DP.dp.toPx()) },
                        ),
                )
            }
            // R6: the rain (and the scene or the owner's video) is the AI page's alone; every other page rests on the
            // calm blue, which never moves.
            val stageVideo = video?.takeIf { aiStage }
            when {
                !aiStage -> AskCalmField(Modifier.matchParentSize().layerBackdrop(backdrop))
                stageVideo != null -> AskVideoField(stageVideo, Modifier.matchParentSize().layerBackdrop(backdrop))
                else -> AskRainField(Modifier.matchParentSize().layerBackdrop(backdrop), withScene = true, quality = quality)
            }
            if (aiStage) AskScrim(Modifier.matchParentSize())
            content()
        }
    }
}

object AskWorld {
    val Canvas = Color(0xFF050608)
    /** The frame watch waits this long after the world appears before judging (start-up frames are always slow). */
    const val WATCH_SETTLE_MS = 4_000L
    /** Gaps longer than this are idle time (nothing asked for a frame), not a slow frame. */
    const val IDLE_GAP_MS = 250.0
    /** Judged against 60 Hz whatever the panel runs at, so a 120 Hz phone dropping to 60 never counts as slow. */
    const val BUDGET_MS = 1000.0 / 60.0
}

/**
 * Auto's frame watch: while Auto is on Full, it times frames over a rolling window and steps down to Lite when too
 * many run late ([JankWindow]). It stops once Lite is chosen; the owner can pick Full again in Settings.
 */
@Composable
private fun AskFrameWatch(quality: GlassQuality) {
    val context = LocalContext.current
    val mode by VisualQuality.mode.collectAsState()
    if (mode != QualityMode.AUTO || quality != GlassQuality.FULL || AskRain.animationsOff(context)) return
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(AskWorld.WATCH_SETTLE_MS)
        val window = JankWindow()
        var previous = -1L
        while (true) {
            val slow = withFrameNanos { now ->
                val frameMs = if (previous < 0L) 0.0 else (now - previous) / 1_000_000.0
                previous = now
                frameMs in 0.1..AskWorld.IDLE_GAP_MS &&
                    window.record(SystemClock.uptimeMillis(), frameMs, AskWorld.BUDGET_MS)
            }
            if (slow) {
                VisualQuality.stepDown(context)
                break
            }
        }
    }
}

/** The soft pool behind a greeting on the rain (Home, the AI page's empty canvas); the app scrim does the rest. */
@Composable
fun AskGreetingPool(modifier: Modifier = Modifier) {
    Box(
        modifier.drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent),
                    center = Offset(size.width / 2f, size.height * 0.14f),
                    radius = size.width * 0.7f,
                ),
            )
        },
    )
}
