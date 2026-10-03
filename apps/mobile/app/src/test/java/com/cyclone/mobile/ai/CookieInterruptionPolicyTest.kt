package com.cyclone.mobile.ai

import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentPageCard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CookieInterruptionPolicyTest {
    @Test fun cookiePauseDoesNotAskTheUserToTakeOverWhenTheSiteIsAlreadyOpen() {
        val agent = sequenceOf(
            java.io.File("src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt"),
            java.io.File("apps/mobile/app/src/main/java/com/cyclone/mobile/ai/OpenRouterAdaptiveAgent.kt"),
        ).first { it.isFile }.readText()
        val complete = agent.indexOf("verifiedSimpleNavigation(goal)")
        val cookies = agent.indexOf("cookieInterruptions.evaluate")
        val takeover = agent.indexOf("CookieInterruptionOutcome.AMBIGUOUS")
        assertTrue(complete >= 0 && cookies > complete)
        assertEquals(-1, takeover)
        assertFalse(agent.contains("NEED_HUMAN, reason = interruption.reason"))
    }

    @Test fun shopifyAcceptAllAndRejectIsAUniqueLocalReject() {
        val accept = AgentElementCandidate("semantic:obs:accept", "obs", "Accept all", "accept all",
            "button", "semantic", 1.0, JSONObject().put("enabled", true).put("clickable", true))
        val reject = accept.copy(elementId = "semantic:obs:reject", label = "Reject", semanticName = "reject")
        val card = page().copy(
            pageSummary = JSONObject().put("summary", "Shopify"),
            pageText = JSONObject().put("text", "We use cookies"),
            controls = listOf(accept, reject),
        )
        val decision = CookieInterruptionPolicy().evaluate(card, "open chrome and go to Shopify")
        assertEquals(CookieInterruptionOutcome.HANDLED, decision.outcome)
        assertEquals("Reject", decision.target?.label)
        assertEquals("cookie.reject_optional", decision.reason)
    }

    @Test fun dutchRejectIsLocalAndAuthenticationControlsAreExcluded() {
        val card = page()
        val reject = card.controls.single().copy(label = "Alleen noodzakelijke cookies")
        val decision = CookieInterruptionPolicy().evaluate(card.copy(controls = listOf(reject,
            reject.copy(elementId = "auth", label = "Log in"), reject.copy(elementId = "permission", label = "Allow"))), "log in")
        assertEquals(CookieInterruptionOutcome.HANDLED, decision.outcome)
        assertEquals(reject.elementId, decision.target?.elementId)
        assertEquals(CookieInterruptionOutcome.BLOCKED_BY_USER_INTENT,
            CookieInterruptionPolicy().evaluate(card, "alle cookies accepteren").outcome)
    }

    @Test fun unresolvedOutcomesArePreciseAndNeverRepeatTheClick() {
        val card = page()
        val target = card.controls.single()
        val ranked = CookieInterruptionPolicy().evaluate(
            card.copy(controls = listOf(target, target.copy(elementId = "other"))), "login")
        assertEquals(CookieInterruptionOutcome.HANDLED, ranked.outcome)
        assertEquals("cookie.reject_unavailable", CookieInterruptionPolicy().evaluate(
            card.copy(controls = listOf(target.copy(evidence = JSONObject().put("enabled", false)))), "login").reason)
        val policy = CookieInterruptionPolicy()
        assertEquals(CookieInterruptionOutcome.HANDLED, policy.evaluate(card, "login").outcome)
        assertEquals("cookie.effect_unverified", policy.evaluate(page("new"), "login").reason)
    }

    @Test fun cookieArticleIsNotAConsentInterruption() {
        assertEquals(CookieInterruptionOutcome.NOT_APPLICABLE, CookieInterruptionPolicy().evaluate(
            page().copy(controls = emptyList()), "read article").outcome)
    }
    @Test fun redditLoginInterruptionRejectsLocallyOnceAcrossObservationChurn() {
        val policy = CookieInterruptionPolicy()
        assertEquals("Reject Optional Cookies", policy.next(page(), "open reddit.com on Chrome and login for me")?.label)
        assertNull(policy.next(page("next"), "open reddit.com on Chrome and login for me"))
    }

    @Test fun explicitCookieChoicesAreLeftToThePlanner() {
        listOf("accept all cookies", "allow optional cookies", "manage cookies", "don't accept all cookies").forEach {
            assertNull(CookieInterruptionPolicy().next(page(), it))
        }
        assertNotNull(CookieInterruptionPolicy().next(page(), "reject cookies and log in"))
    }

    @Test fun ambiguousDisabledStaleAndUnrelatedControlsAreNeverSelected() {
        val card = page()
        val target = card.controls.single()
        listOf(
            card.copy(controls = listOf(target.copy(evidence = JSONObject().put("enabled", false)))),
            card.copy(controls = listOf(target.copy(observationId = "old"))),
            card.copy(controls = listOf(target.copy(label = "Reject transfer"))),
            card.copy(actionable = false),
        ).forEach { assertNull(CookieInterruptionPolicy().next(it, "log in")) }
    }

    @Test fun artemisBurstPrefersRejectAllThenSettingsNotHumanTakeover() {
        val accept = AgentElementCandidate("semantic:obs:accept", "obs", "Accept all", "accept all",
            "button", "semantic", 1.0, JSONObject().put("enabled", true).put("clickable", true))
        val reject = accept.copy(elementId = "semantic:obs:reject", label = "Reject")
        val rejectAll = accept.copy(elementId = "semantic:obs:reject-all", label = "Reject all")
        val settings = accept.copy(elementId = "semantic:obs:settings", label = "Cookie settings")
        val cookieScene = page().copy(
            pageText = JSONObject().put("text", "We use cookies"),
            controls = listOf(accept, reject, rejectAll),
        )
        val decision = CookieInterruptionPolicy().evaluate(cookieScene, "open chrome and go to Shopify")
        assertEquals("Reject all", decision.target?.label)
        assertEquals("cookie.reject_optional", decision.reason)
        val settingsOnly = CookieInterruptionPolicy().evaluate(
            cookieScene.copy(controls = listOf(accept, settings)),
            "open chrome and go to Shopify",
        )
        assertEquals("Cookie settings", settingsOnly.target?.label)
        assertEquals("cookie.open_settings", settingsOnly.reason)
    }

    @Test fun genericRejectRequiresCookieScene() {
        val card = page().let { it.copy(pageText = JSONObject(), controls = listOf(it.controls.single().copy(label = "Reject all"))) }
        assertNull(CookieInterruptionPolicy().next(card, "log in"))
        val cookieScene = card.copy(pageText = JSONObject().put("text", "We use cookies"))
        assertNull(CookieInterruptionPolicy().next(cookieScene, "log in"))
        assertNotNull(CookieInterruptionPolicy().next(cookieScene.copy(controls = cookieScene.controls +
            cookieScene.controls.single().copy(elementId = "semantic:obs:accept", label = "Accept all")), "log in"))
    }

    private fun page(obs: String = "obs") = AgentPageCard(
        obs, 1, true, 1, "com.android.chrome", null, "page-$obs", "structure", "content", "fp",
        JSONObject(), JSONObject().put("text", "We use cookies"), JSONObject(),
        listOf(AgentElementCandidate("semantic:$obs:reject", obs, "Reject Optional Cookies", "reject cookies",
            "button", "semantic", 1.0, JSONObject().put("enabled", true).put("clickable", true))), JSONArray(),
    )
}
