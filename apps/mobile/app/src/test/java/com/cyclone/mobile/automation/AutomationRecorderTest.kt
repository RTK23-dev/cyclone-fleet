package com.cyclone.mobile.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRecorderTest {
    @Test fun recorderPreservesSemanticSelectorAndDeduplicatesActions() {
        val recorder = AutomationRecorder()
        val selector = Selector(resourceId = "com.example:id/save", text = "Save", role = "button")

        recorder.start()
        recorder.recordClick(selector)
        recorder.recordClick(selector)
        val automation = recorder.stop("Save form")

        assertEquals(1, automation.steps.size)
        assertEquals(StepType.PHONE_TOOL, automation.steps.single().type)
        assertEquals("phone.click", automation.steps.single().parameters["tool"])
        assertEquals("com.example:id/save", automation.steps.single().selector?.resourceId)
        assertEquals("Save", automation.steps.single().selector?.text)
    }

    @Test fun textRecordingUsesVariablePlaceholderInsteadOfCredentialValue() {
        val recorder = AutomationRecorder()
        recorder.start()
        recorder.recordText(Selector(resourceId = "com.example:id/message"))
        val automation = recorder.stop("Send message")

        assertEquals("${'$'}{input}", automation.steps.single().parameters["text"])
        assertNull(automation.steps.single().selector?.x)
        assertTrue(automation.trigger.type == TriggerType.MANUAL)
    }

    @Test fun appOpenEventsCollapseUntilPackageChanges() {
        val recorder = AutomationRecorder()
        recorder.start()
        recorder.recordAppOpened("com.example.one")
        recorder.recordAppOpened("com.example.one")
        recorder.recordAppOpened("com.example.two")

        assertEquals(2, recorder.snapshot().size)
        assertEquals("com.example.one", recorder.snapshot()[0].parameters["package"])
        assertEquals("com.example.two", recorder.snapshot()[1].parameters["package"])
    }
}
