package com.cyclone.mobile.fastpath

data class FastPathPlannedAction(
    val tool: String,
    val expectedPageChange: Boolean,
)

data class FastPathIsolationResult<T>(
    val allowed: List<T>,
    val dropped: List<T>,
    val reason: String?,
) {
    val truncated: Boolean get() = dropped.isNotEmpty()
}

/**
 * One screen-changing mutation per agent decision turn. Same-page form field batching is allowed.
 */
object FastPathNavIsolation {
    val FORM_BATCH_TOOLS = setOf("phone.type", "phone.replace_text")
    val INHERENT_NAV_TOOLS = setOf(
        "phone.back",
        "phone.home",
        "phone.open_app",
        "phone.launch_intent",
    )
    val MAYBE_NAV_TOOLS = setOf(
        "phone.click",
        "phone.long_press",
        "phone.tap",
        "phone.scroll",
        "phone.swipe",
    )
    val SCREEN_CHANGING_TOOLS = INHERENT_NAV_TOOLS + MAYBE_NAV_TOOLS
    const val DROP_REASON =
        "NAV_ISOLATION: one screen-changing mutation per agent decision turn; later mutations were dropped. Form field batching is allowed only before navigation."

    fun isFormBatch(tool: String): Boolean = tool in FORM_BATCH_TOOLS

    fun isScreenChanging(tool: String, expectedPageChange: Boolean): Boolean {
        if (isFormBatch(tool)) return false
        if (tool in INHERENT_NAV_TOOLS) return true
        if (tool in MAYBE_NAV_TOOLS) return expectedPageChange
        return expectedPageChange
    }

    fun <T> keep(
        actions: List<T>,
        toolOf: (T) -> String,
        expectedPageChangeOf: (T) -> Boolean,
    ): FastPathIsolationResult<T> {
        val allowed = ArrayList<T>(actions.size)
        val dropped = ArrayList<T>()
        var navSeen = false
        for (action in actions) {
            val nav = isScreenChanging(toolOf(action), expectedPageChangeOf(action))
            if (navSeen) {
                dropped += action
                continue
            }
            allowed += action
            if (nav) navSeen = true
        }
        return FastPathIsolationResult(
            allowed = allowed,
            dropped = dropped,
            reason = if (dropped.isEmpty()) null else DROP_REASON,
        )
    }

    fun keepPlanned(actions: List<FastPathPlannedAction>): FastPathIsolationResult<FastPathPlannedAction> =
        keep(actions, { it.tool }, { it.expectedPageChange })
}
