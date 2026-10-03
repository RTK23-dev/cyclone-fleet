package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * First-stage retraction for in-app Ask Cyclone.
 *
 * Unlike the tiny launcher, this remains a real composer: type, add, choose model/intelligence,
 * dictate or send immediately. The up affordance restores the expanded drawer.
 */
@Composable
internal fun CycloneMinimizedComposerBar(
    text: String,
    onTextChanged: (String) -> Unit,
    onExpand: () -> Unit,
    onAdd: () -> Unit,
    onVoice: () -> Unit,
    onSubmit: () -> Unit,
    sendEnabled: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    voiceActive: Boolean = false,
    onVoiceStop: () -> Unit = {},
) {
    // Plan 27: the folded composer is the same glass Ask bar, with a small open button.
    GlassComposerBar(
        text = text,
        onTextChanged = onTextChanged,
        placeholder = "Ask Cyclone…",
        onAdd = onAdd,
        onVoice = onVoice,
        onVoiceStop = onVoiceStop,
        onSend = onSubmit,
        sendEnabled = sendEnabled,
        busy = busy,
        voiceActive = voiceActive,
        modifier = modifier,
        fieldDescription = "Ask Cyclone minimized composer",
        maxLines = 3,
        onExpand = onExpand,
    )
}
