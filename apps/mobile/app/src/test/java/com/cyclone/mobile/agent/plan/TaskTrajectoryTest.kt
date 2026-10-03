package com.cyclone.mobile.agent.plan

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import com.cyclone.mobile.fastpath.InstalledApp
import com.cyclone.mobile.fastpath.InstalledAppInventory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTrajectoryTest {
    @Test
    fun facebookOpenSeedsLocalAppLanding() {
        val trajectory = TaskTrajectory.seed("open Facebook")
        assertEquals(TaskDifficultyTier.EASY, trajectory.tier)
        assertEquals(WaypointKind.OPEN_APP, trajectory.waypoints.first().kind)
        assertEquals("com.facebook.katana", trajectory.waypoints.first().packageName)
        assertTrue(trajectory.horizonPlanned)
    }

    @Test
    fun installedAppOpenSeedsLocalLandingWithoutAnAlias() {
        InstalledAppInventory.replace(listOf(InstalledApp("com.spotify.music", "Spotify")))
        try {
            val trajectory = TaskTrajectory.seed("open Spotify")
            assertEquals(TaskDifficultyTier.EASY, trajectory.tier)
            assertEquals(WaypointKind.OPEN_APP, trajectory.waypoints.first().kind)
            assertEquals("com.spotify.music", trajectory.waypoints.first().packageName)
        } finally {
            InstalledAppInventory.replace(emptyList())
        }
    }

    @Test
    fun unnamedHotelJobSeedsTheInstalledMapsLanding() {
        InstalledAppInventory.replace(
            listOf(InstalledApp("com.google.android.apps.maps", "Maps", 2)),
        )
        try {
            val trajectory = TaskTrajectory.seed("find a hotel close by")
            assertEquals(TaskDifficultyTier.MEDIUM, trajectory.tier)
            assertEquals(WaypointKind.LAUNCH_INTENT, trajectory.waypoints.first().kind)
            assertTrue(trajectory.waypoints.first().uri.orEmpty().startsWith("geo:"))
            assertTrue(trajectory.waypoints.any { it.kind == WaypointKind.SCENE })
        } finally {
            InstalledAppInventory.replace(emptyList())
        }
    }

    @Test
    fun loginGoalStopsForHumanAfterLanding() {
        val trajectory = TaskTrajectory.seed("open Facebook and login")
        assertEquals(TaskDifficultyTier.MEDIUM, trajectory.tier)
        assertTrue(trajectory.waypoints.any { it.kind == WaypointKind.STOP_HUMAN })
        val facebook = page("com.facebook.katana", listOf(control("Log in")))
        val advanced = trajectory.advanceIfSatisfied(facebook)
        assertEquals(WaypointKind.STOP_HUMAN, advanced.current?.kind)
        assertTrue(TaskTrajectory.looksLikeLoginWall(facebook))
    }

    @Test
    fun horizonParserKeepsDestinationsOnly() {
        val parsed = HorizonPlanner.parse(
            """{"from":"launcher","to":"WhatsApp after Gmail","waypoints":[
              {"do":"open_app","package":"com.google.android.gm","until":"app_foreground","summary":"Open Gmail"},
              {"do":"scene","until":"goal_contract","summary":"Copy the receipt"},
              {"do":"open_app","package":"com.whatsapp","until":"app_foreground","summary":"Open WhatsApp"},
              {"do":"stop_human","until":"login_wall","summary":"Stop if WhatsApp needs sign-in"}
            ]}""",
            "open Gmail then send this to WhatsApp",
            "launcher",
        )!!
        assertEquals(TaskDifficultyTier.HARD, parsed.tier)
        assertEquals(4, parsed.waypoints.size)
        assertEquals(WaypointKind.OPEN_APP, parsed.waypoints[0].kind)
        assertEquals("com.whatsapp", parsed.waypoints[2].packageName)
        assertTrue(parsed.horizonPlanned)
    }

    @Test
    fun namedMultiAppSeedsLocalHardPlanWithoutWaitingForAModel() {
        val trajectory = TaskTrajectory.seed("open Gmail then send this to WhatsApp")
        assertEquals(TaskDifficultyTier.HARD, trajectory.tier)
        assertTrue(trajectory.horizonPlanned)
        assertEquals(2, trajectory.waypoints.count { it.kind == WaypointKind.OPEN_APP })
        assertEquals("com.google.android.gm", trajectory.waypoints.first { it.kind == WaypointKind.OPEN_APP }.packageName)
        val gmailScene = trajectory.waypoints.first { it.kind == WaypointKind.SCENE }
        assertEquals(DestinationAuthority.UNTIL_DESTINATION_READY, gmailScene.until)
        assertEquals("com.google.android.gm", gmailScene.packageName)
    }

    private fun control(label: String) = PageControl(
        key = label.lowercase(),
        label = label,
        semanticName = label.lowercase(),
        role = "button",
        selector = JSONObject(),
        androidActions = listOf("ACTION_CLICK"),
        risk = ActionRisk.SAFE,
    )

    private fun page(packageName: String, controls: List<PageControl>) = PageContext(
        pageKey = "$packageName:page",
        packageName = packageName,
        className = null,
        title = packageName,
        structuralKey = "s",
        contentKey = "c",
        controls = controls,
        observationCount = 1,
        firstSeenAt = 0,
        lastSeenAt = 0,
    )
}
