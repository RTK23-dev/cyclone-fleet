package com.cyclone.mobile.ai

/** A new locator or observation UUID does not authorize repeating an already performed click. */
class ExecutedActionMemory {
    private val unchanged = mutableSetOf<String>()
    fun mayDispatch(action: PageAgentAction, scene: String): Boolean = key(action, scene) !in unchanged
    fun record(action: PageAgentAction, scene: String, androidAccepted: Boolean, verifiedProgress: Boolean) {
        if (verifiedProgress) unchanged.clear()
        else if (androidAccepted && action.tool in setOf("phone.click", "phone.long_press")) unchanged += key(action, scene)
    }

    /** Human takeover can materially change the scene; stale anti-repeat entries must not leak across it. */
    fun resetAfterHandoff() {
        unchanged.clear()
    }
    private fun key(action: PageAgentAction, scene: String): String =
        PageAgentProtocol.actionSignature(PageAgentDecision("act", "", "",
            listOf(action.copy(visualGrounded = false)), null, null), "").orEmpty() + "|" + scene
}
