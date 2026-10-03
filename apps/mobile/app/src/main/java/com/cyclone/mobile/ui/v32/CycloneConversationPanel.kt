package com.cyclone.mobile.ui.v32

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One protective glass surface for the conversation, with smaller task elements inside it.
 * The host owns one bounded scroll viewport; the persistent Ask composer is outside this panel.
 */
@Composable
internal fun CycloneConversationPanel(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    CycloneSignatureCard(modifier = modifier, cornerRadius = 28.dp) {
        Box(Modifier.padding(14.dp), content = content)
    }
}
