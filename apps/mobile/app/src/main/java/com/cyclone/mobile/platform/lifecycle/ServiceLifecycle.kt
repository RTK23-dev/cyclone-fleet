package com.cyclone.mobile.platform.lifecycle

enum class ServiceLifecycleState {
    REGISTERED,
    STARTING,
    READY,
    DEGRADED,
    FAILED,
    STOPPED,
}

data class LifecycleTransition(
    val from: ServiceLifecycleState,
    val to: ServiceLifecycleState,
)

object ServiceLifecyclePolicy {
    private val allowed = mapOf(
        ServiceLifecycleState.REGISTERED to setOf(
            ServiceLifecycleState.STARTING,
            ServiceLifecycleState.STOPPED,
        ),
        ServiceLifecycleState.STARTING to setOf(
            ServiceLifecycleState.READY,
            ServiceLifecycleState.DEGRADED,
            ServiceLifecycleState.FAILED,
            ServiceLifecycleState.STOPPED,
        ),
        ServiceLifecycleState.READY to setOf(
            ServiceLifecycleState.DEGRADED,
            ServiceLifecycleState.FAILED,
            ServiceLifecycleState.STOPPED,
        ),
        ServiceLifecycleState.DEGRADED to setOf(
            ServiceLifecycleState.READY,
            ServiceLifecycleState.FAILED,
            ServiceLifecycleState.STOPPED,
        ),
        ServiceLifecycleState.FAILED to setOf(
            ServiceLifecycleState.STARTING,
            ServiceLifecycleState.STOPPED,
        ),
        ServiceLifecycleState.STOPPED to setOf(ServiceLifecycleState.STARTING),
    )

    fun canTransition(from: ServiceLifecycleState, to: ServiceLifecycleState): Boolean =
        to in allowed.getValue(from)

    fun allowedFrom(state: ServiceLifecycleState): Set<ServiceLifecycleState> = allowed.getValue(state)
}
