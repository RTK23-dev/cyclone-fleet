package com.cyclone.mobile.automation.stock.instagram

import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRunner
import com.cyclone.mobile.automation.AutomationStore
import com.cyclone.mobile.automation.ConfirmationGateway
import com.cyclone.mobile.automation.IntegrationGateway
import com.cyclone.mobile.automation.PhoneToolGateway
import com.cyclone.mobile.automation.PhoneToolResult
import com.cyclone.mobile.automation.RunState
import com.cyclone.mobile.automation.StockSkillGateway
import com.cyclone.mobile.automation.StockSkillResult
import com.cyclone.mobile.automation.TakeoverGateway
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerEvent
import com.cyclone.mobile.automation.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramReelsWarmupStockSkillTest {
    @Test
    fun profilesMatchPinnedSourceExactly() {
        assertEquals(ReelsProfile(1_500, 4_000, 0.28, 0.10, 0.15, 0.05, 4_000, 8_000), ReelsProfiles.get(ReelsPersonality.SKIMMER))
        assertEquals(ReelsProfile(4_000, 9_000, 0.50, 0.22, 0.28, 0.10, 8_000, 15_000), ReelsProfiles.get(ReelsPersonality.CASUAL))
        assertEquals(ReelsProfile(8_000, 18_000, 0.75, 0.40, 0.41, 0.20, 15_000, 30_000), ReelsProfiles.get(ReelsPersonality.ENGAGED))
        assertEquals(ReelsProfile(1_200, 2_200, 1.0, 1.0, 1.0, 0.0, 0, 0), ReelsProfiles.get(ReelsPersonality.DIALED))
    }

    @Test
    fun validatesDurationAndCommentInputs() {
        assertTrue(ReelsWarmupConfig.parse(emptyMap()).isSuccess)
        assertTrue(ReelsWarmupConfig.parse(mapOf("durationMinutes" to "1")).isSuccess)
        assertTrue(ReelsWarmupConfig.parse(mapOf("durationMinutes" to "180")).isSuccess)
        assertTrue(ReelsWarmupConfig.parse(mapOf("durationMinutes" to "0")).isFailure)
        assertTrue(ReelsWarmupConfig.parse(mapOf("durationMinutes" to "181")).isFailure)
        assertTrue(ReelsWarmupConfig.parse(mapOf("personality" to "unknown")).isFailure)
        assertTrue(ReelsWarmupConfig.parse(mapOf("commentEnabled" to "true", "commentText" to "")).isFailure)
    }

    @Test
    fun stockDefinitionNeverContainsSaveAction() {
        val definition = InstagramReelsWarmupStockSkill.definition
        assertEquals(InstagramStockSkillIds.REELS_WARMUP, definition.id)
        assertEquals(1, definition.steps.size)
        assertEquals("STOCK_SKILL", definition.steps.single().type.name)
        val serialized = definition.toString().lowercase()
        assertFalse(serialized.contains("phone.save"))
        assertFalse(definition.inputs.any { it.equals("saveEnabled", ignoreCase = true) })
    }

    @Test
    fun automationRunnerInvokesStockGatewayAndPublishesOutputs() {
        val store = AutomationStore.inMemory()
        store.saveSkill(InstagramReelsWarmupStockSkill.definition)
        var invoked = false
        val runner = AutomationRunner(
            store = store,
            phoneTools = PhoneToolGateway { PhoneToolResult(false, errorCode = "unexpected_phone_tool") },
            integrations = object : IntegrationGateway {},
            confirmations = ConfirmationGateway { _, _ -> true },
            takeover = TakeoverGateway { _, _, _ -> true },
            stockSkills = StockSkillGateway { request ->
                invoked = true
                assertEquals(InstagramStockSkillIds.REELS_WARMUP, request.skillId)
                assertEquals("5", request.arguments["durationMinutes"])
                StockSkillResult(true, mapOf("videosViewed" to "7", "reason" to "completed"))
            },
            sleep = {},
            now = { 1000L },
        )
        val automation = AutomationDefinition(
            id = "test-reels",
            name = "test",
            trigger = TriggerDefinition(TriggerType.MANUAL),
            variables = listOf(
                com.cyclone.mobile.automation.VariableDefinition("durationMinutes", "5"),
                com.cyclone.mobile.automation.VariableDefinition("personality", "casual"),
                com.cyclone.mobile.automation.VariableDefinition("likeEnabled", "true"),
                com.cyclone.mobile.automation.VariableDefinition("commentEnabled", "false"),
                com.cyclone.mobile.automation.VariableDefinition("commentText", ""),
                com.cyclone.mobile.automation.VariableDefinition("switchAccount", ""),
            ),
            steps = InstagramReelsWarmupStockSkill.definition.steps,
        )
        val run = runner.run(automation, TriggerEvent(TriggerType.MANUAL))
        assertTrue(invoked)
        assertEquals(RunState.SUCCESS, run.state)
        assertEquals("7", run.variables["videosViewed"])
        assertEquals("completed", run.variables["reason"])
    }
}
