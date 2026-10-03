package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.ai.AiTraceEvent
import com.cyclone.mobile.ai.RunInsight
import org.json.JSONArray
import org.json.JSONObject

/** Phone-owned projection. Older traces have no navigation fields; malformed/truncated rows are omitted. */
object NavigationRunRecord {
    fun enrich(record: JSONObject, events: List<AiTraceEvent>, steps: List<RunInsight.Step>): JSONObject {
        val clauses = linkedMapOf<String, JSONObject>()
        val facts = linkedMapOf<String, JSONObject>()
        for (event in events) {
            if (event.kind == "NAV_CLAUSE") runCatching {
                val row = JSONObject(event.detail.orEmpty())
                val id = row.optString("id")
                val status = row.optString("status")
                val place = row.opt("place") as? String
                if (!Regex("clause-(?:[1-9]|1[0-6])").matches(id) || status !in ClauseStatus.values().map { it.wire } ||
                    (place != null && !PLACE.matches(place))) return@runCatching
                clauses[id] = JSONObject().put("id", id).put("text", RunInsight.wireText(row.optString("text"), 500))
                    .put("place", place ?: JSONObject.NULL).put("status", status)
                    .put("proof", (row.opt("proof") as? String)?.let { RunInsight.wireText(it, 400) } ?: JSONObject.NULL)
            }
            if (event.kind == "NAV_LEDGER") runCatching {
                val detail = event.detail.orEmpty()
                val rows = if (detail.startsWith("[")) JSONArray(detail) else JSONArray().put(JSONObject(detail))
                for (index in 0 until minOf(4, rows.length())) {
                    val row = rows.optJSONObject(index) ?: continue
                    val key = row.optString("key")
                    val value = row.optString("value")
                    val place = row.optString("sourcePlace")
                    val room = row.optString("sourceRoom")
                    val at = row.optLong("readAtMs", -1)
                    if (key !in KEYS || !MASKED.matches(value) || !PLACE.matches(place) || !ROOM.matches(room) ||
                        row.optString("persona") != "live" || at < 0) continue
                    facts[key] = JSONObject().put("key", key).put("value", value).put("sourcePlace", place)
                        .put("sourceRoom", room).put("persona", "live").put("readAtMs", at)
                }
            }
        }
        if (clauses.isNotEmpty()) record.put("clauses", JSONArray(clauses.values.toList()))
        if (facts.isNotEmpty()) record.put("ledger", JSONArray(facts.values.toList()))
        val failed = clauses.values.lastOrNull { it.optString("status") == "failed" }
        val cause = record.optJSONObject("cause")
        if (record.optString("status") == "failed" && failed != null &&
            cause?.optString("kind") !in setOf("gate", "needs-secret", "human-took-control", "transport")) {
            record.put("cause", JSONObject().put("kind", "clause-failed")
                .put("stepIndex", cause?.opt("stepIndex") ?: JSONObject.NULL)
                .put("headline", "Clause failed: ${failed.optString("text")}".take(400))
                .put("detail", RunInsight.wireText(cause?.optString("detail"), 400))
                .put("fix", "Inspect this clause's live proof and last action; earlier verified clauses are shown separately."))
        }
        record.optJSONArray("steps")?.let { output ->
            for (index in 0 until output.length()) {
                val row = output.optJSONObject(index) ?: continue
                val step = steps.firstOrNull { it.index == row.optInt("index", -1) } ?: continue
                val expected = step.events.firstNotNullOfOrNull {
                    Regex("(?:^|\\s)expectRoom=([^\\s·]+)").find(it.detail.orEmpty())?.groupValues?.get(1)
                }?.takeIf { ROOM.matches(it) }
                if (expected != null) row.put("expectedRoomId", expected)
            }
        }
        return record
    }

    private val KEYS = setOf("signed-in-email", "found-username", "thread-with", "connected-network")
    private val MASKED = Regex("[^\\s*@]\\*{3}(?:@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})?")
    private val ROOM = Regex("screen:[a-z_]{1,40}:[0-9a-f]{8,64}")
    private val PLACE = Regex("(?:package:[A-Za-z][A-Za-z0-9_.]{1,150}|chrome:https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?)")
}
