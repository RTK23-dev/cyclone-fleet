package com.cyclone.mobile.share

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.capture.LiveCaptureConsentActivity
import com.cyclone.mobile.capture.LiveScreenShare
import com.cyclone.mobile.gateway.AndroidGatewayPhoneIdentity
import com.cyclone.mobile.gateway.AndroidGatewayTrustRepository
import com.cyclone.mobile.runtime.session.ExecutionSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Starts the Wi-Fi share server while the owner shares the whole screen, and answers the PC's `share.status` /
 * `share.request`. Sharing always starts on the phone: Android's "Cyclone will start capturing" consent plus the
 * ongoing "Cyclone live view is on" notification with Stop.
 */
object LanShareRuntime {
    private const val MAX_LONG_EDGE = 1280
    private const val JPEG_QUALITY = 70
    private const val CHANNEL = "cyclone_share_request"
    private const val REQUEST_ID = 42_032
    @Volatile private var server: LanShareServer? = null

    @Synchronized
    fun start(context: Context) {
        if (server?.isRunning == true) return
        val app = context.applicationContext
        val identity = AndroidGatewayPhoneIdentity(app)
        val records = AndroidGatewayTrustRepository(app)
        val share = LanShareServer(
            trust = { trustId ->
                records.get(trustId)
                    ?.takeIf { it.revokedAtMs == null && it.phoneId == identity.phoneId }
                    ?.pcPublicKeyBase64
            },
            identity = object : LanShareServer.PhoneSigner {
                override val phoneId: String get() = identity.phoneId
                override fun sign(transcript: String): String = identity.sign(transcript)
            },
            frames = { after -> nextFrame(after) },
        )
        runCatching { share.start() }.onSuccess { server = share }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    fun nextFrame(afterFrameId: Long): LanShareServer.Frame? {
        // A PC is watching: keep capture at the burst cadence (up to 10 fps) instead of the idle 2-3 fps.
        com.cyclone.mobile.capture.LiveCaptureService.sampler.requestBurst(android.os.SystemClock.uptimeMillis())
        val (frameId, bitmap) = LiveVisionRuntime.streamFrame(ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID, afterFrameId, MAX_LONG_EDGE)
            ?: return null
        return try {
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes)
            LanShareServer.Frame(frameId, bytes.toByteArray(), bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    /** `share.status`: whether the screen is being shared and where the PC can reach it on the local network. */
    fun status(context: Context): JSONObject {
        val share = server
        val sharing = share?.isRunning == true
        return JSONObject()
            .put("sharing", sharing)
            .put("port", if (sharing) share!!.port else JSONObject.NULL)
            .put("addresses", JSONArray(if (sharing) localAddresses() else emptyList()))
            .put("phoneId", runCatching { AndroidGatewayPhoneIdentity(context.applicationContext).phoneId }.getOrDefault(""))
            .put("protocol", LanShareProtocol.VERSION)
    }

    /** `share.request`: a PC asks to see the screen. Only a notification; the owner taps it to give consent. */
    fun request(context: Context, pcLabel: String?): JSONObject {
        if (server?.isRunning == true) return JSONObject().put("prompted", false).put("sharing", true)
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Share screen with PC", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A linked PC asks to see this phone's screen."
            },
        )
        val open = PendingIntent.getActivity(
            app,
            REQUEST_ID,
            Intent(app, LiveCaptureConsentActivity::class.java)
                .putExtra(LiveScreenShare.EXTRA_WHOLE_DISPLAY, true)
                .putExtra(LiveScreenShare.EXTRA_REVEAL_OVERLAY, false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val who = pcLabel?.takeIf { it.isNotBlank() }?.take(60) ?: "Your PC"
        manager?.notify(
            REQUEST_ID,
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("$who wants to see your screen")
                .setContentText("Tap to share over Wi-Fi. Stop any time from the notification.")
                .setAutoCancel(true)
                .setTimeoutAfter(120_000)
                .setContentIntent(open)
                .build(),
        )
        return JSONObject().put("prompted", true).put("sharing", false)
    }

    /** IPv4 addresses on up, non-loopback interfaces (Wi-Fi first). */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress } }
            .map { it.hostAddress ?: "" }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)
    }.getOrDefault(emptyList())
}
