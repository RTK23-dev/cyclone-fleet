package com.cyclone.mobile.runtime.background

import com.cyclone.mobile.ui.overlay.TaskAttachment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class WorkspaceDestinationHint(val label: String, val androidUserId: Int)

/** Android inventory is independent of registered app jobs. No synthetic workspace is created. */
internal object ProfileDestinationPresentation {
    fun from(currentUser: Int, visibleUsers: List<Int>): List<WorkspaceDestinationHint> =
        listOf(WorkspaceDestinationHint("Profile A", currentUser)) + visibleUsers.distinct()
            .filter { it >= 0 && it != currentUser }.sorted().mapIndexed { index, user ->
                WorkspaceDestinationHint("Profile ${('B'.code + index).toChar()}", user)
            }
}


data class PendingWorkspaceRequest(
    val id: String,
    val goal: String,
    val attachment: TaskAttachment?,
    val targetPackageName: String? = null,
    val targetAppLabel: String? = null,
    val preferredDestination: WorkspaceDestinationHint? = null,
    val blockedReason: String? = null,
)

internal object WorkspaceQueuePromotionPolicy {
    fun canStart(currentPhase: TaskPhase?, foregroundTaskOwnsSlot: Boolean): Boolean =
        canPromote(currentPhase) && !foregroundTaskOwnsSlot

    fun canPromote(currentPhase: TaskPhase?): Boolean =
        currentPhase == null || currentPhase in setOf(TaskPhase.STOPPED, TaskPhase.FAILED, TaskPhase.DONE)
}

/** In-memory FIFO awaiting a safe execution slot; it never creates a second runner. */
class WorkspaceRequestQueue(private val capacity: Int = 8) {
    private val mutable = MutableStateFlow<List<PendingWorkspaceRequest>>(emptyList())
    val state = mutable.asStateFlow()

    @Synchronized
    fun add(
        goal: String,
        targetPackageName: String? = null,
        targetAppLabel: String? = null,
        attachment: () -> TaskAttachment? = { null },
    ): PendingWorkspaceRequest {
        require(goal.isNotBlank() && goal.length <= 2000) { "Describe a task in 2,000 characters or fewer." }
        // Capacity is checked before taking the attachment so a rejected request never drops it.
        check(mutable.value.size < capacity) { "Up next is full. Stop a queued task first." }
        val request = PendingWorkspaceRequest(
            id = UUID.randomUUID().toString(),
            goal = goal.trim(),
            attachment = attachment(),
            targetPackageName = targetPackageName?.takeIf(String::isNotBlank),
            targetAppLabel = targetAppLabel?.takeIf(String::isNotBlank),
        )
        mutable.value = mutable.value + request
        return request
    }

    @Synchronized fun find(id: String) = mutable.value.firstOrNull { it.id == id }
    @Synchronized fun peek() = mutable.value.firstOrNull()

    @Synchronized
    fun promotableHead(currentPhase: TaskPhase?): PendingWorkspaceRequest? =
        if (WorkspaceQueuePromotionPolicy.canPromote(currentPhase)) mutable.value.firstOrNull() else null

    @Synchronized
    fun bindTarget(id: String, packageName: String, appLabel: String): PendingWorkspaceRequest? {
        var changed: PendingWorkspaceRequest? = null
        mutable.value = mutable.value.map { request ->
            if (request.id == id) request.copy(targetPackageName = packageName, targetAppLabel = appLabel)
                .also { changed = it }
            else request
        }
        return changed
    }

    @Synchronized
    fun steer(id: String, destination: WorkspaceDestinationHint): PendingWorkspaceRequest? {
        var changed: PendingWorkspaceRequest? = null
        mutable.value = mutable.value.map { request ->
            if (request.id == id) request.copy(preferredDestination = destination, blockedReason = null).also { changed = it }
            else request
        }
        return changed
    }

    @Synchronized
    fun blocked(id: String, reason: String) {
        mutable.value = mutable.value.map { if (it.id == id) it.copy(blockedReason = reason.take(240)) else it }
    }

    @Synchronized
    fun remove(id: String) {
        mutable.value = mutable.value.filterNot { it.id == id }
    }
}
