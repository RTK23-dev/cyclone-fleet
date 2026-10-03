package com.cyclone.mobile.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * The car's microphone (plan 24 §5.3, plan 32 D3): when a car kit or headset is connected over Bluetooth, Drive
 * listens through it, since it sits closer to the driver than a mounted phone. Android routes it as a communication
 * device (API 31+). If the link does not come up in time, the phone's own microphone is used; Cyclone's speech keeps
 * playing through the car's media audio.
 *
 * The route is held only while Drive listens and cleared right after, so music and navigation come back.
 */
class CarMic(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    @Volatile private var routed = false

    /** Routes to the Bluetooth microphone and returns it, or null for the phone's microphone. */
    suspend fun acquire(waitMs: Long = LINK_WAIT_MS): AudioDeviceInfo? {
        val am = audio ?: return null
        val out = runCatching { am.availableCommunicationDevices }.getOrNull().orEmpty().firstOrNull { it.type in BLUETOOTH } ?: return null
        if (!runCatching { am.setCommunicationDevice(out) }.getOrDefault(false)) return null
        routed = true
        try {
            // Listening was cancelled while the route was being set: hand it straight back.
            coroutineContext.ensureActive()
            val deadline = SystemClock.elapsedRealtime() + waitMs
            while (SystemClock.elapsedRealtime() < deadline) {
                if (am.communicationDevice?.id == out.id) {
                    runCatching { am.getDevices(AudioManager.GET_DEVICES_INPUTS) }.getOrNull().orEmpty()
                        .firstOrNull { it.type == out.type }?.let { return it }
                }
                delay(POLL_MS)
            }
        } catch (cancelled: CancellationException) {
            release()
            throw cancelled
        }
        release()
        return null
    }

    fun release() {
        if (!routed) return
        routed = false
        runCatching { audio?.clearCommunicationDevice() }
    }

    companion object {
        private const val LINK_WAIT_MS = 1_500L
        private const val POLL_MS = 40L
        private val BLUETOOTH = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
}
