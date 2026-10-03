package com.cyclone.mobile.agent.integration

import com.cyclone.mobile.agent.contract.AgentActionEnvelope
import com.cyclone.mobile.agent.contract.AgentFailureClass
import com.cyclone.mobile.agent.contract.AgentFailureLayer
import com.cyclone.mobile.agent.contract.AgentInspectResult
import com.cyclone.mobile.agent.contract.AgentKnowledgeResult
import com.cyclone.mobile.agent.contract.AgentLearningResult
import com.cyclone.mobile.agent.contract.AgentObservationResult
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.agent.contract.AgentScreenshotResult
import com.cyclone.mobile.agent.contract.AgentSearchResult
import com.cyclone.mobile.agent.contract.AgentSemanticVerification
import com.cyclone.mobile.agent.contract.AgentStateDelta
import com.cyclone.mobile.agent.contract.AgentVerificationStatus
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironmentApi
import com.cyclone.mobile.ai.PageAgentAction
import com.cyclone.mobile.applearner.PageContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CyclonePcParityBridgeTest {
    @Test fun failedPixelsDoNotSpendUsableEvidenceAndOneRetryCanRecover() {
        val environment = FakeEnvironment(listOf(card("one", "page", "one", "fp"), card("two", "page", "two", "fp")))
        environment.images.add(JSONObject().put("available", false).put("errorCode", "IMAGE_CAPTURE_SKEW"))
        environment.images.add(JSONObject().put("available", true).put("pngBase64", "fixture"))
        val bridge = CyclonePcParityBridge(environment)
        var pauses = 0
        val result = bridge.captureVisualEvidence("reject cookies") { delay ->
            pauses++
            assertEquals(500L, delay)
            val recovery = bridge.promptContext("reject cookies").getJSONObject("recovery")
            assertEquals(1, recovery.getInt("visualCaptureAttempts"))
            assertEquals(0, recovery.getInt("usableVisualCaptures"))
            assertFalse(recovery.getJSONArray("attemptedEvidence").toString().contains("SCREENSHOT_VISION"))
        }
        assertTrue(result!!.image!!.getBoolean("available"))
        assertEquals("two", result.page!!.observationId)
        assertEquals(2, environment.imageCalls); assertEquals(1, pauses)
        assertFalse(bridge.claimVisionCapture())
        assertEquals(1, bridge.promptContext("reject cookies").getJSONObject("recovery").getInt("usableVisualCaptures"))
    }

    @Test fun repeatedImageFailureStopsAtTwoAttemptsWithoutClaimingVisualEvidence() {
        val environment = FakeEnvironment(listOf(card("one", "page", "one", "fp"), card("two", "page", "two", "fp")))
        repeat(2) { environment.images.add(JSONObject().put("available", false)) }
        val bridge = CyclonePcParityBridge(environment)
        assertFalse(bridge.captureVisualEvidence("login") {}!!.image!!.getBoolean("available"))
        assertEquals(2, environment.imageCalls)
        assertFalse(bridge.claimVisionCapture())
        assertEquals(0, bridge.promptContext("login").getJSONObject("recovery").getInt("usableVisualCaptures"))
    }

    @Test fun groundingFailureEscalatesDespitePageChurnAndSurvivesNextObserve() {
        val bridge = CyclonePcParityBridge(FakeEnvironment(listOf(
            card("one", "page-one", "one", "fp-one"),
            card("two", "page-two", "two", "fp-two"),
            card("three", "page-three", "three", "fp-three"))))
        val cause = com.cyclone.mobile.agent.recovery.RecoverableCause.STALE_SELECTOR
        bridge.observe("reject cookies")
        assertEquals(com.cyclone.mobile.agent.recovery.RecoveryLevel.GOAL_RANKED_SEARCH,
            bridge.recover(cause, "reject cookies", 1)?.level)
        bridge.observe("reject cookies")
        assertEquals(com.cyclone.mobile.agent.recovery.RecoveryLevel.SILENT_SCREENSHOT_VISION,
            bridge.recover(cause, "reject cookies", 2)?.level)
        bridge.observe("reject cookies")
        assertTrue(bridge.consumeForcedVision())
        assertFalse(bridge.consumeForcedVision())
        assertTrue(bridge.claimVisionCapture())
        assertFalse(bridge.claimVisionCapture())
        bridge.observe("open reddit.com")
        assertFalse(bridge.claimVisionCapture())
        bridge.markVerifiedProgress()
        assertTrue(bridge.claimVisionCapture())
    }

    private class FakeEnvironment(cards: List<AgentPageCard>) : CycloneAgentEnvironmentApi {
        val images = java.util.ArrayDeque<JSONObject>()
        var imageCalls = 0
        private val cards = cards.toMutableList()
        private var current: AgentPageCard? = null
        var lastTool: String? = null
        var lastParams: JSONObject? = null

        override fun observe(goal: String): AgentObservationResult =
            AgentObservationResult(page = next())

        override fun observeWithImage(goal: String): AgentObservationResult {
            imageCalls++
            return AgentObservationResult(page = next(), image = images.pollFirst())
        }

        override fun locate(goal: String): AgentSearchResult {
            val card = next()
            return AgentSearchResult(
                page = card,
                observationId = card.observationId,
                generation = card.generation,
                query = goal,
                goal = goal,
            )
        }

        override fun search(query: String, goal: String): AgentSearchResult =
            AgentSearchResult(
                page = current,
                observationId = current?.observationId,
                generation = current?.generation,
                query = query,
                goal = goal,
            )

        override fun inspect(elementId: String): AgentInspectResult =
            AgentInspectResult(elementId = elementId)

        override fun screenshot(goal: String): AgentScreenshotResult =
            AgentScreenshotResult(goal = goal)

        override fun act(tool: String, params: JSONObject, goal: String): AgentActionEnvelope {
            lastTool = tool
            lastParams = JSONObject(params.toString())
            return AgentActionEnvelope(
                tool = tool,
                goal = goal,
                androidExecutionOk = true,
                executorReportedOk = true,
                verification = AgentSemanticVerification(AgentVerificationStatus.NOT_REQUIRED, false, false),
                before = current,
                after = current,
                pageChanged = false,
                delta = AgentStateDelta(false, false, false, emptyList(), false, "test"),
                errorClass = AgentFailureClass.NONE,
                failureLayer = AgentFailureLayer.NONE,
                retryable = false,
                semanticSuccessClaimed = false,
                beforeObservationId = current?.observationId,
                afterObservationId = current?.observationId,
                observationGeneration = current?.generation,
                learning = AgentLearningResult(false, "test"),
            )
        }

        override fun history(): List<AgentActionEnvelope> = emptyList()

        override fun brainRecall(goal: String): AgentKnowledgeResult =
            AgentKnowledgeResult(goal = goal)

        override fun knownRoutes(goal: String): AgentKnowledgeResult =
            AgentKnowledgeResult(goal = goal)

        private fun next(): AgentPageCard {
            if (cards.isNotEmpty()) current = cards.removeAt(0)
            return checkNotNull(current)
        }
    }

    @Test
    fun rotatedObservationIdDoesNotFakeNewSemanticEvidence() {
        val bridge = CyclonePcParityBridge(
            FakeEnvironment(
                listOf(
                    card("obs-1", pageKey = "settings-root", contentKey = "same", fingerprint = "fp"),
                    card("obs-2", pageKey = "settings-root", contentKey = "same", fingerprint = "fp"),
                ),
            ),
        )
        bridge.observe("Open Apps")
        val first = checkNotNull(bridge.observation())
        bridge.observe("Open Apps")
        val second = checkNotNull(bridge.observation())

        assertEquals(first.identity, second.identity)
        assertEquals(first.evidenceIdentity, second.evidenceIdentity)
    }

    @Test
    fun realSemanticEvidenceChangeChangesConvergenceWitness() {
        val bridge = CyclonePcParityBridge(
            FakeEnvironment(
                listOf(
                    card("obs-1", pageKey = "settings-root", contentKey = "one", fingerprint = "fp-1"),
                    card("obs-2", pageKey = "settings-root", contentKey = "two", fingerprint = "fp-2"),
                ),
            ),
        )
        bridge.observe("Open Apps")
        val first = checkNotNull(bridge.observation())
        bridge.observe("Open Apps")
        val second = checkNotNull(bridge.observation())

        assertNotEquals(first.identity, second.identity)
    }

    @Test
    fun pageAgentAppNameIsNormalizedBeforePcParityExecution() {
        val environment = FakeEnvironment(listOf(card("obs-1", "cyclone", "c", "fp")))
        val bridge = CyclonePcParityBridge(environment)
        bridge.observe("Open Chrome")
        val action = PageAgentAction(
            tool = "phone.open_app",
            controlId = null,
            params = JSONObject().put("appName", "Chrome"),
            expectedPageChange = true,
            displaySummary = "Open Chrome",
        )

        bridge.act(action, legacyPage(), "Open Chrome")

        assertEquals("phone.open_app", environment.lastTool)
        assertEquals("com.android.chrome", environment.lastParams?.getString("package"))
    }

    @Test
    fun pageAgentHttpsIntentSurvivesNormalizationAsAlternativeBrowserStrategy() {
        val environment = FakeEnvironment(listOf(card("obs-1", "cyclone", "c", "fp")))
        val bridge = CyclonePcParityBridge(environment)
        bridge.observe("Open ad.nl")
        val action = PageAgentAction(
            tool = "phone.launch_intent",
            controlId = null,
            params = JSONObject().put("uri", "https://ad.nl"),
            expectedPageChange = true,
            displaySummary = "Open ad.nl another way",
        )

        bridge.act(action, legacyPage(), "Open ad.nl")

        assertEquals("phone.launch_intent", environment.lastTool)
        assertEquals("https://ad.nl", environment.lastParams?.getString("uri"))
    }

    @Test
    fun completionTargetsFinalGoalSegmentInsteadOfGenericEarlierPageWords() {
        val bridge = CyclonePcParityBridge(
            FakeEnvironment(
                listOf(
                    card(
                        "obs-root",
                        pageKey = "settings-root",
                        contentKey = "root",
                        fingerprint = "root-fp",
                        summary = "Android Settings Apps Network Battery",
                    ),
                    card(
                        "obs-pip",
                        pageKey = "picture-in-picture",
                        contentKey = "pip",
                        fingerprint = "pip-fp",
                        summary = "Picture-in-picture app access",
                    ),
                ),
            ),
        )

        bridge.observe("Open Settings, then Picture-in-picture")
        assertFalse(bridge.completionEvidence("Open Settings, then Picture-in-picture"))

        bridge.observe("Open Settings, then Picture-in-picture")
        assertTrue(bridge.completionEvidence("Open Settings, then Picture-in-picture"))
    }

    private fun legacyPage() = PageContext(
        pageKey = "legacy",
        packageName = "com.cyclone.mobile",
        className = "MainActivity",
        title = "Cyclone",
        structuralKey = "struct",
        contentKey = "content",
        controls = emptyList(),
        observationCount = 1,
        firstSeenAt = 1,
        lastSeenAt = 1,
    )

    private fun card(
        observationId: String,
        pageKey: String,
        contentKey: String,
        fingerprint: String,
        summary: String = "Android Settings",
    ) = AgentPageCard(
        observationId = observationId,
        generation = 1,
        actionable = true,
        capturedAtMs = 1,
        packageName = "com.android.settings",
        activity = "Settings",
        pageKey = pageKey,
        structuralKey = "struct-$pageKey",
        contentKey = contentKey,
        accessibilityFingerprint = fingerprint,
        pageSummary = JSONObject().put("summary", summary),
        pageText = JSONObject().put("text", summary),
        pageEvidence = JSONObject().put("rawNodeCount", 10),
        controls = emptyList(),
        nextHopHints = JSONArray(),
    )
}
