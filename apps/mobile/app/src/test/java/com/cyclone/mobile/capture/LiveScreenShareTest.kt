package com.cyclone.mobile.capture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveScreenShareTest {
    @Test fun productShareScreenUsesWholeDisplayAndOverlay() {
        val share = source("ui/v32/CycloneV39AiChatPage.kt")
        val home = source("ui/v32/CycloneHomeComposer.kt")
        val overlay = source("ui/overlay/OverlayChrome.kt")
        val consent = source("capture/LiveCaptureConsentActivity.kt")
        val launcher = source("capture/LiveScreenShare.kt")
        assertTrue(share.contains("LiveScreenShare.start(context)"))
        assertTrue(home.contains("LiveScreenShare.start(context)"))
        // The overlay's Share screen lives in the bottom tools drawer, launched by the controller.
        val controller = source("ai/OverlayChromeController.kt")
        assertTrue(controller.contains("putExtra(\"wholeDisplay\", true)"))
        assertTrue(overlay.contains("OverlayToolsSheetState"))
        assertTrue(launcher.contains("EXTRA_WHOLE_DISPLAY"))
        assertTrue(launcher.contains("OverlayUserAction.ASK_CYCLONE"))
        assertTrue(launcher.contains("CATEGORY_HOME"))
        assertTrue(consent.contains("createConfigForDefaultDisplay()"))
        assertFalse(consent.contains("if (wholeDisplay && android.os.Build.VERSION.SDK_INT >= 34)"))
        assertTrue(consent.contains("OverlayExternalInteraction.active.value = true"))
        assertFalse(share.contains("MediaProjectionManager"))
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/cyclone/mobile/$relative"),
            File("app/src/main/java/com/cyclone/mobile/$relative"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
    }
}
