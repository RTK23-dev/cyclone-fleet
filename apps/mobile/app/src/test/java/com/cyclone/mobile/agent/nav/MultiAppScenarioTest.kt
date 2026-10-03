package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.agent.plan.TaskTrajectory
import com.cyclone.mobile.agent.plan.WaypointKind
import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixed operator sentences. Extended with execution/proof assertions as navigation is built. */
class MultiAppScenarioTest {
    private class FakeRun(goal: String, available: (String) -> Boolean? = { true }) {
        val run = ClauseRun(goal, ClauseCompiler.compile(goal, available))
        val ledger = TaskLedger(0)
        var time = 10L
        var screen: NavigationScreen? = null
        var mutations = 0
        fun see(page: PageContext, place: String = "package:${page.packageName}", emailMatches: Boolean = false) {
            val observed = NavigationScreen(place, "screen:account:aaaaaaaaaaaaaaaa", page, "observation-${time}", time++, signupEmailMatches = emailMatches)
            screen = observed
            if (run.current?.capability == NavCapability.FIND_SIGNED_IN_IDENTITY) {
                LiveTaskFacts.signedInEmails(page).singleOrNull()?.let {
                    ledger.record("signed-in-email", it, place, observed.roomId, observed.persona, observed.readAtMs)
                }
            }
            run.observe(observed, ledger)
        }
        fun act(tool: String, target: PageControl): String? {
            val boundary = NavigationActionPolicy.boundary(run.current, tool, target, screen)
                ?: com.cyclone.mobile.policy.GateClassifier.classify(tool, listOf(target.label))?.name
            if (boundary == null) mutations++
            return boundary
        }
    }

    @Test
    fun everyScenarioPreservesTheOriginalSentence() {
        SCENARIOS.forEach { goal -> assertEquals(goal, TaskTrajectory.seed(goal).to) }
    }

    @Test
    fun openingGmailDoesNotCompleteTheMultiAppRequest() {
        val trajectory = TaskTrajectory.seed(SCENARIOS[1]).advanceIfSatisfied(
            page("com.google.android.gm", "Inbox", "Compose", "Search mail"),
        )
        assertFalse(trajectory.current == null)
        assertFalse(trajectory.current?.kind == WaypointKind.DONE)
    }

    @Test fun gmailIdentityRequiresLiveAccountProof() {
        val fake = FakeRun(SCENARIOS[0])
        fake.see(page("com.google.android.gm", "Inbox", "sender@gmail.com", "Compose"))
        assertFalse(fake.run.complete)
        fake.see(page("com.google.android.gm", "Account", "Manage your Google Account", "jane@gmail.com"))
        assertTrue(fake.run.complete)
        assertEquals("jane@gmail.com", fake.ledger.get("signed-in-email")!!.value)
        assertEquals(SCENARIOS[0], fake.run.goal)
    }

    @Test fun gmailThenInstagramCarriesIdentityAndStopsBeforeSubmission() {
        val fake = FakeRun(SCENARIOS[1])
        assertEquals(listOf(NavCapability.FIND_SIGNED_IN_IDENTITY, NavCapability.CREATE_ACCOUNT), fake.run.clauses().map { it.capability })
        fake.see(page("com.google.android.gm", "Account", "Manage your Google Account", "jane@gmail.com"))
        assertEquals(1, fake.run.index)
        assertEquals("chrome:https://instagram.com", fake.run.current!!.place)
        val form = page("com.android.chrome", "Instagram", "Sign up").copy(controls = listOf(field("Email"), button("Sign up")))
        fake.see(form, "chrome:https://instagram.com")
        assertEquals(ClauseStatus.ACTIVE, fake.run.current!!.status)
        fake.see(form, "chrome:https://instagram.com", emailMatches = true)
        assertEquals(ClauseStatus.NEEDS_APPROVAL, fake.run.current!!.status)
        assertEquals("account-submit", fake.act("phone.click", button("Sign up")))
        assertEquals(0, fake.mutations)
        assertFalse(fake.run.complete)
        assertFalse(fake.run.toJson().toString().contains("jane@gmail.com"))
    }

    @Test fun fiveMinuteTimerNeedsRunningCountdown() {
        val fake = FakeRun(SCENARIOS[2])
        // Clock clauses are proven in whichever clock app the phone has, so they carry no fixed package.
        assertEquals(NavCapability.SET_TIMER, fake.run.current!!.capability)
        val pkg = "com.google.android.deskclock"
        fake.see(page(pkg, "Timer", "5:00", "Start"))
        assertFalse(fake.run.complete)
        fake.see(page(pkg, "Timer", "4:59", "Pause"))
        assertTrue(fake.run.complete)
    }

    @Test fun missingNativeFacebookUsesChromeAndRequiresTheNamedThread() {
        val fake = FakeRun(SCENARIOS[3]) { it != "com.facebook.katana" }
        assertEquals("chrome:https://facebook.com", fake.run.current!!.place)
        assertEquals("Louella", fake.run.current!!.target)
        fake.see(page("com.android.chrome", "Facebook", "Log in", "Password"), "chrome:https://www.facebook.com")
        assertFalse(fake.run.complete)
        assertEquals("needs-secret", fake.act("phone.type", field("Password")))
        fake.see(page("com.android.chrome", "Search results", "Louella"), "chrome:https://www.facebook.com")
        assertFalse(fake.run.complete)
        fake.see(page("com.android.chrome", "Louella").copy(controls = listOf(field("Message"))), "chrome:https://www.facebook.com")
        assertTrue(fake.run.complete)
    }

    @Test fun settingsWifiReadsConnectedNetworkNotNearbyNetworks() {
        val fake = FakeRun(SCENARIOS[4])
        assertEquals(listOf(NavCapability.OPEN_PLACE, NavCapability.OPEN_WIFI, NavCapability.READ_NETWORK), fake.run.clauses().map { it.capability })
        fake.see(page("com.android.settings", "Settings", "Wi-Fi"))
        assertEquals(1, fake.run.index)
        fake.see(page("com.android.settings", "Wi-Fi", "Neighbour network"))
        assertEquals(2, fake.run.index)
        fake.see(page("com.android.settings", "Wi-Fi", "Neighbour network"))
        assertFalse(fake.run.complete)
        fake.see(page("com.android.settings", "Wi-Fi", "HomeNetwork, Connected", "Neighbour network"))
        assertTrue(fake.run.complete)
        assertEquals("HomeNetwork", fake.ledger.get("connected-network")!!.value)
    }

    @Test fun wrongOriginCannotProveSignupAndCanonicalConsequencesStayGated() {
        val fake = FakeRun(SCENARIOS[1])
        fake.see(page("com.google.android.gm", "Account", "Manage your Google Account", "jane@gmail.com"))
        fake.see(page("com.android.chrome", "Instagram", "Sign up"), "chrome:https://instagram.com.evil.test", emailMatches = true)
        assertFalse(fake.run.complete)
        assertEquals(ClauseStatus.ACTIVE, fake.run.current!!.status)
        assertEquals("PAY", fake.act("phone.click", button("Pay now")))
        assertEquals("SEND", fake.act("phone.click", button("Send")))
        assertEquals(0, fake.mutations)
    }

    private fun field(label: String) = button(label).copy(role = "textbox", selector = JSONObject().put("editable", true))
    private fun button(label: String) = PageControl("target", label, label, "button", JSONObject(), listOf("ACTION_CLICK"), ActionRisk.SAFE)

    companion object {
        val SCENARIOS = listOf(
            "open Gmail and tell me which Gmail I am logged in with",
            "open Gmail and check which Gmail I am logged in with, then open Chrome and go to instagram.com sign-up with that email",
            "open the clock app and set a timer for 5 minutes",
            "find the DM of Louella on Facebook",
            "open Settings, then Wi-Fi, then tell me the connected network name",
        )

        fun page(packageName: String, title: String, vararg labels: String) = PageContext(
            pageKey = "$packageName:$title", packageName = packageName, className = null,
            title = title, structuralKey = title, contentKey = labels.joinToString("|"),
            controls = labels.mapIndexed { index, label -> PageControl(
                key = "e$index", label = label, semanticName = label, role = "text",
                selector = JSONObject().put("elementId", "e$index"), androidActions = emptyList(),
                risk = ActionRisk.SAFE,
            ) }, observationCount = 1, firstSeenAt = 1, lastSeenAt = 1,
        )
    }
}
