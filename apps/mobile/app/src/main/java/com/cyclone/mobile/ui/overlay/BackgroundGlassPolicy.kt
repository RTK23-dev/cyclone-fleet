package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi

/**
 * Presentation only: never grants phone input authority.
 *
 * Background glass is a live-status surface, not task history. Terminal results belong in the
 * notification/progress surface so a finished run never keeps a large card floating over Android.
 */
object BackgroundGlassPolicy {
    private val livePhases = setOf(
        TaskPhase.STARTING,
        TaskPhase.WORKING,
        TaskPhase.PAUSED,
        TaskPhase.REVIEW,
        TaskPhase.HUMAN,
    )

    fun visible(task: WorkspaceTaskUi?): Boolean = task?.phase in livePhases

    fun tearDown(task: WorkspaceTaskUi?): Boolean = task?.phase in setOf(
        TaskPhase.DONE,
        TaskPhase.FAILED,
        TaskPhase.STOPPED,
    )
}

/** Every successfully attached window is owned until synchronous removal completes. */
internal class OverlayWindowRegistry<T>(private val remove: (T) -> Unit) {
    private val windows = linkedSetOf<T>()
    val size get() = windows.size
    fun attached(window: T) { windows += window }
    fun remove(window: T) { if (window in windows) { remove.invoke(window); windows -= window } }
    fun clear() { windows.toList().forEach(::remove) }
}

internal object OverlayTeardownContract {
    const val WINDOWS_AFTER_CANCEL = 0
    fun overlayWindowCount(registry: OverlayWindowRegistry<*>): Int = registry.size
    fun cancel(registry: OverlayWindowRegistry<*>) { registry.clear() }
}
