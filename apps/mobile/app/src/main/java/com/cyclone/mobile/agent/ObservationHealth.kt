package com.cyclone.mobile.agent

import com.cyclone.mobile.agent.contract.AgentFailure
import com.cyclone.mobile.agent.contract.AgentFailureClass
import org.json.JSONObject

enum class ObservationState { HEALTHY, EMPTY_VALID, TIMEOUT, PERMISSION_REQUIRED, DISCONNECTED, SCOPE_MISMATCH, TARGET_NOT_VISIBLE, CAPTURE_CHANGED, UNAVAILABLE }
data class ObservationHealth(
    val state: ObservationState,
    val sessionId: String,
    val displayId: Int,
    val attempts: Int = 0,
    val lastSuccessMs: Long? = null,
    val cooldownUntilMs: Long = 0,
) {
    val terminal get() = state in setOf(ObservationState.PERMISSION_REQUIRED, ObservationState.DISCONNECTED, ObservationState.SCOPE_MISMATCH, ObservationState.TARGET_NOT_VISIBLE) ||
        attempts >= (if (state == ObservationState.CAPTURE_CHANGED) 5 else 2)
    val reason get() = "observation.${state.name.lowercase()}"
    val message get() = when (state) {
        ObservationState.HEALTHY -> "Semantic observation is current."
        ObservationState.EMPTY_VALID -> "The current screen has no semantic controls."
        ObservationState.TIMEOUT -> "Semantic screen capture timed out. Check the target app and Accessibility service."
        ObservationState.PERMISSION_REQUIRED -> "Accessibility permission or service is unavailable. Restore it before continuing."
        ObservationState.DISCONNECTED -> "The device or workspace bridge is disconnected. Reconnect it before continuing."
        ObservationState.SCOPE_MISMATCH -> "The requested session or display is unavailable or changed. Reopen this task's workspace."
        ObservationState.TARGET_NOT_VISIBLE -> "The target app is not observable on this task's display. Restore it in this workspace before continuing."
        ObservationState.UNAVAILABLE -> "No current semantic observation is available after bounded recovery."
        ObservationState.CAPTURE_CHANGED -> "The screen or task scope changed during capture. A fresh same-scope observation is required."
    }
    fun toJson() = JSONObject().put("backend", "cyclone_accessibility").put("state", state.name)
        .put("sessionId", sessionId).put("displayId", displayId).put("attempts", attempts)
        .put("lastSuccessMs", lastSuccessMs ?: JSONObject.NULL).put("cooldownUntilMonotonicMs", cooldownUntilMs)
        .put("nextRecovery", if (terminal) "user_or_lifecycle_change" else "same_scope_capture_after_cooldown")
    companion object {
        fun failure(failure: AgentFailure?, session: String, display: Int, attempts: Int, lastSuccess: Long?, now: Long): ObservationHealth {
            val state = if (failure?.reasonCode == "OBSERVATION_CHANGED_DURING_CAPTURE") ObservationState.CAPTURE_CHANGED
                else if (failure?.reasonCode == "FOREGROUND_REQUIRED") ObservationState.TARGET_NOT_VISIBLE else when (failure?.errorClass) {
                AgentFailureClass.TIMEOUT -> ObservationState.TIMEOUT
                AgentFailureClass.ACCESSIBILITY_UNAVAILABLE, AgentFailureClass.AUTH_REQUIRED -> ObservationState.PERMISSION_REQUIRED
                AgentFailureClass.DEVICE_DISCONNECTED -> ObservationState.DISCONNECTED
                AgentFailureClass.STALE_OBSERVATION, AgentFailureClass.INVALID_REQUEST -> ObservationState.SCOPE_MISMATCH
                else -> ObservationState.UNAVAILABLE
            }
            return ObservationHealth(state, session, display, attempts, lastSuccess, now + 500)
        }
    }
}
