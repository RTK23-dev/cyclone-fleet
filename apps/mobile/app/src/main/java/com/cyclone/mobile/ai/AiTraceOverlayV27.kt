package com.cyclone.mobile.ai

import com.cyclone.mobile.CycloneAccessibilityService

/** Compatibility facade. Trace history stays in AgentTraceStore; it never creates a window.
 * The shared Ask Cyclone card is the sole consumer task presentation.
 */
@Suppress("UNUSED_PARAMETER")
object AiTraceOverlayV27Runtime {
    fun startTask(service: CycloneAccessibilityService, sessionId: String) = Unit
    fun finishTask(sessionId: String, ok: Boolean, message: String) = Unit
    fun compilationComplete(sessionId: String, summary: String) = Unit
    fun disableImmediately() = Unit
    fun isVisible(): Boolean = false
}
