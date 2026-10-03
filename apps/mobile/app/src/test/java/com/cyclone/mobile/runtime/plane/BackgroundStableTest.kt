package com.cyclone.mobile.runtime.plane

import com.cyclone.mobile.runtime.background.WorkspaceCommands
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 28: background work that stays working. */
class BackgroundStableTest {
    private val stack = """
        RootTask id=31 bounds=[0,0][1080,2400] displayId=7 userId=0
          taskId=31: com.whatsapp/.HomeActivity bounds=[0,0][1080,2400] userId=0 visible=true
        RootTask id=33 bounds=[0,0][1080,2400] displayId=7 userId=0
          taskId=33: com.whatsapp/.Conversation bounds=[0,0][1080,2400] userId=0 visible=false
        RootTask id=12 bounds=[0,0][1080,2400] displayId=0 userId=0
          taskId=12: com.google.android.apps.nexuslauncher/.NexusLauncherActivity userId=0 visible=true
        RootTask id=20 bounds=[0,0][1080,2400] displayId=0 userId=0
          taskId=20: com.whatsapp/.Share userId=0 visible=false
    """.trimIndent()

    @Test fun theTaskParserKnowsWhatIsShown() {
        val tasks = WorkspaceCommands.tasks(stack)
        assertEquals(listOf(true, false, true, false), tasks.map { it.visible })
        // Unknown visibility counts as shown: it may be the owner's.
        assertTrue(WorkspaceCommands.tasks("RootTask id=1 displayId=0\n  taskId=4: com.a.b/.X userId=0").single().visible)
    }

    @Test fun appsWithSeveralTasksKeepWorkingInTheBackground() {
        val tasks = WorkspaceCommands.tasks(stack)
        // A leftover task in Recents and a second task on Cyclone's display used to fail every status check.
        val owned = WorkspaceCommands.ownedTask(tasks, "com.whatsapp", 7, sharedWithOwner = false)
        assertEquals(31, owned.taskId)
    }

    @Test fun theOwnerOpeningTheAppStillWins() {
        val opened = WorkspaceCommands.tasks(stack.replace("com.whatsapp/.Share userId=0 visible=false", "com.whatsapp/.Share userId=0 visible=true"))
        val refused = assertThrows(IllegalStateException::class.java) { WorkspaceCommands.ownedTask(opened, "com.whatsapp", 7, false) }
        assertTrue(refused.message!!.startsWith("FOREGROUND_REQUIRED"))
        // A second window the owner also has open is allowed only when the session was opened that way.
        assertEquals(31, WorkspaceCommands.ownedTask(opened, "com.whatsapp", 7, sharedWithOwner = true).taskId)
    }

    @Test fun anAppThatLeftTheBackgroundScreenIsNamed() {
        val gone = assertThrows(IllegalStateException::class.java) {
            WorkspaceCommands.ownedTask(WorkspaceCommands.tasks(stack), "com.whatsapp", 9, false)
        }
        assertTrue(gone.message!!.startsWith("TASK_GONE"))
        assertEquals(BackgroundFailure.PLANE_BROKEN, BackgroundFailure.classify("Failed: Background screen: ${gone.message}."))
    }

    @Test fun movingOffTheMainScreenTakesTheShownTaskFirst() {
        val tasks = WorkspaceCommands.tasks("""
            RootTask id=40 displayId=0 userId=0
              taskId=40: com.android.chrome/.Doc userId=0 visible=false
            RootTask id=41 displayId=0 userId=0
              taskId=41: com.android.chrome/.Main userId=0 visible=true
        """.trimIndent())
        assertEquals(41, WorkspaceCommands.mainTask(tasks, "com.android.chrome")!!.taskId)
        assertEquals(40, WorkspaceCommands.mainTask(tasks.take(1), "com.android.chrome")!!.taskId)
        assertNull(WorkspaceCommands.mainTask(tasks, "com.whatsapp"))
    }

    @Test fun onlyStepsThatTrulyNeedTheScreenMoveTheTask() {
        fun kind(text: String) = BackgroundFailure.classify(text)
        // Ordinary misses stay in the background (before, every failure moved the task to the owner's screen).
        assertEquals(BackgroundFailure.ORDINARY, kind("Not done: the screen changed. Background screen: STALE_OBSERVATION: observe this workspace again"))
        assertEquals(BackgroundFailure.ORDINARY, kind("Failed: Background screen: ACTION_FAILED."))
        assertEquals(BackgroundFailure.ORDINARY, kind("Not allowed: Background screen: POLICY_DENIED: GATE human review is required."))
        assertEquals(BackgroundFailure.NEEDS_SCREEN, kind("Failed: Background screen: UNSUPPORTED: a grounded control is required."))
        assertEquals(BackgroundFailure.OWNER_HAS_APP, kind("Background screen: FOREGROUND_REQUIRED: app moved to the human display"))
        assertEquals(BackgroundFailure.AUTHORITY_LOST, kind("Background screen: STALE_SESSION: workspace input authority expired"))
        assertEquals(BackgroundFailure.AUTHORITY_LOST, kind("The owner has control: HUMAN_HAS_CONTROL"))
        assertEquals(BackgroundFailure.LOCKED, kind("Background screen: SCREEN_LOCKED: unlock and resume the task"))
        assertEquals(BackgroundFailure.PLANE_BROKEN, kind("Background screen: DISPLAY_GONE"))
        assertEquals(BackgroundFailure.PLANE_BROKEN, kind("Background screen: STALE_SESSION: workspace is unavailable"))
    }

    private fun report(vararg results: Pair<CheckStep, CheckResult>, at: Long = 1_000L, version: Long = 187L) =
        BackgroundCheckReport(at, version, "com.android.settings",
            results.map { (step, result) -> CheckStepResult(step, result, "detail", 5) }, "accessibility")

    @Test fun theBackgroundCheckReportsTheFirstFailedStepInPlainWords() {
        val failed = report(CheckStep.SERVICE to CheckResult.PASSED, CheckStep.SCREEN to CheckResult.PASSED,
            CheckStep.READ to CheckResult.FAILED, CheckStep.ACT to CheckResult.SKIPPED)
        assertFalse(failed.passed)
        assertEquals(CheckStep.READ, failed.failure!!.step)
        assertTrue(failed.headline.contains("Cyclone can read the app there"))
        val passed = report(CheckStep.SERVICE to CheckResult.PASSED, CheckStep.CLOSE to CheckResult.PASSED)
        assertTrue(passed.passed)
        assertTrue(passed.headline.startsWith("Background check passed"))
        // It survives being stored.
        assertEquals(failed, BackgroundCheckReport.fromJson(JSONObject(failed.toJson().toString())))
    }

    @Test fun aFailedEngineCheckKeepsAutomaticOnTheScreenForThisBuildOnly() {
        val engine = report(CheckStep.SERVICE to CheckResult.PASSED, CheckStep.ACT to CheckResult.FAILED)
        assertTrue(BackgroundCheckReport.blocks(engine, 187, 2_000)!!.contains("gestures do not reach"))
        // A newer build, or a week later, is checked again rather than trusted.
        assertNull(BackgroundCheckReport.blocks(engine, 188, 2_000))
        assertNull(BackgroundCheckReport.blocks(engine, 187, 1_000 + BackgroundCheckReport.TRUST_MS + 1))
        // Setup steps are the capability's job (it already names the fix), and a pass blocks nothing.
        assertNull(BackgroundCheckReport.blocks(report(CheckStep.HELPER to CheckResult.FAILED), 187, 2_000))
        assertNull(BackgroundCheckReport.blocks(report(CheckStep.CLOSE to CheckResult.PASSED), 187, 2_000))
        assertNull(BackgroundCheckReport.blocks(null, 187, 2_000))
    }

    @Test fun everyEngineStepSaysWhatToDo() {
        CheckStep.entries.forEach { assertTrue(it.name, it.advice.isNotBlank() && it.label.isNotBlank()) }
        assertEquals(listOf(CheckStep.ANDROID, CheckStep.HELPER, CheckStep.CONTROL), CheckStep.entries.filterNot { it.engine })
    }
}
