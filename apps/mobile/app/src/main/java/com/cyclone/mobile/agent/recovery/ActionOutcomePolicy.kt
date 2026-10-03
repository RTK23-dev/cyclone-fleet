package com.cyclone.mobile.agent.recovery

import com.cyclone.mobile.PhoneToolErrorCode
import com.cyclone.mobile.agent.CycloneTaskClassification
import com.cyclone.mobile.agent.contract.AgentFailureClass
import com.cyclone.mobile.ai.ProviderFailure
import com.cyclone.mobile.ai.ProviderFailureClass

/**
 * One place decides whether a typed action failure is terminal.
 * Generic capability/scope misses are recovery incidents, not HARD_BLOCKER.
 * OpenRouter/provider misses pause or retry; they are not Android navigation evidence.
 */
object ActionOutcomePolicy {
    fun hardBlocker(errorClass: AgentFailureClass, safeMessage: String?): Boolean {
        if (errorClass == AgentFailureClass.POLICY_DENIED) return true
        return errorClass == AgentFailureClass.CAPABILITY_UNAVAILABLE &&
            safeMessage?.contains("not exposed", ignoreCase = true) == true
    }

    fun shouldCaptureAfter(code: PhoneToolErrorCode?): Boolean {
        if (code == null) return true
        return code != PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED &&
            code != PhoneToolErrorCode.HUMAN_HAS_CONTROL
    }

    /**
     * Provider timeouts and empty routes used to be HARD_BLOCKER, which stopped the
     * task even after a correct local launch. Retryable network/deadline misses
     * recover; missing models and auth ask the person to choose another route.
     */
    fun providerBoundary(reason: String?): CycloneTaskClassification? {
        val code = reason.orEmpty()
        if (code == "provider.cancelled") return CycloneTaskClassification.CANCELLED
        if (code.startsWith("provider.")) return CycloneTaskClassification.PROVIDER_RETRY_LATER
        if (ProviderFailure.message(code) == null) return null
        val failureClass = runCatching { ProviderFailureClass.valueOf(code) }.getOrNull()
        return when (failureClass) {
            ProviderFailureClass.RATE_LIMITED,
            ProviderFailureClass.NETWORK_FAILURE,
            -> CycloneTaskClassification.PROVIDER_RETRY_LATER
            else -> CycloneTaskClassification.HUMAN_OR_GATE
        }
    }
}
