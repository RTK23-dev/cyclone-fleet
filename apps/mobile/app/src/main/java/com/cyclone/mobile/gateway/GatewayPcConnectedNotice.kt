package com.cyclone.mobile.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * "Desk PC is connected to Cyclone" — a quiet notice when a linked PC opens a session after being away, like a chat
 * app's new-login notice. Reconnects within [AWAY_MS] stay silent so a PC that reconnects often never spams.
 * Tapping opens PC Gateway settings, where Linked PCs has Log out per PC. Carries no credential.
 */
internal object GatewayPcConnectedNotice {
    private const val CHANNEL = "cyclone_pc_connected"
    private const val ID = 42_031
    const val AWAY_MS = 6 * 60 * 60 * 1_000L

    /** Announce when the PC was last used more than [AWAY_MS] ago (or never had a session since it was linked). */
    fun shouldAnnounce(previous: GatewayTrustedPc, now: Long): Boolean {
        if (previous.revokedAtMs != null) return false
        val last = previous.lastSessionAtMs.takeIf { it > 0L } ?: return now - previous.createdAtMs >= AWAY_MS
        return now - last >= AWAY_MS
    }

    fun show(context: Context, pcLabel: String) {
        runCatching {
            val app = context.applicationContext
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "PC connected", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "A linked PC connected to Cyclone on this phone."
                },
            )
            val open = PendingIntent.getActivity(
                app,
                ID,
                Intent(app, GatewaySettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val name = pcLabel.ifBlank { "A linked PC" }.take(60)
            manager.notify(
                ID,
                Notification.Builder(app, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("$name is connected to Cyclone")
                    .setContentText("Not you? Open PC Gateway and log it out.")
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        }
    }
}
