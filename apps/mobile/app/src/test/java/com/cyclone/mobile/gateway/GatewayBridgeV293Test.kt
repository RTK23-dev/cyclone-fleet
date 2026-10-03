package com.cyclone.mobile.gateway

import com.cyclone.mobile.applearner.AppGraphSnapshot
import com.cyclone.mobile.applearner.FollowMeProgress
import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.applearner.LearnedApp
import com.cyclone.mobile.applearner.LearnedScreen
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.ScreenRecognition
import com.cyclone.mobile.guided.RoutineTeachingSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class GatewayBridgeV293Test {
    @Test
    fun protocolParsesFrozenRequestShape() {
        val request = GatewayProtocol.parse(
            """{"id":"abc","op":"observe.semantic","args":{"goal":"Apps"},"auth":"token"}""",
        )
        assertEquals("abc", request.id)
        assertEquals("observe.semantic", request.op)
        assertEquals("Apps", request.args.getString("goal"))
        assertEquals("token", request.auth)
    }

    @Test
    fun unknownOperationIsRejected() {
        try {
            GatewayProtocol.requireKnownOperation("root.shell", "req-1")
            fail("Unknown operation should be rejected")
        } catch (error: GatewayProtocolException) {
            assertEquals("UNKNOWN_OPERATION", error.code)
            assertEquals("req-1", error.requestId)
        }
    }

    @Test
    fun authRejectsMissingOrRotatedSessionToken() {
        assertTrue(GatewayAuth.matches("same-token", "same-token"))
        assertFalse(GatewayAuth.matches("same-token", "rotated-token"))
        assertFalse(GatewayAuth.matches("same-token", ""))
        assertFalse(GatewayAuth.matches(null, "same-token"))
    }

    @Test
    fun observationSanitizationRedactsEditableAndSensitiveNodes() {
        val snapshot = JSONObject()
            .put("package", "com.example")
            .put("nodes", JSONArray()
                .put(JSONObject()
                    .put("id", "field")
                    .put("resourceId", "com.example:id/search")
                    .put("editable", true)
                    .put("text", "private typed value")
                    .put("contentDescription", "Search"))
                .put(JSONObject()
                    .put("id", "otp")
                    .put("resourceId", "com.example:id/otp_code")
                    .put("editable", false)
                    .put("text", "123456")
                    .put("contentDescription", "OTP code")))
        val sanitized = GatewayPrivacy.sanitizeAccessibilitySnapshot(snapshot)
        val nodes = sanitized.getJSONArray("nodes")
        assertEquals("<redacted>", nodes.getJSONObject(0).getString("text"))
        assertEquals("Search", nodes.getJSONObject(0).getString("contentDescription"))
        assertEquals("<redacted>", nodes.getJSONObject(1).getString("text"))
        assertEquals("<redacted>", nodes.getJSONObject(1).getString("contentDescription"))
    }

    @Test
    fun phoneTypeParamsNeverEchoTypedValue() {
        val params = JSONObject()
            .put("selector", JSONObject().put("resourceId", "com.example:id/search"))
            .put("value", "do not export me")
            .put("timeoutMs", 1000)
        val safe = GatewayPrivacy.redactActionParams("phone.type", params)
        assertEquals("<redacted>", safe.getString("value"))
        assertFalse(safe.toString().contains("do not export me"))
        assertEquals("com.example:id/search", safe.getJSONObject("selector").getString("resourceId"))
    }

    @Test
    fun uiSearchRanksSemanticCandidateAndElementIdsAreObservationScoped() {
        val page = PageContext(
            pageKey = "page-key",
            packageName = "com.android.settings",
            className = "Settings",
            title = "Settings",
            structuralKey = "struct",
            contentKey = "content",
            controls = emptyList(),
            observationCount = 1,
            firstSeenAt = 1,
            lastSeenAt = 1,
        )
        val semanticId = "semantic:obs-1:continue"
        val rawId = "raw:obs-1:node-2"
        val semantic = GatewayElement(
            semanticId,
            "semantic",
            "Continue",
            "continue",
            "button",
            JSONObject()
                .put("resourceId", "com.example:id/continue")
                .put("contentDescription", "Continue")
                .put("bounds", JSONObject().put("left", 0).put("top", 0).put("right", 100).put("bottom", 60))
                .put("androidActions", JSONArray().put("ACTION_CLICK")),
        )
        val raw = GatewayElement(
            rawId,
            "raw_accessibility",
            "Continue later",
            "continue_later",
            "text",
            JSONObject().put("resourceId", "com.example:id/later").put("actions", JSONArray()),
        )
        val observation = GatewayObservation(
            "obs-1", 1, page, JSONObject(), linkedMapOf(semanticId to semantic, rawId to raw),
        )
        val results = GatewayObservationAdapter.search(observation, "Continue", 20)
        assertEquals(semanticId, results.getJSONObject(0).getString("elementId"))
        assertEquals("semantic", results.getJSONObject(0).getString("source"))
        try {
            GatewayObservationAdapter.element(observation, "semantic:old-observation:continue")
            fail("Stale IDs should fail")
        } catch (error: GatewayProtocolException) {
            assertEquals("STALE_ELEMENT", error.code)
        }
    }

    @Test
    fun multiwordSearchDoesNotReturnUnrelatedSettingsRows() {
        val page = PageContext("page", "com.android.settings", "Settings", "Settings", "struct", "content", emptyList(), 1, 1, 1)
        val timeout = GatewayElement("raw:obs:timeout", "raw_accessibility", "Screen timeout", "screen_timeout", "row", JSONObject())
        val dark = GatewayElement("raw:obs:dark", "raw_accessibility", "Dark theme", "dark_theme", "row", JSONObject())
        val rotation = GatewayElement("raw:obs:rotation", "raw_accessibility", "Auto-rotate screen", "auto_rotate_screen", "row", JSONObject())
        val observation = GatewayObservation("obs", 1, page, JSONObject(),
            linkedMapOf(timeout.id to timeout, dark.id to dark, rotation.id to rotation))
        val found = GatewayObservationAdapter.search(observation, "Auto-rotate screen setting", 20)
        assertEquals(1, found.length())
        assertEquals(rotation.id, found.getJSONObject(0).getString("elementId"))
        val generic = GatewayObservationAdapter.search(observation, "screen", 20)
        assertTrue(generic.length() >= 2)
    }

    @Test
    fun actionMappingUsesFrozenPhoneToolsAndNoLongerOwnsPolicy() {
        assertEquals(
            setOf(
                "phone.observe", "phone.find", "phone.click", "phone.long_press", "phone.swipe",
                "phone.scroll", "phone.type", "phone.back", "phone.home", "phone.open_app", "phone.wait_for",
            ),
            GatewayActionAdapter.allowedTools.toSet(),
        )
        assertFalse(GatewayActionAuthorityRegistry.isProductionAuthorityBound())
        val denied = GatewayActionAuthorityDecision(
            GatewayActionAuthorityOutcome.POLICY_DENIED,
            "V31_POLICY_DENY",
            "Denied by V3 policy.",
        )
        try {
            denied.requireAuthorized("req-policy")
            fail("Policy denial must stop the action handoff")
        } catch (error: GatewayProtocolException) {
            assertEquals("POLICY_DENIED", error.code)
        }
    }

    @Test
    fun androidVerificationRequiresExpectedPackageOrSemanticAfterStateWitness() {
        assertTrue(GatewayV33ActionAdapter.verifiedByAfterState(
            "phone.open_app", "com.android.settings", "before", "fp-1",
            "com.android.settings", "after", "fp-2",
        ))
        assertFalse(GatewayV33ActionAdapter.verifiedByAfterState(
            "phone.click", "", "settings-home", "fp-1",
            "com.android.settings", "settings-apps", "fp-2",
        ))
        assertTrue(GatewayV33ActionAdapter.verifiedByAfterState(
            "phone.click", "", "settings-home", "fp-1",
            "com.android.settings", "settings-apps", "fp-2",
            goalLabel = "Apps",
            afterHaystack = "Settings Apps",
        ))
        assertFalse(GatewayV33ActionAdapter.verifiedByAfterState(
            "phone.click", "", "settings-home", "fp-1",
            "com.android.settings", "settings-home", "fp-1",
        ))
    }

    @Test
    fun teachingStateMappingUsesCanonicalSessionMetadata() {
        val progress = FollowMeProgress(
            active = true,
            paused = false,
            currentPackage = "com.android.settings",
            screensSeen = 3,
            actionsSeen = 4,
            pathsLearned = 2,
            teachingSessionId = "teach-1",
            message = "Learning",
        )
        val session = RoutineTeachingSession(
            id = "teach-1",
            name = "Teach",
            modelId = "model",
            startedAt = 100,
            status = "ACTIVE",
        )
        val mapped = GatewayTeachingMapper.toJson(progress, "teach-1", session, 2, "page-apps", null)
        assertEquals("teach-1", mapped.getString("sessionId"))
        assertEquals("page-apps", mapped.getString("currentPageKey"))
        assertEquals(3, mapped.getInt("pageCount"))
        assertEquals(4, mapped.getInt("actionCount"))
        assertEquals(2, mapped.getInt("gestureCount"))
        assertEquals("ACTIVE", mapped.getString("canonicalSessionStatus"))
    }

    @Test
    fun pageDebugExportPreserves2500To450To80To36EvidenceWithoutPrompt() {
        val capture = JSONObject()
            .put("schema", "cyclone-page-debug-v293")
            .put("captureId", "capture-1")
            .put("capturedAt", 1)
            .put("package", "com.android.settings")
            .put("pageKey", "page-apps")
            .put("pageTitle", "Apps")
            .put("metrics", JSONObject()
                .put("rawAccessibilityCollectionLimit", 2500)
                .put("semanticNodeScanLimit", 450)
                .put("semanticControlStoreLimit", 80)
                .put("agentControlLimit", 36)
                .put("rawNodes", 921)
                .put("visibleNodes", 410)
                .put("visibleInteractive", 92)
                .put("unlabeledInteractive", 7)
                .put("semanticControls", 80)
                .put("agentControls", 36))
            .put("diagnosis", JSONObject().put("stage", "AGENT_CONTEXT_TRUNCATION"))
            .put("rawAccessibility", JSONObject().put("nodes", JSONArray()))
            .put("semanticPageFull", JSONObject().put("controls", JSONArray()))
            .put("agentInputCurrent", JSONObject().put("CURRENT_PAGE", JSONObject()))
            .put("agentInputFullControls", JSONObject().put("CURRENT_PAGE", JSONObject()))
            .put("agentSystemPrompt", "must not export")
        val exported = GatewayPageDebugAdapter.safeExport(capture)
        val funnel = exported.getJSONObject("funnel")
        assertEquals(2500, funnel.getInt("rawAccessibilityCollectionLimit"))
        assertEquals(450, funnel.getInt("semanticNodeScanLimit"))
        assertEquals(80, funnel.getInt("semanticControlStoreLimit"))
        assertEquals(36, funnel.getInt("agentControlLimit"))
        assertFalse(exported.has("agentSystemPrompt"))
        assertTrue(exported.getString("reasoningDisclosure").contains("hidden chain-of-thought"))
    }

    @Test
    fun appGraphQueryMatchesPageKeyWithoutDumpingWholeGraph() {
        val screen = LearnedScreen(
            id = "screen-apps",
            packageName = "com.android.settings",
            identity = "apps",
            title = "Apps",
            purpose = "Manage apps",
            recognition = ScreenRecognition(
                semanticFingerprint = "page-apps",
                structuralFingerprint = "structure",
                stableAnchors = listOf("apps"),
                className = "Settings",
                titleHints = listOf("Apps"),
            ),
            knowledgeState = KnowledgeState.VERIFIED,
            confidence = 0.9,
        )
        val graph = AppGraphSnapshot(
            app = LearnedApp(packageName = "com.android.settings", label = "Settings"),
            screens = listOf(screen),
            actions = emptyList(),
            transitions = emptyList(),
        )
        assertEquals("screen-apps", GatewayGraphQuery.matchedScreenId(graph, "page-apps"))
        assertEquals(null, GatewayGraphQuery.matchedScreenId(graph, "other-page"))
    }

    @Test
    fun brainRecallPrivacyRedactsCredentialFieldsAndProviderKeys() {
        val recall = JSONObject()
            .put("microSkills", JSONArray().put(JSONObject()
                .put("tool", "phone.type")
                .put("params", JSONObject().put("password", "hunter2").put("token", "secret-token"))))
            .put("provider", JSONObject().put("apiKey", "sk-abcdefghijklmnop"))
        val safe = GatewayPrivacy.sanitizeDeep(recall) as JSONObject
        val text = safe.toString()
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("secret-token"))
        assertFalse(text.contains("sk-abcdefghijklmnop"))
        assertTrue(text.contains("<redacted>"))
    }

    @Test
    fun boundedLineReaderHandlesAdbDisconnectAndOversizedInput() {
        assertEquals(
            "{\"id\":\"1\"}",
            GatewayLineReader.readUtf8Line(ByteArrayInputStream("{\"id\":\"1\"}\nnext".toByteArray()), 64),
        )
        assertEquals(null, GatewayLineReader.readUtf8Line(ByteArrayInputStream(ByteArray(0)), 64))
        try {
            GatewayLineReader.readUtf8Line(ByteArrayInputStream("abcd".toByteArray()), 3)
            fail("Oversized line should fail")
        } catch (error: GatewayProtocolException) {
            assertEquals("REQUEST_TOO_LARGE", error.code)
        }
    }
}
