package com.cyclone.mobile.ui.v32

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeProfilesTest {
    private fun p(key: String, label: String, current: Boolean = false, active: Boolean = false, owner: Boolean = false, ready: Boolean = true, apps: Int = 0) =
        HomeProfile(key, label, (1..apps).map { "app$it" }, ready, current, owner, active)

    @Test fun `the profile in use comes first, then working ones, then by name`() {
        val order = HomeProfiles.order(listOf(p("b", "Work"), p("a", "Anna", active = true), p("c", "Main", current = true, owner = true)))
        assertEquals(listOf("c", "a", "b"), order.map { it.key })
    }

    @Test fun `with no profiles the slider still shows this phone`() {
        assertEquals(listOf(HomeProfiles.THIS_PHONE), HomeProfiles.order(emptyList()))
    }

    @Test fun `the lines say what the profile is doing and how many apps it has`() {
        assertEquals("Profile · Setting up", HomeProfiles.kind(p("x", "X", ready = false)))
        assertEquals("Profile · Working", HomeProfiles.kind(p("x", "X", active = true)))
        assertEquals("Profile · In use", HomeProfiles.kind(p("x", "X", current = true)))
        assertEquals("Profile · Ready", HomeProfiles.kind(p("x", "X")))
        assertEquals("Your main profile", HomeProfiles.apps(p("x", "X", owner = true)))
        assertEquals("1 app", HomeProfiles.apps(p("x", "X", apps = 1)))
        assertEquals("5 apps", HomeProfiles.apps(p("x", "X", apps = 5)))
    }
}
