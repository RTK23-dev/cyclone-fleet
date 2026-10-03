package com.cyclone.mobile.automation.stock.instagram

import android.content.Context
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.SkillDefinition
import com.cyclone.mobile.automation.StockSkillGateway
import com.cyclone.mobile.automation.StockSkillRequest
import com.cyclone.mobile.automation.StockSkillResult
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerType
import com.cyclone.mobile.automation.VariableDefinition

/** Single Mobile stock-skill registry so adding a new built-in never creates another automation engine. */
class InstagramStockSkillCollectionGateway(private val context: Context) : StockSkillGateway {
    override fun execute(request: StockSkillRequest): StockSkillResult = when (request.skillId) {
        InstagramStockSkillIds.REELS_WARMUP -> InstagramReelsWarmupRunner(context).run(request)
        InstagramReelsFollowingStockSkill.ID -> InstagramReelsFollowingRunner(context).run(request)
        InstagramColdDmsStockSkill.ID -> InstagramColdDmsRunner(context).run(request)
        InstagramPreparePostStockSkill.ID -> InstagramPreparePostRunner(context).run(request)
        else -> StockSkillResult(false, message = "unknown_stock_skill:${request.skillId}")
    }

    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"

        val definitions: List<SkillDefinition> = listOf(
            InstagramReelsWarmupStockSkill.definition,
            InstagramReelsFollowingStockSkill.definition,
            InstagramColdDmsStockSkill.definition,
            InstagramPreparePostStockSkill.definition,
        )

        /** Routines rows for the current stock set. Manual, enabled, grouped under Instagram. */
        fun routines(): List<AutomationDefinition> = definitions.map { skill ->
            AutomationDefinition(
                id = skill.id,
                name = skill.name,
                description = skill.description,
                enabled = true,
                version = skill.version,
                trigger = TriggerDefinition(TriggerType.MANUAL),
                variables = skill.inputs.map { VariableDefinition(it, defaultValue(it)) },
                steps = skill.steps,
                outputVariables = skill.outputs,
                appPackages = listOf(INSTAGRAM_PACKAGE),
                categories = listOf("Instagram"),
            )
        }

        private fun defaultValue(input: String): String = when (input) {
            "durationMinutes" -> "5"
            "personality" -> "casual"
            "likeEnabled" -> "true"
            "commentEnabled" -> "false"
            "cycles" -> "1"
            "betweenMs" -> "15000"
            "jitterMs" -> "4000"
            "verifyEnabled" -> "true"
            "leadBatch" -> "5"
            "skipPrivate" -> "true"
            "retryFailed" -> "false"
            "mediaCount" -> "1"
            "destination" -> "draft"
            else -> ""
        }
    }
}
