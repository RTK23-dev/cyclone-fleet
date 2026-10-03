package com.cyclone.mobile.automation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationProposalCompilerTest {
    @Test
    fun compilesAgent3BenignProposalIntoDisabledTypedAutomation() {
        val raw = JSONObject()
            .put("name", "Open battery settings")
            .put("trigger", JSONObject().put("type", "manual"))
            .put("steps", JSONArray()
                .put(JSONObject()
                    .put("type", "phone_tool")
                    .put("tool", "phone.open_app")
                    .put("params", JSONObject().put("package", "com.android.settings")))
                .put(JSONObject()
                    .put("type", "phone_tool")
                    .put("tool", "phone.click")
                    .put("params", JSONObject().put("selector", JSONObject().put("text", "Battery"))))
                .put(JSONObject()
                    .put("type", "assertion")
                    .put("condition", JSONObject().put("text", "Battery"))))

        val compiled = AutomationProposalCompiler.compile(raw)

        assertEquals(TriggerType.MANUAL, compiled.trigger.type)
        assertFalse(compiled.enabled)
        assertEquals(3, compiled.steps.size)
        assertEquals("phone.open_app", compiled.steps[0].parameters["tool"])
        assertEquals("com.android.settings", compiled.steps[0].parameters["package"])
        assertEquals("phone.click", compiled.steps[1].parameters["tool"])
        assertEquals("Battery", compiled.steps[1].selector?.text)
        assertEquals("phone.assert", compiled.steps[2].parameters["tool"])
        assertEquals("Battery", compiled.steps[2].parameters["text"])
    }

    @Test
    fun rejectsConsequentialProposalWithoutRequiredConfirmation() {
        val raw = JSONObject()
            .put("name", "Unsafe submission")
            .put("trigger", JSONObject().put("type", "manual"))
            .put("steps", JSONArray().put(JSONObject()
                .put("type", "phone_tool")
                .put("tool", "phone.click")
                .put("consequential", true)))

        val error = assertThrows(IllegalStateException::class.java) {
            AutomationProposalCompiler.compile(raw)
        }
        assertTrue(error.message.orEmpty().contains("must require confirmation"))
    }

    @Test
    fun expandsTakeoverIntoCheckpointAndResumeAssertion() {
        val raw = JSONObject()
            .put("name", "Sign-in assisted task")
            .put("trigger", JSONObject().put("type", "manual"))
            .put("steps", JSONArray().put(JSONObject()
                .put("type", "request_human_takeover")
                .put("reason", "Complete sign-in")
                .put("resumeCondition", JSONObject()
                    .put("type", "package_equals")
                    .put("package", "com.example.app"))))

        val compiled = AutomationProposalCompiler.compile(raw)

        assertEquals(2, compiled.steps.size)
        assertEquals(StepType.REQUEST_HUMAN_TAKEOVER, compiled.steps[0].type)
        assertEquals("Complete sign-in", compiled.steps[0].parameters["reason"])
        assertEquals(StepType.PHONE_TOOL, compiled.steps[1].type)
        assertEquals("phone.assert", compiled.steps[1].parameters["tool"])
        assertEquals("package_equals", compiled.steps[1].parameters["type"])
        assertEquals("com.example.app", compiled.steps[1].parameters["package"])
        assertEquals(FailureAction.REQUEST_HUMAN, compiled.steps[1].recovery.onFailure)
    }

    @Test
    fun rejectsLiteralDefaultForSecretVariable() {
        val raw = JSONObject()
            .put("name", "Secret workflow")
            .put("trigger", JSONObject().put("type", "manual"))
            .put("variables", JSONArray().put(JSONObject()
                .put("name", "password")
                .put("secret", true)
                .put("defaultValue", "plaintext")))
            .put("steps", JSONArray().put(JSONObject()
                .put("type", "phone_tool")
                .put("tool", "phone.observe")))

        val error = assertThrows(IllegalStateException::class.java) {
            AutomationProposalCompiler.compile(raw)
        }
        assertTrue(error.message.orEmpty().contains("cannot contain a literal default"))
    }
}
