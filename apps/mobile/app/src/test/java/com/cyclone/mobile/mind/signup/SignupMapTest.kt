package com.cyclone.mobile.mind.signup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Plan 43 T6: a sign-up map is a template. It records labels, kinds and checks, never a value the owner typed. */
class SignupMapTest {
    private fun recorder() = SignupRecorder("com.instagram.android", "Instagram", "350.0", clock = { 1_000L })

    private fun field(label: String, kind: String, vararg extra: Pair<String, Any?>): Map<String, Any?> =
        mapOf("label" to label, "kind" to kind) + extra

    private fun refused(block: () -> Unit): String = try {
        block()
        fail("expected a refusal")
        ""
    } catch (refused: SignupRecorder.Refused) {
        refused.message.orEmpty()
    }

    @Test fun anInstagramSignupBecomesAMap() {
        val r = recorder()
        r.page("Enter your mobile number or email", listOf(field("Mobile number or email", "email")), "Next", null)
        r.page("Confirm your email", listOf(field("Confirmation code", "text")), "Next", "email_code")
        r.page("What's your name?", listOf(field("Full name", "full_name")), "Next", null)
        r.page("Create a password", listOf(field("Password", "password", "hint" to "At least 6 characters")), "Next", null)
        r.page("What's your birthday?", listOf(field("Birthday", "birthday")), "Next", null)
        r.page("Create a username", listOf(field("Username", "username")), "Next", null)
        r.page("Gender", listOf(field("Gender", "gender", "choices" to listOf("Female", "Male", "Custom", "Prefer not to say"),
            "required" to false)), "Next", null)
        r.final("Sign up")
        val map = r.finish(complete = true)
        assertEquals(7, map.pages.size)
        assertEquals(listOf("email", "confirmation_code", "full_name", "password", "birthday", "username", "gender"), map.fields.map { it.key })
        assertEquals(listOf(SignupCheck.EMAIL_CODE), map.checks)
        assertFalse(map.fields.last().required)
        assertTrue(map.complete)
        val back = SignupMap.fromJson(JSONObject(map.toJson().toString()))!!
        assertEquals(map, back)
    }

    @Test fun whatWasTypedNeverEntersTheMap() {
        val r = recorder()
        r.typed("Brand One Studio")
        r.typed("brandone.official")
        assertTrue(refused { r.page("Name", listOf(field("Brand One Studio", "full_name")), "Next", null) }.contains("typed"))
        assertTrue(refused { r.page("Username", listOf(field("Username", "username", "hint" to "e.g. brandone.official")), "Next", null) }.contains("typed"))
        assertTrue(refused { r.page("Email", listOf(field("Email", "email", "hint" to "owner@example.com")), "Next", null) }.contains("value"))
        assertTrue(refused { r.page("Phone", listOf(field("Phone", "phone", "hint" to "+31612345678")), "Next", null) }.contains("value"))
        assertTrue(refused { r.page("Pw", listOf(field("Password", "password", "value" to "hunter22")), "Next", null) }.contains("never values"))
        assertTrue(refused { r.page("Pw", listOf(field("password: hunter22", "password")), "Next", null) }.contains("value"))
        assertEquals(0, r.pageCount)
    }

    @Test fun kindsChecksAndLimitsAreStrict() {
        val r = recorder()
        assertTrue(refused { r.page("x", listOf(field("Name", "nickname")), "Next", null) }.contains("kind is one of"))
        assertTrue(refused { r.page("x", listOf(field("Code", "text")), "Next", "solve_it") }.contains("check is one of"))
        assertTrue(refused { r.page("", listOf(field("Name", "text")), "Next", null) }.contains("required"))
        assertTrue(refused { r.final("Sign up") }.contains("first"))
        assertTrue(refused { r.finish(true) }.contains("Nothing"))
        r.page("Name", listOf(field("First name", "first_name"), field("First name", "first_name")), "Next", null)
        assertEquals(listOf("first_name", "first_name_2"), r.finish(false).fields.map { it.key })
    }

    @Test fun aMapStoppedBeforeTheEndIsNotComplete() {
        val r = recorder()
        r.page("Email", listOf(field("Email", "email")), "Next", null)
        assertFalse(r.finish(complete = true).complete, "no final control, so it can't be complete")
    }

    private fun assertFalse(value: Boolean, message: String) = org.junit.Assert.assertFalse(message, value)
}
