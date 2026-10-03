package com.cyclone.mobile.applearner

import com.cyclone.mobile.brain.BrainMicroSkill
import org.json.JSONObject

/** Typed, coordinate-free next-hop evidence reusable by Gateway/MCP. */
data class GatewayRouteDescriptor(
    val routeId: String,
    val source: String,
    val label: String,
    val fromPageKey: String,
    val fromPageTitle: String,
    val toPageKey: String?,
    val toPageTitle: String?,
    val selector: JSONObject,
    val confidence: Double,
    val successfulCount: Int,
    val observedCount: Int,
    val lastVerifiedAtMs: Long,
    val freshness: String,
) {
    val dedupeKey: String get() = selectorKey(selector)

    fun toJson(): JSONObject = JSONObject()
        .put("routeId", routeId)
        .put("source", source)
        .put("kind", "VERIFIED_NEXT_HOP")
        .put("label", label)
        .put("advisory", true)
        .put("confidence", confidence)
        .put("freshness", JSONObject().put("state", freshness).put("lastVerifiedAtMs", lastVerifiedAtMs))
        .put("evidence", JSONObject()
            .put("successfulCount", successfulCount)
            .put("observedCount", observedCount)
            .put("verification", "VERIFIED"))
        .put("from", JSONObject().put("pageKey", fromPageKey).put("pageTitle", fromPageTitle))
        .put("to", JSONObject()
            .put("pageKey", toPageKey ?: JSONObject.NULL)
            .put("pageTitle", toPageTitle ?: JSONObject.NULL))
        .put("action", JSONObject()
            .put("tool", "phone.click")
            .put("selector", JSONObject(selector.toString()))
            .put("coordinateFree", true))
        .put("invocation", JSONObject()
            .put("operation", "action.execute")
            .put("tool", "phone.click")
            .put("params", JSONObject().put("selector", JSONObject(selector.toString())))
            .put("expectedAfterPageKey", toPageKey ?: JSONObject.NULL)
            .put("requires", org.json.JSONArray()
                .put("fresh_current_observation")
                .put("gateway_policy_authorization")
                .put("semantic_after_state_verification")))

}

/**
 * Converts only repeatedly successful, semantic-safe evidence into optional navigation hints.
 * It never turns a coordinate-only action, an input action, or a consequential action into a
 * reusable route.
 */
object VerifiedRouteDescriptors {
    private const val FRESH_MS = 7L * 24 * 60 * 60 * 1000
    private const val AGING_MS = 30L * 24 * 60 * 60 * 1000

    fun fromAppGraph(
        page: PageContext,
        graph: AppGraphSnapshot?,
        nowMs: Long,
    ): List<GatewayRouteDescriptor> {
        val current = graph?.screens?.firstOrNull { it.recognition.semanticFingerprint == page.pageKey } ?: return emptyList()
        return graph.outgoing(current.id)
            .asSequence()
            .mapNotNull { (action, transition) ->
                val target = graph.screens.firstOrNull { it.id == transition.toScreenId } ?: return@mapNotNull null
                val selector = parseSemanticSelector(action.selectorJson) ?: return@mapNotNull null
                if (
                    action.risk != ActionRisk.SAFE ||
                    action.requiredInput != null ||
                    action.knowledgeState != KnowledgeState.VERIFIED ||
                    transition.knowledgeState != KnowledgeState.VERIFIED ||
                    target.knowledgeState == KnowledgeState.STALE ||
                    transition.successfulCount < 2 ||
                    action.androidActions.none { it.contains("CLICK") }
                ) return@mapNotNull null
                GatewayRouteDescriptor(
                    routeId = "app-graph:${transition.id}",
                    source = "APP_GRAPH",
                    label = action.label,
                    fromPageKey = page.pageKey,
                    fromPageTitle = page.title,
                    toPageKey = target.recognition.semanticFingerprint,
                    toPageTitle = target.title,
                    selector = selector,
                    confidence = minOf(action.confidence, transition.confidence, target.confidence),
                    successfulCount = transition.successfulCount,
                    observedCount = transition.observedCount,
                    lastVerifiedAtMs = transition.lastObservedAt,
                    freshness = freshness(transition.lastObservedAt, nowMs),
                )
            }
            .sortedWith(compareByDescending<GatewayRouteDescriptor> { it.confidence }.thenByDescending { it.lastVerifiedAtMs })
            .take(5)
            .toList()
    }

    fun fromBrain(
        page: PageContext,
        accessibilityFingerprint: String,
        skills: List<BrainMicroSkill>,
        nowMs: Long,
    ): List<GatewayRouteDescriptor> = skills.asSequence()
        .filter { skill ->
            skill.tool == "phone.click" &&
                skill.fromPackage == page.packageName &&
                skill.fromFingerprint == accessibilityFingerprint &&
                skill.successCount >= 2 &&
                skill.failureCount < skill.successCount &&
                skill.confidence >= 0.80
        }
        .mapNotNull { skill ->
            val params = runCatching { JSONObject(skill.paramsJson) }.getOrNull() ?: return@mapNotNull null
            val selector = params.optJSONObject("selector")?.let(::parseSemanticSelector) ?: return@mapNotNull null
            GatewayRouteDescriptor(
                routeId = "brain:${skill.signature}",
                source = "BRAIN",
                label = skill.name,
                fromPageKey = page.pageKey,
                fromPageTitle = page.title,
                toPageKey = null,
                toPageTitle = null,
                selector = selector,
                confidence = skill.confidence,
                successfulCount = skill.successCount,
                observedCount = skill.successCount + skill.failureCount,
                lastVerifiedAtMs = skill.lastUsedAt,
                freshness = freshness(skill.lastUsedAt, nowMs),
            )
        }
        .sortedWith(compareByDescending<GatewayRouteDescriptor> { it.confidence }.thenByDescending { it.lastVerifiedAtMs })
        .take(5)
        .toList()

    private fun parseSemanticSelector(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()?.let(::parseSemanticSelector)

    private fun parseSemanticSelector(raw: JSONObject): JSONObject? {
        val safe = JSONObject()
        listOf("resourceId", "text", "textContains", "contentDescription", "contentDescriptionContains", "role", "className")
            .forEach { key -> raw.optString(key).takeIf(String::isNotBlank)?.let { safe.put(key, it.take(180)) } }
        listOf("clickable", "enabled").forEach { key -> if (raw.has(key)) safe.put(key, raw.optBoolean(key)) }
        return safe.takeIf { it.length() > 0 }
    }

    private fun freshness(lastSeenAtMs: Long, nowMs: Long): String = when ((nowMs - lastSeenAtMs).coerceAtLeast(0L)) {
        in 0..FRESH_MS -> "FRESH"
        in (FRESH_MS + 1)..AGING_MS -> "AGING"
        else -> "STALE"
    }
}

private fun selectorKey(selector: JSONObject): String = listOf(
    selector.optString("resourceId"),
    selector.optString("text"),
    selector.optString("textContains"),
    selector.optString("contentDescription"),
    selector.optString("contentDescriptionContains"),
    selector.optString("role"),
).joinToString("|")
