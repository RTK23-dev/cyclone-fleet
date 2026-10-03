package com.cyclone.mobile.owner

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.runtime.background.WorkspaceTasks
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeState

/** The live Owner Moment from the app's actual state: the current task, the Mind inbox and the overlay approval card. */
object OwnerMomentsRuntime {
    fun current(): OwnerMoment? = of(WorkspaceTasks.state.value)

    /** The live task, read-only, for surfaces that only watch (Drive's voice). */
    val task: StateFlow<com.cyclone.mobile.runtime.background.WorkspaceTaskUi?> get() = WorkspaceTasks.state

    /** The live moment as a flow: it changes whenever the task, the Mind inbox or the overlay approval card does. */
    fun moments(): Flow<OwnerMoment?> = combine(WorkspaceTasks.state, MindMissions.inbox.pending, OverlayChromeRuntime.activity) { task, request, overlay ->
        OwnerMoments.project(task, request, overlay == OverlayChromeState.GATE && OverlayChromeRuntime.gateWait() == OverlayChromeRuntime.GateWait.PENDING)
    }.distinctUntilChanged()

    fun of(task: com.cyclone.mobile.runtime.background.WorkspaceTaskUi?): OwnerMoment? = OwnerMoments.project(
        task,
        MindMissions.inbox.pending.value,
        OverlayChromeRuntime.gateWait() == OverlayChromeRuntime.GateWait.PENDING,
    )
}

/** Recomposes whenever any of the moment's sources change. */
@Composable
fun rememberOwnerMoment(): OwnerMoment? {
    val task by WorkspaceTasks.state.collectAsState()
    val request by MindMissions.inbox.pending.collectAsState()
    val overlay by OverlayChromeRuntime.activity.collectAsState()
    return remember(task, request, overlay) {
        OwnerMoments.project(task, request, overlay == OverlayChromeState.GATE &&
            OverlayChromeRuntime.gateWait() == OverlayChromeRuntime.GateWait.PENDING)
    }
}
