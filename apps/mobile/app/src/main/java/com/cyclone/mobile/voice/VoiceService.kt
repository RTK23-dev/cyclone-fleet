package com.cyclone.mobile.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.cyclone.mobile.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The microphone's foreground service (plan 24 §5.3): it runs only while Drive is listening, so Android keeps the
 * microphone open behind other apps and shows its privacy indicator. It never runs while Cyclone speaks or works.
 */
class VoiceService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Cyclone listening", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cyclone_status)
            .setContentTitle("Cyclone is listening")
            .setContentText("Only while the orb is open. Tap Stop to end.")
            .setOngoing(true)
            .build()
        runCatching { startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
            .onSuccess { _foreground.value = true }
            .onFailure { _foreground.value = false; stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _foreground.value = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cyclone_voice"
        private const val NOTIFICATION_ID = 49_001
        /** How long listening waits for the service to hold the microphone before it records anyway. */
        const val FOREGROUND_WAIT_MS = 800L

        private val _foreground = MutableStateFlow(false)
        /** True while the service holds the microphone in the foreground. */
        val foreground: StateFlow<Boolean> = _foreground

        /**
         * Starts the service and waits until it is really in the foreground (alpha.72). Starting it is asynchronous:
         * recording before it holds the microphone is what Android silences for an app in the background. False if
         * it did not come up in [FOREGROUND_WAIT_MS]; the recording then still runs, and a silenced one is caught by
         * [MicSilence] and handed to the system recognizer.
         */
        suspend fun startAndWait(context: Context): Boolean {
            start(context)
            return withTimeoutOrNull(FOREGROUND_WAIT_MS) { foreground.first { it } } ?: false
        }

        fun start(context: Context) {
            // Without the microphone permission Android refuses the microphone service after it was started, and a
            // started service that never goes foreground crashes the app: then there is nothing to keep open anyway.
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            runCatching { context.startForegroundService(Intent(context, VoiceService::class.java)) }
        }

        fun stop(context: Context) {
            // Alpha.78: false at once. Stopping is asynchronous; a listen that starts before onDestroy ran must not read
            // the old "true" and record before the new service holds the microphone.
            _foreground.value = false
            runCatching { context.stopService(Intent(context, VoiceService::class.java)) }
        }
    }
}
