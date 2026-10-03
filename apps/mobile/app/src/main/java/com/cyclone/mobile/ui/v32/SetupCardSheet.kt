package com.cyclone.mobile.ui.v32

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyclone.mobile.setup.SetupCard
import com.cyclone.mobile.setup.SetupCopy
import com.cyclone.mobile.setup.SetupFlow
import com.cyclone.mobile.setup.SetupState
import com.cyclone.mobile.setup.SetupStore
import com.cyclone.mobile.ui.overlay.GlassCapsuleButton
import com.cyclone.mobile.ui.overlay.GlassDim
import com.cyclone.mobile.ui.overlay.GlassInk
import com.cyclone.mobile.ui.overlay.GlassMuted
import com.cyclone.mobile.ui.overlay.GlassTeal
import com.cyclone.mobile.ui.overlay.OverlayStackGeometry
import com.cyclone.mobile.ui.overlay.glass.FollowPhoneLight
import com.cyclone.mobile.ui.overlay.glass.GlassRoundButton
import com.cyclone.mobile.ui.overlay.glass.TiltGlassTheme
import com.cyclone.mobile.ui.overlay.glass.tiltGlass
import com.cyclone.mobile.ui.overlay.glass.veilPill

/**
 * Plan 30: the setup cards, on Tilt Glass (docs/design/CYCLONE_TILT_GLASS.md). With [only] null it walks every card
 * that is still off; with a card it shows just that one (the ⓘ in Settings). Settings that are already on are skipped.
 * [refreshTick] changes when the owner comes back from Android's settings, so the card re-checks and moves on.
 */
@Composable
internal fun SetupCardSheet(context: Context, refreshTick: Int, only: SetupCard?, onClose: () -> Unit) {
    val cards = remember { SetupState.cards(context) }
    val done = remember(refreshTick) { SetupState.done(context) }
    var passed by remember { mutableStateOf(emptySet<SetupCard>()) }

    fun close() {
        SetupStore.markSeen(context, if (only != null) listOf(only) else cards)
        onClose()
    }
    BackHandler { close() }
    FollowPhoneLight()

    val current = only ?: SetupFlow.next(cards, done, passed)
    val left = SetupFlow.left(cards, done, passed)

    // A quiet veil over the app; tapping it does nothing, so a stray touch never skips a card.
    Box(
        Modifier.fillMaxSize()
            .background(Color(0xFF04181D).copy(alpha = 0.62f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter,
    ) {
        val radius = OverlayStackGeometry.CARD_RADIUS_DP.dp
        Box(
            Modifier.navigationBarsPadding().padding(16.dp).widthIn(max = 520.dp).fillMaxWidth()
                .tiltGlass(radius).clip(RoundedCornerShape(radius)),
        ) {
            TiltGlassTheme {
                Box(Modifier.fillMaxWidth().padding(14.dp).background(Color(0x4D04181D), RoundedCornerShape(22.dp)).padding(14.dp)) {
                    AnimatedContent(
                        targetState = current,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(140)) },
                        label = "setup-card",
                    ) { card ->
                        if (card == null) {
                            SetupCardBody(
                                meta = "SET UP CYCLONE",
                                icon = Icons.Rounded.CheckCircle,
                                title = SetupCopy.FINISHED_TITLE,
                                why = SetupCopy.finished(SetupFlow.stillOff(cards, done)),
                                on = false,
                                primary = SetupCopy.DONE,
                                onPrimary = { close() },
                                secondary = null,
                                onSecondary = {},
                                onClose = { close() },
                            )
                        } else {
                            val on = card in done
                            SetupCardBody(
                                meta = if (only != null) "CYCLONE SETUP" else SetupCopy.meta(left),
                                icon = card.icon,
                                title = card.title,
                                why = card.why,
                                on = on,
                                primary = if (on) SetupCopy.MANAGE else SetupCopy.SETUP,
                                onPrimary = { SetupState.open(context, card) },
                                secondary = SetupCopy.SKIP,
                                onSecondary = {
                                    if (only != null) close() else {
                                        SetupStore.markSeen(context, listOf(card))
                                        passed = passed + card
                                    }
                                },
                                onClose = { close() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupCardBody(
    meta: String,
    icon: ImageVector,
    title: String,
    why: String,
    on: Boolean,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String?,
    onSecondary: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                meta, Modifier.weight(1f), color = GlassDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                letterSpacing = 0.3.sp, maxLines = 1,
            )
            GlassRoundButton(SetupCopy.CLOSE, onClose, size = 40.dp) {
                Icon(Icons.Rounded.Close, null, Modifier.size(20.dp), tint = GlassInk)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Not pressable, so no lit rim (principle 6): a soft disc only.
            Box(Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(24.dp), tint = GlassInk)
            }
            if (on) {
                Text(
                    SetupCopy.ON, Modifier.veilPill().padding(horizontal = 12.dp, vertical = 5.dp),
                    color = GlassTeal, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = GlassInk, fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        Text(why, color = GlassMuted, fontSize = 15.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassCapsuleButton(primary, primary = true, onClick = onPrimary, modifier = Modifier.weight(1f))
            if (secondary != null) GlassCapsuleButton(secondary, primary = false, onClick = onSecondary)
        }
    }
}

private val SetupCard.icon: ImageVector get() = when (this) {
    SetupCard.PHONE_CONTROL -> Icons.Rounded.Smartphone
    SetupCard.OVER_APPS -> Icons.Rounded.Layers
    SetupCard.RESULTS -> Icons.Rounded.Notifications
    SetupCard.READ_NOTIFICATIONS -> Icons.Rounded.Sms
    SetupCard.KEEP_RUNNING -> Icons.Rounded.BatteryChargingFull
    SetupCard.BACKGROUND -> Icons.Rounded.CloudQueue
    SetupCard.CALENDAR -> Icons.Rounded.CalendarMonth
    SetupCard.CONTACTS -> Icons.Rounded.Contacts
    SetupCard.VOICE -> Icons.Rounded.Mic
    SetupCard.DRIVER -> Icons.Rounded.DirectionsCar
}
