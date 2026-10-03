package com.cyclone.mobile.agent.tools

import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.gateway.GatewayElement
import com.cyclone.mobile.gateway.GatewayObservation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ObservationProjectionTest {
    @Test fun shadowDetectsEachExecutableFieldIndependentlyWithoutLeakingValues() {
        val card = ObservationProjections.pageCard(source(), "", 1, true)
        val changes = mapOf<String, Any>("bounds" to JSONObject().put("left", 50), "enabled" to false,
            "clickable" to false, "longClickable" to true, "editable" to true, "scrollable" to true,
            "visibleToUser" to false, "actions" to JSONArray().put("private_action"),
            "androidActions" to JSONArray().put("private_action"), "selected" to true, "checked" to true,
            "focused" to true, "resourceId" to "private_id", "contentDescription" to "private_text",
            "selector" to JSONObject().put("text", "private_text"))
        changes.forEach { (field, value) ->
            val changed = card.copy(controls = card.controls.map { it.copy(evidence = JSONObject(it.evidence.toString()).put(field, value)) })
            val report = ObservationProjections.shadow(card, changed)
            assertFalse(field, report.getBoolean("matches"))
            assertTrue(report.getJSONArray("differences").toString().contains("control_$field"))
            assertFalse(report.toString().contains("private_"))
        }
    }

    @Test fun shadowComparesCompleteCaptureIdentityAndActionability() {
        val card = ObservationProjections.pageCard(source(), "", 1, true)
        val identity = card.observation!!
        val changes = listOf(identity.copy(profileId = 13), identity.copy(windowSignature = "other"),
            identity.copy(rotation = 2), identity.copy(generation = 78), identity.copy(startedMonotonicMs = 11),
            identity.copy(executionGeneration = 2), identity.copy(captureClock = "other"), identity.copy(freshness = "stale"))
        changes.forEach { assertFalse(ObservationProjections.shadow(card, card.copy(observation = it)).getBoolean("matches")) }
        assertFalse(ObservationProjections.shadow(card, card.copy(actionable = false)).getBoolean("matches"))
    }
    @Test fun learnedControlsAndPreviewCannotMasqueradeAsCurrentEvidence() {
        val learned = source().page.copy(controls = listOf(com.cyclone.mobile.applearner.PageControl(
            "historical", "Old button", "old", "button", JSONObject(), emptyList(), com.cyclone.mobile.applearner.ActionRisk.SAFE)),
            previewPath = "old-frame.png")
        val current = ObservationProjections.freshLegacy(JSONObject().put("package", "browser").put("nodes", JSONArray()).put("timestampMs", 200), learned)
        assertTrue(current.controls.isEmpty())
        assertNull(current.previewPath)
        assertEquals(200L, current.lastSeenAt)
    }
    private fun source(id: String = "one", generation: Long = 77): GatewayObservation {
        val elementId = "semantic:$id:reject"
        val evidence = JSONObject().put("elementId", elementId).put("observationId", id)
            .put("label", "Reject optional cookies").put("semanticName", "reject").put("role", "button")
            .put("clickable", true).put("enabled", true)
        return GatewayObservation(id, 100, PageContext("cookie", "browser", "Activity", "Consent", "s", "c",
            emptyList(), 1, 100, 100), JSONObject().put("activity", "Activity").put("semanticControls", JSONArray().put(evidence))
            .put("pageEvidence", JSONObject().put("captureStartMonotonicMs", 10).put("captureEndMonotonicMs", 25)
                .put("profileId", 12).put("captureWidth", 100).put("captureHeight", 200).put("rotation", 0).put("windowSignature", "w1"))
            .put("windows", JSONArray().put(JSONObject().put("id", 1).put("type", 1))),
            mapOf(elementId to GatewayElement(elementId, "semantic", "Reject optional cookies", "reject", "button", evidence)), generation = generation)
    }

    @Test fun oneCaptureFeedsEveryProjectionWithoutCallingTheSourceAgain() {
        var captures = 0
        fun capture(): GatewayObservation { captures++; return source(if (captures == 1) "one" else "changed", captures.toLong()) }
        val captured = capture()
        repeat(5) {
            val card = ObservationProjections.pageCard(captured, "cookies", 99, true)
            val prompt = ObservationProjections.prompt(card)
            val snapshot = ObservationProjections.snapshot(card)
            val learning = card.legacyPage!!.toAgentJson()
            assertEquals(1L, card.generation)
            listOf(prompt, snapshot, learning).forEach { projection ->
                val identity = projection.getJSONObject("observation")
                assertEquals("one", identity.getString("evidenceId"))
                assertEquals(1L, identity.getLong("generation"))
                assertEquals(12, identity.getInt("profileId"))
            }
            assertEquals(1L, card.controls.single().evidence.getLong("generation"))
        }
        assertEquals(1, captures)
    }

    @Test fun missingFieldsAreExplicitAndNeverBorrowedFromAnotherCapture() {
        val first = ObservationProjections.pageCard(source(), "", 1, true)
        val next = ObservationProjections.pageCard(source("two", 78).copy(payload = JSONObject()), "", 2, true)
        assertEquals(12, first.observation!!.profileId)
        val identity = next.observation!!.toJson()
        assertTrue(identity.isNull("profileId"))
        assertTrue(identity.isNull("rotation"))
        assertEquals("unavailable", identity.getJSONObject("fieldState").getString("captureTiming"))
        assertEquals(78L, next.legacyPage!!.observation!!.generation)
    }

    @Test fun shadowFindsPageAndScopeRacesWithoutIncludingRawValues() {
        val card = ObservationProjections.pageCard(source(), "", 1, true)
        assertTrue(ObservationProjections.shadow(card, card).getBoolean("matches"))
        val report = ObservationProjections.shadow(card, card.copy(pageKey = "private changed page", displayId = 8))
        assertFalse(report.getBoolean("matches"))
        assertTrue(report.getJSONArray("differences").toString().contains("scope"))
        assertFalse(report.toString().contains("private changed page"))
        assertEquals(0, report.getInt("projectionCaptureCount"))
    }

    @Test fun modifyingPromptDoesNotMutateSourceEvidence() {
        val captured = source()
        val card = ObservationProjections.pageCard(captured, "", 1, true)
        card.controls.single().evidence.put("label", "changed")
        assertEquals("Reject optional cookies", captured.elements.values.single().evidence.getString("label"))
        assertEquals(77L, card.observation!!.generation)
    }
}
