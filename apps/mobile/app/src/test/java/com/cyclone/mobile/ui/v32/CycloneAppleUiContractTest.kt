package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the 4.4.8 Apple-like visual cleanup: one shell, one theme, one page inset. */
class CycloneAppleUiContractTest {
    @Test fun retiredVersionedShellsAreGone() {
        val names = uiDir().list()?.toSet().orEmpty()
        listOf(
            "CycloneMobileV23App.kt",
            "CycloneMobileV24App.kt",
            "CycloneMobileV25App.kt",
            "CycloneMobileV26App.kt",
            "CycloneMobileV27App.kt",
            "CycloneMobileV291App.kt",
            "CycloneMobileV292App.kt",
            "SetupExperience.kt",
            "CycloneAlpineBackdrop.kt",
            "GatewayAiCard.kt",
            "SetupComposeCompat.kt",
        ).forEach { name ->
            assertFalse("retired UI shell still present: $name", names.contains(name) || File(uiDir(), name).isFile)
        }
        assertFalse(File(uiDir(), "v31").isDirectory)
        assertFalse(File(uiDir(), "modules").isDirectory)
        assertFalse(File(uiDir(), "GatewayAiCard.kt").isFile)
        assertFalse(File(uiDir(), "v32/CycloneAlpineBackdrop.kt").isFile)
        val features = source("CycloneV32FeaturePages.kt")
        assertTrue(features.contains("internal fun V32TeachPage"))
        assertFalse(features.contains("internal fun V32AiPage"))
        assertFalse(features.contains("internal fun V32BrainPage"))
        assertFalse(features.contains("internal fun V32SettingsPage"))
        assertTrue(File(uiDir(), "CycloneMobileApp.kt").isFile)
        val theme = File(uiDir(), "CycloneMobileApp.kt").readText()
        assertTrue(theme.contains("CycloneIdentityTheme"))
        assertFalse(theme.contains("dynamicLightColorScheme"))
        assertFalse(theme.contains("fun CycloneMobileApp()"))
    }

    @Test fun homeAndAskShareOneAttachmentMenu() {
        val home = source("CycloneHomeComposer.kt")
        val ask = source("CycloneV39AiChatPage.kt")
        assertTrue(home.contains("internal fun CycloneAttachmentTools"))
        assertTrue(home.contains("\"Camera\""))
        assertTrue(home.contains("\"Photos\""))
        assertTrue(home.contains("filesLabel: String = \"Files\""))
        assertFalse(home.contains("\"Files & photos\""))
        assertTrue(home.contains("\"Share screen\""))
        assertTrue(ask.contains("CycloneAttachmentTools("))
        assertFalse(ask.contains("Text(\"File\")"))
        assertFalse(ask.contains("Text(\"Photo\")"))
    }

    @Test fun pagesDoNotDoublePadTheTabBar() {
        // R6: Home's top is the header with the search pill and the profile slider; the other pages keep CyclonePageHeader.
        assertTrue(source("CycloneV32App.kt").contains("HomeTopBar(onSettings = onSettings, onSearch = onSearch, onAi = onAi)"))
        assertFalse(source("CycloneV32App.kt").contains("‹"))
        listOf(
            "CycloneProfilesPage.kt",
            "CycloneRoutinesPage.kt",
            "CycloneV39BrainPage.kt",
            "CycloneFollowMePage.kt",
        ).forEach { name ->
            val text = source(name)
            assertTrue("$name should use cyclonePageInsets", text.contains("cyclonePageInsets("))
            assertFalse("$name still hard-codes 96.dp page padding", text.contains("bottom = 96.dp"))
        }
    }

    @Test fun activityDoesNotStackAnUnthemedRescueBarAboveScaffold() {
        val main = sequenceOf(
            File("src/main/java/com/cyclone/mobile/MainActivity.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/MainActivity.kt"),
        ).first { it.isFile }.readText()
        assertFalse(main.contains("ProfileRescueBar()"))
        assertTrue(main.contains("CycloneMobileV32App()"))
    }

    @Test fun consumerPagesShareOneLargeTitleAndNativeBack() {
        val design = source("CycloneV32DesignSystem.kt")
        assertTrue(design.contains("fun CyclonePageHeader("))
        assertTrue(design.contains("fun CycloneBackRow("))
        assertTrue(design.contains("fun CycloneHairline("))
        assertTrue(design.contains("headlineLarge = TextStyle(fontSize = 32.sp"))
        // Cards are flat Teal Matrix glass: no Material elevation or shadow.
        assertTrue(design.contains("CycloneMatrixCard(modifier = modifier, cornerRadius = 20.dp"))
        assertFalse(design.contains("shadowElevation"))
        assertFalse(design.contains("eyebrow.uppercase()"))

        // R6: Home's top is the header with the search pill and the profile slider; the other pages keep CyclonePageHeader.
        assertTrue(source("CycloneV32App.kt").contains("HomeTopBar(onSettings = onSettings, onSearch = onSearch, onAi = onAi)"))
        assertFalse(source("CycloneV32App.kt").contains("‹"))
        listOf(
            "CycloneProfilesPage.kt",
            "CycloneRoutinesPage.kt",
            "CycloneV39BrainPage.kt",
        ).forEach { name ->
            val text = source(name)
            assertTrue("$name should use CyclonePageHeader", text.contains("CyclonePageHeader("))
            assertFalse("$name still uses ASCII back chevrons", text.contains("‹"))
        }
        assertTrue(source("CycloneSettings426.kt").contains("CycloneHairline("))
        assertTrue(source("CycloneSettings426.kt").contains("cyclonePageInsets("))
        assertFalse(source("CycloneSettings426.kt").contains("shadowElevation = 1.dp"))
    }

    @Test fun askEmptyStateIsAProductGreetingNotAQuoteCard() {
        val ask = source("CycloneV39AiChatPage.kt")
        // R3: the greeting and its words live in AskCopy; the page draws the rain and the home.
        val copy = source("ask/AskCopy.kt")
        assertTrue(copy.contains("const val TITLE = \"Ask Cyclone\""))
        assertTrue(copy.contains("\"Good morning\""))
        assertTrue(copy.contains("\"Good afternoon\""))
        assertTrue(copy.contains("\"Good evening\""))
        assertTrue(copy.contains("\"What can I do for you?\""))
        assertTrue(ask.contains("AskRainField(Modifier.matchParentSize().layerBackdrop(backdrop))"))
        assertTrue(ask.contains("AskHome("))
        assertFalse(ask.contains("CycloneAlpineBackdrop"))
        assertFalse(ask.contains("progress today"))
        assertFalse(ask.contains("Ideas become real"))
        assertFalse(ask.contains("Text(\"You\""))
    }

    @Test fun liquidSelectorsAreOneObjectWithChromaticMotion() {
        val chrome = source("CycloneLiquidChrome.kt")
        val selectors = source("CycloneLiquidSelectors.kt")
        val overlay = sequenceOf(
            File("src/main/java/com/cyclone/mobile/ui/overlay/OverlayAppleLiquidComposer.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayAppleLiquidComposer.kt"),
        ).first { it.isFile }.readText()
        assertTrue(chrome.contains("LocalCycloneInsideLiquidHost"))
        assertTrue(chrome.contains("chromaticAberration = true"))
        assertTrue(chrome.contains("spring(dampingRatio = 0.84f, stiffness = 420f)"))
        assertTrue(chrome.contains("0.88f"))
        assertTrue(chrome.contains("0.84f"))
        assertTrue(chrome.contains("Color.White.copy(alpha = 0.62f)"))
        assertTrue(chrome.contains("blur(8f.dp.toPx())"))
        assertTrue(chrome.contains("blur(14f.dp.toPx())"))
        assertTrue(chrome.contains("LocalCycloneOverlayChrome"))
        assertFalse(chrome.contains("Color.White.copy(alpha = 0.22f)"))
        assertFalse(chrome.contains("Color.White.copy(alpha = 0.25f)"))
        assertFalse(chrome.contains("Color.White.copy(alpha = 0.48f)"))
        assertFalse(chrome.contains("chromaticAberration = false"))
        assertFalse(chrome.contains("Color.Black.copy(alpha = 0.035f)"))
        assertTrue(selectors.contains("LocalCycloneInsideLiquidHost.current"))
        assertTrue(selectors.contains("if (embedded)"))
        val signature = source("CycloneSignatureGlass.kt")
        assertTrue(overlay.contains("CycloneSignatureGlass("))
        assertTrue(signature.contains("LocalCycloneOverlayChrome provides true"))
        assertFalse(signature.contains("chromaticAberration = true"))
        val overlayChrome = sequenceOf(
            File("src/main/java/com/cyclone/mobile/ui/overlay/OverlayChrome.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChrome.kt"),
        ).first { it.isFile }.readText()
        // Model & intelligence now lives in the bottom tools drawer, never inside the work panel.
        val sheet = sequenceOf(
            File("src/main/java/com/cyclone/mobile/ui/overlay/OverlayToolsSheet.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayToolsSheet.kt"),
        ).first { it.isFile }.readText()
        assertTrue(sheet.contains("ComposerAccessory.MODEL -> Box("))
        assertTrue(sheet.contains("CycloneModelIntelligencePanel("))
        assertFalse(overlayChrome.contains("ComposerAccessory.MODEL ->"))
        assertFalse(overlayChrome.contains("ComposerAccessory.MODEL -> CycloneLiquidPanel("))
    }

    @Test fun overlayIdleGlowStaysCoolNotMagenta() {
        val overlay = sequenceOf(
            File("src/main/java/com/cyclone/mobile/ui/overlay/OverlayChrome.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui/overlay/OverlayChrome.kt"),
        ).first { it.isFile }.readText()
        assertTrue(overlay.contains("AuroraBlue"))
        assertTrue(overlay.contains("AuroraCyan"))
        assertFalse(overlay.contains("0xFFE56CFF"))
        assertFalse(overlay.contains("0xFF8568FF"))
        assertFalse(overlay.contains("AuroraMagenta"))
        assertFalse(overlay.contains("AuroraViolet"))
    }

    private fun uiDir(): File = sequenceOf(
        File("src/main/java/com/cyclone/mobile/ui"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui"),
    ).first { it.isDirectory }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/ui/v32/$name"
        return sequenceOf(File(relative), File("apps/mobile/app/$relative"))
            .first { it.isFile }.readText()
    }
}
