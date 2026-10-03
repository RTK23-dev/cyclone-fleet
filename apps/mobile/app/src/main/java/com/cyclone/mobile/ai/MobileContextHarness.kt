package com.cyclone.mobile.ai

import android.content.Context
import com.cyclone.mobile.CapabilityRegistry
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Cyclone Quick Agent Protocol (CQAP) context builder.
 * Produces a fresh, compact representation of the phone environment for each model decision.
 */
object MobileContextHarness {
    private const val MAX_ELEMENTS = 48
    private const val MAX_RECENT_ACTIONS = 10

    fun observe(context: Context, goal: String): JSONObject {
        val result = PhoneToolExecutor.execute(
            context,
            PhoneToolRequest("cqap-observe-${UUID.randomUUID()}", "phone.observe", JSONObject()),
        )
        val snapshot = result.payload as? JSONObject
        return build(context, goal, snapshot)
    }

    fun build(context: Context, goal: String, snapshot: JSONObject?): JSONObject {
        val out = JSONObject()
            .put("protocol", "cyclone-quick-agent-v1")
            .put("goal", goal)
            .put("controller", DeviceState.controller.name.lowercase())
            .put("currentPackage", snapshot?.optString("package")?.takeIf { it.isNotBlank() }
                ?: DeviceState.currentPackage ?: JSONObject.NULL)
            .put("currentClass", snapshot?.optString("class")?.takeIf { it.isNotBlank() }
                ?: DeviceState.currentClassName ?: JSONObject.NULL)
            .put("fingerprint", snapshot?.optString("fingerprint") ?: JSONObject.NULL)
            .put("screen", snapshot?.optJSONObject("screen") ?: JSONObject.NULL)
            .put("capabilities", CapabilityRegistry.toJson(context))
            .put("importantElements", compactElements(snapshot?.optJSONArray("nodes")))
            .put("recentActions", recentActions())
            .put("freshObservationRequired", DeviceState.requireFreshObservation)

        return out
    }

    private fun compactElements(nodes: JSONArray?): JSONArray {
        if (nodes == null) return JSONArray()
        val candidates = mutableListOf<Pair<Int, JSONObject>>()
        for (i in 0 until nodes.length()) {
            val node = nodes.optJSONObject(i) ?: continue
            if (!node.optBoolean("visibleToUser", true)) continue
            val text = node.optString("text").trim()
            val description = node.optString("contentDescription").trim()
            val resourceId = node.optString("resourceId").trim()
            val interactive = node.optBoolean("clickable") || node.optBoolean("editable") || node.optBoolean("scrollable") || node.optBoolean("checkable")
            if (!interactive && text.isBlank() && description.isBlank()) continue

            var score = 0
            if (interactive) score += 8
            if (node.optBoolean("focused")) score += 4
            if (text.isNotBlank()) score += 3
            if (description.isNotBlank()) score += 2
            if (resourceId.isNotBlank()) score += 2
            if (node.optString("role") in setOf("button", "edit_text", "checkbox", "switch", "tab")) score += 3
            score -= node.optInt("depth", 0).coerceAtMost(6)
            candidates += score to compactNode(node)
        }
        return JSONArray().also { array ->
            candidates.sortedByDescending { it.first }.take(MAX_ELEMENTS).forEach { array.put(it.second) }
        }
    }

    private fun compactNode(node: JSONObject): JSONObject = JSONObject().apply {
        put("id", node.optString("id"))
        put("role", node.optString("role"))
        put("text", node.optString("text").take(180))
        put("description", node.optString("contentDescription").take(180))
        put("resourceId", node.optString("resourceId").take(180))
        put("bounds", node.optJSONObject("bounds") ?: JSONObject.NULL)
        put("clickable", node.optBoolean("clickable"))
        put("editable", node.optBoolean("editable"))
        put("scrollable", node.optBoolean("scrollable"))
        put("checked", node.optBoolean("checked"))
        put("selected", node.optBoolean("selected"))
        put("enabled", node.optBoolean("enabled", true))
        put("path", node.optString("path"))
    }

    private fun recentActions(): JSONArray = JSONArray().also { array ->
        DeviceState.commandAudit.take(MAX_RECENT_ACTIONS).reversed().forEach { audit ->
            array.put(
                JSONObject()
                    .put("tool", audit.tool)
                    .put("ok", audit.ok)
                    .put("error", audit.errorCode ?: JSONObject.NULL)
                    .put("before", audit.beforeFingerprint ?: JSONObject.NULL)
                    .put("after", audit.afterFingerprint ?: JSONObject.NULL),
            )
        }
    }
}
