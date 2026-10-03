package com.cyclone.mobile.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import com.cyclone.mobile.ui.overlay.OverlayExternalInteraction

/** Each service session consumes one new OS consent token. Whole-display capture avoids Android 14+ "Share one app" split UI. */
class LiveCaptureConsentActivity : Activity() {
    private var generation = 0L
    private var wholeDisplay = false
    private var consentLaunched = false
    override fun onDestroy() {
        if (!isChangingConfigurations) OverlayExternalInteraction.active.value = false
        super.onDestroy()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayExternalInteraction.active.value = true
        wholeDisplay = intent.getBooleanExtra(LiveScreenShare.EXTRA_WHOLE_DISPLAY, false)
        generation = savedInstanceState?.getLong("generation") ?: (LiveCaptureSessionManager.request(
            if (wholeDisplay) CaptureScope.WHOLE_DISPLAY else CaptureScope.USER_CHOICE) ?: run { finish(); return })
        consentLaunched = savedInstanceState?.getBoolean("consentLaunched", false) ?: false
        if (!consentLaunched) launchConsent()
    }
    private fun launchConsent() {
        runCatching {
            val manager = getSystemService(MediaProjectionManager::class.java)
            consentLaunched = true
            val capture = if (android.os.Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
            startActivityForResult(capture, CONSENT)
        }.onFailure {
            LiveCaptureSessionManager.transition(generation, ScreenSharePhase.ERROR, "Screen sharing is unavailable. Try again.")
            finish()
        }
    }
    private fun cancel() {
        LiveCaptureSessionManager.transition(generation, ScreenSharePhase.OFF)
        finish()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("generation", generation)
        outState.putBoolean("consentLaunched", consentLaunched)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Activity result retained for this isolated consent-only activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CONSENT) return
        val granted = resultCode == RESULT_OK && data != null &&
            LiveCaptureSessionManager.transition(generation, ScreenSharePhase.STARTING)
        if (granted) {
            runCatching {
                startForegroundService(Intent(this, LiveCaptureService::class.java)
                    .putExtra("resultCode", resultCode).putExtra("consent", data)
                    .putExtra("generation", generation).putExtra("wholeDisplay", wholeDisplay))
            }.onFailure { LiveCaptureSessionManager.transition(generation, ScreenSharePhase.ERROR, "Could not start screen sharing.") }
            LiveScreenShare.revealAfterConsent(this, true)
        } else {
            LiveCaptureSessionManager.transition(generation, ScreenSharePhase.OFF)
        }
        finish()
    }
    companion object { private const val CONSENT = 901 }
}
