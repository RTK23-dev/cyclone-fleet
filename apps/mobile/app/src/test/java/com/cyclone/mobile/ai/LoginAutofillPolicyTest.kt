package com.cyclone.mobile.ai

import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentPageCard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginAutofillPolicyTest {
    @Test
    fun loginWallAsksTheUserUntilAutofillIsAuthorized() {
        val card = loginPage()
        val policy = LoginAutofillPolicy()
        val asked = policy.evaluate(card, authorized = false)
        assertEquals(LoginAutofillOutcome.ASK_USER, asked.outcome)
        assertEquals("login.ask_user", asked.reason)
        val focused = policy.evaluate(card, authorized = true)
        assertEquals(LoginAutofillOutcome.FOCUS_FIELD, focused.outcome)
        assertEquals("Email or phone", focused.target?.label)
    }

    @Test
    fun authorizedBurstFocusesThenSubmitsWithoutStoringSecrets() {
        val policy = LoginAutofillPolicy()
        val first = loginPage()
        assertEquals("Email or phone", policy.evaluate(first, true).target?.label)
        val second = loginPage("obs-2")
        assertEquals("Password", policy.evaluate(second, true).target?.label)
        val third = loginPage("obs-3")
        val submit = policy.evaluate(third, true)
        assertEquals(LoginAutofillOutcome.SUBMIT, submit.outcome)
        assertEquals("Log in", submit.target?.label)
        assertFalse(submit.reason.contains("password", ignoreCase = true) && submit.reason.contains("="))
    }

    @Test
    fun resetAfterHumanHandoffRestartsAutofillFromFreshUsernameField() {
        val policy = LoginAutofillPolicy()
        assertEquals("Email or phone", policy.evaluate(loginPage("obs-1"), true).target?.label)
        assertEquals("Password", policy.evaluate(loginPage("obs-2"), true).target?.label)

        policy.reset()

        assertEquals("Email or phone", policy.evaluate(loginPage("obs-3"), true).target?.label)
    }

    @Test
    fun signupGoalSuppressesAutofillEvenWhenLoginControlsAreAlsoVisible() {
        val base = loginPage()
        val card = base.copy(
            controls = base.controls + control("signup", "Create new account", "button"),
        )

        assertTrue(LoginAutofillPolicy.isLoginWall(card))
        assertFalse(LoginAutofillPolicy.shouldHandle("open Facebook and make an account using my email", card))
        assertEquals(
            LoginAutofillOutcome.NOT_APPLICABLE,
            LoginAutofillPolicy().evaluate(card, authorized = true, goal = "sign up for Facebook").outcome,
        )
    }

    @Test
    fun explicitLoginStillUsesAutofillWhenCreateAccountLinkIsSecondary() {
        val base = loginPage()
        val card = base.copy(
            controls = base.controls + control("signup", "Create new account", "button"),
        )

        assertTrue(LoginAutofillPolicy.shouldHandle("log in to Facebook", card))
        assertEquals(
            LoginAutofillOutcome.FOCUS_FIELD,
            LoginAutofillPolicy().evaluate(card, authorized = true, goal = "log in to Facebook").outcome,
        )
    }

    @Test
    fun primaryRegistrationFormIsNotTreatedAsLoginWall() {
        val obs = "obs"
        val card = loginPage().copy(
            pageText = JSONObject().put("text", "Create your account"),
            controls = listOf(
                control("email", "Email", "edittext", obs, JSONObject().put("editable", true).put("enabled", true).put("visibleToUser", true)),
                control("pass", "Password", "edittext", obs, JSONObject().put("editable", true).put("password", true).put("enabled", true).put("visibleToUser", true)),
                control("signup", "Sign up", "button", obs, JSONObject().put("clickable", true).put("enabled", true).put("visibleToUser", true)),
            ),
        )

        assertFalse(LoginAutofillPolicy.isLoginWall(card))
        assertFalse(LoginAutofillPolicy.shouldHandle("continue", card))
    }

    @Test
    fun signedInHomeIsNotALoginWall() {
        val card = loginPage().copy(
            controls = listOf(
                control("home", "Home", "button"),
                control("out", "Log out", "button"),
            ),
        )
        assertFalse(LoginAutofillPolicy.isLoginWall(card))
        assertEquals(LoginAutofillOutcome.NOT_APPLICABLE, LoginAutofillPolicy().evaluate(card, true).outcome)
    }

    @Test
    fun cookieBannerIsNotAutofill() {
        val reject = control("reject", "Reject", "button")
        val card = loginPage().copy(
            pageText = JSONObject().put("text", "We use cookies"),
            controls = listOf(reject),
        )
        assertFalse(LoginAutofillPolicy.isLoginWall(card))
    }

    private fun loginPage(obs: String = "obs") = AgentPageCard(
        obs, 1, true, 1, "com.facebook.katana", null, "page-$obs", "structure", "content", "fp",
        JSONObject(), JSONObject().put("text", "Log in to Facebook"), JSONObject(),
        listOf(
            control("email", "Email or phone", "edittext", obs, JSONObject().put("editable", true).put("enabled", true).put("visibleToUser", true)),
            control("pass", "Password", "edittext", obs, JSONObject().put("editable", true).put("password", true).put("enabled", true).put("visibleToUser", true)),
            control("submit", "Log in", "button", obs, JSONObject().put("clickable", true).put("enabled", true).put("visibleToUser", true)),
        ),
        JSONArray(),
    )

    private fun control(
        id: String,
        label: String,
        role: String,
        obs: String = "obs",
        evidence: JSONObject = JSONObject().put("enabled", true).put("visibleToUser", true).put("clickable", true),
    ) = AgentElementCandidate(
        "semantic:$obs:$id",
        obs,
        label,
        label.lowercase(),
        role,
        "semantic",
        1.0,
        evidence,
    )
}
