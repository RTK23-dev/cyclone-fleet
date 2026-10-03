package com.cyclone.mobile.mind.decide

import com.cyclone.mobile.mind.modes.BoxQuestion
import com.cyclone.mobile.mind.modes.BoxRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Alpha.78: JEV answers Cyclone's decisions, text only; OpenAI Decisions is frozen until it is available. */
class DecisionsTest {
    private val request = BoxRequest("Pick the shutter.", "Screen: Camera\nControls: Mode, Button 3",
        listOf(BoxQuestion("next", "Which control takes the photo?", listOf("Mode", "Button 3", "none"))),
        image = "data:image/png;base64,AAAA")

    @Test fun `jev is the live provider and reads text only`() {
        assertEquals(DecisionProvider.JEV, Decisions.active())
        assertEquals("~typesafe/jev-latest", Decisions.active().model)
        assertFalse(DecisionProvider.JEV.vision)
    }

    @Test fun `openai decisions is frozen and never picked`() {
        assertFalse(DecisionProvider.OPENAI_DECISIONS.live)
        assertNull(DecisionProvider.OPENAI_DECISIONS.endpoint)
    }

    @Test fun `jev never gets the screenshot and asks in the decisions shape`() {
        val body = ProviderDecisionBox.body(DecisionProvider.JEV, request)
        assertEquals("~typesafe/jev-latest", body.getString("model"))
        assertFalse(body.toString().contains("image"))
        assertTrue(body.getJSONObject("questions").getJSONObject("next").getJSONArray("choices").length() == 3)
    }

    @Test fun `a provider with vision would keep it (ready for openai decisions)`() {
        assertFalse(ProviderDecisionBox("k", DecisionProvider.JEV).sees)
        assertTrue(DecisionProvider.OPENAI_DECISIONS.vision)
    }
}
