package com.cyclone.mobile.applearner

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

object ScreenSemanticizer {
    data class Candidate(
        val identity: String,
        val title: String,
        val purpose: String,
        val recognition: ScreenRecognition,
        val actions: List<LearnedAction>,
        val forms: Int,
        val sensitiveFields: Int,
    )

    fun fromSnapshot(packageName: String, snapshot: JSONObject, existingScreenId: String? = null): Candidate {
        val nodes = snapshot.optJSONArray("nodes") ?: JSONArray()
        val visible = buildList {
            for (i in 0 until nodes.length()) {
                val node = nodes.optJSONObject(i) ?: continue
                if (node.optBoolean("visibleToUser", true)) add(node)
            }
        }
        val className = snapshot.optString("class").takeIf { it.isNotBlank() }
        val anchors = stableAnchors(visible)
        val titleHints = titleHints(visible)
        val title = titleHints.firstOrNull()?.take(72)
            ?: className?.substringAfterLast('.')?.removeSuffix("Activity")?.humanize()
            ?: "Screen"
        val identity = slugify(title.ifBlank { "screen" }).ifBlank { "screen" }
        val structural = hash(structuralSignature(visible, className))
        val semantic = hash(listOf(className.orEmpty(), anchors.take(18).joinToString("|"), structural).joinToString("|"))
        val purpose = inferPurpose(title, anchors)
        val screenId = existingScreenId ?: "candidate:$identity"
        val actions = extractActions(packageName, screenId, visible)
        val forms = visible.count { it.optBoolean("editable") || it.optString("role").equals("textbox", true) }
        val sensitive = visible.count(ActionSafetyPolicy::looksSensitiveField)
        return Candidate(
            identity = identity,
            title = title,
            purpose = purpose,
            recognition = ScreenRecognition(semantic, structural, anchors, className, titleHints),
            actions = actions,
            forms = forms,
            sensitiveFields = sensitive,
        )
    }

    fun normalizeDynamicText(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.lowercase(Locale.ROOT)
            .replace(Regex("[a-f0-9]{8}-[a-f0-9-]{27,}"), "<id>")
            .replace(Regex("\\b(?:order|invoice|ticket|ref(?:erence)?)?[#:]?\\s*[a-z]*\\d{4,}\\b", RegexOption.IGNORE_CASE), "<id>")
            .replace(Regex("\\b\\d{1,2}[:.]\\d{2}(?:\\s?[ap]m)?\\b", RegexOption.IGNORE_CASE), "<time>")
            .replace(Regex("\\b\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{2,4}\\b"), "<date>")
            .replace(Regex("[$€£]\\s?\\d+(?:[.,]\\d{1,2})?"), "<money>")
            .replace(Regex("\\b\\d+(?:[.,]\\d+)?\\b"), "<n>")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun similarity(a: ScreenRecognition, b: ScreenRecognition): Double {
        if (a.semanticFingerprint == b.semanticFingerprint) return 1.0
        val aa = a.stableAnchors.toSet()
        val bb = b.stableAnchors.toSet()
        val union = aa union bb
        val jaccard = if (union.isEmpty()) 0.0 else (aa intersect bb).size.toDouble() / union.size
        val structural = if (a.structuralFingerprint == b.structuralFingerprint) 1.0 else 0.0
        val classMatch = if (!a.className.isNullOrBlank() && a.className == b.className) 1.0 else 0.0
        return (jaccard * 0.62 + structural * 0.28 + classMatch * 0.10).coerceIn(0.0, 1.0)
    }

    private fun stableAnchors(nodes: List<JSONObject>): List<String> = nodes.asSequence()
        .mapNotNull { node ->
            if (ActionSafetyPolicy.looksSensitiveField(node)) return@mapNotNull null
            val resource = node.optString("resourceId").substringAfterLast('/').takeIf { it.isNotBlank() }
            val role = node.optString("role").takeIf { it.isNotBlank() && it != "generic" }
            val text = normalizeDynamicText(node.optString("text").ifBlank { node.optString("contentDescription") })
                .takeIf { it.isNotBlank() && it != "<n>" && it.length <= 80 }
            when {
                resource != null -> "id:$resource"
                text != null -> "${role ?: "text"}:$text"
                role != null && (node.optBoolean("clickable") || node.optBoolean("scrollable") || node.optBoolean("editable")) -> "role:$role"
                else -> null
            }
        }
        .distinct()
        .take(40)
        .toList()

    private fun titleHints(nodes: List<JSONObject>): List<String> {
        val scored = nodes.mapNotNull { node ->
            if (ActionSafetyPolicy.looksSensitiveField(node)) return@mapNotNull null
            val raw = node.optString("text").trim().ifBlank { node.optString("contentDescription").trim() }
            if (raw.isBlank() || raw.length !in 2..80) return@mapNotNull null
            val normalized = normalizeDynamicText(raw)
            if (normalized.startsWith("<") || normalized.count { it == '<' } > 1) return@mapNotNull null
            var score = 0
            val role = node.optString("role")
            val cls = node.optString("class").lowercase()
            if (!node.optBoolean("clickable")) score += 3
            if (role == "text") score += 2
            if ("textview" in cls) score += 1
            val bounds = node.optJSONObject("bounds")
            if ((bounds?.optInt("top") ?: 9999) < 500) score += 3
            if (raw.length < 36) score += 2
            score to raw
        }
        return scored.sortedByDescending { it.first }.map { it.second }.distinct().take(6)
    }

    private fun structuralSignature(nodes: List<JSONObject>, className: String?): String = buildString {
        append(className.orEmpty())
        nodes.asSequence().take(240).forEach { node ->
            append('|').append(node.optString("role"))
            append('|').append(node.optString("class").substringAfterLast('.'))
            append('|').append(node.optString("resourceId").substringAfterLast('/'))
            append('|').append(if (node.optBoolean("clickable")) 'c' else '-')
            append(if (node.optBoolean("editable")) 'e' else '-')
            append(if (node.optBoolean("scrollable")) 's' else '-')
            append('|').append((node.optJSONArray("actions")?.length() ?: 0))
        }
    }

    private fun extractActions(packageName: String, screenId: String, nodes: List<JSONObject>): List<LearnedAction> {
        return nodes.asSequence()
            .filter { node ->
                node.optBoolean("clickable") || node.optBoolean("longClickable") || node.optBoolean("editable") ||
                    node.optBoolean("scrollable") || (node.optJSONArray("actions")?.length() ?: 0) > 0
            }
            .mapNotNull { node ->
                val label = node.optString("text").trim()
                    .ifBlank { node.optString("contentDescription").trim() }
                    .ifBlank { node.optString("resourceId").substringAfterLast('/').humanize() }
                    .ifBlank { node.optString("role").humanize() }
                if (label.isBlank()) return@mapNotNull null
                val resourceId = node.optString("resourceId")
                val description = node.optString("contentDescription")
                val risk = ActionSafetyPolicy.classify(label, resourceId, description)
                val selector = JSONObject().apply {
                    resourceId.takeIf { it.isNotBlank() }?.let { put("resourceId", it) }
                    node.optString("text").takeIf { it.isNotBlank() && !ActionSafetyPolicy.looksSensitiveField(node) }?.let { put("text", it.take(180)) }
                    description.takeIf { it.isNotBlank() && !ActionSafetyPolicy.looksSensitiveField(node) }?.let { put("contentDescription", it.take(180)) }
                    node.optString("role").takeIf { it.isNotBlank() }?.let { put("role", it) }
                    if (node.optBoolean("clickable")) put("clickable", true)
                    if (node.optBoolean("editable")) put("editable", true)
                    if (node.optBoolean("scrollable")) put("scrollable", true)
                    val bounds = node.optJSONObject("bounds")
                    if (length() == 0 && bounds != null) {
                        put("x", (bounds.optInt("left") + bounds.optInt("right")) / 2)
                        put("y", (bounds.optInt("top") + bounds.optInt("bottom")) / 2)
                    }
                }
                val advertised = buildList {
                    val arr = node.optJSONArray("actions")
                    if (arr != null) for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    if (node.optBoolean("clickable") && none { it.contains("CLICK") }) add("ACTION_CLICK")
                    if (node.optBoolean("longClickable") && none { it.contains("LONG_CLICK") }) add("ACTION_LONG_CLICK")
                    if (node.optBoolean("scrollable") && none { it.contains("SCROLL") }) add("ACTION_SCROLL")
                    if (node.optBoolean("editable") && none { it.contains("SET_TEXT") }) add("ACTION_SET_TEXT")
                }.distinct()
                val semantic = slugify(label).ifBlank { "action" }
                LearnedAction(
                    packageName = packageName,
                    screenId = screenId,
                    semanticName = semantic,
                    label = label.take(120),
                    androidActions = advertised,
                    selectorJson = selector.toString(),
                    risk = risk,
                    requiredInput = if (node.optBoolean("editable")) "text" else null,
                    knowledgeState = KnowledgeState.UNDERSTOOD,
                    confidence = when {
                        resourceId.isNotBlank() -> 0.88
                        node.optString("text").isNotBlank() || description.isNotBlank() -> 0.78
                        else -> 0.55
                    },
                )
            }
            .distinctBy { it.semanticName + "|" + it.selectorJson }
            .take(80)
            .toList()
    }

    private fun inferPurpose(title: String, anchors: List<String>): String {
        val context = (title + " " + anchors.joinToString(" ")).lowercase()
        return when {
            "invoice" in context -> "Shows invoices, billing documents, or invoice-related actions."
            "order" in context -> "Shows order information and navigation to order-related details."
            "deliver" in context || "tracking" in context -> "Shows delivery or tracking information."
            "account" in context || "profile" in context -> "Shows account or profile information and related settings."
            "search" in context -> "Lets the user search or browse results."
            "setting" in context -> "Shows application settings or configuration options."
            "battery" in context -> "Shows battery information or battery-related settings."
            else -> "A learned screen in the selected app with ${anchors.size} stable semantic anchors."
        }
    }

    internal fun slugify(value: String): String = normalizeDynamicText(value)
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .take(64)

    private fun String.humanize(): String = replace('_', ' ').replace('-', ' ')
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(24)
}
