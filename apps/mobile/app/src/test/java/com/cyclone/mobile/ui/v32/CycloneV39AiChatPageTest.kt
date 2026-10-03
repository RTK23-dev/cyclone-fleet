package com.cyclone.mobile.ui.v32

import com.cyclone.mobile.ai.CycloneAiAccessProfile
import com.cyclone.mobile.ai.OpenRouterModelPresets
import com.cyclone.mobile.ai.QuickAgentResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CycloneV39AiChatPageTest {
    @Test fun emptyRequestCannotSubmit() {
        val gate = V39AiSubmitGate()
        assertNull(gate.tryAccept("  \n ", hasKey = true))
    }

    @Test fun oneAcceptedSendBlocksDuplicateUntilCompletion() {
        val gate = V39AiSubmitGate()
        assertEquals("Go to ad.nl", gate.tryAccept("  Go to ad.nl  ", hasKey = true))
        assertNull(gate.tryAccept("Second request", hasKey = true))
        gate.complete()
        assertEquals("Second request", gate.tryAccept("Second request", hasKey = true))
    }

    @Test fun missingKeyCannotSubmitChat() {
        val gate = V39AiSubmitGate()
        assertNull(gate.tryAccept("Explain Android", hasKey = false))
    }

    @Test fun legacyModelReloadRestoresStoredId() {
        val chosen = OpenRouterModelPresets.all.last()
        assertEquals(chosen.id, V39AiChatContract.modelForStored(chosen.id).id)
        assertEquals(chosen.label, V39AiChatContract.modelForStored(chosen.id).label)
    }

    @Test fun emptySelectionNeverRestoresAnUnselectedDefault() {
        assertEquals("", V39AiChatContract.modelForStored(null).id)
        assertEquals("", V39AiChatContract.modelForStored(" ").id)
    }

    @Test fun existingSafetyProfileFeedsQuickAgentConfig() {
        CycloneAiAccessProfile.entries.forEach { profile ->
            val config = V39AiChatContract.config(OpenRouterModelPresets.DEFAULT.id, profile)
            assertEquals(profile, config.accessProfile)
            assertEquals(profile != CycloneAiAccessProfile.FULL, config.safeMode)
        }
    }

    @Test fun resultStatusIsVisibleContract() {
        assertEquals("Completed and checked", V39AiChatContract.finalStatus(QuickAgentResult(true, "done", 1, "model")))
        assertEquals("Stopped safely", V39AiChatContract.finalStatus(QuickAgentResult(false, "failed", 1, "model")))
    }

    @Test fun productionAiDestinationRoutesToV39Page() {
        val app = source("CycloneV32App.kt")
        assertTrue(app.contains("V32Destination.AI -> V39AiChatPage("))
        assertTrue(app.contains("onSettingsSection = { section ->"))
        assertFalse(app.contains("V32Destination.AI -> V32AiPage("))
    }

    @Test fun productionPageHasOneAutoRoutedComposerAndNoModeToggle() {
        val page = source("CycloneV39AiChatPage.kt")
        assertFalse(page.contains("listOf(\"Chat\", \"Phone task\")"))
        assertFalse(page.contains("var phoneTask"))
        assertFalse(page.contains("Text(\"New request\""))
        assertTrue(page.contains("RequestIntentRouter.route(normalized"))
        assertTrue(page.indexOf("RequestIntentRouter.route(normalized") < page.indexOf("WorkspaceTasks.canStartRequest()"))
        assertTrue(page.contains("RequestDispatch.CHAT"))
        assertTrue(page.contains("RequestDispatch.START_PHONE_TASK"))
        assertTrue(page.contains("RequestDispatch.QUEUE_PHONE_TASK"))
    }

    @Test fun composerIsMultilineLiquidChromeWithOnePrimarySendControl() {
        val page = source("CycloneV39AiChatPage.kt")
        // Plan 27: the glass Ask bar (InAppGlass.kt), multi-line, one send and one voice control.
        val glass = source("InAppGlass.kt")
        assertTrue(page.contains("GlassComposerBar("))
        assertTrue(glass.contains("BasicTextField("))
        assertTrue(glass.contains("fieldDescription: String = \"Ask Cyclone composer\""))
        assertTrue(glass.contains("maxLines: Int = 4"))
        assertTrue(glass.contains("ImeAction.Send"))
        assertEquals(1, Regex("SignatureGlyph.SEND").findAll(glass).count())
        assertEquals(1, Regex("SignatureGlyph.MIC").findAll(glass).count())
        assertFalse(page.contains("FilledIconButton("))
    }

    @Test fun modelIntelligenceControlLivesInPlusSheetWithoutDuplicateHeaderPill() {
        val page = source("CycloneV39AiChatPage.kt")
        // R3: the model selector is the header pill (it replaced the word "Cyclone"); its sheet drops from there.
        // The + drawer's "Model & intelligence" opens the same sheet, so there is still one model control.
        val sheets = source("ask/AskSheets.kt")
        val askGlass = page.indexOf("GlassComposerBar(")
        val quickControl = page.indexOf("AskModelSheet(", askGlass)
        assertTrue(askGlass >= 0)
        assertTrue(quickControl > askGlass)
        assertTrue(source("InAppGlass.kt").contains("BasicTextField("))
        assertTrue(sheets.contains("contentDescription = \"Model and thinking\""))
        assertTrue(page.contains("\"Model & intelligence\" -> modelMenuOpen = true"))
        assertTrue(page.contains("if (modelMenuOpen && !keyboardOpen)"))
        assertFalse(page.contains(".imePadding()"))
        assertFalse(page.contains("CycloneModelPill("))
    }

    @Test fun chatAndPhoneDispatchUseSeparateExistingPaths() {
        val page = source("CycloneV39AiChatPage.kt")
        assertFalse(page.contains("agent.execute("))
        assertTrue(page.contains("CycloneTextChat.answer(context"))
        assertTrue(page.contains("WorkspaceTasks.queueRequest(normalized)"))
        assertTrue(page.contains("OverlayChromeRuntime.submitRequest(normalized)"))
        assertFalse(page.contains("AccessibilityService"))
        assertTrue(page.contains("LiveScreenShare.start(context)"))
        assertFalse(page.contains("MediaProjectionManager"))
    }

    @Test fun modelAndReasoningPreferencesStayCanonical() {
        val page = source("CycloneV39AiChatPage.kt")
        val controls = source("CycloneIntelligenceControls.kt")
        val reasoning = source("CycloneReasoningSelector.kt")
        assertTrue(page.contains("const val PREFS = \"cyclone_ai\""))
        assertTrue(page.contains("const val MODEL_KEY = \"openrouter_model\""))
        val sheets = source("ask/AskSheets.kt")
        assertTrue(controls.contains("OpenRouterCatalogStore.setActive(context"))
        assertTrue(reasoning.contains("OpenRouterCatalogStore.setReasoningEffort(context, canonical, value)"))
        assertTrue(reasoning.contains("reasoningSelectorMode(options)"))
        // R3's model sheet stores through the same canonical store calls, with exact provider tokens.
        assertTrue(sheets.contains("OpenRouterCatalogStore.setActive(context, option.id)"))
        assertTrue(sheets.contains("OpenRouterCatalogStore.setReasoningEffort(context, canonical, option)"))
        assertTrue(sheets.contains("V39AiChatContract.storageId(option)"))
        assertTrue(page.contains("onChanged = ::persistAiControls"))
    }

    @Test fun missingKeyBlocksChatButNotPhoneRoutingContract() {
        val page = source("CycloneV39AiChatPage.kt")
        assertTrue(page.contains("OpenRouter key required for chat"))
        assertTrue(page.contains("RequestIntent.PHONE_TASK -> true"))
        assertTrue(page.contains("RequestIntent.CHAT -> hasKey && !session.busy"))
    }

    @Test fun attachmentsAreTakenOnlyInsideChatAndRestoredOnFailure() {
        val page = source("CycloneV39AiChatPage.kt")
        val chat = page.indexOf("RequestDispatch.CHAT ->")
        val take = page.indexOf("PendingTaskAttachment.take()", chat)
        assertTrue(chat >= 0)
        assertTrue(take > chat)
        assertTrue(page.contains("restoreAttachmentAfterChatFailure(attachment)"))
        assertTrue(page.contains("WorkspaceTasks.queueRequest(normalized)"))
    }

    @Test fun stopReplyCancelsOnlyProviderResponse() {
        val page = source("CycloneV39AiChatPage.kt")
        val stop = page.indexOf("Stop reply")
        assertTrue(stop >= 0)
        val window = page.substring((stop - 300).coerceAtLeast(0), (stop + 300).coerceAtMost(page.length))
        assertTrue(window.contains("chatJob?.cancel()"))
        assertFalse(window.contains("WorkspaceTasks.command"))
    }

    @Test fun taskAndForegroundWorkAreFirstClassConversationItemsAboveComposer() {
        val page = source("CycloneV39AiChatPage.kt")
        val task = page.indexOf("InAppTaskStack(current)")
        val foreground = page.indexOf("InAppForegroundCard(foregroundSnapshot)")
        val queued = page.indexOf("CyclonePendingRequests()")
        val drawer = page.indexOf("CycloneChatDrawerSurface(")
        val composer = page.lastIndexOf("GlassComposerBar(")
        assertTrue(task in 0 until drawer)
        assertTrue(foreground in 0 until drawer)
        assertTrue(queued in 0 until drawer)
        assertTrue(drawer in 0 until composer)
        assertTrue(page.contains("session.append(V39ChatRole.CYCLONE, \"Got it. I'll work on that on your phone.\")"))
    }

    @Test fun translucentChatUsesSharedBubblePrimitiveWithoutElevatedGhostBands() {
        val page = source("CycloneV39AiChatPage.kt")
        val bubble = source("CycloneConversationBubble.kt")
        val design = source("CycloneV32DesignSystem.kt")
        assertFalse(page.contains(".background(MaterialTheme.colorScheme.surface.copy(alpha = .56f))"))
        assertTrue(page.contains("CycloneConversationBubble("))
        assertTrue(page.contains("if (session.busy) \"Thinking…\" else session.status"))
        assertTrue(bubble.contains(".background(userContainer)"))
        assertTrue(bubble.contains("CycloneConversationTokens.bubbleRadius"))
        assertFalse(page.contains("shadowElevation = 5.dp"))
        assertFalse(bubble.contains("shadowElevation"))
        assertFalse(design.substringAfter("fun CycloneGlassSurface").contains("shadowElevation = 4.dp"))
    }

    @Test fun dotFieldEmptyStateUsesPreferredAskComposition() {
        val page = source("CycloneV39AiChatPage.kt")
        val copy = source("ask/AskCopy.kt")
        val home = source("ask/AskScreen.kt")
        assertFalse(page.contains("CycloneAlpineBackdrop"))
        assertTrue(copy.contains("\"Ask Cyclone\""))
        assertTrue(copy.contains("\"Good morning\""))
        assertTrue(copy.contains("\"Good afternoon\""))
        assertTrue(copy.contains("\"Good evening\""))
        assertTrue(copy.contains("\"What can I do for you?\""))
        assertFalse(page.contains("progress today"))
        assertFalse(page.contains("Ideas become real"))
        assertFalse(page.contains("Contributor · prompts and responses may be used for training."))
        assertFalse(page.contains("Take a screenshot\", onSuggestion"))
        assertFalse(page.contains("compactHeader = true"))
        assertFalse(page.contains("expandInLayout = false"))
        // R3: no orb, no dot field, no teal canvas. Suggestions fill the bar; they never send.
        assertFalse(page.contains("AskCycloneOrb"))
        assertFalse(page.contains("AskCycloneDotField"))
        assertFalse(page.contains("askCycloneCanvasBrush"))
        assertTrue(page.contains("onSuggestion = { composer = it }"))
        assertTrue(home.contains("AskCopy.SUGGESTIONS"))
        val tools = page.indexOf("CycloneAttachmentTools(")
        val modelSheet = page.indexOf("AskModelSheet(")
        assertTrue(tools in 0 until modelSheet)
    }

    @Test fun inAppChatUsesTypeableFirstStageRetractionAndDraggablePlusSheet() {
        val page = source("CycloneV39AiChatPage.kt")
        val drawer = source("CycloneChatDrawer.kt")
        val minimized = source("CycloneMinimizedComposerBar.kt")
        assertTrue(page.contains("var drawerCollapsed by rememberSaveable"))
        assertTrue(page.contains("CycloneChatDrawerSurface("))
        assertTrue(page.contains("CycloneMinimizedComposerBar("))
        assertTrue(page.contains("label = \"Ask Cyclone retraction\""))
        assertTrue(page.contains("CycloneConversationTokens.stateTransitionMs"))
        assertFalse(page.contains("CycloneCollapsedAskPill("))
        assertTrue(page.contains("CycloneSheetDismissHandle("))
        assertTrue(page.contains("keyboardController?.hide()"))
        assertTrue(minimized.contains("GlassComposerBar("))
        assertTrue(source("InAppGlass.kt").contains("BasicTextField("))
        assertTrue(minimized.contains("fieldDescription = \"Ask Cyclone minimized composer\""))
        assertFalse(minimized.contains("modelLabel"))
        assertTrue(drawer.contains("Drag down or tap to minimize Cyclone chat"))
    }

    @Test fun plusPanelAndVoiceStayBehindOneComposer() {
        val page = source("CycloneV39AiChatPage.kt")
        val homeComposer = source("CycloneHomeComposer.kt")
        assertTrue(page.contains("CycloneAttachmentTools("))
        assertTrue(page.contains("onPhotos = { openPhotos() }"))
        assertTrue(page.contains("filesLabel = \"Files\""))
        assertTrue(page.contains("Create a routine"))
        assertTrue(page.contains("Model & intelligence"))
        assertTrue(homeComposer.contains("Icons.Rounded.PhotoLibrary"))
        assertTrue(homeComposer.contains("\"Photos\""))
        assertTrue(homeComposer.contains("Icons.Rounded.AttachFile, filesLabel"))
        assertFalse(page.contains("Take screenshot"))
        assertFalse(page.contains("Deep research"))
        assertFalse(page.contains("Explain this screen"))
        assertFalse(page.contains("Open app"))
        assertTrue(page.contains("AskCycloneVoiceMode"))
        assertTrue(page.contains("Listening…"))
        assertEquals(0, Regex("SignatureIcon\\(SignatureGlyph.ADD").findAll(page).count())
        assertEquals(1, Regex("SignatureIcon\\(SignatureGlyph.ADD").findAll(source("InAppGlass.kt")).count())
    }

    @Test fun askPlusContainsModelAndIntelligenceSelector() {
        val page = source("CycloneV39AiChatPage.kt")
        val controls = source("CycloneIntelligenceControls.kt")
        // R3: the header shows the model's name; the words come from AskCopy.modelLabel.
        assertTrue(page.contains("AskCopy.modelLabel("))
        assertTrue(page.contains("modelLabel = modelLabel"))
        assertTrue(page.contains("onChanged = ::persistAiControls"))
        assertTrue(controls.contains("private enum class OverlaySettingsStep { MODEL, INTELLIGENCE }"))
        assertFalse(controls.contains("OverlaySettingsStep.AUTONOMY"))
    }

    @Test fun headerModelNameStaysTwoWords() {
        assertEquals("DeepSeek Flash", cycloneShortModelLabel("DeepSeek: DeepSeek Flash Latest"))
        assertEquals("Muse Spark", cycloneShortModelLabel("Meta: Muse Spark 1.3 Contributor"))
        assertEquals("Cyclone", cycloneShortModelLabel(""))
    }

    @Test fun routineBuilderHasNoCallSurfaceFromChatPage() {
        val page = source("CycloneV39AiChatPage.kt")
        assertFalse(page.contains("buildWorkflow"))
        assertFalse(page.contains("V32RoutineBuilder"))
        assertFalse(page.contains("AutomationRuntime"))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/ui/v32/$name"
        val candidates = sequenceOf(
            File(relative),
            File("apps/mobile/app/$relative"),
            File(System.getProperty("user.dir"), relative),
            File(System.getProperty("user.dir"), "apps/mobile/app/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("Could not locate $relative from ${System.getProperty("user.dir")}", file)
        return file!!.readText()
    }
}
