package com.cyclone.mobile.agent.plan

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskDifficultyEscalatorTest {
    @Test
    fun easyStaysEasyForNamedOpen() {
        assertEquals(
            TaskDifficultyTier.EASY,
            TaskDifficultyEscalator.next(
                TaskDifficultyTier.EASY,
                "open Facebook",
                page("com.facebook.katana"),
                setOf("com.facebook.katana"),
                0,
            ),
        )
    }

    @Test
    fun twoUserAppsPromoteToHard() {
        assertEquals(
            TaskDifficultyTier.HARD,
            TaskDifficultyEscalator.next(
                TaskDifficultyTier.MEDIUM,
                "open Gmail then send this to WhatsApp",
                page("com.whatsapp"),
                setOf("com.google.android.gm", "com.whatsapp"),
                0,
            ),
        )
    }

    @Test
    fun launcherChurnDoesNotPromote() {
        assertEquals(
            TaskDifficultyTier.MEDIUM,
            TaskDifficultyEscalator.next(
                TaskDifficultyTier.MEDIUM,
                "DM Jacob on Instagram that I am late",
                page("com.instagram.android"),
                setOf("com.instagram.android", "com.google.android.apps.nexuslauncher"),
                1,
            ),
        )
    }

    @Test
    fun noProgressOnATwoAppGoalPromotesToHard() {
        assertEquals(
            TaskDifficultyTier.HARD,
            TaskDifficultyEscalator.next(
                TaskDifficultyTier.MEDIUM,
                "open Gmail then WhatsApp",
                page("com.google.android.gm"),
                setOf("com.google.android.gm"),
                2,
            ),
        )
    }

    @Test
    fun chromeCustomTabDoesNotPromoteAOneAppTask() {
        assertEquals(
            TaskDifficultyTier.MEDIUM,
            TaskDifficultyEscalator.next(
                TaskDifficultyTier.MEDIUM,
                "DM Jacob on Instagram that I am late",
                page("com.instagram.android"),
                setOf("com.instagram.android", "com.android.chrome"),
                1,
            ),
        )
    }

    @Test
    fun facebookLiteDoesNotCountAsASecondApp() {
        assertEquals(
            TaskDifficultyTier.EASY,
            TaskDifficultyEscalator.next(
                TaskDifficultyTier.EASY,
                "open Facebook",
                page("com.facebook.lite"),
                setOf("com.facebook.katana", "com.facebook.lite"),
                0,
            ),
        )
    }

    private fun page(packageName: String) = PageContext(
        pageKey = "$packageName:page",
        packageName = packageName,
        className = null,
        title = packageName,
        structuralKey = "s",
        contentKey = "c",
        controls = listOf(
            PageControl(
                key = "home",
                label = "Home",
                semanticName = "home",
                role = "button",
                selector = JSONObject(),
                androidActions = listOf("ACTION_CLICK"),
                risk = ActionRisk.SAFE,
            ),
        ),
        observationCount = 1,
        firstSeenAt = 0,
        lastSeenAt = 0,
    )
}
