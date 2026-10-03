package com.cyclone.mobile.fastpath

/** Fresh IDs and fingerprints must describe the same execution surface at dispatch. */
object MutationGrounding {
    fun matches(requestedId: String, currentId: String?, observedFingerprint: String?, liveFingerprint: String?,
        expectedSession: String, expectedDisplay: Int, actualSession: String?, actualDisplay: Int?): Boolean =
        requestedId.isNotBlank() && requestedId == currentId &&
            expectedSession == actualSession && expectedDisplay == actualDisplay &&
            !observedFingerprint.isNullOrBlank() && observedFingerprint == liveFingerprint

    fun verifiedTransition(executed: Boolean, changed: Boolean?, expectationVerified: Boolean = false): Boolean =
        executed && (expectationVerified || changed == true)

    /**
     * Planner landings (open_app / launch_intent / home / wait) change the foreground
     * package. Requiring the pre-launch observation ID turns launcher churn into
     * STALE_OBSERVATION and skips the launch. UI tools still need a fresh target.
     */
    fun requiredFor(tool: String): Boolean =
        FastPathSurface.roleForPhoneTool(tool) == FastPathToolRole.UI
}
