package com.cyclone.mobile.agent.plan

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationAuthorityTest {
    private val goal =
        "Open Gmail and find my current logged-in email, then go to Chrome and log in to Facebook using that email."

    @Test
    fun gmailThenFacebookPlanIsDestinationScopedNotWholeGoal() {
        val plan = TaskDifficulty.hardWaypoints(goal)!!
        val gmailScene = plan.first { it.kind == WaypointKind.SCENE }
        assertEquals("com.google.android.gm", gmailScene.packageName)
        assertEquals(DestinationAuthority.UNTIL_ACCOUNT_OBSERVED, gmailScene.until)
        assertEquals("Finding the signed-in email address", gmailScene.summary)
        assertFalse(plan.filter { it.kind == WaypointKind.SCENE }.any { it.until == "goal_contract" })

        val launch = plan.first { it.kind == WaypointKind.LAUNCH_INTENT }
        assertEquals("https://facebook.com", launch.uri)
        assertEquals("com.android.chrome", launch.packageName)
        assertEquals(DestinationAuthority.UNTIL_HOST_VISIBLE, launch.until)

        val stop = plan.first { it.kind == WaypointKind.STOP_HUMAN }
        assertEquals(DestinationAuthority.UNTIL_LOGIN_WALL, stop.until)
        assertEquals("com.android.chrome", stop.packageName)
        assertEquals("https://facebook.com", stop.uri)

        assertTrue(plan.none { it.packageName == "com.facebook.katana" })
        assertEquals(WaypointKind.DONE, plan.last().kind)
    }

    @Test
    fun openingGmailIsNotEmailEvidence() {
        val trajectory = TaskTrajectory.seed(goal)
        val gmailInbox = page(
            "com.google.android.gm",
            listOf(control("Compose"), control("Inbox"), control("Primary")),
        )
        val advanced = trajectory.advanceIfSatisfied(gmailInbox)
        assertEquals(WaypointKind.SCENE, advanced.current?.kind)
        assertEquals(DestinationAuthority.UNTIL_ACCOUNT_OBSERVED, advanced.current?.until)
        assertEquals(2, advanced.index)
    }

    @Test
    fun oneVisibleEmailAdvancesGmailAndLandsFacebookInChrome() {
        val trajectory = TaskTrajectory.seed(goal)
        val gmail = page(
            "com.google.android.gm",
            listOf(control("Compose"), control("Google Account: Ada ada@gmail.com")),
        )
        val afterGmail = trajectory.advanceIfSatisfied(gmail)
        assertEquals(WaypointKind.LAUNCH_INTENT, afterGmail.current?.kind)
        assertEquals("https://facebook.com", afterGmail.current?.uri)
        assertEquals("com.android.chrome", afterGmail.current?.packageName)
        val hint = DestinationAuthority.landingHint(afterGmail.current!!)!!
        assertEquals("phone.launch_intent", hint.tool)
        assertEquals("https://facebook.com", hint.uri)
        assertEquals("com.android.chrome", hint.packageName)

        val sighting = DestinationAuthority.visibleEmails(gmail)
        assertTrue(sighting.unique)
        assertEquals("a***@gmail.com", sighting.maskedSingle)
        assertEquals("ada@gmail.com", sighting.rawSingle)
    }

    @Test
    fun twoVisibleEmailsDoNotAdvanceAndAreAmbiguous() {
        val trajectory = TaskTrajectory.seed(goal)
        val gmail = page(
            "com.google.android.gm",
            listOf(control("ada@gmail.com"), control("other@gmail.com"), control("Compose")),
        )
        val advanced = trajectory.advanceIfSatisfied(gmail)
        assertEquals(WaypointKind.SCENE, advanced.current?.kind)
        assertTrue(DestinationAuthority.visibleEmails(gmail).ambiguous)
        assertEquals(2, DestinationAuthority.visibleEmails(gmail).count)
    }

    @Test
    fun gmailSignedInDoesNotSkipFacebookLoginWall() {
        val trajectory = TaskTrajectory.seed(goal)
        val gmailWithEmail = page(
            "com.google.android.gm",
            listOf(control("Compose"), control("ada@gmail.com"), control("Sign out")),
        )
        val afterGmail = trajectory.advanceIfSatisfied(gmailWithEmail)
        val stillGmail = afterGmail.advanceIfSatisfied(gmailWithEmail)
        assertEquals(WaypointKind.LAUNCH_INTENT, stillGmail.current?.kind)

        val facebookLogin = page(
            "com.android.chrome",
            listOf(control("Log in"), control("Email or phone"), control("Password")),
            title = "Facebook – log in or sign up",
        )
        val atFacebook = stillGmail.advanceIfSatisfied(facebookLogin)
        assertEquals(WaypointKind.STOP_HUMAN, atFacebook.current?.kind)
        assertTrue(TaskTrajectory.looksLikeLoginWall(facebookLogin))
        assertFalse(TaskTrajectory.satisfied(atFacebook.current!!, facebookLogin))
        assertTrue(DestinationAuthority.loginHandoffActive(atFacebook))
    }

    @Test
    fun alreadySignedInFacebookSkipsTheLoginWall() {
        val trajectory = TaskTrajectory.seed(goal).copy(index = 5)
        assertEquals(WaypointKind.STOP_HUMAN, trajectory.current?.kind)
        val signedIn = page(
            "com.android.chrome",
            listOf(control("Home"), control("Log out")),
            title = "Facebook",
        )
        val advanced = trajectory.advanceIfSatisfied(signedIn)
        assertEquals(WaypointKind.DONE, advanced.current?.kind)
    }

    @Test
    fun nativeFacebookOpenIsUnchanged() {
        val assessment = TaskDifficulty.assess("open Facebook and login")
        assertEquals(1, assessment.destinationCount)
        assertEquals("com.facebook.katana", assessment.destinations.single().value)
        val trajectory = TaskTrajectory.seed("open Facebook and login")
        assertTrue(trajectory.waypoints.any { it.kind == WaypointKind.STOP_HUMAN })
        assertTrue(trajectory.waypoints.none { it.uri.orEmpty().contains("facebook.com") })
    }

    @Test
    fun placeholderEmailsAreIgnored() {
        val gmail = page(
            "com.google.android.gm",
            listOf(control("email@example.com"), control("Compose")),
        )
        assertTrue(DestinationAuthority.visibleEmails(gmail).missing)
        assertNull(DestinationAuthority.landingHint(TaskWaypoint(WaypointKind.SCENE, until = "account_observed", summary = "x")))
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

    private fun page(packageName: String, controls: List<PageControl>, title: String = packageName) = PageContext(
        pageKey = "$packageName:page",
        packageName = packageName,
        className = null,
        title = title,
        structuralKey = "s",
        contentKey = "c",
        controls = controls,
        observationCount = 1,
        firstSeenAt = 0,
        lastSeenAt = 0,
    )
}
