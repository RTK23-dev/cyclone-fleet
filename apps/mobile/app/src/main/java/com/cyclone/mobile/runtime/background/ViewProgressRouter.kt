package com.cyclone.mobile.runtime.background

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionIdentityException
import com.cyclone.mobile.runtime.session.SessionPlaneKind
import org.json.JSONObject

/**
 * View progress opens the exact Session Contract plane.
 * Session Kernel VD → owned-display frames. Layer 2 → the display-0 workspace app. Never mix.
 */
sealed class ViewProgressTarget {
    data class VdSessionFrames(val sessionId: String, val displayId: Int) : ViewProgressTarget()
    data class Layer2App(
        val workspaceId: String,
        val workspaceGeneration: Long,
        val packageName: String,
    ) : ViewProgressTarget()
    data class ProgressDetail(val taskId: String) : ViewProgressTarget()
}

object ViewProgressRouter {
    const val EXTRA_PLANE = "plane"
    const val EXTRA_TASK = "task"
    const val EXTRA_SESSION = "session"
    const val EXTRA_DISPLAY_ID = "displayId"
    const val EXTRA_WORKSPACE_ID = "workspaceId"
    const val EXTRA_WORKSPACE_GENERATION = "workspaceGeneration"
    const val EXTRA_PACKAGE = "packageName"
    const val PLANE_VD = "session_kernel_vd"
    const val PLANE_LAYER2 = "layer2_workspace"
    const val PLANE_PENDING = "pending"

    fun target(task: WorkspaceTaskUi): ViewProgressTarget {
        if (task.workspaceId != null || task.workspaceGeneration != null) {
            val plane = SessionContract.classify(task.identityJson())
            if (plane.kind != SessionPlaneKind.LAYER2_WORKSPACE) {
                throw SessionIdentityException(
                    "View progress cannot open Layer 2 as a VD session",
                    SessionContract.PLANE_MISMATCH,
                )
            }
            val workspaceId = task.workspaceId
                ?: throw SessionIdentityException(
                    "workspaceId and workspaceGeneration must be supplied together",
                    SessionContract.WORKSPACE_GENERATION_REQUIRED,
                )
            val generation = task.workspaceGeneration
                ?: throw SessionIdentityException(
                    "workspaceId and workspaceGeneration must be supplied together",
                    SessionContract.WORKSPACE_GENERATION_REQUIRED,
                )
            return ViewProgressTarget.Layer2App(workspaceId, generation, task.packageName)
        }
        val session = task.sessionId
        val display = task.displayId
        if (!session.isNullOrBlank() && session != "default-foreground" && display != null && display > 0) {
            val plane = SessionContract.classify(
                JSONObject().put("sessionId", session).put("displayId", display),
            )
            if (plane.kind != SessionPlaneKind.SESSION_KERNEL_VD) {
                throw SessionIdentityException(
                    "View progress cannot open a named session as Layer 2",
                    SessionContract.PLANE_MISMATCH,
                )
            }
            return ViewProgressTarget.VdSessionFrames(session, display)
        }
        return ViewProgressTarget.ProgressDetail(task.taskId)
    }

    fun showsVdFrames(task: WorkspaceTaskUi): Boolean = target(task) is ViewProgressTarget.VdSessionFrames

    fun showsLayer2App(task: WorkspaceTaskUi): Boolean = target(task) is ViewProgressTarget.Layer2App

    fun intent(context: Context, task: WorkspaceTaskUi): Intent {
        val detail = progressActivity(context, task)
        return when (val destination = target(task)) {
            is ViewProgressTarget.Layer2App -> {
                val launch = context.packageManager.getLaunchIntentForPackage(destination.packageName)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    launch.putExtra(EXTRA_TASK, task.taskId)
                    launch.putExtra(EXTRA_WORKSPACE_ID, destination.workspaceId)
                    launch.putExtra(EXTRA_WORKSPACE_GENERATION, destination.workspaceGeneration)
                    launch.putExtra(EXTRA_PACKAGE, destination.packageName)
                    launch.putExtra(EXTRA_PLANE, PLANE_LAYER2)
                    launch
                } else detail.putExtra(EXTRA_PLANE, PLANE_LAYER2)
            }
            is ViewProgressTarget.VdSessionFrames ->
                detail.putExtra(EXTRA_PLANE, PLANE_VD)
            is ViewProgressTarget.ProgressDetail -> {
                // A normal live task is already in the human window. Open that app, not a log page.
                if (task.working && task.confirmation == null && task.sessionId == "default-foreground" && task.displayId == 0) {
                    context.packageManager.getLaunchIntentForPackage(task.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?: detail.putExtra(EXTRA_PLANE, PLANE_PENDING)
                } else detail.putExtra(EXTRA_PLANE, PLANE_PENDING)
            }
        }
    }

    private fun progressActivity(context: Context, task: WorkspaceTaskUi): Intent =
        Intent(context, WorkspaceProgressActivity::class.java)
            .setData(Uri.parse("cyclone://task/${task.taskId}/progress"))
            .putExtra(EXTRA_TASK, task.taskId)
            .putExtra(EXTRA_SESSION, task.sessionId)
            .putExtra(EXTRA_DISPLAY_ID, task.displayId ?: -1)
            .putExtra(EXTRA_WORKSPACE_ID, task.workspaceId)
            .putExtra(EXTRA_WORKSPACE_GENERATION, task.workspaceGeneration ?: -1L)
            .putExtra(EXTRA_PACKAGE, task.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
