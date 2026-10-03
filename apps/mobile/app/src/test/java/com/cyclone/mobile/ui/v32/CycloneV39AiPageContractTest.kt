package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CycloneV39AiPageContractTest {
    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/$relative"),
            File("app/src/main/java/$relative"),
            File("apps/mobile/app/src/main/java/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Could not locate source file $relative from ${File(".").absolutePath}")
    }

    @Test fun aiPageHasOneAutoRoutedComposer() {
        val text = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        assertFalse(text.contains("listOf(\"Chat\", \"Phone task\")"))
        assertFalse(text.contains("\"New request\""))
        assertTrue(text.contains("RequestIntentRouter.route(normalized"))
        assertTrue(text.indexOf("RequestIntentRouter.route(normalized") < text.indexOf("WorkspaceTasks.canStartRequest()"))
        assertFalse(text.contains("AccessibilityService"))
        assertFalse(text.contains("MediaProjectionManager"))
    }

    @Test fun chatAndPhoneDispatchUseSeparateExistingPaths() {
        val text = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        assertTrue(text.contains("RequestDispatch.CHAT"))
        assertTrue(text.contains("OverlayChromeRuntime.submitRequest(normalized)"))
        assertTrue(text.contains("WorkspaceTasks.queueRequest(normalized)"))
        assertTrue(text.contains("PendingTaskAttachment.take()"))
        assertTrue(text.contains("restoreAttachmentAfterChatFailure"))
    }

    @Test fun taskConversationAndQuickModelControlStayAboveOneLiquidComposer() {
        val text = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        val header = text.indexOf("AskHeader(")
        val current = text.indexOf("InAppTaskStack(current)")
        val queued = text.indexOf("CyclonePendingRequests()")
        val drawer = text.indexOf("CycloneChatDrawerSurface(")
        val composer = text.lastIndexOf("GlassComposerBar(")
        // R3: the model selector is the header pill; its sheet drops from the header, drawn above the composer.
        val modelSheet = text.indexOf("AskModelSheet(", composer)
        assertTrue(header in 0 until current)
        assertTrue(current in 0 until drawer)
        assertTrue(queued in 0 until drawer)
        assertTrue(composer in drawer until modelSheet)
        assertTrue(text.contains("onModel = {"))
        assertFalse(text.contains("CycloneModelPill("))
    }

    @Test fun emptyStateMatchesDotFieldAskConcept() {
        val page = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        val copy = source("com/cyclone/mobile/ui/v32/ask/AskCopy.kt")
        assertTrue(copy.contains("\"Ask Cyclone\""))
        assertTrue(copy.contains("\"Good morning\""))
        assertTrue(copy.contains("\"Good afternoon\""))
        assertTrue(copy.contains("\"Good evening\""))
        assertTrue(copy.contains("\"What can I do for you?\""))
        // R3: the rain replaced the breathing dot field.
        assertTrue(page.contains("AskRainField(Modifier.matchParentSize().layerBackdrop(backdrop))"))
        assertFalse(page.contains("AskCycloneDotField"))
        assertFalse(page.contains("CycloneAlpineBackdrop"))
        assertFalse(page.contains("cyclone_alpine"))
        assertFalse(page.contains("progress today"))
        assertFalse(page.contains("Ideas become real"))
        assertFalse(page.contains("Contributor · prompts and responses may be used for training."))
    }

    @Test fun replyStopIsChatOnly() {
        val text = source("com/cyclone/mobile/ui/v32/CycloneV39AiChatPage.kt")
        val stopReply = text.indexOf("Stop reply")
        assertTrue(stopReply >= 0)
        val stopWindow = text.substring((stopReply - 500).coerceAtLeast(0), (stopReply + 500).coerceAtMost(text.length))
        assertTrue(stopWindow.contains("chatJob?.cancel()"))
        assertFalse(stopWindow.contains("WorkspaceTasks.command"))
    }

    @Test fun navigationIsLiquidInsetSafeAndUsesCycloneAiAsset() {
        val nav = source("com/cyclone/mobile/ui/v32/CycloneV32Components.kt")
        assertTrue(nav.contains("navigationBarsPadding()"))
        assertTrue(nav.contains("WindowInsets.ime"))
        assertTrue(nav.contains("if (imeVisible) return"))
        assertTrue(nav.contains("ic_cyclone_ai_42"))
        assertTrue(nav.contains("CycloneLiquidTray(height = 62.dp"))
        assertTrue(nav.contains("CycloneLiquidSelectionLens("))
        assertTrue(nav.contains("height = 48.dp"))
        assertTrue(nav.contains("horizontalInset = 4.dp"))
        assertFalse(nav.contains("NavigationBarItem("))
        assertFalse(nav.contains("Modifier.height(74.dp)"))
    }
}
