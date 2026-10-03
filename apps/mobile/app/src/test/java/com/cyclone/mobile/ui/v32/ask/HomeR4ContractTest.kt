package com.cyclone.mobile.ui.v32.ask

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R4 → R6 (docs/design/redesign/rounds/R6-calm.md): Home on the calm blue with smoked glass, the search and the profile slider. */
class HomeR4ContractTest {
    private fun source(relative: String): String = listOf(
        File("src/main/java/$relative"), File("app/src/main/java/$relative"), File("apps/mobile/app/src/main/java/$relative"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Could not locate $relative")

    private val app get() = source("com/cyclone/mobile/ui/v32/CycloneV32App.kt")
    private val home get() = app.substringAfter("private fun V32HomePage(").substringBefore("internal fun V32RoutineDetail(")

    @Test fun homeIsOnTheCalmBlueNotTheRain() {
        // R6: every page but the AI page rests on the still calm blue; the rain and the scene are the AI page's alone.
        assertTrue(home.contains("com.cyclone.mobile.ui.v32.ask.AskGlassPage(withScene = false, greetingShade = false)"))
        val world = source("com/cyclone/mobile/ui/v32/ask/AskGlassWorld.kt")
        assertTrue(world.contains("!aiStage -> AskCalmField(Modifier.matchParentSize().layerBackdrop(backdrop))"))
        assertTrue(world.contains("LocalAskCalm provides !aiStage"))
        val calm = source("com/cyclone/mobile/ui/v32/ask/AskCalm.kt")
        // Drawn once per size and never animated: no frame clock, no infinite transition.
        assertFalse(calm.contains("withFrameNanos"))
        assertFalse(calm.contains("rememberInfiniteTransition"))
        val rain = source("com/cyclone/mobile/ui/v32/ask/AskRain.kt")
        assertTrue(rain.contains("val scene = remember(withScene) { if (withScene) AskScene(context.applicationContext) else null }"))
    }

    @Test fun homeKeepsItsContentOnTheNewComponents() {
        val home = home.substringBefore("private fun openSearchResult(")
        val order = listOf(
            "HomeTopBar(onSettings = onSettings, onSearch = onSearch, onAi = onAi)",
            "ProfileSlider(profiles) { onProfiles() }",
            "HomeActions(onAi = onAi, onRoutines = onRoutines, onBrain = onBrain, onMore = onSettings)",
            "InAppTaskStack(current)",
            "AskSectionHeader(\"Recent activity\", \"Open chat\", onAi)",
            "AskSectionHeader(\"Your routines\", \"See all\", onRoutines)",
            "CycloneHomeComposer(seed = 0 to \"\")",
        ).map { home.indexOf(it) }
        assertTrue("order $order", order.all { it >= 0 } && order == order.sorted())
        // No teal Teal Matrix components remain on Home.
        listOf("CycloneMatrixCard(", "CycloneMatrixQuickAction(", "CycloneMatrixAppBar(", "CycloneMatrixIconTile(",
            "TealMatrix.", "CycloneMatrixCheck(", "CycloneMatrixRing(").forEach {
            assertFalse("home still uses $it", home.contains(it))
        }
        // The slider shows the Profiles tab's own model, read off the main thread.
        val r6 = source("com/cyclone/mobile/ui/v32/HomeR6.kt")
        assertTrue(r6.contains("buildProfileClusters("))
        assertTrue(r6.contains("profiles = withContext(Dispatchers.IO) {"))
        assertTrue(r6.contains("HorizontalPager(pager"))
    }

    @Test fun thePlusDrawerRisesAboveTheAskBarAndLabelsAreNotClipped() {
        val composer = source("com/cyclone/mobile/ui/v32/CycloneHomeComposer.kt")
        // Owner's report (alpha.73): the + panel opened under the bar and pushed it up. It is a drawer above it now.
        assertTrue(composer.indexOf("CycloneAttachmentTools(") in 0 until composer.indexOf("        capsule {"))
        assertTrue(composer.contains("expandVertically(expandFrom = Alignment.Bottom)"))
        assertTrue(composer.contains("BackHandler(tools) { tools = false }"))
        // Owner's report: "Routines" was cut off by the capsule clip around the round action and its label.
        val r6 = source("com/cyclone/mobile/ui/v32/HomeR6.kt")
        val action = r6.substringAfter("private fun HomeAction(")
        assertFalse(action.contains("Modifier.clip(ContinuousCapsule).clickable"))
        assertTrue(action.contains("maxLines = 1, softWrap = false"))
    }

    @Test fun homeAskBarIsTheSmokedBarWithItsDots() {
        val composer = source("com/cyclone/mobile/ui/v32/CycloneHomeComposer.kt")
        assertTrue(composer.contains("val askBackdrop = com.cyclone.mobile.ui.v32.ask.LocalAskBackdrop.current"))
        assertTrue(composer.contains(".askWhorls()"))
        assertTrue(composer.contains("CycloneSignatureGlass("))
        val glass = source("com/cyclone/mobile/ui/v32/CycloneSignatureGlass.kt")
        assertTrue(glass.contains("com.cyclone.mobile.ui.v32.ask.LocalAskBackdrop.current != null -> Color.White.copy(alpha = .14f)"))
    }
}
