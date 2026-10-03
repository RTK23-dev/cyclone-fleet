package com.cyclone.mobile.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import com.cyclone.mobile.runtime.session.ExecutionBackendKind
import com.cyclone.mobile.ai.vision.live.FrameSourceType
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.runtime.session.ExecutionSession

/** User-selected content is read-only context; only explicit whole-display consent supplies control frames. */
class LiveCaptureService : Service() {
    private val thread = HandlerThread("cyclone-live-capture")
    private lateinit var handler: Handler
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var revision = 0L
    private var generation = 0L
    private var ownsSession = false
    private var sessionId = CONTEXT_SESSION
    private var lastFrameAt = 0L
    private var startedAt = 0L
    private var lastFingerprint: Long? = null
    @Volatile private var closing = false
    private val watchdog = object : Runnable {
        override fun run() {
            if (closing) return
            val now = SystemClock.uptimeMillis()
            if (lastFrameAt == 0L && now - startedAt > 10_000) {
                fail("Screen sharing did not receive a frame. Please share again.")
                return
            }
            // Android may deliver no buffers while content is static. Do not revoke consent for
            // an undamaged screen, and do not manufacture freshness by re-timestamping old pixels.
            if (lastFrameAt > 0 && now - lastFrameAt > 750) {
                LiveCaptureSessionManager.transition(generation, ScreenSharePhase.STARTING, "Waiting for screen update")
            }
            handler.postDelayed(this, 500)
        }
    }
    private fun fail(message: String) {
        LiveCaptureSessionManager.transition(generation, ScreenSharePhase.ERROR, message)
        invalidateSource(sessionId)
        stopSelf()
    }
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (closing) return
            invalidateSource(sessionId)
            LiveCaptureSessionManager.transition(generation, ScreenSharePhase.REVOKED, "Screen sharing ended by Android.")
            stopSelf()
        }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (!isVisible && !closing) fail("Shared content is no longer visible. Please share again.")
        }
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (!closing && width > 0 && height > 0 && display != null) {
                // Old dimensions must never survive rotation or selected-app resizing.
                revision = LiveVisionRuntime.startSource(sessionId, 0, FrameSourceType.MEDIA_PROJECTION)
                LiveCaptureSessionManager.transition(generation, ScreenSharePhase.STARTING)
                startedAt = SystemClock.uptimeMillis()
                attachReader(width, height)
                display?.resize(width, height, resources.configuration.densityDpi)
                display?.surface = reader?.surface
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread.start()
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            LiveCaptureSessionManager.transition(generation, ScreenSharePhase.STOPPING)
            invalidateSource(sessionId)
            stopSelf(); return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        generation = intent?.getLongExtra("generation", 0) ?: 0
        if (generation != LiveCaptureSessionManager.state.value.generation ||
            LiveCaptureSessionManager.state.value.phase != ScreenSharePhase.STARTING) {
            stopSelf(); return START_NOT_STICKY
        }
        ownsSession = LiveCaptureSessionManager.claimService(generation)
        if (!ownsSession) { stopSelf(); return START_NOT_STICKY }
        sessionId = if (intent?.getBooleanExtra("wholeDisplay", false) == true) SESSION else CONTEXT_SESSION
        val consent = intent?.getParcelableExtra("consent", Intent::class.java)
        if (consent == null || intent.getIntExtra("resultCode", 0) != android.app.Activity.RESULT_OK) {
            fail("Screen sharing permission is missing."); return START_NOT_STICKY
        }
        try {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Live phone view", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, LiveCaptureService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(901, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Cyclone live view is on")
            .setContentText("Your screen is available to your active Cyclone task.")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(android.app.Activity.RESULT_OK, consent)
            projection!!.registerCallback(callback, handler)
            val size = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            if (sessionId == CONTEXT_SESSION && LiveVisionRuntime.sessions.snapshot().none { it.sessionId == sessionId }) {
                LiveVisionRuntime.sessions.registerSynthetic(sessionId, 0, ExecutionBackendKind.VIRTUAL_DISPLAY)
            }
            revision = LiveVisionRuntime.startSource(sessionId, 0, FrameSourceType.MEDIA_PROJECTION)
            attachReader(size.width(), size.height())
            display = projection!!.createVirtualDisplay("Cyclone physical live view", size.width(), size.height(),
                resources.configuration.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler)
            startedAt = SystemClock.uptimeMillis()
            handler.postDelayed(watchdog, 1_000)
            // Whole-screen sharing also serves linked PCs over Wi-Fi (view-only, AnyDesk-style); it stops with this service.
            if (sessionId == SESSION) runCatching { com.cyclone.mobile.share.LanShareRuntime.start(applicationContext) }
        } catch (_: RuntimeException) { fail("Could not start screen sharing. Please try again.") }
        return START_NOT_STICKY
    }

    private fun attachReader(width: Int, height: Int) {
        reader?.close()
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { images ->
            images.setOnImageAvailableListener({ source ->
                runCatching {
                    if (source !== reader || closing) return@runCatching
                    source.acquireLatestImage()?.use { image ->
                        if (closing || LiveCaptureSessionManager.state.value.generation != generation ||
                            LiveCaptureSessionManager.state.value.phase !in setOf(ScreenSharePhase.STARTING, ScreenSharePhase.LIVE) || !sampler.admit(SystemClock.uptimeMillis())) return@use
                        val plane = image.planes.first()
                        // A small sparse fingerprint changes cadence, never replaces freshness checks.
                        val buffer = plane.buffer
                        var fingerprint = 1L
                        for (y in 0 until image.height step maxOf(1, image.height / 16)) {
                            for (x in 0 until image.width step maxOf(1, image.width / 16)) {
                                val index = y * plane.rowStride + x * plane.pixelStride
                                if (index + 3 < buffer.limit()) fingerprint = fingerprint * 31 + buffer.getInt(index)
                            }
                        }
                        sampler.contentChanged(lastFingerprint != fingerprint)
                        lastFingerprint = fingerprint
                        val paddedWidth = plane.rowStride / plane.pixelStride
                        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                        val cropped = try {
                            padded.copyPixelsFromBuffer(plane.buffer)
                            Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                        } catch (error: Throwable) { padded.recycle(); throw error }
                        if (cropped !== padded) padded.recycle()
                        if (LiveVisionRuntime.publish(sessionId, 0, FrameSourceType.MEDIA_PROJECTION, revision,
                            image.timestamp / 1_000_000, cropped)) {
                            lastFrameAt = SystemClock.uptimeMillis()
                            LiveCaptureSessionManager.transition(generation, ScreenSharePhase.LIVE)
                        }
                    }
                }.onFailure { if (!closing) fail("Could not read shared content. Please share again.") }
            }, handler)
        }
    }

    override fun onDestroy() {
        closing = true
        runCatching { com.cyclone.mobile.share.LanShareRuntime.stop() }
        if (ownsSession) invalidateSource(sessionId)
        handler.removeCallbacks(watchdog)
        // Serialize resource disposal after the last image callback.
        handler.post {
            runCatching { projection?.unregisterCallback(callback) }
            runCatching { display?.release() }
            runCatching { reader?.close() }
            runCatching { projection?.stop() }
            if (ownsSession) {
                if (sessionId == CONTEXT_SESSION) LiveVisionRuntime.sessions.remove(sessionId)
                LiveCaptureSessionManager.transition(generation, ScreenSharePhase.OFF)
                LiveCaptureSessionManager.releaseService(generation)
            }
            thread.quitSafely()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private fun invalidateSource(sessionId: String) {
            if (LiveVisionRuntime.sessions.snapshot().any { it.sessionId == sessionId })
                LiveVisionRuntime.stopSource(sessionId)
        }
        val sampler = CaptureFrameSampler()
        const val CONTEXT_SESSION = "shared-context"
        fun stop(context: android.content.Context) {
            val state = LiveCaptureSessionManager.state.value
            LiveCaptureSessionManager.transition(state.generation, ScreenSharePhase.STOPPING)
            invalidateSource(if (state.scope == CaptureScope.WHOLE_DISPLAY) SESSION else CONTEXT_SESSION)
            if (!context.stopService(Intent(context, LiveCaptureService::class.java))) {
                LiveCaptureSessionManager.transition(state.generation, ScreenSharePhase.OFF)
            }
        }
        private const val CHANNEL = "cyclone-live-capture"
        private const val STOP = "com.cyclone.mobile.capture.STOP"
        private const val SESSION = ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID
    }
}
