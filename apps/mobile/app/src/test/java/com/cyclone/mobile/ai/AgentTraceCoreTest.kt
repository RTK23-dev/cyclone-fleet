package com.cyclone.mobile.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceCoreTest {
    @Test fun accountEmailsAreMaskedEvenWhenTheModelEchoesThem() {
        assertEquals("Account j***@gmail.com", TracePrivacy.clean("Account jane@gmail.com"))
        assertEquals("Account j***@gmail.com", TracePrivacy.clean("Account j***@gmail.com"))
    }
    @Test
    fun tracePrivacyRedactsSecretsAndBinaryPayloads() {
        val dirty = "password=hunter2 token:abc123456789 Bearer abcdefghijklmnop pngBase64:${"A".repeat(220)}"
        val clean = TracePrivacy.clean(dirty)
        assertFalse(clean.contains("hunter2"))
        assertFalse(clean.contains("abc123456789"))
        assertFalse(clean.contains("abcdefghijklmnop"))
        assertFalse(clean.contains("A".repeat(100)))
        assertTrue(clean.contains("REDACTED"))
    }

    @Test fun cardNumbersAreRedactedButTimestampsAndIdsSurvive() {
        assertTrue(TracePrivacy.clean("card 4111 1111 1111 1111 ok").contains("[PAYMENT_REDACTED]"))
        assertTrue(TracePrivacy.clean("card 4111111111111111").contains("[PAYMENT_REDACTED]"))
        assertTrue("grouped like a card", TracePrivacy.clean("card 1234-5678-9012-3456").contains("[PAYMENT_REDACTED]"))
        // alpha.22 diagnostics: a millisecond timestamp and a hex page key were shown as payment data.
        assertEquals("{\"readAtMs\":1790283787000}", TracePrivacy.clean("{\"readAtMs\":1790283787000}"))
        assertEquals("page=8194b87cb4429371", TracePrivacy.clean("page=8194b87cb4429371"))
        assertEquals("page=698461bb3b6e1790283787001dc", TracePrivacy.clean("page=698461bb3b6e1790283787001dc"))
    }

    @Test
    fun traceHumanizerNeverIncludesTypedValue() {
        val params = JSONObject()
            .put("value", "very-private-text")
            .put("selector", JSONObject().put("text", "Email"))
        val summary = TraceHumanizer.decision("phone.type", params, null)
        assertFalse(summary.contains("very-private-text"))
        assertTrue(summary.contains("field", ignoreCase = true))
    }

    @Test
    fun modelProvidedTypingSummaryIsIgnoredToPreventSecretEcho() {
        val params = JSONObject()
            .put("value", "1234-secret-value")
            .put("selector", JSONObject().put("text", "Verification code"))
        val summary = TraceHumanizer.decision(
            "phone.type",
            params,
            "Typing verification code 1234-secret-value into the form",
        )
        assertFalse(summary.contains("1234-secret-value"))
        assertEquals("Filling the requested field without storing its contents", summary)
    }

    @Test
    fun customOpenRouterSlugIsAcceptedAsConfiguration() {
        val slug = "my-provider/my-custom-model"
        val custom = OpenRouterModelPresets.byId(slug)
        assertEquals(slug, custom.id)
        assertEquals(slug, custom.label)
        assertFalse(custom.vision)
    }

    @Test
    fun explicitDisplaySummaryWinsButIsSanitizedForUserTrace() {
        val params = JSONObject().put("selector", JSONObject().put("text", "Battery"))
        val summary = TraceHumanizer.decision("phone.click", params, "Opening Battery because it matches the requested destination")
        assertEquals("Opening Battery because it matches the requested destination", summary)
    }
}
