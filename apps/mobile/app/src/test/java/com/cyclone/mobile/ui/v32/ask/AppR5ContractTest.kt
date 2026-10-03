package com.cyclone.mobile.ui.v32.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R5 (docs/design/redesign/rounds/R5-app.md): one glass world for the app, chrome over content, Full / Lite / Auto. */
class AppR5ContractTest {
    private fun source(relative: String): String = listOf(
        File("src/main/java/$relative"), File("app/src/main/java/$relative"), File("apps/mobile/app/src/main/java/$relative"),
    ).firstOrNull(File::isFile)?.readText() ?: error("Could not locate $relative")

    private fun v32(name: String) = source("com/cyclone/mobile/ui/v32/$name")
    private fun ask(name: String) = source("com/cyclone/mobile/ui/v32/ask/$name")

    @Test fun theAppDrawsOneGlassWorldAndThePagesReuseIt() {
        val app = v32("CycloneV32App.kt")
        assertTrue(app.contains("CycloneV32Theme(glass = true)"))
        assertTrue(app.contains("AskGlassWorld(aiStage = destination == V32Destination.AI && !settingsOpen && !marketOpen)"))
        val world = ask("AskGlassWorld.kt")
        assertTrue(world.contains("LocalAskWorld provides true"))
        assertTrue(world.contains("else -> AskRainField(Modifier.matchParentSize().layerBackdrop(backdrop), withScene = true, quality = quality)"))
        // The owner's video plays only while the AI page is in front.
        assertTrue(world.contains("val stageVideo = video?.takeIf { aiStage }"))
        // Home and the AI page draw no rain of their own inside the world.
        val kit = ask("AskGlassKit.kt")
        assertTrue(kit.indexOf("if (LocalAskWorld.current) {") in 0 until kit.indexOf("val backdrop = rememberLayerBackdrop()"))
        val page = v32("CycloneV39AiChatPage.kt")
        assertTrue(page.contains("val world = LocalAskWorld.current"))
        assertTrue(page.contains("if (homeCanvas) AskGreetingPool(Modifier.matchParentSize())"))
        // The teal theme draws no canvas under the glass world.
        val theme = v32("CycloneV32DesignSystem.kt").substringAfter("if (glass) {").substringBefore("return")
        assertFalse(theme.contains("TealMatrixBackdrop"))
        assertTrue(theme.contains("colorScheme = com.cyclone.mobile.ui.v32.ask.AskGlassScheme"))
    }

    @Test fun navigationIsTheDarkerChromeGlass() {
        val glass = ask("SmokedGlass.kt")
        assertTrue(glass.contains("const val CHROME_SMOKE = 0.72f"))
        assertTrue(AskGlass.CHROME_SMOKE > AskGlass.SMOKE)
        assertTrue(AskGlass.CHROME_BLUR_DP > AskGlass.BLUR_DP)
        assertTrue(glass.contains("tier == GlassTier.CHROME -> AskGlass.CHROME_SMOKE"))
        assertTrue(AskCalm.CHROME_SMOKE > AskCalm.SMOKE)
        // Header chips, the model pill, sheets, the Ask bars, trays (the tab bar), back chips and status chips.
        assertTrue(ask("AskScreen.kt").contains(".askGlass(size / 2, GlassTier.CHROME"))
        assertTrue(ask("AskScreen.kt").contains(".askGlass(22.dp, GlassTier.CHROME"))
        assertEquals(3, Regex("askGlass\\(\\d+\\.dp, GlassTier\\.CHROME").findAll(ask("AskSheets.kt")).count())
        assertTrue(v32("InAppGlass.kt").contains("askGlass(33.dp, GlassTier.CHROME"))
        assertTrue(v32("CycloneHomeComposer.kt").contains("askGlass(33.dp, GlassTier.CHROME"))
        assertTrue(v32("CycloneLiquidChrome.kt").contains(".askGlass(height / 2, com.cyclone.mobile.ui.v32.ask.GlassTier.CHROME"))
        assertTrue(v32("CycloneV32DesignSystem.kt").contains(".askGlass(20.dp, com.cyclone.mobile.ui.v32.ask.GlassTier.CHROME"))
        assertTrue(ask("AskGlassKit.kt").contains(".askGlass(18.dp, GlassTier.CHROME"))
        // Content glass keeps the content tier.
        assertTrue(ask("AskGlassKit.kt").contains(".askGlass(24.dp, shineOffset = shineOffset)"))
    }

    @Test fun liteSharesOneBlurAndDropsLensAndShadowOnContentOnly() {
        val glass = ask("SmokedGlass.kt")
        assertTrue(glass.contains("val lite = quality == GlassQuality.LITE && !chrome"))
        assertTrue(glass.contains("val source = if (lite && blurred != null) blurred else backdrop"))
        assertTrue(glass.contains("lite -> null"))
        // The shine is its own layer, so it never re-runs the blur.
        assertTrue(glass.contains("private fun Modifier.shineLayer("))
        val world = ask("AskGlassWorld.kt")
        assertTrue(world.contains("LocalAskBlurredBackdrop provides if (lite) shared else null"))
        assertTrue(world.contains("effects = { blur(AskGlass.LITE_SHARED_BLUR_DP.dp.toPx()) }"))
        // The shared blur is drawn before (under) the rain.
        assertTrue(world.indexOf(".layerBackdrop(shared)") < world.indexOf("AskRainField("))
        val rain = ask("AskRain.kt")
        assertTrue(rain.contains("const val LITE_FRAME_NS = 50_000_000L"))
        assertTrue(rain.contains("if (AskMotion.holding(android.os.SystemClock.uptimeMillis())) return@withFrameNanos"))
        assertTrue(world.contains("AskMotion.scrolled(SystemClock.uptimeMillis())"))
    }

    @Test fun autoWatchesFramesAndTheOwnerCanChoose() {
        val world = ask("AskGlassWorld.kt")
        assertTrue(world.contains("if (mode != QualityMode.AUTO || quality != GlassQuality.FULL || AskRain.animationsOff(context)) return"))
        assertTrue(world.contains("VisualQuality.stepDown(context)"))
        assertTrue(world.contains("frameMs in 0.1..AskWorld.IDLE_GAP_MS"))
        val settings = v32("CycloneSettings426.kt")
        assertTrue(settings.contains("Settings426Row(VISUAL_QUALITY, VISUAL_QUALITY, Icons.Rounded.AutoAwesome, visualQualityValue())"))
        assertTrue(settings.contains("onSelect = { index -> com.cyclone.mobile.ui.v32.ask.VisualQuality.setMode(context, modes[index]) }"))
        assertEquals(listOf("Auto", "Full", "Lite"), QualityMode.entries.map { it.label })
        // Only the choice is stored: a plain preference, nothing about the phone or the frames.
        val quality = ask("AskQuality.kt")
        assertTrue(quality.contains("private const val KEY_MODE = \"visual_quality\""))
        val keys = Regex("\\.put(?:String|Boolean)\\((\\w+)").findAll(quality).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("KEY_MODE", "KEY_STEPPED"), keys)
    }

    @Test fun pagesFollowThroughTheSharedPrimitivesWithoutTeal() {
        val signature = v32("CycloneSignatureGlass.kt")
        assertTrue(signature.contains("MaterialTheme(colorScheme = com.cyclone.mobile.ui.v32.ask.AskGlassScheme, content = content)"))
        assertTrue(signature.contains(".askGlass(cornerRadius, shineOffset = 0.4f, shape = shape)"))
        val matrix = v32("CycloneTealMatrix.kt")
        assertTrue(matrix.contains("internal fun matrixAccent(): Color"))
        val chrome = v32("CycloneLiquidChrome.kt")
        assertTrue(chrome.contains("// R5: the chosen option is a white veil sliding inside the smoked tray."))
        listOf("AskGlassWorld.kt", "AskQuality.kt", "AskQualityPolicy.kt").forEach { name ->
            listOf("0xFF83DBD7", "SignatureTeal", "TealMatrix", "0xFF41D7CB", "0xFF061A20").forEach { teal ->
                assertFalse("$name uses $teal", ask(name).contains(teal))
            }
        }
        // The scheme on the rain is neutral: white primary, graphite surfaces.
        val world = ask("AskGlassWorld.kt")
        assertTrue(world.contains("primary = Color(0xFFF5F7FA), onPrimary = Color(0xFF0B0C0F)"))
        assertTrue(world.contains("surface = Color(0xFF15171B)"))
    }

    @Test fun overlaysAreUnchanged() {
        // "No overlays yet": the floating overlay's theme stays teal with no glass world.
        val overlay = source("com/cyclone/mobile/ai/OverlayChromeController.kt")
        assertFalse(overlay.contains("glass = true"))
        assertFalse(overlay.contains("AskGlassWorld"))
    }
}
