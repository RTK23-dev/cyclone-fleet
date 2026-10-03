package com.cyclone.mobile.brain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBrainLogicTest {
    @Test
    fun typedValuesAreNeverStoredInMicroSkillParams() {
        val params = JSONObject()
            .put("value", "very-private-password")
            .put("selector", JSONObject().put("resourceId", "com.example:id/password").put("role", "textbox"))

        val safe = AdaptiveBrainLogic.sanitizeParams("phone.type", params)

        assertFalse(safe.toString().contains("very-private-password"))
        assertTrue(safe.has("selector"))
        assertEquals("com.example:id/password", safe.getJSONObject("selector").getString("resourceId"))
    }

    @Test
    fun confidenceImprovesWithRepeatedSuccessAndFallsWithFailure() {
        val first = AdaptiveBrainLogic.confidence(1, 0, "AI_EXECUTION")
        val repeated = AdaptiveBrainLogic.confidence(4, 0, "AI_EXECUTION")
        val withFailure = AdaptiveBrainLogic.confidence(4, 2, "AI_EXECUTION")

        assertTrue(repeated > first)
        assertTrue(withFailure < repeated)
    }

    @Test
    fun goalKeyNormalizesPoliteNoise() {
        assertEquals("open spotify", AdaptiveBrainLogic.goalKey("Please can you Cyclone open my Spotify now?"))
    }

    @Test
    fun homeSkillIdentityIsStableAcrossScreens() {
        val a = AdaptiveBrainLogic.skillIdentity("phone.home", JSONObject(), "com.example.one", "fingerprint-a")
        val b = AdaptiveBrainLogic.skillIdentity("phone.home", JSONObject(), "com.example.two", "fingerprint-b")
        assertEquals(a, b)
    }

    @Test
    fun appOpenIdentityDependsOnPackage() {
        val spotify = AdaptiveBrainLogic.skillIdentity("phone.open_app", JSONObject().put("package", "com.spotify.music"), null, null)
        val settings = AdaptiveBrainLogic.skillIdentity("phone.open_app", JSONObject().put("package", "com.android.settings"), null, null)
        assertNotEquals(spotify, settings)
    }

    @Test
    fun selectorSanitizerKeepsSemanticFieldsOnly() {
        val raw = JSONObject()
            .put("resourceId", "pkg:id/orders")
            .put("text", "Orders")
            .put("unknownDangerousBlob", "do-not-store")
        val safe = AdaptiveBrainLogic.sanitizeSelector(raw)
        assertEquals("Orders", safe.getString("text"))
        assertFalse(safe.has("unknownDangerousBlob"))
    }
}
