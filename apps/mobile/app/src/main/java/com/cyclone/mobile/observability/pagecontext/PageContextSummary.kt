package com.cyclone.mobile.observability.pagecontext

import com.cyclone.mobile.applearner.ActionSafetyPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a compact "what is on this page and what can I do here" summary from a sanitized
 * Accessibility snapshot plus canonical PageContext identity. Pure JSON so it is JVM-testable.
 */
object PageContextSummary {
    const val DEFAULT_PLAIN_LIMIT = 500

    fun build(
        snapshot: JSONObject,
        pageKey: String,
        title: String,
        controlCount: Int,
        textLineCount: Int,
    ): JSONObject {
        val nodes = snapshot.optJSONArray("nodes") ?: JSONArray()
        val headings = mutableListOf<String>()
        val buttons = mutableListOf<String>()
        val tabs = mutableListOf<String>()
        val fields = mutableListOf<String>()
        val switches = mutableListOf<String>()
        val scrollableKeys = linkedSetOf<String>()
        var sensitiveFields = 0
        var interactiveCount = 0

        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            if (!node.optBoolean("visibleToUser", true)) continue
            if (ActionSafetyPolicy.looksSensitiveField(node)) sensitiveFields++

            val role = node.optString("role")
            val clickable = node.optBoolean("clickable") || node.optBoolean("longClickable")
            val editable = node.optBoolean("editable")
            val checkable = node.optBoolean("checkable")
            if (clickable || editable || checkable || node.optBoolean("scrollable")) interactiveCount++

            val label = readableLabel(node, editable)
            when {
                editable && label.isNotBlank() -> fields += label
                role == "tab" && label.isNotBlank() -> tabs += label
                (role == "switch" || checkable) && label.isNotBlank() -> switches += label
                clickable && label.isNotBlank() && role !in setOf("switch", "checkbox") -> buttons += label
                !clickable && !ActionSafetyPolicy.looksSensitiveField(node) && headingCandidate(node, label) -> headings += label
            }
            if (node.optBoolean("scrollable")) {
                val bounds = node.optJSONObject("bounds")
                scrollableKeys += bounds?.let {
                    "${it.optInt("left")}/${it.optInt("top")}/${it.optInt("right")}/${it.optInt("bottom")}"
                } ?: "scrollable-${node.optString("path")}"
            }
        }

        val primaryNote = buildString {
            append("$textLineCount visible text lines, $interactiveCount interactive nodes")
            if (fields.isNotEmpty()) append(", ${fields.size} form field(s)")
            if (scrollableKeys.isNotEmpty()) append(", ${scrollableKeys.size} scrollable region(s)")
        }
        val plain = listOf(title.trim(), primaryNote)
            .filter { it.isNotBlank() }
            .joinToString(". ")
            .take(DEFAULT_PLAIN_LIMIT)
        return JSONObject()
            .put("protocol", "cyclone-page-summary-v1")
            .put("pageKey", pageKey)
            .put("title", title)
            .put("headings", JSONArray(headings.distinct().take(4)))
            .put("buttons", JSONArray(buttons.distinct().take(12)))
            .put("tabs", JSONArray(tabs.distinct().take(8)))
            .put("formFields", JSONArray(fields.distinct().take(12)))
            .put("switches", JSONArray(switches.distinct().take(8)))
            .put("scrollableRegions", scrollableKeys.size)
            .put("interactiveCount", interactiveCount)
            .put("sensitiveFieldsRedacted", sensitiveFields)
            .put("controlCount", controlCount)
            .put("textLineCount", textLineCount)
            .put("contentNote", primaryNote)
            .put("text", plain)
    }

    fun flattened(summary: JSONObject, limit: Int = DEFAULT_PLAIN_LIMIT): String {
        val direct = summary.optString("text").trim()
        if (direct.isNotBlank()) return direct.take(limit)
        val parts = mutableListOf<String>()
        summary.optString("title").trim().takeIf { it.isNotBlank() }?.let(parts::add)
        summary.optString("contentNote").trim().takeIf { it.isNotBlank() }?.let(parts::add)
        listOf("headings", "buttons", "tabs", "switches").forEach { key ->
            val items = summary.optJSONArray(key) ?: return@forEach
            val labels = (0 until items.length()).mapNotNull { items.optString(it).trim().takeIf(String::isNotBlank) }
            if (labels.isNotEmpty()) parts += "$key: ${labels.take(8).joinToString(", ")}"
        }
        return parts.joinToString(". ").take(limit)
    }

    private fun readableLabel(node: JSONObject, editable: Boolean): String {
        val text = node.optString("text").trim()
        val description = node.optString("contentDescription").trim()
        val resource = node.optString("resourceId").substringAfterLast('/').replace('_', ' ').trim()
        // Editable text is user-entered state; only the field label may be shown.
        val label = if (editable) description.ifBlank { resource } else text.ifBlank { description.ifBlank { resource } }
        return label.takeIf { it.isNotBlank() && it != "<redacted>" }.orEmpty()
    }

    private fun headingCandidate(node: JSONObject, label: String): Boolean {
        if (label.length !in 2..60 || node.optInt("depth", 99) > 4) return false
        if (label.matches(Regex("^[0-9.,:% /-]+$"))) return false
        val role = node.optString("role")
        val cls = node.optString("class").substringAfterLast('.').lowercase()
        return role == "text" || role == "heading" || "textview" in cls
    }
}
