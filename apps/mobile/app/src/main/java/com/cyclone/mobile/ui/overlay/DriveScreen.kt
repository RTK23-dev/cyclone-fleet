package com.cyclone.mobile.ui.overlay

import android.content.Context
import android.content.Intent
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.runtime.plane.MissionPlanes
import com.cyclone.mobile.runtime.plane.PlaneMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drive's screen rule (plan 24 §4.8): a spoken request runs in the background when the phone can do it, so Maps
 * never leaves the screen. When it cannot, the task borrows the screen and, when it ends, gives it back to the app
 * the owner was in.
 */
object DriveScreen {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watch: Job? = null

    fun begin(context: Context) {
        val app = context.applicationContext
        val ready = MissionPlanes.capability(app).ready && MissionPlanes.blocker(app) == null
        if (ready) {
            MissionPlanes.preferOnce(PlaneMode.BACKGROUND)
            return
        }
        val returnTo = DeviceState.currentPackage?.takeIf { it != app.packageName } ?: return
        watch?.cancel()
        watch = scope.launch {
            // Wait for the task to start, then to end.
            withTimeoutOrNull(START_WAIT_MS) { WorkspaceTasks.state.first { it != null && it.phase !in TERMINAL } } ?: return@launch
            WorkspaceTasks.state.first { it == null || it.phase in TERMINAL }
            delay(RETURN_DELAY_MS)
            if (DeviceState.currentPackage != returnTo) giveBack(app, returnTo)
        }
    }

    private fun giveBack(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
    }

    private const val RETURN_DELAY_MS = 1_200L
    private const val START_WAIT_MS = 30_000L
    private val TERMINAL = setOf(TaskPhase.DONE, TaskPhase.FAILED, TaskPhase.STOPPED)
}
