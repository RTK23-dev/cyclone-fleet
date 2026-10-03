package com.cyclone.mobile.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OwnerSkillsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aRunsGoalBecomesTheOwnersRecipeAndSavingTwiceKeepsOne() {
        val skills = OwnerSkills(folder.root.resolve("owner-skills.json"))
        val first = skills.save("Open my calculator and work out 12 times 7", listOf("com.google.android.calculator"))
        val again = skills.save("  open my calculator and work out 12 times 7 ", emptyList())
        assertEquals(first.id, again.id)
        assertEquals(1, skills.list().size)
        val saved = skills.list().single()
        assertTrue(saved.id.startsWith("you."))
        assertEquals("You", saved.publisher.name)
        assertFalse(saved.publisher.verified)
        assertEquals(OwnerSkills.CATEGORY, saved.category)
        assertEquals("Open my calculator and work out…", saved.name)
        assertEquals(listOf("com.google.android.calculator"), saved.apps)
        assertEquals("Open my calculator and work out 12 times 7", MarketRules.fill(saved, emptyMap()))
        assertTrue(skills.remove(saved.id))
        assertTrue(skills.list().isEmpty())
    }

    @Test fun secretShapedGoalsAreNeverSaved() {
        val skills = OwnerSkills(folder.root.resolve("owner-skills.json"))
        val error = runCatching { skills.save("Log in with password hunter2", emptyList()) }.exceptionOrNull()
        assertTrue(error is MarketError)
        assertEquals("Goals that mention secrets are not saved as skills.", error!!.message)
        assertTrue(skills.list().isEmpty())
    }

    @Test fun bracesInAGoalStayLiteralInsteadOfBecomingInputs() {
        val listing = OwnerSkills.draft("Note {groceries} in Keep", emptyList())
        assertEquals("Note (groceries) in Keep", listing.goal)
        MarketRules.validate(listing)
    }
}
