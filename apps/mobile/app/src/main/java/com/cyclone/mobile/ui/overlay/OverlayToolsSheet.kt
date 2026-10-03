package com.cyclone.mobile.ui.overlay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.v32.CycloneModelIntelligencePanel
import com.cyclone.mobile.ui.overlay.glass.TiltGlassTheme
import com.cyclone.mobile.ui.overlay.glass.tiltGlass
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The Ask overlay's tools drawer (the "+" menu).
 *
 * It no longer grows inside the Cyclone work panel: it is its own full-screen window that slides up
 * from the bottom edge over the Ask overlay, blurs and dims everything behind it, and collapses by
 * dragging or flicking the handle down, tapping outside, or Back. [page] is the single source of
 * truth; the overlay controller adds the window while it is not NONE.
 */
internal object OverlayToolsSheetState {
    private val mutablePage = MutableStateFlow(ComposerAccessory.NONE)
    val page: StateFlow<ComposerAccessory> = mutablePage.asStateFlow()

    fun toggle(next: ComposerAccessory = ComposerAccessory.ATTACHMENTS) { mutablePage.value = mutablePage.value.toggle(next) }
    fun show(next: ComposerAccessory) { mutablePage.value = next }
    fun close() { mutablePage.value = ComposerAccessory.NONE }
}

/** Pure drag/flick rules so the collapse gesture feels the same everywhere and is unit tested. */
internal object OverlayToolsSheetPhysics {
    /** Fraction of the sheet height the user must drag down before releasing closes it. */
    const val DISMISS_FRACTION = 0.28f
    /** A downward flick faster than this closes the sheet regardless of distance (px/s). */
    const val DISMISS_VELOCITY = 1_100f
    /** Maximum background blur, in dp, when the device allows cross-window blur. */
    const val BLUR_DP = 26f

    fun shouldDismiss(dragPx: Float, velocityPxPerS: Float, sheetHeightPx: Float): Boolean {
        if (velocityPxPerS > DISMISS_VELOCITY) return true
        if (velocityPxPerS < -DISMISS_VELOCITY) return false
        return sheetHeightPx > 0f && dragPx > sheetHeightPx * DISMISS_FRACTION
    }

    /** 0 = fully hidden (offset == height), 1 = fully open (offset == 0). */
    fun openFraction(offsetPx: Float, sheetHeightPx: Float): Float =
        if (sheetHeightPx <= 0f) 0f else (1f - offsetPx / sheetHeightPx).coerceIn(0f, 1f)

    /** Dragging up past the open position is resisted, never free. */
    fun resistedOffset(current: Float, delta: Float): Float {
        val next = current + delta
        return if (next < 0f) current + delta * 0.18f else next
    }
}

internal class OverlayToolsSheetActions(
    val onCamera: () -> Unit,
    val onPhotos: () -> Unit,
    val onFiles: () -> Unit,
    val onShareScreen: () -> Unit,
    val onCrossAppShare: () -> Unit,
)

@Composable
internal fun OverlayToolsSheet(
    page: ComposerAccessory,
    sharingActive: Boolean,
    aiSettings: OverlayAiSettings,
    onAiSettingsChanged: (OverlayAiSettings) -> Unit,
    actions: OverlayToolsSheetActions,
    blurAvailable: Boolean,
    onBlurFraction: (Float) -> Unit,
    closeRequests: Int,
    onClosed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Offset from the open position, in px. Starts off-screen and springs up once measured.
    val offset = remember { Animatable(Float.MAX_VALUE) }
    val sheetHeight = remember { floatArrayOf(0f) }
    fun fraction() = OverlayToolsSheetPhysics.openFraction(offset.value, sheetHeight[0])

    fun animateClosed() {
        scope.launch {
            val h = sheetHeight[0]
            if (h > 0f && offset.value < h) {
                offset.animateTo(h, tween(durationMillis = 220)) { onBlurFraction(fraction()) }
            }
            onBlurFraction(0f)
            onClosed()
        }
    }

    fun settle(velocity: Float) {
        val h = sheetHeight[0]
        if (OverlayToolsSheetPhysics.shouldDismiss(offset.value, velocity, h)) {
            animateClosed()
        } else {
            scope.launch {
                offset.animateTo(0f, spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow), velocity) {
                    onBlurFraction(fraction())
                }
            }
        }
    }

    // Back / external close requests run the same smooth exit as a drag.
    LaunchedEffect(closeRequests) { if (closeRequests > 0) animateClosed() }

    val dragState = rememberDraggableState { delta ->
        val next = OverlayToolsSheetPhysics.resistedOffset(offset.value, delta)
        scope.launch { offset.snapTo(next) }
        onBlurFraction(OverlayToolsSheetPhysics.openFraction(next, sheetHeight[0]))
    }

    val scrimAlpha = (if (blurAvailable) 0.30f else 0.52f) * fraction()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF01080A).copy(alpha = scrimAlpha))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Close tools",
                ) { animateClosed() },
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                .onSizeChanged { size ->
                    val first = sheetHeight[0] == 0f
                    sheetHeight[0] = size.height.toFloat()
                    if (first) {
                        scope.launch {
                            offset.snapTo(size.height.toFloat())
                            offset.animateTo(0f, spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessMediumLow)) {
                                onBlurFraction(fraction())
                            }
                        }
                    }
                }
                .offset { IntOffset(0, if (offset.value == Float.MAX_VALUE) 100_000 else offset.value.roundToInt()) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity -> settle(velocity) },
                ),
        ) {
            // Tilt Glass: the drawer is a working card (same glass, grabber and inner veil); every control on it is a
            // lit capsule or round button with the press glow, and the model page uses the glass palette.
            Box(
                Modifier
                    .fillMaxWidth()
                    .tiltGlass(OverlayStackGeometry.CARD_RADIUS_DP.dp)
                    .clip(RoundedCornerShape(OverlayStackGeometry.CARD_RADIUS_DP.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* Taps inside the sheet never fall through to the scrim. */ },
            ) {
                TiltGlassTheme {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Handle: the whole sheet drags; this is the card's grabber and a click target.
                        Box(
                            Modifier
                                .size(width = 64.dp, height = 22.dp)
                                .semantics {
                                    contentDescription = "Tools drawer handle"
                                    onClick(label = "Close tools") { animateClosed(); true }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.size(width = 32.dp, height = 3.dp).background(GlassMuted.copy(alpha = 0.38f), CircleShape))
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                                .background(Color(0x4D04181D), RoundedCornerShape(22.dp))
                                .padding(12.dp),
                        ) {
                            AnimatedContent(
                                targetState = page,
                                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                                label = "Tools drawer page",
                            ) { shown ->
                                when (shown) {
                                    ComposerAccessory.MODEL -> Box(Modifier.fillMaxWidth()) {
                                        CycloneModelIntelligencePanel(aiSettings.modelId, aiSettings.reasoningEffort) { model, effort ->
                                            onAiSettingsChanged(aiSettings.copy(modelId = model, reasoningEffort = effort))
                                        }
                                    }
                                    else -> OverlayAppleToolsContent(
                                        sharingActive = sharingActive,
                                        onCamera = actions.onCamera,
                                        onPhotos = actions.onPhotos,
                                        onFiles = actions.onFiles,
                                        onShareScreen = actions.onShareScreen,
                                        onCrossAppShare = actions.onCrossAppShare,
                                        onModelAndIntelligence = { OverlayToolsSheetState.show(ComposerAccessory.MODEL) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
