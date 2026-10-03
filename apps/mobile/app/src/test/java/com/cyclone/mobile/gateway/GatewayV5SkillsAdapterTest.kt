package com.cyclone.mobile.gateway

import com.cyclone.mobile.market.OwnerSkills
import com.cyclone.mobile.market.SkillAnchor
import com.cyclone.mobile.market.SkillGroundState
import com.cyclone.mobile.market.SkillHealth
import com.cyclone.mobile.market.SkillWaypoint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayV5SkillsAdapterTest {
    private val clock = "com.google.android.deskclock"

    @Test fun skillsListRepliesWithTitlesHealthAndMapIdsOnly() {
        val listing = OwnerSkills.draft("Set a 10 minute timer for Louella's pasta", listOf(clock))
        val anchor = SkillAnchor(clock, listOf(SkillWaypoint("$clock:alarm", "Alarm"), SkillWaypoint("$clock:timer", "Timer")), 4, 7)
        GatewayV5SkillsAdapter.skills = {
            listOf(
                GatewayV5SkillsAdapter.Row(listing, anchor, SkillHealth(SkillGroundState.GROUNDED, "1 known move to “Timer”.", 1, "s2")),
                GatewayV5SkillsAdapter.Row(OwnerSkills.draft("Check the weather", emptyList()), null, SkillHealth(SkillGroundState.NOT_GROUNDED, "Saved before skills were grounded.")),
            )
        }
        GatewayV5SkillsAdapter.learnedScreenId = { _, key -> if (key.endsWith(":alarm")) "mind-abc" else null }
        val reply = GatewayV5SkillsAdapter.dispatch("skills.list", JSONObject())
        assertFalse(reply.getBoolean("truncated"))
        val first = reply.getJSONArray("skills").getJSONObject(0)
        assertEquals(setOf("skillId", "name", "placeId", "ground", "detail", "routeMoves", "route", "finishSteps", "savedAt"), first.keys().asSequence().toSet())
        assertEquals("grounded", first.getString("ground"))
        assertEquals("package:$clock", first.getString("placeId"))
        val route = first.getJSONArray("route")
        assertTrue(route.getJSONObject(0).getString("screenId").startsWith("page:"))
        assertTrue(route.getJSONObject(1).isNull("screenId"))
        assertFalse("the goal (with a name in it) never leaves the phone", reply.toString().contains("pasta"))
        val legacy = reply.getJSONArray("skills").getJSONObject(1)
        assertEquals("not-grounded", legacy.getString("ground"))
        assertTrue(legacy.isNull("placeId"))
        assertEquals(0, legacy.getJSONArray("route").length())
    }

    @Test fun skillsListTakesNoArguments() {
        val error = runCatching { GatewayV5SkillsAdapter.dispatch("skills.list", JSONObject().put("x", 1)) }.exceptionOrNull()
        assertTrue(error is GatewayProtocolException)
    }
}
