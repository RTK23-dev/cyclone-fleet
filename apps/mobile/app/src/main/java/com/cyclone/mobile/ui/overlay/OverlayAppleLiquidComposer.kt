package com.cyclone.mobile.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.ui.v32.CycloneSignatureGlass
import com.cyclone.mobile.ui.overlay.glass.GlassRoundButton
import com.cyclone.mobile.ui.overlay.glass.GlassToolRow
import com.cyclone.mobile.ui.overlay.glass.GlassToolTile
import com.cyclone.mobile.ui.overlay.glass.VoiceOrbButton
import com.cyclone.mobile.ui.overlay.glass.litRim
import com.cyclone.mobile.ui.overlay.glass.tiltGlass
import com.cyclone.mobile.ui.overlay.glass.veilPill
import com.cyclone.mobile.ui.v32.SignatureAction
import com.cyclone.mobile.ui.v32.SignatureGlyph
import com.cyclone.mobile.ui.v32.SignatureIcon
import com.cyclone.mobile.ui.v32.SignatureInk
import com.cyclone.mobile.ui.v32.SignatureMuted
import androidx.compose.ui.draw.clip

private val OverlayText = SignatureInk
private val OverlaySecondaryText = SignatureMuted
private val OverlayBlue = Color(0xFF83DBD7)

/** Quiet companion panel sharing the ask capsule's teal optical material. */
@Composable
internal fun OverlayAppleGlass(
    modifier: Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp,
    content: @Composable BoxScope.() -> Unit,
) {
    CycloneSignatureGlass(
        modifier = modifier,
        textured = false,
        solidBacking = true,
        cornerRadius = cornerRadius,
        content = content,
    )
}

@Composable
internal fun OverlayAppleComposerBar(
    text: String,
    onTextChanged: (String) -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    placeholder: String,
    menuOpen: Boolean,
    voiceListening: Boolean,
    working: Boolean,
    paused: Boolean,
    taskKey: String,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onMenu: () -> Unit,
    onDictate: () -> Unit,
    onStopDictation: () -> Unit = {},
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    /** Plan 38: typed text sent while Cyclone works opens the Steer / Queue / Parallel list (null: not offered). */
    onSendWhileWorking: (() -> Unit)? = null,
) {
    // Plan 27: the Ask bar on tilt-lit glass. Round controls carry the lit edge; the words sit on one soft pill that
    // fills the space between + and the voice button; the voice button becomes the glowing orb only while listening.
    Box(modifier.fillMaxWidth().heightIn(min = 66.dp).tiltGlass(33.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 66.dp).clip(RoundedCornerShape(33.dp)).padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GlassRoundButton(if (menuOpen) "Close tools" else "Open tools", onMenu) {
                SignatureIcon(SignatureGlyph.ADD, color = SignatureInk)
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChanged,
                singleLine = true,
                textStyle = TextStyle(color = SignatureInk, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
                cursorBrush = SolidColor(OverlayBlue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (text.isNotBlank()) { if (!working) onPrimary() else onSendWhileWorking?.invoke() }
                }),
                modifier = Modifier.weight(1f).focusRequester(focusRequester)
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .heightIn(min = 46.dp).veilPill().padding(horizontal = 18.dp, vertical = 12.dp)
                    .semantics { contentDescription = "Ask Cyclone" },
                decorationBox = { field ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(placeholder, color = SignatureInk, fontSize = 17.sp, maxLines = 1)
                        field()
                    }
                },
            )
            // Tap to talk, or hold while speaking and let go; a bounce or a quick second touch never stops it.
            VoiceOrbButton(
                listening = voiceListening, enabled = !working,
                description = if (voiceListening) "Listening. Tap to stop" else "Dictate request. Tap, or hold while you speak",
                onStart = onDictate, onStop = onStopDictation,
            ) { tint -> SignatureIcon(SignatureGlyph.MIC, color = tint) }
            OverlayRequestAction(working, paused, taskKey, text.isNotBlank(), onPrimary, onPause, onStop, onSendWhileWorking)
        }
    }
}

@Composable
internal fun OverlayAppleToolsMenu(
    sharingActive: Boolean,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onShareScreen: () -> Unit,
    onCrossAppShare: () -> Unit,
    onModelAndIntelligence: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OverlayAppleGlass(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 30.dp,
    ) {
        OverlayAppleToolsContent(
            sharingActive, onCamera, onPhotos, onFiles, onShareScreen, onCrossAppShare, onModelAndIntelligence,
        )
    }
}

/** Tools list shared by the glass menu and the bottom tools drawer. */
@Composable
internal fun OverlayAppleToolsContent(
    sharingActive: Boolean,
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onFiles: () -> Unit,
    onShareScreen: () -> Unit,
    onCrossAppShare: () -> Unit,
    onModelAndIntelligence: () -> Unit,
    modifier: Modifier = Modifier,
) {
    run {
        Column(
            modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OverlayAppleToolTile(Icons.Rounded.PhotoLibrary, "Photos", onPhotos, Modifier.weight(1f))
                OverlayAppleToolTile(Icons.Rounded.CameraAlt, "Camera", onCamera, Modifier.weight(1f))
            }
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OverlayAppleMenuRow(Icons.Rounded.AttachFile, "Files", onFiles)
                OverlayAppleMenuRow(Icons.Rounded.ScreenShare, "Share screen", onShareScreen, enabled = !sharingActive)
                OverlayAppleMenuRow(Icons.Rounded.Apps, "Cross-app share", onCrossAppShare, enabled = !sharingActive)
                OverlayAppleMenuRow(Icons.Rounded.Tune, "Model & intelligence", onModelAndIntelligence)
            }
        }
    }
}

/** Photos / Camera: a lit glass capsule, like the working card's buttons (Tilt Glass). */
@Composable
private fun OverlayAppleToolTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassToolTile(icon, label, onClick, modifier)
}

/** One drawer row: the icon on a lit round glass button, the label beside it. */
@Composable
private fun OverlayAppleMenuRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    GlassToolRow(icon, label, onClick, enabled = enabled)
}

@Composable
internal fun OverlayAppleStatusPill(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    OverlayAppleGlass(
        modifier = modifier.fillMaxWidth().heightIn(min = 46.dp),
        cornerRadius = 23.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text, color = OverlayText, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                Text(
                    actionLabel,
                    color = OverlayBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onAction),
                )
            }
        }
    }
}

@Composable
internal fun OverlayRequestAction(
    working: Boolean, paused: Boolean, taskKey: String, canSend: Boolean,
    onSend: () -> Unit, onPauseOrResume: () -> Unit, onStop: () -> Unit,
    onSendWhileWorking: (() -> Unit)? = null,
) {
    // Plan 38: with typed text the button is Send even while working (it opens the options); empty, it is pause and
    // the two-tap stop.
    if (working && canSend && onSendWhileWorking != null) {
        Box(Modifier.size(46.dp).clip(CircleShape)
            .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.White.copy(alpha = .07f), Color.White.copy(alpha = .02f))))
            .litRim()
            .clickable(role = Role.Button, onClick = onSendWhileWorking)
            .semantics { contentDescription = "Send. Choose steer, queue or parallel" }, contentAlignment = Alignment.Center) {
            SignatureIcon(SignatureGlyph.SEND, color = SignatureInk)
        }
        return
    }
    val gesture = remember(taskKey, working) { ComposerStopGesture() }
    val pauseState by rememberUpdatedState(paused)
    val pauseAction by rememberUpdatedState(onPauseOrResume)
    val stopAction by rememberUpdatedState(onStop)
    var progress by remember(taskKey, working) { mutableFloatStateOf(0f) }
    LaunchedEffect(gesture) {
        while (working) {
            withFrameNanos { progress = gesture.progress(android.os.SystemClock.uptimeMillis()) }
        }
    }
    fun accessibleTap() {
        val now = android.os.SystemClock.uptimeMillis()
        if (gesture.armed(now)) { gesture.reset(); stopAction() }
        else if (pauseState) { gesture.reset(); pauseAction() }
        else { gesture.press(now); gesture.release(now); pauseAction() }
    }
    val action = if (working) Modifier
        .pointerInput(gesture) {
            detectTapGestures(onPress = {
                coroutineScope {
                    val wasPaused = pauseState
                    val wasArmed = gesture.armed(android.os.SystemClock.uptimeMillis())
                    gesture.press(android.os.SystemClock.uptimeMillis())
                    if (!wasPaused) pauseAction()
                    val hold = launch {
                        delay(ComposerStopGesture.HOLD_MS)
                        if (gesture.hold(android.os.SystemClock.uptimeMillis())) stopAction()
                    }
                    try {
                        if (tryAwaitRelease()) {
                            when (gesture.release(android.os.SystemClock.uptimeMillis())) {
                                ComposerStopGesture.Release.STOP -> stopAction()
                                ComposerStopGesture.Release.FIRST_TAP -> if (wasPaused && !wasArmed) {
                                    gesture.reset(); pauseAction()
                                }
                                else -> Unit
                            }
                        } else gesture.cancel()
                    } finally { hold.cancel() }
                }
            })
        }
        .semantics {
            contentDescription = if (paused) "Resume request. Hold two seconds or tap twice to stop" else "Pause request. Hold two seconds or tap twice to stop"
            onClick { accessibleTap(); true }
            customActions = listOf(CustomAccessibilityAction("Stop request") { gesture.reset(); stopAction(); true })
        }
    else Modifier.clickable(enabled = canSend, role = Role.Button, onClick = onSend)
        .semantics { contentDescription = "Send request" }
    Box(Modifier.size(46.dp).clip(CircleShape)
        .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.White.copy(alpha = .07f), Color.White.copy(alpha = .02f))))
        .litRim()
        .then(action), contentAlignment = Alignment.Center) {
        SignatureIcon(if (!working) SignatureGlyph.SEND else if (paused) SignatureGlyph.PLAY else SignatureGlyph.PAUSE,
            color = SignatureInk.copy(alpha = if (working || canSend) 1f else .50f))
        if (progress > 0f) Canvas(Modifier.size(48.dp)) {
            drawArc(OverlayBlue, -90f, progress * 360f, false,
                style = Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}
