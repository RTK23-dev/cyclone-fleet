package com.cyclone.mobile.debug

import com.cyclone.mobile.applearner.PageContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Classifies where an expected control disappears between Android -> semantic page -> agent payload.
 * This is deliberately local/deterministic so the debugger can diagnose perception without spending
 * model tokens or letting a model grade itself.
 */
object PageDebugDiagnosisV293 {
    fun diagnose(
        expected: String,
        rawSnapshot: JSONObject,
        page: PageContext,
        agentInput: JSONObject,
    ): JSONObject {
        val query = normalize(expected)
        val rawNodes = rawSnapshot.optJSONArray("nodes") ?: JSONArray()
        val visibleNodes = mutableListOf<JSONObject>()
        var interactive = 0
        var unlabeledInteractive = 0
        for (i in 0 until rawNodes.length()) {
            val node = rawNodes.optJSONObject(i) ?: continue
            if (!node.optBoolean("visibleToUser", true)) continue
            visibleNodes += node
            if (isInteractive(node)) {
                interactive++
                val label = listOf(
                    node.optString("text"),
                    node.optString("contentDescription"),
                    node.optString("resourceId"),
                ).joinToString(" ").trim()
                if (label.isBlank()) unlabeledInteractive++
            }
        }

        val rawHit = query.isNotBlank() && visibleNodes.any { matches(query, rawNodeCorpus(it)) }
        val semanticHit = query.isNotBlank() && page.controls.any { control ->
            matches(query, listOf(
                control.label,
                control.semanticName,
                control.role,
                control.selector.toString(),
                control.androidActions.joinToString(" "),
            ).joinToString(" "))
        }
        val currentPage = agentInput.optJSONObject("CURRENT_PAGE") ?: JSONObject()
        val agentHit = query.isNotBlank() && matches(query, currentPage.toString())

        val stage = when {
            query.isBlank() -> "ADD_EXPECTED_TARGET"
            !rawHit -> "ACCESSIBILITY_PERCEPTION"
            rawHit && !semanticHit -> "SEMANTICIZATION_LOSS"
            semanticHit && !agentHit -> "AGENT_CONTEXT_TRUNCATION"
            else -> "AGENT_REASONING_OR_MEMORY"
        }
        val explanation = when (stage) {
            "ADD_EXPECTED_TARGET" -> "Describe the obvious next control/action to get a deterministic layer-by-layer diagnosis."
            "ACCESSIBILITY_PERCEPTION" -> "The expected target is not present in Android's visible Accessibility snapshot. The app may use a custom canvas/WebView, hide semantics, or require visual understanding."
            "SEMANTICIZATION_LOSS" -> "Android exposes the expected target, but Cyclone's PageContext does not preserve it. This points at PageSignatureEngine labeling/parent-child semantics or the 450-node semantic scan."
            "AGENT_CONTEXT_TRUNCATION" -> "The semantic page knows the expected target, but the exact payload sent to the Page Agent does not contain it. The 36-control agent cap/order is a primary suspect."
            else -> "The expected target reaches the exact Page Agent payload. If the model still chooses the wrong next action, compare Current vs No-memory vs Full-controls vs Raw-visible vs Minimal-prompt probes."
        }

        return JSONObject()
            .put("expected", expected)
            .put("stage", stage)
            .put("explanation", explanation)
            .put("rawHit", rawHit)
            .put("semanticHit", semanticHit)
            .put("agentHit", agentHit)
            .put("rawNodeCount", rawNodes.length())
            .put("visibleNodeCount", visibleNodes.size)
            .put("visibleInteractiveCount", interactive)
            .put("unlabeledInteractiveCount", unlabeledInteractive)
            .put("semanticControlCount", page.controls.size)
            .put("agentControlCount", currentPage.optJSONArray("controls")?.length() ?: 0)
            .put("nodesBeyondSemanticScan", (visibleNodes.size - 450).coerceAtLeast(0))
            .put("controlsBeyondAgentCap", (page.controls.size - 36).coerceAtLeast(0))
    }

    fun outputLooksRelevant(expected: String, output: String): Boolean {
        val query = normalize(expected)
        return query.isNotBlank() && matches(query, output)
    }

    private fun isInteractive(node: JSONObject): Boolean =
        node.optBoolean("clickable") || node.optBoolean("longClickable") || node.optBoolean("editable") ||
            node.optBoolean("scrollable") || node.optBoolean("checkable") ||
            node.optString("role").lowercase(Locale.US) in setOf("button", "tab", "switch", "checkbox", "edit_text", "textbox")

    private fun rawNodeCorpus(node: JSONObject): String = listOf(
        node.optString("text"),
        node.optString("contentDescription"),
        node.optString("resourceId"),
        node.optString("role"),
        node.optString("class"),
        node.optString("path"),
    ).joinToString(" ")

    private fun matches(normalizedQuery: String, corpus: String): Boolean {
        val normalizedCorpus = normalize(corpus)
        val tokens = normalizedQuery.split(' ').filter { it.length >= 2 }.distinct()
        if (tokens.isEmpty()) return normalizedCorpus.contains(normalizedQuery)
        return tokens.all { normalizedCorpus.contains(it) }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
