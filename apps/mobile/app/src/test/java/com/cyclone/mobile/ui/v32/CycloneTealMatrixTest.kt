package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.runtime.background.WorkspaceTaskUi
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneTealMatrixTest {
    private fun task(id: String, phase: TaskPhase, goal: String = "Open Instagram and check my login") =
        WorkspaceTaskUi(taskId = id, app = "Instagram", packageName = "com.instagram.android", goal = goal, phase = phase)

    @Test fun recentActivityKeepsNewestFirstAndUpdatesInPlace() {
        var items = CycloneRecentActivity.merge(emptyList(), task("a", TaskPhase.WORKING), nowMs = 1_000)
        items = CycloneRecentActivity.merge(items, task("b", TaskPhase.WORKING), nowMs = 2_000)
        items = CycloneRecentActivity.merge(items, task("a", TaskPhase.DONE), nowMs = 3_000)

        assertEquals(listOf("a", "b"), items.map { it.taskId })
        assertEquals(CycloneTaskVisualState.DONE, items.first().state)
        assertEquals(3_000, items.first().updatedAtMs)
    }

    @Test fun recentActivityDropsUserStoppedTasksAndCapsTheList() {
        var items = emptyList<RecentActivityItem>()
        repeat(CycloneRecentActivity.LIMIT + 3) { items = CycloneRecentActivity.merge(items, task("t$it", TaskPhase.DONE), it.toLong()) }
        assertEquals(CycloneRecentActivity.LIMIT, items.size)
        assertEquals("t${CycloneRecentActivity.LIMIT + 2}", items.first().taskId)

        items = CycloneRecentActivity.merge(items, task(items.first().taskId, TaskPhase.STOPPED), 99)
        assertFalse(items.any { it.taskId == "t${CycloneRecentActivity.LIMIT + 2}" })
    }

    @Test fun recentActivityStatusCopyIsCalmAndRelative() {
        val done = CycloneRecentActivity.merge(emptyList(), task("a", TaskPhase.DONE), nowMs = 0).first()
        assertEquals("Completed · 2m ago", CycloneRecentActivity.statusLine(done, nowMs = 125_000))
        val running = CycloneRecentActivity.merge(emptyList(), task("b", TaskPhase.WORKING), nowMs = 0).first()
        assertEquals("In progress", CycloneRecentActivity.statusLine(running, nowMs = 9_000_000))
        val needs = CycloneRecentActivity.merge(emptyList(), task("c", TaskPhase.HUMAN), nowMs = 0).first()
        assertEquals("Needs you · just now", CycloneRecentActivity.statusLine(needs, nowMs = 10_000))
        assertEquals("3h ago", CycloneRecentActivity.relativeAge(3 * 60 * 60_000L + 5))
        assertEquals("2d ago", CycloneRecentActivity.relativeAge(49 * 60 * 60_000L))
    }

    @Test fun recentActivityIsNeverPersisted() {
        val recorder = source("CycloneRecentActivity.kt")
        assertFalse(recorder.contains("SharedPreferences"))
        assertFalse(recorder.contains("getSharedPreferences"))
        assertFalse(recorder.contains("import com.cyclone.mobile.brain"))
        assertFalse(recorder.contains("java.io.File"))
        assertTrue(recorder.contains("MutableStateFlow"))
    }

    @Test fun homeFollowsTheTealMatrixReference() {
        val home = source("CycloneV32App.kt")
        // R6: Home on the calm blue with smoked glass; the header with search, the profile slider and round actions.
        assertTrue(home.contains("com.cyclone.mobile.ui.v32.ask.AskGlassPage(withScene = false, greetingShade = false)"))
        assertTrue(home.contains("HomeTopBar(onSettings = onSettings, onSearch = onSearch, onAi = onAi)"))
        assertTrue(home.contains("HomeActions(onAi = onAi, onRoutines = onRoutines, onBrain = onBrain, onMore = onSettings)"))
        assertTrue(home.contains("\"Research a topic\""))
        assertTrue(home.contains("\"Create a routine\""))
        assertTrue(home.contains("AskSectionHeader(\"Recent activity\", \"Open chat\", onAi)"))
        assertTrue(home.contains("com.cyclone.mobile.ui.v32.ask.AskPipState.DONE"))
        assertTrue(home.contains("item.progress,"))
        assertTrue(home.contains("CycloneHomeComposer(seed = 0 to \"\")"))
        assertTrue(home.contains("LaunchedEffect(task) { CycloneRecentActivity.record(task) }"))
        assertTrue(home.contains("CycloneSignatureSystemBars(enabled = true, glass = true)"))
    }

    @Test fun homeComposerIsTheAskCycloneGlassCapsule() {
        val composer = source("CycloneHomeComposer.kt")
        assertTrue(composer.contains("CycloneSignatureGlass("))
        assertTrue(composer.contains("focused = focused"))
        assertTrue(composer.contains("SignatureGlyph.ADD"))
        assertTrue(composer.contains("SignatureGlyph.MIC"))
        assertTrue(composer.contains("SignatureGlyph.SEND"))
        assertTrue(composer.contains("\"Ask Cyclone…\""))
        // Quick actions only prefill; the user still sends the request.
        assertFalse(composer.substringAfter("LaunchedEffect(seed.first)").substringBefore("fun send()").contains("onSubmit("))
    }

    @Test fun sharedChromeUsesTheTealGlassMaterial() {
        val glass = source("CycloneSignatureGlass.kt")
        assertTrue(glass.contains("accent: Color = SignatureTeal"))
        assertTrue(glass.contains("focused: Boolean = false"))
        assertTrue(glass.contains("val specular = Brush.horizontalGradient("))
        val chrome = source("CycloneLiquidChrome.kt")
        assertTrue(chrome.substringAfter("fun CycloneLiquidTray(").contains("if (LocalCycloneSignatureTheme.current)"))
        val panel = source("CycloneAskTaskPanel.kt")
        assertTrue(panel.contains("MatrixTone.ATTENTION.accent"))
        assertTrue(panel.contains("MatrixTone.SUCCESS.accent"))
        val matrix = source("CycloneTealMatrix.kt")
        assertTrue(matrix.contains("fun TealMatrixStaticBackdrop("))
        assertTrue(source("CycloneTealMatrixField.kt").contains("fun TealMatrixBackdrop("))
        assertTrue(matrix.contains("fun CycloneMatrixCard("))
        // The static fallback is cached per size; the living field runs on a capped frame clock, not an infinite transition.
        assertFalse(matrix.contains("rememberInfiniteTransition"))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/ui/v32/$name"
        return sequenceOf(File(relative), File("apps/mobile/app/$relative"))
            .first { it.isFile }.readText()
    }
}
