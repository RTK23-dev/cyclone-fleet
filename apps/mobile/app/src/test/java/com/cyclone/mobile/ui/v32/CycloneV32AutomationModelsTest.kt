package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.automation.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneV32AutomationModelsTest {
    @Test
    fun oneTapDraftCreatesExistingAutomationContract() {
        val draft = V32AutomationDraft(
            name = "Open podcasts",
            trigger = V32TriggerChoice.ONE_TAP,
            actions = listOf(V32ActionDraft(choice = V32ActionChoice.OPEN_APP, value = "com.example.podcasts")),
        )

        val automation = draft.toAutomation(nowMillis = 1_000)

        assertEquals("Open podcasts", automation.name)
        assertEquals(TriggerType.MANUAL, automation.trigger.type)
        assertTrue(automation.enabled)
        assertEquals(StepType.PHONE_TOOL, automation.steps.single().type)
        assertEquals("phone.open_app", automation.steps.single().parameters["tool"])
        assertEquals("com.example.podcasts", automation.steps.single().parameters["package"])
    }

    @Test
    fun notificationDraftUsesOnlyExistingTypedTriggerFields() {
        val draft = V32AutomationDraft(
            name = "Open messages from work",
            trigger = V32TriggerChoice.NOTIFICATION,
            sourcePackage = "com.example.work",
            containsText = "new shift",
            actions = listOf(V32ActionDraft(choice = V32ActionChoice.HOME)),
        )

        val automation = draft.toAutomation(nowMillis = 1_000)

        assertEquals(TriggerType.NOTIFICATION, automation.trigger.type)
        assertEquals(mapOf("package" to "com.example.work", "text" to "new shift"), automation.trigger.parameters)
        assertEquals("phone.home", automation.steps.single().parameters["tool"])

        val withoutAccess = draft.toAutomationForDevice(notificationAccess = false, nowMillis = 1_000)
        assertFalse(withoutAccess.enabled)
        assertTrue(draft.toAutomationForDevice(notificationAccess = true, nowMillis = 1_000).enabled)
    }

    @Test
    fun scheduleRequiresFutureTimeAndPreservesRepeat() {
        val invalid = V32AutomationDraft(
            name = "Morning",
            trigger = V32TriggerChoice.SCHEDULE,
            scheduledAtMillis = 900,
            actions = listOf(V32ActionDraft(choice = V32ActionChoice.WAIT, value = "500")),
        )
        assertTrue(invalid.validationIssues(nowMillis = 1_000).any { "future" in it })

        val valid = invalid.copy(scheduledAtMillis = 2_000, scheduleRepeatMillis = 86_400_000)
        val automation = valid.toAutomation(nowMillis = 1_000)
        assertEquals("2000", automation.trigger.parameters["atMillis"])
        assertEquals("86400000", automation.trigger.parameters["intervalMs"])
    }

    @Test
    fun blankNamesMissingActionsAndMalformedPackagesFailClosed() {
        val draft = V32AutomationDraft(
            name = "",
            actions = listOf(V32ActionDraft(choice = V32ActionChoice.OPEN_APP, value = "not a package")),
        )
        val issues = draft.validationIssues(nowMillis = 1_000)
        assertTrue(issues.any { "name" in it })
        assertTrue(issues.any { "package" in it })
        assertThrows(IllegalArgumentException::class.java) { draft.toAutomation(nowMillis = 1_000) }

        assertFalse(V32AutomationDraft(name = "Still empty").validationIssues(nowMillis = 1_000).isEmpty())
    }

    @Test
    fun humanTakeoverIsExplicitAndConfirmationBound() {
        val automation = V32AutomationDraft(
            name = "Pause for me",
            actions = listOf(V32ActionDraft(choice = V32ActionChoice.HUMAN, value = "Confirm the final screen")),
        ).toAutomation(nowMillis = 1_000)

        val step = automation.steps.single()
        assertEquals(StepType.REQUEST_HUMAN_TAKEOVER, step.type)
        assertTrue(step.confirmationRequired)
        assertEquals("Confirm the final screen", step.parameters["reason"])
    }
}
