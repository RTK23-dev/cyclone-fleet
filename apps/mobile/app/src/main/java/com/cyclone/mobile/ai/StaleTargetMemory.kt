package com.cyclone.mobile.ai

import com.cyclone.mobile.agent.contract.AgentPageCard

/**
 * Bounded stale-target quarantine keyed by semantic target evidence rather than observation UUID.
 * A dynamic page may legitimately invalidate a target once; two independent fresh captures rejecting
 * the same logical control require a different grounding strategy instead of a third blind dispatch.
 */
internal class StaleTargetMemory(
    private val maxRejectionsPerLogicalTarget: Int = 2,
) {
    private val rejections = mutableMapOf<String, Int>()

    init {
        require(maxRejectionsPerLogicalTarget > 0)
    }

    fun key(action: PageAgentAction, page: AgentPageCard?): String {
        val target = action.controlId?.let { id -> page?.controls?.firstOrNull { it.elementId == id } }
        if (target != null) {
            val resource = target.evidence.optString("resourceId").substringAfterLast('/')
            return listOf(
                action.tool,
                normalize(target.label),
                normalize(target.semanticName),
                target.role.lowercase(),
                resource.lowercase(),
            ).joinToString("|")
        }
        return PageAgentProtocol.actionSignature(
            PageAgentDecision("act", "", "", listOf(action.copy(visualGrounded = false)), null, null),
            "",
        ).orEmpty()
    }

    fun mayDispatch(key: String): Boolean = (rejections[key] ?: 0) < maxRejectionsPerLogicalTarget

    fun recordRejected(key: String) {
        if (key.isNotBlank()) rejections[key] = (rejections[key] ?: 0) + 1
    }

    fun markVerifiedProgress() {
        rejections.clear()
    }

    fun resetAfterHandoff() {
        rejections.clear()
    }

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")
}
