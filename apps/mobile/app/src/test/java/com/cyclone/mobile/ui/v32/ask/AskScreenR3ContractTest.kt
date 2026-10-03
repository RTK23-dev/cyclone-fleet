package com.cyclone.mobile.ui.v32.ask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R3 (docs/design/redesign/rounds/R3-ai-screen.md): the AI screen is the Cyclone rain behind smoked glass. */
class AskScreenR3ContractTest {
    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("apps/mobile/app/src/main/java/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate source file $relative from ${File(".").absolutePath}")
    }

    private val page get() = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
    private fun ask(name: String) = source("com/cyclone/mobile/ui/v32/ask/$name")

    @Test fun rainIsTheBackdropEveryGlassSurfaceBlurs() {
        val page = page
        assertTrue(page.contains("val backdrop = rememberLayerBackdrop()"))
        assertTrue(page.contains("AskRainField(Modifier.matchParentSize().layerBackdrop(backdrop))"))
        assertTrue(page.contains("AskScrim(Modifier.matchParentSize(), greeting = homeCanvas)"))
        // R5: inside the app the glass world's backdrop and shine are used; standing alone the page records its own.
        assertTrue(page.contains("LocalAskBackdrop provides if (world) LocalAskBackdrop.current else backdrop"))
        assertTrue(page.contains("LocalAskShine provides if (world) LocalAskShine.current else shine"))
        assertTrue(page.contains("LocalGlassPalette provides GlassPalette.SMOKE"))
        assertTrue(page.indexOf("AskRainField(") < page.indexOf("AskHeader("))
    }

    @Test fun rainUsesTheTraceFieldGlyphsCalmlyAndRespectsStillMode() {
        val rain = ask("AskRain.kt")
        assertTrue(rain.contains("TraceFieldShader.GLYPHS"))
        assertTrue(rain.contains("RuntimeShader(SOURCE)"))
        assertTrue(rain.contains("const val FRAME_NS = 32_000_000L"))
        assertTrue(rain.contains("if (rain != null && !still)"))
        assertTrue(rain.contains("ANIMATOR_DURATION_SCALE"))
        // A shader that cannot run leaves a dark gradient, never a crash.
        assertTrue(rain.contains("if (!drawn) drawRect(AskRain.FALLBACK)"))
        assertTrue(rain.contains("}.getOrNull()"))
    }

    @Test fun smokedGlassBlursLensesAndDarkens() {
        val glass = ask("SmokedGlass.kt")
        assertTrue(glass.contains("blur(AskGlass.BLUR_DP.dp.toPx())"))
        assertTrue(glass.contains("lens(AskGlass.LENS_HEIGHT_DP.dp.toPx(), AskGlass.LENS_AMOUNT_DP.dp.toPx())"))
        assertTrue(glass.contains("drawRect(Color.Black.copy(alpha = smoke))"))
        assertTrue(glass.contains("const val SMOKE = 0.52f"))
        assertTrue(glass.contains("const val SHINE_MS = 6_000"))
        // Without a recorded backdrop the glass is painted graphite, not transparent.
        assertTrue(glass.contains("if (backdrop == null)"))
        assertTrue(glass.contains(".background(if (chrome) AskGlass.PaintedChrome else AskGlass.Painted, shape)"))
    }

    @Test fun noTealOnTheAiScreen() {
        listOf("AskCopy.kt", "AskRain.kt", "SmokedGlass.kt", "AskScreen.kt", "AskSheets.kt").forEach { name ->
            val text = ask(name)
            listOf("0xFF83DBD7", "SignatureTeal", "TealMatrix", "0xFF41D7CB", "0xFF061A20").forEach { teal ->
                assertFalse("$name uses $teal", text.contains(teal))
            }
        }
        val tilt = source("com/cyclone/mobile/ui/overlay/glass/TiltGlass.kt")
        assertTrue(tilt.contains("SMOKE(Triple(Color(0xFF1C1E23)"))
        assertTrue(tilt.contains("val LocalGlassPalette = androidx.compose.runtime.staticCompositionLocalOf { GlassPalette.TEAL }"))
        // The shared task cards take the page's palette; the floating overlay never sets it, so it stays teal.
        val stack = source("com/cyclone/mobile/ui/overlay/OverlayGlassStack.kt")
        assertEquals(4, Regex("palette = LocalGlassPalette.current").findAll(stack).count())
        assertFalse(source("com/cyclone/mobile/ui/overlay/OverlayChrome.kt").contains("LocalGlassPalette provides"))
    }

    @Test fun askBarKeepsItsDotsAndShineOnSmokedGlass() {
        val bar = source("com/cyclone/mobile/ui/v32/InAppGlass.kt")
        assertTrue(bar.contains("val askBackdrop = com.cyclone.mobile.ui.v32.ask.LocalAskBackdrop.current"))
        assertTrue(bar.contains(".askWhorls()"))
        assertTrue(bar.contains("Modifier.tiltGlass(33.dp)"))
        val glass = ask("SmokedGlass.kt")
        assertTrue(glass.contains("fun Modifier.askWhorls("))
        assertTrue(glass.contains("for (ridge in 1..25)"))
    }

    @Test fun headerIsBurgerModelSelectorAndMark() {
        val header = ask("AskScreen.kt")
        val menu = header.indexOf("AskRoundChip(\"Menu\", onMenu")
        val model = header.indexOf("contentDescription = \"Model: \$modelLabel. Choose a model\"")
        val mark = header.indexOf("AskRoundChip(\"Cyclone\", onLogo")
        assertTrue(menu in 0 until model)
        assertTrue(model in 0 until mark)
        assertTrue(header.contains("CycloneOrbitMark(Modifier.size(22.dp))"))
        assertFalse(page.contains("AskCycloneOrb"))
    }

    @Test fun homeIsGreetingSuggestionsAndRecentRuns() {
        val home = ask("AskScreen.kt")
        val greeting = home.indexOf("AskCopy.greeting(")
        val suggestions = home.indexOf("AskCopy.SUGGESTIONS.chunked(2)")
        val runs = home.indexOf("AskCopy.RUNS_LABEL")
        assertTrue(greeting in 0 until suggestions)
        assertTrue(suggestions in 0 until runs)
        assertTrue(home.contains(".take(3)"))
        // Suggestions only write into the Ask bar.
        assertTrue(home.contains("onSuggestion(suggestion.seed)"))
        assertTrue(page.contains("onSuggestion = { composer = it }"))
        // Runs left the chat thread; they live on home and in the menu.
        assertFalse(page.contains("item(key = \"missions\")"))
    }

    @Test fun sheetsKeepTheExistingRulesAndStoreNothingNew() {
        val sheets = ask("AskSheets.kt")
        assertTrue(sheets.contains("if (mission.status.resumable) AskPillButton(\"Resume\", enabled = canResume"))
        assertTrue(sheets.contains("canResume = live == null"))
        assertTrue(sheets.contains("MindMissions.resume(context, mission.id)"))
        assertTrue(sheets.contains("MindMissions.delete(context, mission.id)"))
        // Driver mode switches exactly as in Settings: turning it on plays the Drive film, which asks for the microphone.
        assertTrue(sheets.contains("DriverMode.setEnabled(context, on)"))
        assertTrue(sheets.contains("if (on) com.cyclone.mobile.ui.overlay.DriveIntroActivity.start(context)"))
        val intro = source("com/cyclone/mobile/ui/overlay/DriveIntro.kt")
        assertTrue(intro.contains("askMic.launch(Manifest.permission.RECORD_AUDIO)"))
        assertTrue(sheets.contains("com.cyclone.mobile.brain.UserMdRuntime.setEnabled(context, on)"))
        listOf("getSharedPreferences", "OpenRouterSecretStore", "writeText(", "Log.").forEach {
            assertFalse("sheets must not use $it", sheets.contains(it))
        }
        assertTrue(page.contains("androidx.activity.compose.BackHandler(menuOpen || logoOpen || modelMenuOpen)"))
    }

    @Test fun ownersVideoIsReadInPlaceMutedAndFallsBackToTheRain() {
        val background = ask("AskBackground.kt")
        assertTrue(page.contains("if (video != null) AskVideoField(video, Modifier.matchParentSize().layerBackdrop(backdrop))"))
        // Only Android's read grant is kept; the file is never copied, uploaded or opened as a stream by Cyclone.
        assertTrue(background.contains("takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)"))
        listOf("openInputStream", "FileOutputStream", "copyTo(", "OpenRouter", "http").forEach {
            assertFalse("background must not use $it", background.contains(it))
        }
        assertTrue(background.contains("player.setVolume(0f, 0f)"))
        assertTrue(background.contains("player.isLooping = true"))
        assertTrue(background.contains("runCatching { player.release() }"))
        assertTrue(background.contains("AskBackground.failed(uri)"))
        assertTrue(background.contains("Lifecycle.Event.ON_PAUSE -> if (player.isPlaying) player.pause()"))
        // Picked from the logo panel, with a way back to the rain.
        val sheets = ask("AskSheets.kt")
        assertTrue(sheets.contains("pickVideo.launch(arrayOf(\"video/*\"))"))
        assertTrue(sheets.contains("AskBackground.useRain(context)"))
    }

    @Test fun officialSceneIsTinyAndDrivesTheLiveDigits() {
        val asset = listOf(File("src/main/res/raw/ask_scene.mp4"), File("app/src/main/res/raw/ask_scene.mp4"),
            File("apps/mobile/app/src/main/res/raw/ask_scene.mp4")).first(File::isFile)
        // The scene carries only each cell's light and colour; the digits are drawn live. It stays under 1 MB.
        assertTrue("scene is ${asset.length()} bytes", asset.length() in 100_000L until 1_000_000L)
        val rain = ask("AskRain.kt")
        assertTrue(rain.contains("uniform shader scene;"))
        assertTrue(rain.contains("float l = max(max(float(sc.r), float(sc.g)), float(sc.b));"))
        assertTrue(rain.contains("scene?.frame?.takeIf { !scene.failed }"))
        assertTrue(rain.contains("Lifecycle.Event.ON_PAUSE -> scene.stop()"))
        assertTrue(rain.contains("const val CELL_W_DP = 4f"))
        val decoder = ask("AskScene.kt")
        assertTrue(decoder.contains("R.raw.ask_scene"))
        assertTrue(decoder.contains("if (still && frame != null) break"))
        assertTrue(decoder.contains("failed = true"))
        listOf("http", "OpenRouter", "FileOutputStream").forEach { assertFalse(decoder.contains(it)) }
    }

    @Test fun navigationStillOwnsTheAiDestination() {
        val app = source("com/cyclone/mobile/ui/v32/CycloneV32App.kt")
        assertTrue(app.contains("if (!settingsOpen) CycloneV32BottomBar(destination)"))
        assertTrue(app.contains("onRoutines = { destination = V32Destination.ROUTINES }"))
        assertTrue(app.contains("onBrain = { destination = V32Destination.BRAIN }"))
    }
}
