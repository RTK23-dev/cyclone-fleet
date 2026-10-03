package com.cyclone.mobile.ai

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageAgentProtocolTest {
    @Test fun rotatedCaptureIdsAndPageNoiseDoNotHideRepeatedIntent() {
        fun decision(id: String) = PageAgentDecision("act", "", "", listOf(
            PageAgentAction("phone.click", id, JSONObject(), true, "Reject optional cookies")), null, null)
        assertEquals(PageAgentProtocol.actionSignature(decision("semantic:capture-a:target"), "page-one"),
            PageAgentProtocol.actionSignature(decision("semantic:capture-b:target"), "page-two"))
        assertFalse(PageAgentProtocol.actionSignature(decision("semantic:capture-a:target"), "page-one") ==
            PageAgentProtocol.actionSignature(decision("semantic:capture-b:other-target"), "page-two"))
    }

    private val control = PageControl(
        key = "battery-control",
        label = "Battery",
        semanticName = "battery",
        role = "button",
        selector = JSONObject().put("resourceId", "com.android.settings:id/battery").put("text", "Battery").put("clickable", true),
        androidActions = listOf("ACTION_CLICK"),
        risk = ActionRisk.SAFE,
        confidence = .9,
    )
    private val page = PageContext(
        pageKey = "settings:root",
        packageName = "com.android.settings",
        className = "com.android.settings.Settings",
        title = "Settings",
        structuralKey = "structure",
        contentKey = "content",
        controls = listOf(control),
        observationCount = 1,
        firstSeenAt = 1L,
        lastSeenAt = 1L,
    )

    @Test
    fun pageControlIdResolvesToLocalSemanticSelector() {
        val decision = PageAgentProtocol.parse(
            """{"status":"act","pageSummary":"Settings menu","displaySummary":"Opening Battery","actions":[{"tool":"phone.click","controlId":"battery-control","params":{},"expectedPageChange":true,"displaySummary":"Opening Battery"}]}""",
        )
        val params = PageAgentProtocol.resolveParams(decision.actions.single(), page).getOrThrow()
        assertEquals("com.android.settings:id/battery", params.getJSONObject("selector").getString("resourceId"))
        assertTrue(PageAgentProtocol.shouldStopBatch(decision.actions.single(), page, page.copy(pageKey = "battery:page")))
    }

    @Test
    fun invalidInventedControlIsRejectedLocally() {
        val action = PageAgentAction("phone.click", "made-up-id", JSONObject(), true, "Clicking")
        assertTrue(PageAgentProtocol.resolveParams(action, page).isFailure)
    }

    @Test
    fun openAppRepairsCommonAppNameIntoRequiredPackage() {
        val action = PageAgentAction(
            "phone.open_app",
            null,
            JSONObject().put("appName", "Chrome"),
            true,
            "Open Chrome",
        )
        val params = PageAgentProtocol.resolveParams(action, page).getOrThrow()
        assertEquals("com.android.chrome", params.getString("package"))
        assertEquals(
            "phone.open_app:package=com.android.chrome",
            PageAgentProtocol.actionSignature(
                PageAgentDecision("act", "", "", listOf(action), null, null),
                page.pageKey,
            ),
        )
    }

    @Test
    fun openAppWithoutResolvablePackageIsRejectedBeforeAndroid() {
        val action = PageAgentAction(
            "phone.open_app",
            null,
            JSONObject().put("appName", "Unrecognizable Browser XYZ"),
            true,
            "Open the requested app",
        )
        assertTrue(PageAgentProtocol.resolveParams(action, page).isFailure)
    }

    @Test
    fun launchIntentAcceptsOnlyHttpOrHttpsInLocalModelContract() {
        val safe = PageAgentAction(
            "phone.launch_intent",
            null,
            JSONObject().put("uri", "https://ad.nl"),
            true,
            "Open ad.nl another way",
        )
        assertTrue(PageAgentProtocol.resolveParams(safe, page).isSuccess)

        val unsafe = safe.copy(params = JSONObject().put("uri", "javascript:alert(1)"))
        assertTrue(PageAgentProtocol.resolveParams(unsafe, page).isFailure)
    }

    @Test
    fun signaturesAndDiagnosticsNeverEchoTypedSecretsOrUrlQueries() {
        val typed = PageAgentAction(
            "phone.type",
            "battery-control",
            JSONObject().put("value", "secret-typed-value"),
            false,
            "Fill field",
        )
        val detail = PageAgentProtocol.diagnosticActionDetail(typed)
        val typedSignature = PageAgentProtocol.actionSignature(
            PageAgentDecision("act", "", "", listOf(typed), null, null),
            page.pageKey,
        ).orEmpty()
        assertFalse(detail.contains("secret-typed-value"))
        assertFalse(typedSignature.contains("secret-typed-value"))
        assertTrue(detail.contains("REDACTED_TYPED_VALUE"))

        val launch = PageAgentAction(
            "phone.launch_intent",
            null,
            JSONObject().put("uri", "https://ad.nl/search?q=private#fragment"),
            true,
            "Open site",
        )
        val launchSignature = PageAgentProtocol.actionSignature(
            PageAgentDecision("act", "", "", listOf(launch), null, null),
            page.pageKey,
        ).orEmpty()
        assertEquals("phone.launch_intent:uri=https://ad.nl/search", launchSignature)
        assertFalse(launchSignature.contains("private"))
    }

    @Test
    fun parseDropsLaterScreenChangingActionsForNavIsolation() {
        val decision = PageAgentProtocol.parse(
            """{"status":"act","pageSummary":"Home","displaySummary":"Opening then going back","actions":[
                {"tool":"phone.click","controlId":"battery-control","params":{},"expectedPageChange":true,"displaySummary":"Open"},
                {"tool":"phone.back","controlId":"","params":{},"expectedPageChange":true,"displaySummary":"Back"}
            ]}""",
        )
        assertEquals(1, decision.actions.size)
        assertEquals("phone.click", decision.actions.single().tool)
    }

    @Test
    fun parseKeepsFormBatchThenOneNav() {
        val decision = PageAgentProtocol.parse(
            """{"status":"act","pageSummary":"Form","displaySummary":"Fill then submit","actions":[
                {"tool":"phone.type","controlId":"battery-control","params":{"value":"x"},"expectedPageChange":false,"displaySummary":"Type"},
                {"tool":"phone.click","controlId":"battery-control","params":{},"expectedPageChange":true,"displaySummary":"Submit"}
            ]}""",
        )
        assertEquals(2, decision.actions.size)
        assertEquals("phone.type", decision.actions[0].tool)
        assertEquals("phone.click", decision.actions[1].tool)
    }

    @Test
    fun freeModeInstructionsRemainBoundedByPolicyAndRequireDifferentStrategy() {
        assertTrue(PageAgentProtocol.SYSTEM_PROMPT.contains("FREE mode"))
        assertTrue(PageAgentProtocol.SYSTEM_PROMPT.contains("materially different", ignoreCase = true))
        assertTrue(PageAgentProtocol.SYSTEM_PROMPT.contains("never bypasses policy", ignoreCase = true))
        assertTrue(PageAgentProtocol.SYSTEM_PROMPT.contains("phone.launch_intent"))
        assertTrue(PageAgentProtocol.SYSTEM_PROMPT.contains("fastPathLanding") || PageAgentProtocol.SYSTEM_PROMPT.contains("open_app"))
        assertTrue(PageAgentProtocol.SYSTEM_PROMPT.contains("fingerprint"))

        val context = PageAgentProtocol.context(
            goal = "open Chrome and visit ad.nl",
            page = page,
            transitions = JSONArray(),
            appGraph = null,
            brain = JSONObject(),
            successfulActions = emptyList(),
            failedActions = listOf("phone.open_app::INVALID_REQUEST"),
        )
        assertTrue(context.getJSONObject("RUN_STATE").getJSONArray("failedActions").length() == 1)
    }

    @Test
    fun lunaIsBalancedDefaultAndGeminiFlashIsCurrent() {
        assertEquals("openai/gpt-5.6-luna", OpenRouterModelPresets.DEFAULT.id)
        assertEquals("medium", OpenRouterModelPresets.GPT_5_6_LUNA.reasoningEffort)
        assertEquals("google/gemini-3.8-flash", OpenRouterModelPresets.GEMINI_3_8_FLASH.id)
        assertEquals(6, QuickAgentConfig().maxDecisions)
    }
}
