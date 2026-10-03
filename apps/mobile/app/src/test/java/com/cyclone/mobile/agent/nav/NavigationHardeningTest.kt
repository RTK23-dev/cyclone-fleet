package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.agent.nav.MultiAppScenarioTest.Companion.SCENARIOS
import com.cyclone.mobile.agent.nav.MultiAppScenarioTest.Companion.page
import com.cyclone.mobile.agent.tools.ObservationProjections
import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.PageControl
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.gateway.GatewayElement
import com.cyclone.mobile.gateway.GatewayObservation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review follow-ups on the navigation branch: learned routes, mapping captures, fast paths and long timers. */
class NavigationHardeningTest {
    private val signupClause = ClauseCompiler.compile(SCENARIOS[1]) { true }[1]

    private fun learned(label: String) = LearnedAction(packageName = "com.android.chrome", screenId = "s1",
        semanticName = label, label = label, androidActions = listOf("ACTION_CLICK"), selectorJson = "{}", risk = ActionRisk.SAFE)

    private fun screen(vararg controls: PageControl) = NavigationScreen("chrome:https://instagram.com",
        "screen:form:aaaaaaaaaaaaaaaa", page("com.android.chrome", "Instagram").copy(controls = controls.toList()), "obs-1", 10)

    private val emailField = PageControl("f", "Email", "Email", "textbox", JSONObject().put("editable", true), emptyList(), ActionRisk.SAFE)
    private val signUpButton = PageControl("b", "Sign up", "Sign up", "button", JSONObject(), emptyList(), ActionRisk.SAFE)

    @Test fun learnedRoutesCannotSubmitAnAccountForm() {
        assertEquals(NavCapability.CREATE_ACCOUNT, signupClause.capability)
        assertEquals("account-submit", NavigationActionPolicy.learnedRoute(signupClause, learned("Sign up"), screen(emailField, signUpButton)))
        // No readable live screen: a submit-like learned label is refused, not assumed to be navigation.
        assertEquals("account-submit", NavigationActionPolicy.learnedRoute(signupClause, learned("Create account"), null))
        // A "Sign up" link on a page without a form is only navigation toward the form.
        assertNull(NavigationActionPolicy.learnedRoute(signupClause, learned("Sign up"), screen(signUpButton)))
        assertNull(NavigationActionPolicy.learnedRoute(signupClause, learned("Explore"), screen(emailField)))
        // Other clauses keep their learned routes.
        val identity = ClauseCompiler.compile(SCENARIOS[0]) { true }.single()
        assertNull(NavigationActionPolicy.learnedRoute(identity, learned("Continue"), null))
    }

    @Test fun mappingPassScreensNeverReachLiveFactReaders() {
        val mapping = screen(emailField).copy(persona = AtlasPersona.MAPPING, placeId = "package:com.google.android.gm",
            page = page("com.google.android.gm", "Account", "Manage your Google Account", "jane@gmail.com"))
        val identity = ClauseCompiler.compile(SCENARIOS[0]) { true }.single()
        val ledger = TaskLedger(0)
        assertNull(ClauseProof.check(identity, mapping, ledger, genericProof = true))
        assertFalse(ledger.record("signed-in-email", "jane@gmail.com", mapping.placeId!!, mapping.roomId, mapping.persona, mapping.readAtMs))
        assertNull(LiveNavigationScreen.from(capture(persona = AtlasPersona.MAPPING), card(capture(persona = AtlasPersona.MAPPING)), ledger))
        assertNull("unknown producer is not live", LiveNavigationScreen.from(capture(persona = null), card(capture(persona = null)), ledger))
    }

    @Test fun plainOpenGoalsKeepTheFastPathAndMultiClauseGoalsGetClauses() {
        fun needs(goal: String) = ClauseCompiler.needsClauseRun(ClauseCompiler.compile(goal) { true })
        assertFalse(needs("open YouTube"))
        assertFalse(needs("open Settings"))
        // Timers and alarms always get a clause run: a typed live proof and the one-step clock intent route.
        assertTrue(needs("set a timer for 5 minutes"))
        assertTrue(needs("open clock and set an alarm for 5 minutes"))
        SCENARIOS.forEach { assertTrue(it, needs(it)) }
    }

    @Test fun hourLongTimersCanBeProven() {
        assertEquals(listOf(3600L), ClauseProof.countdowns("1:00:00"))
        assertEquals(listOf(299L), ClauseProof.countdowns("4:59"))
        assertEquals(listOf(3725L), ClauseProof.countdowns("Remaining 1:02:05"))
        val clause = ClauseCompiler.compile("open the clock app and set a timer for 1 hour") { true }.single()
        assertEquals("3600", clause.target)
        val pkg = "com.google.android.deskclock"
        val running = NavigationScreen("package:$pkg", "screen:timer:aaaaaaaaaaaaaaaa", page(pkg, "Timer", "0:59:58", "Pause"), "obs-2", 20)
        assertTrue(ClauseProof.check(clause, running, TaskLedger(0), false) != null)
    }

    private fun capture(persona: AtlasPersona?): GatewayObservation {
        val elementId = "raw:obs-live:1"
        val evidence = JSONObject().put("elementId", elementId).put("observationId", "obs-live")
            .put("label", "jane@gmail.com").put("semanticName", "account").put("role", "text")
        return GatewayObservation("obs-live", 100, page("com.google.android.gm", "Account"),
            JSONObject().put("activity", "Activity").put("semanticControls", JSONArray().put(evidence)),
            mapOf(elementId to GatewayElement(elementId, "raw", "jane@gmail.com", "account", "text", evidence)),
            generation = 1, persona = persona)
    }

    private fun card(capture: GatewayObservation) = ObservationProjections.pageCard(capture, "", 1, true)
}
