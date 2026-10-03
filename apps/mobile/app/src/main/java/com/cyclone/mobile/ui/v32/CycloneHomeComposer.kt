package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.ui.overlay.glass.GlassToolRow
import com.cyclone.mobile.ui.overlay.glass.GlassToolTile
import com.cyclone.mobile.ui.overlay.glass.LocalTiltGlass
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.cyclone.mobile.ui.v32.ask.askWhorls
import com.cyclone.mobile.ui.v32.ask.GlassTier
import com.cyclone.mobile.ui.v32.ask.askGlass

@Composable
fun CycloneHomeComposer(seed: Pair<Int, String> = 0 to "", onSubmit: (String) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var tools by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                text = listOf(text, it).filter(String::isNotBlank).joinToString(" ")
            }
        }
    }
    // Quick actions prefill the capsule and focus it; the user always sends the request.
    LaunchedEffect(seed.first) {
        if (seed.first > 0 && seed.second.isNotBlank()) {
            text = seed.second
            runCatching { focusRequester.requestFocus() }
        }
    }

    fun send() {
        val request = text.trim()
        if (request.isBlank()) return
        onSubmit(request)
        text = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Home is a launcher, not a second AI settings surface. Model and intelligence live in
        // the AI workspace; phone autonomy lives in Settings. The capsule is the Ask Cyclone glass.
        // R4: on Home's rain the capsule is the AI page's smoked Ask bar (dots and shine); elsewhere the teal glass.
        val askBackdrop = com.cyclone.mobile.ui.v32.ask.LocalAskBackdrop.current
        val capsule: @Composable (@Composable () -> Unit) -> Unit = { inner ->
            if (askBackdrop != null) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .askGlass(33.dp, GlassTier.CHROME, 0.7f, com.kyant.capsule.ContinuousCapsule)
                        .askWhorls(),
                    contentAlignment = Alignment.Center,
                ) { inner() }
            } else {
                CycloneSignatureGlass(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    focused = focused,
                    refract = true,
                ) { inner() }
            }
        }
        // The + drawer rises above the Ask bar (never under it), on the same glass as the bar's sheets.
        androidx.activity.compose.BackHandler(tools) { tools = false }
        androidx.compose.animation.AnimatedVisibility(
            visible = tools,
            enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Bottom) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Bottom) + androidx.compose.animation.fadeOut(),
        ) {
            val drawer = if (askBackdrop != null) {
                Modifier.fillMaxWidth()
                    .askGlass(28.dp, GlassTier.CHROME, 0.3f, smoke = com.cyclone.mobile.ui.v32.ask.AskGlass.SHEET_SMOKE + 0.1f)
                    .clip(com.kyant.capsule.ContinuousRoundedRectangle(28.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            } else Modifier.fillMaxWidth()
            Box(drawer.semantics { contentDescription = "Attachments" }) {
                CycloneAttachmentTools(
                    onCamera = {
                        tools = false
                        context.startActivity(
                            Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java)
                                .putExtra("camera", true),
                        )
                    },
                    onFiles = {
                        tools = false
                        context.startActivity(Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java))
                    },
                    onPhotos = {
                        tools = false
                        context.startActivity(
                            Intent(context, com.cyclone.mobile.ui.overlay.OverlayAttachmentActivity::class.java)
                                .putExtra("photos", true),
                        )
                    },
                    onShareScreen = {
                        tools = false
                        com.cyclone.mobile.capture.LiveScreenShare.start(context)
                    },
                )
            }
        }
        capsule {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SignatureAction(
                    SignatureGlyph.ADD,
                    "Add attachment",
                    onClick = { tools = !tools },
                    selected = tools,
                )

                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp, max = 72.dp)
                        .padding(horizontal = 8.dp, vertical = 12.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = "Home request composer" },
                    cursorBrush = SolidColor(if (askBackdrop != null) Color.White else SignatureTeal),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (askBackdrop != null) Color.White else SignatureInk),
                    maxLines = 2,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (text.isEmpty()) {
                                Text(
                                    "Ask Cyclone…",
                                    color = if (askBackdrop != null) com.cyclone.mobile.ui.v32.ask.AskGlass.Muted else SignatureMuted,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                            field()
                        }
                    },
                )

                if (text.isBlank()) {
                    SignatureAction(
                        SignatureGlyph.MIC,
                        "Dictate request",
                        onClick = {
                            runCatching {
                                voice.launch(
                                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM),
                                )
                            }.onFailure { error = "Voice is unavailable. You can type your request." }
                        },
                    )
                } else {
                    SignatureAction(SignatureGlyph.SEND, "Send request", onClick = { send() }, selected = true)
                }
            }
        }

        if (error.isNotBlank()) {
            Text(
                error,
                modifier = Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
internal fun CycloneAttachmentTools(
    onCamera: () -> Unit,
    onFiles: () -> Unit,
    onShareScreen: () -> Unit,
    onPhotos: (() -> Unit)? = null,
    extras: List<Pair<ImageVector, String>> = emptyList(),
    tileExtras: List<Pair<ImageVector, String>> = emptyList(),
    onExtra: (String) -> Unit = {},
    filesLabel: String = "Files",
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!LocalTiltGlass.current) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .padding(top = 2.dp, bottom = 2.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = .45f)),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CyclonePlusTile(
                Icons.Rounded.PhotoLibrary,
                "Photos",
                Modifier.weight(1f),
                onPhotos ?: onFiles,
            )
            CyclonePlusTile(
                Icons.Rounded.CameraAlt,
                "Camera",
                Modifier.weight(1f),
                onCamera,
            )
        }
        tileExtras.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (icon, label) ->
                    CyclonePlusTile(icon, label, Modifier.weight(1f)) { onExtra(label) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CycloneAttachmentToolRow(Icons.Rounded.AttachFile, filesLabel, onFiles)
            CycloneAttachmentToolRow(Icons.Rounded.ScreenShare, "Share screen", onShareScreen)
            extras.forEach { (icon, label) ->
                CycloneAttachmentToolRow(icon, label) { onExtra(label) }
            }
        }
    }
}

@Composable
private fun CyclonePlusTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (LocalTiltGlass.current) {
        GlassToolTile(icon, label, onClick, modifier, minHeight = 104.dp)
        return
    }
    Surface(
        modifier = modifier
            .heightIn(min = 104.dp)
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .78f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun CycloneAttachmentToolRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    if (LocalTiltGlass.current) {
        GlassToolRow(icon, label, onClick)
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
