package com.cyclone.mobile.gateway

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.AppGraphSnapshot
import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.LearnedApp
import com.cyclone.mobile.applearner.LearnedScreen
import com.cyclone.mobile.applearner.LearnedTransition
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.PageControl
import com.cyclone.mobile.applearner.ScreenRecognition
import com.cyclone.mobile.brain.BrainMicroSkill
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRouteEvidenceTest {
    @Test
    fun verifiedSemanticGraphAndBrainHopsAreBoundedAdvisoryAndCoordinateFree() {
        val page = page()
        val source = screen("source", page.pageKey, "Home")
        val safe = action(source.id, "safe", "Open settings", selector("id/settings"), ActionRisk.SAFE)
        val unsafe = action(source.id, "unsafe", "Delete", selector("id/delete"), ActionRisk.CONSEQUENTIAL)
        val coordinateOnly = action(source.id, "coordinate", "Loose control", JSONObject().put("x", 10).put("y", 10), ActionRisk.SAFE)
        val target = screen("settings", "pkg:Settings:next", "Settings")
        val graph = AppGraphSnapshot(
            LearnedApp("pkg", "Example"),
            listOf(source, target),
            listOf(safe, unsafe, coordinateOnly),
            listOf(
                transition(source.id, safe.id, target.id, 0.94),
                transition(source.id, unsafe.id, target.id, 0.99),
                transition(source.id, coordinateOnly.id, target.id, 0.99),
            ),
        )
        val brain = BrainMicroSkill(
            signature = "brain-safe",
            name = "Open inbox",
            tool = "phone.click",
            paramsJson = JSONObject().put("selector", selector("id/inbox")).toString(),
            goalHints = "open inbox",
            fromPackage = "pkg",
            fromFingerprint = "fp-1",
            toPackage = "pkg",
            toFingerprint = "fp-2",
            successCount = 3,
            failureCount = 0,
            confidence = 0.91,
            source = "PC_CODEX_VERIFIED_ROUTE",
            lastUsedAt = 99_900,
        )

        val hints = GatewayRouteEvidence.nextHops(page, "fp-1", graph, listOf(brain), nowMs = 100_000)

        assertEquals(2, hints.length())
        assertEquals("APP_GRAPH", hints.getJSONObject(0).getString("source"))
        assertEquals("BRAIN", hints.getJSONObject(1).getString("source"))
        for (index in 0 until hints.length()) {
            val hint = hints.getJSONObject(index)
            assertTrue(hint.getBoolean("advisory"))
            assertTrue(hint.getJSONObject("action").getBoolean("coordinateFree"))
            assertFalse(hint.getJSONObject("action").getJSONObject("selector").has("x"))
            assertEquals("action.execute", hint.getJSONObject("invocation").getString("operation"))
            assertEquals("FRESH", hint.getJSONObject("freshness").getString("state"))
        }
    }

    @Test
    fun pageEvidenceKeepsIdentityContentCountsAndHintsInOneBoundedContract() {
        val text = JSONObject().put("lineCount", 2).put("truncated", false)
        val summary = JSONObject().put("summary", "Home")
        val evidence = GatewayRouteEvidence.pageEvidence(
            page = page(),
            packageName = "pkg",
            activity = "pkg.HomeActivity",
            pageText = text,
            pageSummary = summary,
            semanticControls = 4,
            supplementalControls = 2,
            rawNodes = 20,
            windows = 1,
            nextHopHints = org.json.JSONArray(),
        )

        assertEquals("pkg", evidence.getString("package"))
        assertEquals("pkg.HomeActivity", evidence.getString("activity"))
        assertEquals("Home", evidence.getString("pageTitle"))
        assertEquals(6, evidence.getJSONObject("counts").getInt("totalControls"))
        assertEquals(2, evidence.getJSONObject("pageText").getInt("lineCount"))
        assertTrue(evidence.getBoolean("hintsAdvisory"))
    }

    private fun page() = PageContext(
        pageKey = "pkg:Home:source",
        packageName = "pkg",
        className = "pkg.HomeActivity",
        title = "Home",
        structuralKey = "source",
        contentKey = "content",
        controls = listOf(PageControl("settings", "Open settings", "open_settings", "button", selector("id/settings"), listOf("ACTION_CLICK"), ActionRisk.SAFE)),
        observationCount = 1,
        firstSeenAt = 1,
        lastSeenAt = 1,
    )

    private fun screen(id: String, pageKey: String, title: String) = LearnedScreen(
        id = id,
        packageName = "pkg",
        identity = id,
        title = title,
        purpose = title,
        recognition = ScreenRecognition(pageKey, "structure-$id", listOf(title), "Activity", listOf(title)),
        knowledgeState = KnowledgeState.VERIFIED,
        confidence = 0.95,
    )

    private fun action(screenId: String, name: String, label: String, selector: JSONObject, risk: ActionRisk) = LearnedAction(
        id = name,
        packageName = "pkg",
        screenId = screenId,
        semanticName = name,
        label = label,
        androidActions = listOf("ACTION_CLICK"),
        selectorJson = selector.toString(),
        risk = risk,
        knowledgeState = KnowledgeState.VERIFIED,
        confidence = 0.95,
    )

    private fun transition(from: String, action: String, to: String, confidence: Double) = LearnedTransition(
        packageName = "pkg",
        fromScreenId = from,
        actionId = action,
        toScreenId = to,
        knowledgeState = KnowledgeState.VERIFIED,
        confidence = confidence,
        observedCount = 3,
        successfulCount = 3,
        lastObservedAt = 99_900,
    )

    private fun selector(resource: String) = JSONObject().put("resourceId", resource).put("clickable", true)
}
