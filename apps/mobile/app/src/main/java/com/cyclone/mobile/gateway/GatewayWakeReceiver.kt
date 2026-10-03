package com.cyclone.mobile.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets the PC bring Cyclone's process back after Android closed it or it crashed, without showing anything on the
 * phone (alpha 88). Receiving the broadcast starts the process, which starts the USB gateway listener; that listener
 * still accepts nothing but the trust handshake without a valid session, so waking grants no access.
 *
 * Only adb (the shell) and the system can send it: the manifest guards it with android.permission.DUMP, which ordinary
 * apps can't hold.
 */
class GatewayWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        runCatching { GatewayRuntime.startPairingBootstrap(context.applicationContext) }
    }

    companion object {
        const val ACTION = "com.cyclone.mobile.action.WAKE_GATEWAY"
    }
}
