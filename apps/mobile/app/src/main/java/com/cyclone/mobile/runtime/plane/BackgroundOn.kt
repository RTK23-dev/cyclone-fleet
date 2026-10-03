package com.cyclone.mobile.runtime.plane

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.cyclone.mobile.R
import com.cyclone.mobile.runtime.background.BackgroundSetup
import com.cyclone.mobile.runtime.background.BackgroundSetupActivity

/**
 * Plan 26 (A42-1): background work that stays on. The owner turns it on once; after a reboot or when a piece goes
 * missing Cyclone says so once, quietly, with the one tap that fixes it: never in the middle of a task.
 */
object BackgroundWatch {
    private const val CHANNEL = "cyclone_background"
    private const val NOTIFICATION_ID = 42_001
    private const val PREFS = "cyclone_planes"
    private const val TOLD_KEY = "background_told_at"

    /** Check now (boot, app start, a day later) and tell the owner if something they can fix is missing. */
    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val capability = MissionPlanes.capability(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (capability.ready || capability.level == CapabilityLevel.OFF || capability.level == CapabilityLevel.UNSUPPORTED) {
            cancel(app)
            return
        }
        if (!force && !BackgroundCapabilities.shouldNotify(capability, prefs.getLong(TOLD_KEY, 0), now)) return
        prefs.edit().putLong(TOLD_KEY, now).apply()
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Background work", NotificationManager.IMPORTANCE_LOW))
        val action = capability.action ?: CapabilityAction.OPEN_SETUP
        val notification = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_cyclone_status)
            .setContentTitle(if (capability.level == CapabilityLevel.NEEDS_START) "Background work is paused" else "Background work needs a step")
            .setContentText(capability.headline)
            .setStyle(Notification.BigTextStyle().bigText(capability.headline))
            .setContentIntent(fix(app, action))
            .addAction(Notification.Action.Builder(null as Icon?, action.label, fix(app, action)).build())
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }

    /** The one tap: start the helper, or open the guided setup at the missing step. */
    fun fixIntent(context: Context, action: CapabilityAction): Intent = when (action) {
        CapabilityAction.START_HELPER -> context.packageManager.getLaunchIntentForPackage(BackgroundSetup.SHIZUKU_PACKAGE)
            ?: Intent(context, BackgroundSetupActivity::class.java)
        CapabilityAction.OPEN_SETUP, CapabilityAction.TURN_ON -> Intent(context, BackgroundSetupActivity::class.java)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun fix(context: Context, action: CapabilityAction): PendingIntent =
        PendingIntent.getActivity(context, action.ordinal, fixIntent(context, action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** After a reboot or an update: is background work still ready? Not exported; only the system sends these. */
class BackgroundBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        // Shizuku may start itself a little after boot (start on boot); look again after that before telling anyone.
        val pending = goAsync()
        Thread {
            try {
                Thread.sleep(BOOT_GRACE_MS)
                BackgroundWatch.check(context, force = true)
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val BOOT_GRACE_MS = 8_000L
    }
}

/**
 * Quick Settings tile "Cyclone background": on when background work is ready, off when switched off, and a tap on a
 * phone that is not ready opens the one step that fixes it.
 */
class CycloneBackgroundTile : TileService() {
    override fun onStartListening() {
        render()
    }

    override fun onClick() {
        val capability = MissionPlanes.capability(this)
        when {
            capability.level == CapabilityLevel.UNSUPPORTED -> Unit
            capability.ready -> MissionPlanes.setBackgroundOn(this, false)
            capability.level == CapabilityLevel.OFF -> {
                MissionPlanes.setBackgroundOn(this, true)
                val next = MissionPlanes.capability(this)
                if (!next.ready) open(next.action ?: CapabilityAction.OPEN_SETUP)
            }
            else -> open(capability.action ?: CapabilityAction.OPEN_SETUP)
        }
        render()
    }

    // The Intent overload is only reached below Android 14 (minSdk 33), where it is the supported call.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(action: CapabilityAction) {
        val intent = BackgroundWatch.fixIntent(this, action)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val capability = MissionPlanes.capability(this)
        tile.label = "Cyclone background"
        tile.state = when (capability.level) {
            CapabilityLevel.READY -> Tile.STATE_ACTIVE
            CapabilityLevel.UNSUPPORTED -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when (capability.level) {
            CapabilityLevel.READY -> "On"
            CapabilityLevel.OFF -> "Off"
            CapabilityLevel.NEEDS_START -> "Paused"
            CapabilityLevel.NEEDS_SETUP -> "Set up"
            CapabilityLevel.UNSUPPORTED -> "Not on this phone"
        }
        tile.updateTile()
    }
}
