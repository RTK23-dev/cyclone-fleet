package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.ai.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NavigationRunRecordTest {
    private val session = AiTraceSession("ai-nav-test", "open Gmail then open Chrome", "test-provider", "FAILED", 1, 100, "", 2)
    private val room = "screen:account:aaaaaaaaaaaaaaaa"
    private fun event(kind: String, detail: String, id: String = kind) =
        AiTraceEvent(id, session.id, 10, kind, kind, null, false, detail)
    private fun export(events: List<AiTraceEvent>) = NavigationRunRecord.enrich(
        RunInsight.detailJson(session, events), events, RunInsight.steps(events))

    @Test fun preservesLegacyPayloadAndAddsBoundedClauseFailureAndExpectedRoom() {
        assertFalse(export(emptyList()).has("clauses"))
        val clause = ClauseCompiler.compile("open Gmail").single().copy(status = ClauseStatus.FAILED)
        val events = listOf(event("TOOL_REQUESTED", "action=atlas:door · expectRoom=$room"),
            event("NAV_CLAUSE", clause.toJson().toString()))
        val result = export(events)
        assertEquals("clause-failed", result.getJSONObject("cause").getString("kind"))
        assertTrue(result.getJSONObject("cause").getString("headline").contains("open Gmail"))
        assertEquals(room, result.getJSONArray("steps").getJSONObject(0).getString("expectedRoomId"))
        assertEquals("map", result.getJSONArray("steps").getJSONObject(0).getString("decisionSource"))
    }

    @Test fun onlyMaskedLiveLedgerAndLastClauseStatusCrossTheWire() {
        val ledger = TaskLedger(0)
        ledger.record("signed-in-email", "jane@gmail.com", "package:com.google.android.gm", room,
            com.cyclone.mobile.brain.graphv2.AtlasPersona.LIVE, 10)
        val clause = ClauseCompiler.compile("open Gmail").single()
        val events = listOf(event("NAV_CLAUSE", clause.toJson().toString(), "start"),
            event("NAV_CLAUSE", clause.copy(status = ClauseStatus.VERIFIED).toJson().toString(), "done"),
            event("NAV_LEDGER", ledger.maskedTrace().getJSONObject(0).toString()))
        val result = export(events)
        assertEquals(1, result.getJSONArray("clauses").length())
        assertEquals("verified", result.getJSONArray("clauses").getJSONObject(0).getString("status"))
        assertEquals("j***@gmail.com", result.getJSONArray("ledger").getJSONObject(0).getString("value"))
        assertFalse(export(listOf(event("NAV_LEDGER", ledger.modelContext().toString()))).has("ledger"))
        assertFalse(export(listOf(event("NAV_LEDGER", "[{\"truncated\":"))).has("ledger"))
    }
}
