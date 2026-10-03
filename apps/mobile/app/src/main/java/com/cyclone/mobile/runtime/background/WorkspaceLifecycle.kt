package com.cyclone.mobile.runtime.background

import com.cyclone.mobile.runtime.session.InputOwner

enum class WorkspaceState {
    CREATING, BACKGROUND_OK, PAUSED, BACKGROUND_NEEDS_HANDOFF, FOREGROUND_REQUIRED,
    WAITING_FOR_CONFIRMATION, SECURE_CONTENT_UNAVAILABLE, COMPLETED, FAILED, CANCELLED,
}

/** An observation carries this lease. Pause/handoff/loss invalidates already queued work. */
data class WorkspaceLease(val sessionId: String, val displayId: Int, val generation: Long)

class WorkspaceLifecycle(val sessionId: String, val displayId: Int) {
    var generation = 1L; private set
    var owner = InputOwner.CYCLONE; private set
    var state = WorkspaceState.CREATING; private set
    init { require(sessionId.isNotBlank() && displayId > 0) }
    fun lease() = WorkspaceLease(sessionId, displayId, generation)
    fun requireMutation(lease: WorkspaceLease, displayAlive: Boolean, backendAlive: Boolean) {
        check(lease == lease() && displayAlive && backendAlive && owner == InputOwner.CYCLONE && state == WorkspaceState.BACKGROUND_OK) {
            "STALE_SESSION: workspace input authority expired"
        }
    }
    fun transition(next: WorkspaceState) {
        check(state !in setOf(WorkspaceState.COMPLETED, WorkspaceState.CANCELLED, WorkspaceState.FAILED))
        if (next != state) generation++
        state = next
        owner = if (next == WorkspaceState.BACKGROUND_OK) InputOwner.CYCLONE else InputOwner.HUMAN
    }
}
