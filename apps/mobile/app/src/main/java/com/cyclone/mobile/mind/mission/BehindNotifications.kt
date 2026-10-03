package com.cyclone.mobile.mind.mission

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.cyclone.mobile.R
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskCommands

/**
 * One quiet notification per task working behind the front one (plan 26 §6): what it does, and Stop. When it needs
 * the owner (a question, an approval), the notification says so and offers Approve / Decline through Task Kit, like
 * the front task's. Never the owner's content beyond the task's own goal and status line.
 */
object BehindNotifications {
    private const val CHANNEL = "cyclone-behind-tasks"
    private const val BASE_ID = 960
    private const val SLOTS = 30

    fun id(taskId: String): Int = BASE_ID + Math.floorMod(taskId.hashCode(), SLOTS)

    fun post(context: Context, task: WorkspaceTaskUi, request: OwnerRequest?) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Cyclone tasks behind your screen", NotificationManager.IMPORTANCE_LOW))
            val needsYou = request != null || task.phase == TaskPhase.REVIEW
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_cyclone_status)
                .setContentTitle(if (needsYou) "Behind your screen · needs you" else "Behind your screen")
                .setContentText(task.message.ifBlank { task.goal }.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText("${task.goal.take(160)}\n${(request?.text ?: task.message).take(300)}"))
                .setOnlyAlertOnce(true)
                .setOngoing(task.phase == TaskPhase.WORKING || task.phase == TaskPhase.REVIEW || task.phase == TaskPhase.HUMAN)
                .setSilent(!needsYou)
                .setPriority(if (needsYou) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            if (request?.kind == OwnerRequestKind.APPROVAL) {
                builder.addAction(0, TaskCommand.Approve.label, TaskCommands.pendingIntent(context, task, TaskCommand.Approve))
                builder.addAction(0, TaskCommand.Decline.label, TaskCommands.pendingIntent(context, task, TaskCommand.Decline))
            }
            builder.addAction(0, TaskCommand.Stop.label, TaskCommands.pendingIntent(context, task, TaskCommand.Stop))
            manager.notify(id(task.taskId), builder.build())
        }
    }

    fun cancel(context: Context, taskId: String) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(id(taskId)) }
    }
}
