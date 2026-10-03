package com.cyclone.mobile.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LivePhoneUiState { ON, PAUSED, OFF }

/** Presentation and an additional stop boundary, never a mutation engine. */
object LivePhoneMode {
    private const val ID = 42015
    private val current = MutableStateFlow("Ready for Cloud AI agents")
    val state = current.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences("live_phone", Context.MODE_PRIVATE)

    fun uiState(context: Context): LivePhoneUiState {
        val preferences = prefs(context)
        return when {
            preferences.getBoolean("stopped", false) -> LivePhoneUiState.OFF
            preferences.getBoolean("paused", false) -> LivePhoneUiState.PAUSED
            else -> LivePhoneUiState.ON
        }
    }

    fun setPaused(context: Context, paused: Boolean, stopped: Boolean = false) {
        prefs(context).edit()
            .putBoolean("paused", paused)
            .putBoolean("stopped", stopped && paused)
            .apply()
        current.value = when {
            stopped -> "Off"
            paused -> "Paused"
            else -> "Ready for Cloud AI agents"
        }
        if (stopped) context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    internal fun check(context: Context, request: GatewayRequest) {
        if (!request.args.optBoolean("livePhone")) return
        if (request.args.optString("sessionId") != "default-foreground" || request.args.optInt("displayId", -1) != 0) {
            throw GatewayProtocolException("PLANE_MISMATCH", "Live Phone requires the physical foreground screen")
        }
        val paused = prefs(context).getBoolean("paused", false)
        if (paused) throw GatewayProtocolException("LIVE_PHONE_PAUSED", "Resume Live Phone on the phone")
        current.value = "Cloud AI agents · Foreground access"
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("live_phone", "Live Phone", NotificationManager.IMPORTANCE_LOW))
            fun action(name: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
                context,
                code,
                Intent(context, LivePhoneControlReceiver::class.java).setAction(name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                ID,
                Notification.Builder(context, "live_phone")
                    .setSmallIcon(android.R.drawable.ic_menu_view)
                    .setContentTitle("LIVE PHONE · Cloud AI agents")
                    .setContentText("Visible phone screen access. Pause or stop new phone actions.")
                    .setOngoing(true)
                    .setTimeoutAfter(30_000)
                    .setContentIntent(
                        PendingIntent.getActivity(
                            context,
                            42015,
                            Intent(context, GatewaySettingsActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .addAction(Notification.Action.Builder(null, "Pause", action("pause", 42016)).build())
                    .addAction(Notification.Action.Builder(null, "Stop", action("stop", 42017)).build())
                    .build(),
            )
        }
    }
}

class LivePhoneControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LivePhoneMode.setPaused(context, true, intent.action == "stop")
    }
}
