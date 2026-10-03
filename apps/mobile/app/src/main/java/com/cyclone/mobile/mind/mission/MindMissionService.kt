package com.cyclone.mobile.mind.mission

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import com.cyclone.mobile.runtime.background.TaskProgressNotification
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps a running mission alive: a visible foreground service for its whole length and a wake lock bounded by the
 * mission's working time, so a model call in flight finishes when the screen goes off and Android does not reclaim
 * the process mid-sprint. It owns no logic; the mission thread in [MindMissions] does the work and stops the service.
 */
class MindMissionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val taskId = intent?.getStringExtra(EXTRA_TASK)
        val task = WorkspaceTasks.state.value?.takeIf { it.taskId == taskId }
        if (task == null || !MindMissions.isLive()) {
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cyclone tasks", NotificationManager.IMPORTANCE_DEFAULT))
        val notification = TaskProgressNotification.build(this, CHANNEL, task)
        runCatching { startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
            .onFailure { stopSelf(); return START_NOT_STICKY }
        val holdMs = intent!!.getLongExtra(EXTRA_HOLD_MS, DEFAULT_HOLD_MS).coerceIn(60_000L, MAX_HOLD_MS)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Cyclone:mission")
                .apply { setReferenceCounted(false); acquire(holdMs) }
        }
        scope.launch {
            WorkspaceTasks.state.collect { state ->
                if (state != null && state.taskId == taskId) {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, TaskProgressNotification.build(this@MindMissionService, CHANNEL, state))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        // Same id and channel as the foreground task notification, so the owner sees one card, not two.
        const val NOTIFICATION_ID = 903
        const val CHANNEL = "cyclone-agent-task"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_HOLD_MS = "holdMs"
        private const val DEFAULT_HOLD_MS = 35 * 60_000L
        private const val MAX_HOLD_MS = 3 * 60 * 60_000L

        fun start(context: Context, taskId: String, holdMs: Long): Boolean = runCatching {
            context.startForegroundService(Intent(context, MindMissionService::class.java)
                .putExtra(EXTRA_TASK, taskId).putExtra(EXTRA_HOLD_MS, holdMs))
        }.isSuccess

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, MindMissionService::class.java)) }
        }
    }
}
