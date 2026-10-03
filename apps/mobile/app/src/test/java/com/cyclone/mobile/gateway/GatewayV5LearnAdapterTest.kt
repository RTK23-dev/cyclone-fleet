package com.cyclone.mobile.gateway

import com.cyclone.mobile.mind.learn.AppLearned
import com.cyclone.mobile.mind.learn.LearnOutcome
import com.cyclone.mobile.mind.learn.LearnReport
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayV5LearnAdapterTest {
    @Test fun learnRunRepliesWithCountsOnly() {
        GatewayV5LearnAdapter.learn = { LearnOutcome.Learned(LearnReport(listOf(AppLearned("com.google.android.calculator", "Calculator", 2, 2, 14, 1)), 0, 1L), false) }
        val reply = GatewayV5LearnAdapter.dispatch("learn.run", JSONObject().put("runId", "run-123"))
        assertEquals(setOf("runId", "learned", "alreadyLearned", "sentence", "apps", "refusal"), reply.keys().asSequence().toSet())
        assertTrue(reply.getBoolean("learned"))
        assertEquals("Learned 2 screens, 14 controls and 1 move in Calculator.", reply.getString("sentence"))
        assertEquals(setOf("package", "label", "screens", "newScreens", "controls", "transitions"),
            reply.getJSONArray("apps").getJSONObject(0).keys().asSequence().toSet())
        assertTrue(reply.isNull("refusal"))
    }

    @Test fun aRefusalComesBackAsData() {
        GatewayV5LearnAdapter.learn = { LearnOutcome.Refused("NOTHING_TO_LEARN", "This run is from before Learn existed.") }
        val reply = GatewayV5LearnAdapter.dispatch("learn.run", JSONObject().put("runId", "run-123"))
        assertFalse(reply.getBoolean("learned"))
        assertEquals("NOTHING_TO_LEARN", reply.getJSONObject("refusal").getString("code"))
        assertEquals(0, reply.getJSONArray("apps").length())
    }

    @Test fun malformedRequestsAreRefused() {
        for (args in listOf(JSONObject(), JSONObject().put("runId", "../x"), JSONObject().put("runId", "run-123").put("extra", 1))) {
            val error = runCatching { GatewayV5LearnAdapter.dispatch("learn.run", args) }.exceptionOrNull()
            assertTrue(error is GatewayProtocolException)
        }
        assertTrue("learn.run" in GatewayProtocol.operations)
    }
}
