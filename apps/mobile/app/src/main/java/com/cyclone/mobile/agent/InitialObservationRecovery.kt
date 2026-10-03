package com.cyclone.mobile.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One bridge, one scope, at most two observations; deterministic blockers never retry. */
internal object InitialObservationRecovery {
    suspend fun <T> capture(observe: () -> T?, health: () -> ObservationHealth,
        wait: suspend (Long) -> Unit): T? {
        currentCoroutineContext().ensureActive()
        val first = observe()
        currentCoroutineContext().ensureActive()
        if (first != null || health().terminal) return first
        wait(550L)
        currentCoroutineContext().ensureActive()
        return observe().also { currentCoroutineContext().ensureActive() }
    }
}
