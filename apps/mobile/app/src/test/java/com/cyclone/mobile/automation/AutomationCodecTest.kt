package com.cyclone.mobile.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationCodecTest {
    @Test
    fun richSelectorSurvivesAutomationJsonRoundTrip() {
        val selector = Selector(
            resourceId = "com.example:id/action",
            text = "Claim",
            partialText = "Cla",
            contentDescription = "Claim shift",
            contentDescriptionContains = "shift",
            role = "button",
            className = "android.widget.Button",
            ancestor = "Available shifts",
            descendant = "Now",
            relativePosition = "below:Available shifts",
            relativeToText = "Available shifts",
            relativeDirection = "below",
            fuzzyText = "claim shift",
            minFuzzyScore = 0.81,
            requireClickable = true,
            requireEditable = false,
            requireScrollable = false,
            x = 400,
            y = 900
        )
        val automation = AutomationDefinition(
            id = "selector-roundtrip",
            name = "Selector round trip",
            trigger = TriggerDefinition(TriggerType.MANUAL),
            steps = listOf(
                StepDefinition(
                    id = "click",
                    name = "Click",
                    type = StepType.PHONE_TOOL,
                    parameters = mapOf("tool" to "phone.click"),
                    selector = selector
                )
            )
        )

        val decoded = AutomationCodec.automationFromJson(AutomationCodec.automationToJson(automation))

        assertEquals(selector, decoded.steps.single().selector)
    }
}
