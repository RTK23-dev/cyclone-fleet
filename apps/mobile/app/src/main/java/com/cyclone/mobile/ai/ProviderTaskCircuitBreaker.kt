package com.cyclone.mobile.ai

/**
 * Task-scoped breaker for provider transport failures after ProviderRequests has already exhausted
 * its bounded retry. It never changes the selected model or authorizes a different provider route.
 */
internal class ProviderTaskCircuitBreaker {
    private val openRoutes = linkedMapOf<String, String>()

    fun isOpen(routeKey: String): Boolean = routeKey in openRoutes

    fun openedBy(routeKey: String): String? = openRoutes[routeKey]

    fun record(routeKey: String, failure: SanitizedProviderFailure): Boolean {
        if (!failure.retryable) return false
        openRoutes[routeKey] = failure.code
        return true
    }

    companion object {
        fun routeKey(modelId: String, providerSort: String): String =
            modelId.trim() + "|" + providerSort.trim().lowercase()
    }
}
