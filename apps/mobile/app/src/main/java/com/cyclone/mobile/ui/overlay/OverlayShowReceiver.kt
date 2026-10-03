package com.cyclone.mobile.ui.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Plan 27: the task notification's Show. When the owner folded the overlay down to the notification only, this brings
 * the island back (it only changes what Cyclone shows; the task itself is untouched). Not exported.
 */
class OverlayShowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (OverlayChromeRuntime.snapshot().launcherCollapsed || OverlayChromeRuntime.snapshot().minimized) {
            OverlayChromeRuntime.dispatch(OverlayUserAction.ASK_CYCLONE)
        }
    }

    companion object {
        const val ACTION = "com.cyclone.mobile.action.SHOW_OVERLAY"
    }
}
