package com.cyclone.mobile.gateway

import com.cyclone.mobile.places.PlaceResolver
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal object GatewayPrivacy {
    private const val REDACTED = "<redacted>"
    private val sensitiveKey = Regex("(?i)(password|passcode|passwd|secret|token|api.?key|otp|one.?time|verification.?code|cvv|card.?number|pin)")
    private val providerKeyPattern = Regex("(?i)\\b(sk-[A-Za-z0-9_-]{12,}|(?:api[_ -]?key|bearer|token)\\s*[:=]\\s*[A-Za-z0-9._-]{8,})")
    private val inlineSecretPattern = Regex("(?i)\\b(password|passcode|passwd|pin|otp|verification\\s*code|api[_ -]?key|token|secret)\\s*(?:is|:|=)\\s*[^\\s,;]+")

    fun isSensitiveHint(text: String): Boolean = sensitiveKey.containsMatchIn(text)

    fun sanitizeAccessibilitySnapshot(snapshot: JSONObject): JSONObject {
        val out = JSONObject(snapshot.toString())
        val source = snapshot.optJSONArray("nodes") ?: JSONArray()
        val nodes = JSONArray()
        for (index in 0 until source.length()) {
            source.optJSONObject(index)?.let { nodes.put(sanitizeNode(it)) }
        }
        out.put("nodes", nodes)
        return sanitizeDeep(out) as JSONObject
    }

    fun sanitizeNode(node: JSONObject): JSONObject {
        val out = JSONObject(node.toString())
        val browserAddress = PlaceResolver.isChromeAddressBarResourceId(node.optString("resourceId"))
        val hints = listOf(
            node.optString("resourceId"),
            node.optString("contentDescription"),
            node.optString("role"),
            node.optString("class"),
        ).joinToString(" ").lowercase(Locale.US)
        val sensitive = node.optBoolean("password", false) || sensitiveKey.containsMatchIn(hints)
        // Editable text is user-entered state. Export field metadata, never the entered value.
        if (node.optBoolean("editable", false) || sensitive || browserAddress) out.put("text", REDACTED)
        else out.put("text", cleanString(node.optString("text")).take(500))
        if (sensitive || browserAddress) out.put("contentDescription", REDACTED)
        else out.put("contentDescription", cleanString(node.optString("contentDescription")).take(500))
        return out
    }

    fun redactActionParams(tool: String, params: JSONObject): JSONObject {
        if (tool != "phone.type" && tool != "phone.replace_text") return sanitizeDeep(JSONObject(params.toString())) as JSONObject
        val out = JSONObject()
        params.optString("elementId").takeIf { it.isNotBlank() }?.let { out.put("elementId", it) }
        params.optJSONObject("selector")?.let { selector ->
            val safeSelector = sanitizeDeep(JSONObject(selector.toString())) as JSONObject
            if (safeSelector.has("value")) safeSelector.put("value", REDACTED)
            if (safeSelector.has("text")) safeSelector.put("text", REDACTED)
            out.put("selector", safeSelector)
        }
        if (params.has("value")) out.put("value", REDACTED)
        if (params.has("text")) out.put("text", REDACTED)
        if (params.has("user_authorized")) out.put("user_authorized", params.optBoolean("user_authorized"))
        if (params.has("userAuthorized")) out.put("userAuthorized", params.optBoolean("userAuthorized"))
        listOf("retries", "waitForChangeMs", "timeoutMs", "currentObservationId").forEach { key ->
            if (params.has(key)) out.put(key, params.opt(key))
        }
        return out
    }

    fun sanitizeDeep(value: Any?, keyHint: String = ""): Any? = when (value) {
        null, JSONObject.NULL -> JSONObject.NULL
        is JSONObject -> sanitizeObject(value)
        is JSONArray -> JSONArray().also { out ->
            for (index in 0 until value.length()) out.put(sanitizeDeep(value.opt(index), keyHint))
        }
        is String -> if (sensitiveKey.containsMatchIn(keyHint)) REDACTED else cleanString(value)
        else -> value
    }

    private fun sanitizeObject(source: JSONObject): JSONObject {
        val out = JSONObject()
        val nodeLike = source.has("editable") || source.has("resourceId") || source.has("contentDescription")
        val browserAddress = PlaceResolver.isChromeAddressBarResourceId(source.optString("resourceId"))
        val nodeSensitive = if (nodeLike) {
            val hints = listOf(
                source.optString("resourceId"), source.optString("contentDescription"),
                source.optString("role"), source.optString("class"),
            ).joinToString(" ")
            source.optBoolean("password", false) || sensitiveKey.containsMatchIn(hints)
        } else false
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val raw = source.opt(key)
            val redactNodeValue = nodeLike && key in setOf("text", "value") &&
                (source.optBoolean("editable", false) || nodeSensitive || browserAddress)
            val redactNodeDescription = nodeLike && key == "contentDescription" && (nodeSensitive || browserAddress)
            out.put(key, when {
                sensitiveKey.containsMatchIn(key) -> REDACTED
                redactNodeValue || redactNodeDescription -> REDACTED
                else -> sanitizeDeep(raw, key)
            })
        }
        return out
    }

    private fun cleanString(value: String): String {
        val inlineSafe = inlineSecretPattern.replace(value) { match -> "${match.groupValues[1]} $REDACTED" }
        return providerKeyPattern.replace(inlineSafe) { REDACTED }
    }
}
