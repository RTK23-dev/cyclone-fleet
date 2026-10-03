package com.cyclone.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTaskCircuitBreakerTest {
    @Test
    fun retryableFailureOpensOnlyThatTaskRoute() {
        val breaker = ProviderTaskCircuitBreaker()
        val route = ProviderTaskCircuitBreaker.routeKey("model/a", "price")
        val other = ProviderTaskCircuitBreaker.routeKey("model/b", "price")
        val failure = ProviderFailure.classify(429, """{"error":{"message":"rate limited"}}""", "model/a")

        assertTrue(breaker.record(route, failure))
        assertTrue(breaker.isOpen(route))
        assertFalse(breaker.isOpen(other))
        assertEquals("RATE_LIMITED", breaker.openedBy(route))
    }

    @Test
    fun nonRetryableAuthFailureDoesNotOpenCircuit() {
        val breaker = ProviderTaskCircuitBreaker()
        val route = ProviderTaskCircuitBreaker.routeKey("model/a", "price")
        val failure = ProviderFailure.classify(401, """{"error":{"message":"bad key"}}""", "model/a")

        assertFalse(breaker.record(route, failure))
        assertFalse(breaker.isOpen(route))
    }
}
