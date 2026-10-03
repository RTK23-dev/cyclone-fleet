package com.cyclone.mobile.ai

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutedActionMemoryTest {
    @Test
    fun humanHandoffClearsSceneBoundNoRepeatMemory() {
        val memory = ExecutedActionMemory()
        val action = PageAgentAction(
            tool = "phone.click",
            controlId = "semantic:obs:continue",
            params = JSONObject(),
            expectedPageChange = true,
            displaySummary = "Continue",
        )

        assertTrue(memory.mayDispatch(action, "scene-a"))
        memory.record(action, "scene-a", androidAccepted = true, verifiedProgress = false)
        assertFalse(memory.mayDispatch(action, "scene-a"))

        memory.resetAfterHandoff()

        assertTrue(memory.mayDispatch(action, "scene-a"))
    }
}
