package com.cyclone.mobile.agent.tools

import com.cyclone.mobile.agent.contract.*
import com.cyclone.mobile.gateway.GatewayObservation
import com.cyclone.mobile.gateway.GatewayObservationAdapter
import com.cyclone.mobile.runtime.session.ObservationIdentity
import org.json.JSONArray
import org.json.JSONObject

enum class ObservationProjectionMode { SHADOW, AUTHORITATIVE }

/** Pure projections. This object has no runtime/device/source port and cannot capture. */
internal object ObservationProjections {
    fun freshLegacy(snapshot: JSONObject, learned: com.cyclone.mobile.applearner.PageContext): com.cyclone.mobile.applearner.PageContext =
        com.cyclone.mobile.applearner.PageSignatureEngine.fromSnapshot(snapshot).copy(
            observationCount = learned.observationCount, firstSeenAt = learned.firstSeenAt,
            lastSeenAt = snapshot.optLong("timestampMs", 0), isNew = learned.isNew, previewPath = null)

    private const val PAGE_CARD_GOAL_CANDIDATES = 10
    private const val PAGE_CARD_CONTROL_LIMIT = 36
    private fun copyObject(value: JSONObject?) = value?.let { JSONObject(it.toString()) } ?: JSONObject()
    private fun copyArray(value: JSONArray?) = value?.let { JSONArray(it.toString()) } ?: JSONArray()
    private fun candidates(
        observation: GatewayObservation,
        query: String,
        limit: Int,
    ): List<AgentElementCandidate> {
        val results = GatewayObservationAdapter.search(observation, query, limit)
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val id = item.optString("elementId")
                val evidence = runCatching { GatewayObservationAdapter.element(observation, id) }.getOrNull() ?: JSONObject()
                add(candidateFrom(item, evidence))
            }
        }
    }

    fun pageCard(
        observation: GatewayObservation,
        goal: String,
        generation: Long,
        actionable: Boolean,
    ): AgentPageCard {
        val identity = ObservationIdentity.fromPayload(observation.id, observation.generation.takeIf { it > 0 } ?: generation, observation.execution, observation.capturedAt, observation.payload).copy(freshness = if (actionable) "current" else "stale")
        val ranked = if (goal.isBlank()) {
            emptyList()
        } else {
            candidates(observation, goal, PAGE_CARD_GOAL_CANDIDATES)
        }
        val byId = linkedMapOf<String, AgentElementCandidate>()
        ranked.forEach { byId[it.elementId] = it }

        val semantic = observation.payload.optJSONArray("semanticControls") ?: JSONArray()
        for (index in 0 until semantic.length()) {
            val evidence = semantic.optJSONObject(index) ?: continue
            if (byId.size >= PAGE_CARD_CONTROL_LIMIT &&
                !com.cyclone.mobile.ai.CookieInterruptionPolicy.isRejectLabel(evidence.optString("label"))) continue
            val id = evidence.optString("elementId")
            if (id.isBlank() || id in byId) continue
            byId[id] = AgentElementCandidate(
                elementId = id,
                observationId = observation.id,
                label = evidence.optString("label"),
                semanticName = evidence.optString("semanticName"),
                role = evidence.optString("role"),
                source = evidence.optString("source"),
                relevance = 0.0,
                evidence = JSONObject(evidence.toString()),
                elementIndex = evidence.optInt("elementIndex", evidence.optInt("element_index", -1)).takeIf { it > 0 },
            )
        }

        return AgentPageCard(
            observationId = observation.id,
            generation = identity.generation,
            actionable = actionable,
            capturedAtMs = observation.capturedAt,
            packageName = observation.page.packageName,
            activity = observation.payload.optString("activity").takeIf {
                it.isNotBlank() && it != "null"
            },
            pageKey = observation.page.pageKey,
            structuralKey = observation.page.structuralKey,
            contentKey = observation.page.contentKey,
            accessibilityFingerprint = observation.payload.optString("accessibilityFingerprint"),
            pageSummary = copyObject(observation.payload.optJSONObject("pageSummary")),
            pageText = copyObject(observation.payload.optJSONObject("pageText")),
            pageEvidence = copyObject(observation.payload.optJSONObject("pageEvidence")),
            controls = byId.values.sortedByDescending { com.cyclone.mobile.ai.CookieInterruptionPolicy.isRejectLabel(it.label) }
                .take(PAGE_CARD_CONTROL_LIMIT).map { it.copy(evidence = JSONObject(it.evidence.toString())
                    .put("observation", identity.toJson()).put("generation", identity.generation)) },
            nextHopHints = copyArray(observation.payload.optJSONArray("nextHopHints")),
            perceptionMode = observation.payload.optString("perceptionMode", "a11y").ifBlank { "a11y" },
            treeUseful = observation.payload.optBoolean("treeUseful", true),
            sessionId = observation.execution.sessionId,
            displayId = observation.execution.displayId,
            legacyPage = observation.page.copy(observation = identity, controls = observation.page.controls.map { it.copy(selector = JSONObject(it.selector.toString())) }),
            observation = identity,
        )
    }

    private fun candidateFrom(item: JSONObject, evidence: JSONObject) = AgentElementCandidate(
        elementId = item.optString("elementId"),
        observationId = item.optString("observationId"),
        label = item.optString("label"),
        semanticName = item.optString("semanticName"),
        role = item.optString("role"),
        source = item.optString("source"),
        relevance = item.optDouble("relevance", 0.0),
        evidence = JSONObject(evidence.toString()),
        elementIndex = item.optInt("elementIndex", evidence.optInt("elementIndex", evidence.optInt("element_index", -1))).takeIf { it > 0 },
    )


    fun prompt(card: AgentPageCard): JSONObject = JSONObject()
        .put("observation", card.observation?.toJson() ?: JSONObject.NULL)
        .put("sessionId", card.sessionId)
        .put("displayId", card.displayId)
        .put("observationId", card.observationId)
        .put("generation", card.generation)
        .put("package", card.packageName)
        .put("activity", card.activity ?: JSONObject.NULL)
        .put("pageKey", card.pageKey)
        .put("structuralKey", card.structuralKey)
        .put("contentKey", card.contentKey)
        .put("accessibilityFingerprint", card.accessibilityFingerprint)
        .put("pageSummary", JSONObject(card.pageSummary.toString()))
        .put("pageText", JSONObject(card.pageText.toString()))
        .put("pageEvidence", JSONObject(card.pageEvidence.toString()))
        .put("controls", JSONArray().also { array -> card.controls.forEach { array.put(candidateJson(it)) } })
        .put("nextHopHints", JSONArray(card.nextHopHints.toString()))
        .put("perceptionMode", card.perceptionMode)
        .put("treeUseful", card.treeUseful)

    private fun candidateJson(candidate: AgentElementCandidate): JSONObject = JSONObject()
        .put("observation", candidate.evidence.optJSONObject("observation") ?: JSONObject.NULL)
        .put("generation", candidate.evidence.opt("generation") ?: JSONObject.NULL)
        .put("observationId", candidate.observationId)
        .put("controlId", candidate.elementId)
        .put("elementId", candidate.elementId)
        .put("elementIndex", candidate.elementIndex ?: JSONObject.NULL)
        .put("label", candidate.label)
        .put("semanticName", candidate.semanticName)
        .put("role", candidate.role)
        .put("source", candidate.source)
        .put("relevance", candidate.relevance)
        .put("androidActions", candidate.evidence.optJSONArray("androidActions") ?: JSONArray())
        .put("risk", candidate.evidence.optString("risk"))
        .put("expectedEffect", candidate.evidence.opt("expectedEffect") ?: JSONObject.NULL)
        .put("clickable", candidate.evidence.optBoolean("clickable"))
        .put("enabled", candidate.evidence.optBoolean("enabled", true))
        .put("visibleToUser", candidate.evidence.optBoolean("visibleToUser", true))
        .put("bounds", candidate.evidence.optJSONObject("bounds") ?: JSONObject.NULL)
        .put("editable", candidate.evidence.optBoolean("editable"))
        .put("scrollable", candidate.evidence.optBoolean("scrollable"))
        .put("selected", candidate.evidence.optBoolean("selected"))
        .put("checked", candidate.evidence.optBoolean("checked"))
        .put("focused", candidate.evidence.optBoolean("focused"))

    fun snapshot(card: AgentPageCard): JSONObject = JSONObject()
        .put("observation", card.observation?.toJson() ?: JSONObject.NULL)
        .put("observationId", card.observationId).put("generation", card.generation)
        .put("sessionId", card.sessionId).put("displayId", card.displayId)
        .put("package", card.packageName).put("class", card.activity ?: JSONObject.NULL)
        .put("fingerprint", card.accessibilityFingerprint)
        .put("pageSummary", JSONObject(card.pageSummary.toString()))
        .put("pageText", JSONObject(card.pageText.toString())).put("pageEvidence", JSONObject(card.pageEvidence.toString()))

    fun shadow(legacy: AgentPageCard, candidate: AgentPageCard): JSONObject {
        val differences = buildList {
            if (legacy.observationId != candidate.observationId) add("observation_id")
            if (legacy.sessionId != candidate.sessionId || legacy.displayId != candidate.displayId) add("scope")
            if (legacy.pageKey != candidate.pageKey || legacy.contentKey != candidate.contentKey) add("page")
            if (legacy.observation != candidate.observation || legacy.generation != candidate.generation ||
                legacy.capturedAtMs != candidate.capturedAtMs || legacy.actionable != candidate.actionable) add("identity")
            if (legacy.controls.map { listOf(it.elementId, it.observationId, it.label, it.role, it.semanticName, it.source) } !=
                candidate.controls.map { listOf(it.elementId, it.observationId, it.label, it.role, it.semanticName, it.source) }) add("controls")
            for (field in listOf("bounds", "enabled", "clickable", "longClickable", "editable", "scrollable", "visibleToUser",
                "actions", "androidActions", "selected", "checked", "focused", "resourceId", "contentDescription", "selector")) {
                if (legacy.controls.map { canonical(it.evidence.opt(field)) } != candidate.controls.map { canonical(it.evidence.opt(field)) })
                    add("control_$field")
            }
        }
        return JSONObject().put("mode", "shadow").put("matches", differences.isEmpty())
            .put("differences", JSONArray(differences)).put("evidenceId", candidate.observationId)
            .put("sourceGeneration", candidate.generation).put("projectionCaptureCount", 0)
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { key ->
            JSONObject.quote(key) + ":" + canonical(value.opt(key))
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.opt(it)) }
        else -> JSONObject().put("value", value ?: JSONObject.NULL).toString()
    }
}
