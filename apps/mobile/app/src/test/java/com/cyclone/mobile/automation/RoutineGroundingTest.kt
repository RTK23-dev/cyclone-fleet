package com.cyclone.mobile.automation

import com.cyclone.mobile.ui.v32.V32ActionChoice
import com.cyclone.mobile.ui.v32.V32ActionDraft
import com.cyclone.mobile.ui.v32.V32AutomationDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Routines grounded on the map (plan 23): a trigger plus saved skills, run through the same entry as an Ask. */
class RoutineGroundingTest {
    private fun runner(started: MutableList<String>, refuse: Boolean = false) = AutomationRunner(
        store = AutomationStore.inMemory(),
        phoneTools = PhoneToolGateway { PhoneToolResult(true) },
        integrations = object : IntegrationGateway {
            override fun runGroundedSkill(skillId: String): PhoneToolResult {
                started += skillId
                return if (refuse) PhoneToolResult(false, errorCode = "ASK_BUSY", message = "Cyclone is busy")
                else PhoneToolResult(true, mapOf("skillStarted" to skillId))
            }
        },
        confirmations = ConfirmationGateway { _, _ -> true },
        takeover = TakeoverGateway { _, _, _ -> true },
        sleep = {},
        now = { 1_000L },
    )

    private fun routine(vararg steps: StepDefinition) =
        AutomationDefinition(id = "r", name = "Morning timer", trigger = TriggerDefinition(TriggerType.MANUAL), steps = steps.toList())

    @Test fun aRoutineOfSavedSkillsIsGroundedAndScriptedTapsAreNot() {
        assertEquals(RoutineGround.GROUNDED, RoutineGrounding.of(routine(RoutineGrounding.skillStep("s", "you.abc"))))
        assertEquals(RoutineGround.SCRIPTED, RoutineGrounding.of(routine(RoutineGrounding.skillStep("s", "you.abc"),
            StepDefinition("t", "Tap", StepType.PHONE_TOOL, mapOf("tool" to "phone.click")))))
        assertEquals(RoutineGround.NO_PHONE, RoutineGrounding.of(routine(StepDefinition("w", "Wait", StepType.DELAY, mapOf("ms" to "500")))))
    }

    @Test fun theSkillStepStartsTheSavedSkillAndARefusalFailsTheRunHonestly() {
        val started = mutableListOf<String>()
        val ok = runner(started).run(routine(RoutineGrounding.skillStep("s", "you.abc")), TriggerEvent(TriggerType.MANUAL))
        assertEquals(RunState.SUCCESS, ok.state)
        assertEquals(listOf("you.abc"), started)

        val busy = runner(mutableListOf(), refuse = true).run(routine(RoutineGrounding.skillStep("s", "you.abc")), TriggerEvent(TriggerType.MANUAL))
        assertEquals(RunState.FAILED, busy.state)

        val notASkill = runner(started).run(routine(RoutineGrounding.skillStep("s", "stock.instagram.x")), TriggerEvent(TriggerType.MANUAL))
        assertEquals("only the owner's skills run this way", RunState.FAILED, notASkill.state)
        assertEquals(1, started.size)
    }

    @Test fun theBuilderMakesAGroundedRoutineFromASkill() {
        val action = V32ActionDraft(choice = V32ActionChoice.RUN_SKILL, value = "you.abc")
        assertNull(action.validationIssue())
        assertEquals("Choose one of your skills.", V32ActionDraft(choice = V32ActionChoice.RUN_SKILL).validationIssue())
        val built = V32AutomationDraft(name = "Morning timer", actions = listOf(action)).toAutomation()
        assertEquals(RoutineGround.GROUNDED, RoutineGrounding.of(built))
        assertEquals(RoutineGround.GROUNDED, RoutineGrounding.of(AutomationCodec.automationFromJson(AutomationCodec.automationToJson(built))))
    }
}
