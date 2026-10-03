package com.cyclone.mobile.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayExternalInteraction
import com.cyclone.mobile.ui.overlay.OverlayUserAction

/** Product Share screen: whole-display capture plus the overlay composer. Never Android's split "Share one app" UI. */
object LiveScreenShare {
    const val EXTRA_WHOLE_DISPLAY = "wholeDisplay"
    const val EXTRA_REVEAL_OVERLAY = "revealOverlay"

    fun start(context: Context, revealOverlay: Boolean = true) {
        OverlayExternalInteraction.active.value = true
        val overlayReady = OverlayChromeRuntime.isAttached()
        if (revealOverlay && overlayReady) {
            OverlayChromeRuntime.dispatch(OverlayUserAction.ASK_CYCLONE)
        } else if (revealOverlay && !overlayReady) {
            android.widget.Toast.makeText(
                context,
                "Turn on Phone control in Settings so Cyclone can stay over other apps.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        val intent = Intent(context, LiveCaptureConsentActivity::class.java)
            .putExtra(EXTRA_WHOLE_DISPLAY, true)
            .putExtra(EXTRA_REVEAL_OVERLAY, revealOverlay)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun revealAfterConsent(activity: Activity, granted: Boolean) {
        if (!granted || !activity.intent.getBooleanExtra(EXTRA_REVEAL_OVERLAY, false)) return
        if (!OverlayChromeRuntime.isAttached()) return
        OverlayChromeRuntime.dispatch(OverlayUserAction.ASK_CYCLONE)
        activity.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
