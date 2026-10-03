package com.cyclone.mobile.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * "Connect this PC?" on the phone, like linking WhatsApp Web: when a PC (Cyclone One / Glass) asks for trust,
 * a heads-up notification shows the PC name and the six-digit match code and opens the Allow card in one tap.
 *
 * The decision itself stays in [GatewaySettingsActivity] (visible, unlocked phone). The notification carries no
 * credential and never allows anything on its own.
 *
 * Alpha 88: when the phone is unlocked and in use, and Cyclone may draw over other apps (which also lets it open a
 * screen from the background), the Allow card opens by itself, so connecting is one tap: Allow. Otherwise the
 * notification is the way in, as before.
 */
internal object GatewayTrustPrompt {
    private const val CHANNEL = "cyclone_pc_connect"
    private const val ID = 42_030

    fun show(context: Context, challenge: GatewayPendingTrust) {
        runCatching {
            val app = context.applicationContext
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Connect a PC", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "A PC asks to connect to Cyclone on this phone."
                },
            )
            val open = PendingIntent.getActivity(
                app,
                ID,
                Intent(app, GatewaySettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val code = formatCode(GatewayTrustProtocolV33.matchCode(challenge))
            val lifetime = (challenge.expiresAtMs - System.currentTimeMillis()).coerceIn(5_000L, GatewayTrustProtocolV33.CHALLENGE_LIFETIME_MS)
            manager.notify(
                ID,
                Notification.Builder(app, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Connect ${challenge.pcLabel}?")
                    .setContentText("Code $code · tap to check the code and allow")
                    .setAutoCancel(true)
                    .setTimeoutAfter(lifetime)
                    .setContentIntent(open)
                    .build(),
            )
            if (canOpenDirectly(app)) {
                app.startActivity(Intent(app, GatewaySettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun canOpenDirectly(context: Context): Boolean = runCatching {
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        android.provider.Settings.canDrawOverlays(context) && keyguard?.isDeviceLocked != true && power?.isInteractive == true
    }.getOrDefault(false)

    fun clear(context: Context) {
        runCatching { context.applicationContext.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }

    fun formatCode(code: String): String = if (code.length == 6) "${code.take(3)} ${code.drop(3)}" else code
}
