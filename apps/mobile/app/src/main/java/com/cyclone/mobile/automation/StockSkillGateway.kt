package com.cyclone.mobile.automation

/**
 * Bridge for built-in, long-running skills that need native control flow while still routing
 * every device mutation through Cyclone's authoritative PhoneToolExecutor.
 *
 * Stock skill implementations must not talk to AccessibilityService directly. They return
 * structured outputs to the normal AutomationRunner so run records/checkpoints stay unified.
 */
data class StockSkillRequest(
    val skillId: String,
    val runId: String,
    val stepId: String,
    val arguments: Map<String, String>,
)

data class StockSkillResult(
    val success: Boolean,
    val output: Map<String, String> = emptyMap(),
    val waitingForHuman: Boolean = false,
    val message: String? = null,
)

fun interface StockSkillGateway {
    fun execute(request: StockSkillRequest): StockSkillResult

    companion object {
        val UNCONFIGURED = StockSkillGateway {
            StockSkillResult(false, message = "stock_skill_gateway_not_configured")
        }
    }
}
