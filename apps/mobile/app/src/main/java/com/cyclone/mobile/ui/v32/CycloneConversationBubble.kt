package com.cyclone.mobile.ui.v32

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class CycloneConversationSpeaker { USER, CYCLONE }

/**
 * Shared conversation primitive used by Ask Cyclone in-app and by the live overlay.
 *
 * User turns are compact right-aligned bubbles. Cyclone turns stay visually lighter as inline text,
 * matching the reference instead of creating alternating card soup.
 */
@Composable
fun CycloneConversationBubble(
    text: String,
    speaker: CycloneConversationSpeaker,
    modifier: Modifier = Modifier,
    userContainer: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .68f),
    userContent: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    assistantContent: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = Int.MAX_VALUE,
) {
    val clean = text.trim().replace("**", "")
    if (clean.isBlank()) return

    when (speaker) {
        CycloneConversationSpeaker.CYCLONE -> {
            Row(
                modifier
                    .fillMaxWidth()
                    .padding(end = CycloneConversationTokens.space24, top = 2.dp, bottom = 2.dp)
                    .animateContentSize(),
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    clean,
                    modifier = Modifier.padding(horizontal = 2.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = assistantContent,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        CycloneConversationSpeaker.USER -> {
            Row(
                modifier.fillMaxWidth().animateContentSize(),
                horizontalArrangement = Arrangement.End,
            ) {
                CycloneSignatureCard(
                    modifier = Modifier
                        .fillMaxWidth(.80f)
                        .clip(
                            RoundedCornerShape(
                                CycloneConversationTokens.bubbleRadius,
                                CycloneConversationTokens.bubbleRadius,
                                6.dp,
                                CycloneConversationTokens.bubbleRadius,
                            ),
                        )
                        .background(userContainer),
                    cornerRadius = CycloneConversationTokens.bubbleRadius,
                ) {
                    Text(
                        clean,
                        modifier = Modifier.padding(
                            horizontal = CycloneConversationTokens.space16,
                            vertical = CycloneConversationTokens.space12,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SignatureInk,
                        maxLines = maxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
