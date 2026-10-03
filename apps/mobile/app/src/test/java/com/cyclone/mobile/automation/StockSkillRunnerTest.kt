package com.cyclone.mobile.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StockSkillRunnerTest {
    @Test
    fun stockSkillRunsInsideAutomationRunnerAndMergesOutputs() {
        val store = AutomationStore.inMemory()
        var request: StockSkillRequest? = null
        val runner = runner(
            store = store,
            stockSkills = StockSkillGateway { value ->
                request = value
                StockSkillResult(true, mapOf("videosViewed" to "7", "reason" to "completed"), message = "done")
            },
        )
        val automation = AutomationDefinition(
            id = "stock-runner-success",
            name = "Stock runner success",
            trigger = TriggerDefinition(TriggerType.MANUAL),
            variables = listOf(VariableDefinition("durationMinutes", "5")),
            steps = listOf(StepDefinition(
                id = "native-step",
                name = "Native step",
                type = StepType.STOCK_SKILL,
                parameters = mapOf(
                    "skillId" to "stock.instagram.reels_warmup",
                    "durationMinutes" to "${'$'}{durationMinutes}",
                ),
            )),
        )

        val run = runner.run(automation, TriggerEvent(TriggerType.MANUAL))

        assertEquals(RunState.SUCCESS, run.state)
        assertEquals("7", run.variables["videosViewed"])
        assertEquals("completed", run.variables["reason"])
        assertEquals("stock.instagram.reels_warmup", request?.skillId)
        assertEquals("5", request?.arguments?.get("durationMinutes"))
        assertEquals("native-step", request?.stepId)
    }

    @Test
    fun stockSkillHumanReviewUsesNormalWaitingCheckpointAndTakeover() {
        val store = AutomationStore.inMemory()
        var takeoverReason: String? = null
        var takeoverStep: String? = null
        val runner = runner(
            store = store,
            stockSkills = StockSkillGateway {
                StockSkillResult(
                    success = false,
                    output = mapOf("reason" to "waiting_for_human"),
                    waitingForHuman = true,
                    message = "human review is required",
                )
            },
            takeover = TakeoverGateway { reason, _, stepId ->
                takeoverReason = reason
                takeoverStep = stepId
                true
            },
        )
        val automation = AutomationDefinition(
            id = "stock-runner-handoff",
            name = "Stock runner handoff",
            trigger = TriggerDefinition(TriggerType.MANUAL),
            steps = listOf(StepDefinition(
                id = "native-step",
                name = "Native step",
                type = StepType.STOCK_SKILL,
                parameters = mapOf("skillId" to "stock.instagram.cold_dms"),
            )),
        )

        val run = runner.run(automation, TriggerEvent(TriggerType.MANUAL))

        assertEquals(RunState.WAITING_FOR_HUMAN, run.state)
        assertEquals("waiting_for_human", run.variables["reason"])
        assertEquals("human review is required", takeoverReason)
        assertEquals("native-step", takeoverStep)
        val checkpoint = store.getCheckpoint(run.id)
        assertNotNull(checkpoint)
        assertTrue(checkpoint!!.waitingForHuman)
        assertEquals(0, checkpoint.nextStepIndex)
    }

    private fun runner(
        store: AutomationStore,
        stockSkills: StockSkillGateway,
        takeover: TakeoverGateway = TakeoverGateway { _, _, _ -> true },
    ) = AutomationRunner(
        store = store,
        phoneTools = PhoneToolGateway { PhoneToolResult(true) },
        integrations = object : IntegrationGateway {},
        confirmations = ConfirmationGateway { _, _ -> true },
        takeover = takeover,
        stockSkills = stockSkills,
        sleep = {},
        now = { 1_000L },
    )
}
