package com.cyclone.mobile.runtime.background

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.LruCache
import com.cyclone.mobile.R

/** Native task card shared by display-0 and workspace execution. System UI owns its shape. */
internal object TaskProgressNotification {
    // Public Notification.EXTRA_REQUEST_PROMOTED_ONGOING wire key (API 36.1).
    // A Bundle keeps this request compatible with our API 35 compile baseline and older devices.
    private const val REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    private val appIcons = LruCache<String, Icon>(8)

    fun build(context: Context, channel: String, task: WorkspaceTaskUi): Notification {
        val progress = PendingIntent.getActivity(context, 0, WorkspaceTasks.progressIntent(context, task),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = TaskNotificationProjection.body(task)
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_cyclone_status)
            .setColor(Color.rgb(141, 227, 210))
            .setContentTitle(TaskNotificationProjection.title(task))
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(progress)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_cyclone_status)
                .setContentTitle("Cyclone task")
                .setContentText("Unlock to view task progress").build())
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(task.working)
            .setAutoCancel(!task.working)
            .addExtras(Bundle().apply { putBoolean(REQUEST_PROMOTED_ONGOING, task.working) })
        // The app the task works in (plan 27: never Cyclone itself); the small status-bar icon stays Cyclone's, as Android requires.
        com.cyclone.mobile.ui.overlay.WorkingApp.forTask(task.taskId, task.packageName)?.let { appIcon(context, it) }?.let { builder.setLargeIcon(it) }
        if (task.working) {
            val percent = TaskNotificationProjection.progressPercent(task)
            // Plan 27: on Android 16 the Live Update shows the task's real steps as segments.
            val segmented = android.os.Build.VERSION.SDK_INT >= 36 && runCatching {
                val snapshot = TaskPresentationProjector.project(task)
                val total = snapshot.totalCount?.takeIf { it in 1..20 }
                val style = Notification.ProgressStyle().setStyledByProgress(true)
                if (total == null) style.setProgressIndeterminate(true)
                else style.setProgressSegments(List(total) { Notification.ProgressStyle.Segment(1) })
                    .setProgress(snapshot.completedCount.coerceIn(0, total))
                builder.setStyle(style)
            }.isSuccess
            if (!segmented) builder.setProgress(100, percent ?: 0, percent == null)
        }
        // A task that needs its owner shows its Owner Moment: the same buttons as the card, answered from the shade.
        // Otherwise preserve every available interruption/confirmation command. The card itself always opens
        // exact-task details; running tasks have Stop task followed by the explicit View progress.
        val moment = runCatching { com.cyclone.mobile.owner.OwnerMomentsRuntime.of(task) }.getOrNull()
        val count = if (moment != null) {
            builder.setContentTitle(moment.title).setContentText(moment.text).setStyle(Notification.BigTextStyle().bigText(moment.text))
            val actions = com.cyclone.mobile.owner.OwnerMoments.notificationActions(moment)
            actions.forEach { action ->
                val intent = com.cyclone.mobile.task.TaskCommands.pendingIntent(context, task, action.command, reply = action.reply)
                builder.addAction(Notification.Action.Builder(null, action.label, intent).apply {
                    if (action.reply) addRemoteInput(android.app.RemoteInput.Builder(com.cyclone.mobile.task.TaskCommands.EXTRA_REPLY)
                        .setLabel("Your answer").build())
                    // Approving, confirming or answering from a locked phone would let anyone holding it act for the owner.
                    if (com.cyclone.mobile.owner.OwnerMoments.needsUnlock(action)) setAuthenticationRequired(true)
                }.build())
            }
            actions.size
        } else {
            val plane = com.cyclone.mobile.runtime.plane.MissionPlanes.ui.value
                ?.takeIf { com.cyclone.mobile.task.TaskEngines.MIND_TASK_PREFIX + it.missionId == task.taskId }
            val projected = TaskNotificationProjection.actions(task, plane?.kind?.wire, plane?.available == true, waiting = plane?.waitingFor != null)
                .let { if (showOverlay(task)) it.take(2) else it }
            // Plan 27: Show first, so the owner can bring the overlay back after folding it to the notification.
            if (showOverlay(task)) {
                val show = PendingIntent.getBroadcast(context, task.taskId.hashCode(),
                    android.content.Intent(com.cyclone.mobile.ui.overlay.OverlayShowReceiver.ACTION).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(Notification.Action.Builder(null, "Show", show).build())
            }
            projected.forEach { (command, label) ->
                val parsed = com.cyclone.mobile.task.TaskCommand.parse(command, task.confirmation?.token) ?: return@forEach
                val action = com.cyclone.mobile.task.TaskCommands.pendingIntent(context, task, parsed)
                builder.addAction(Notification.Action.Builder(null, label, action).build())
            }
            projected.size + if (showOverlay(task)) 1 else 0
        }
        if (count < 3) {
            builder.addAction(Notification.Action.Builder(null, "View progress", progress).build())
        }
        return builder.build()
    }

    /** The overlay can come back for this running task (it is attached and Cyclone runs the task on the phone). */
    private fun showOverlay(task: WorkspaceTaskUi): Boolean =
        task.working && runCatching { com.cyclone.mobile.ui.overlay.OverlayChromeRuntime.isAttached() }.getOrDefault(false)

    private fun appIcon(context: Context, packageName: String): Icon? = appIcons.get(packageName) ?: runCatching {
        val drawable = context.packageManager.getApplicationIcon(packageName).mutate()
        val size = (48 * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        Icon.createWithBitmap(bitmap).also { appIcons.put(packageName, it) }
    }.getOrNull()
}
