package com.cyclone.mobile

import com.cyclone.mobile.gesture.HumanGestureRuntimeCapabilities
import org.json.JSONArray
import org.json.JSONObject

data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

    fun toJson(): JSONObject = JSONObject()
        .put("left", left)
        .put("top", top)
        .put("right", right)
        .put("bottom", bottom)
        .put("width", width)
        .put("height", height)
}

data class UiNodeSnapshot(
    val id: String,
    val path: String,
    val parentId: String?,
    val childIds: List<String>,
    val depth: Int,
    val windowId: Int,
    val className: String,
    val role: String,
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val bounds: UiBounds,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val checked: Boolean,
    val checkable: Boolean,
    val focused: Boolean,
    val focusable: Boolean,
    val visibleToUser: Boolean,
    val actions: List<String> = emptyList(),
    val password: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("path", path)
        .put("parentId", parentId ?: JSONObject.NULL)
        .put("childIds", JSONArray(childIds))
        .put("depth", depth)
        .put("windowId", windowId)
        .put("class", className)
        .put("role", role)
        .put("text", text)
        .put("contentDescription", contentDescription)
        .put("resourceId", resourceId)
        .put("bounds", bounds.toJson())
        .put("clickable", clickable)
        .put("longClickable", longClickable)
        .put("editable", editable)
        .put("scrollable", scrollable)
        .put("enabled", enabled)
        .put("selected", selected)
        .put("checked", checked)
        .put("checkable", checkable)
        .put("focused", focused)
        .put("focusable", focusable)
        .put("visibleToUser", visibleToUser)
        .put("actions", JSONArray(actions))
        .put("password", password)
}

data class UiWindowSnapshot(
    val id: Int,
    val title: String,
    val type: Int,
    val layer: Int,
    val active: Boolean,
    val focused: Boolean,
    val bounds: UiBounds,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("type", type)
        .put("layer", layer)
        .put("active", active)
        .put("focused", focused)
        .put("bounds", bounds.toJson())
}

data class UiSnapshot(
    val packageName: String?,
    val className: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val timestampMs: Long,
    val fingerprint: String,
    val controller: String,
    val windows: List<UiWindowSnapshot>,
    val nodes: List<UiNodeSnapshot>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("package", packageName ?: JSONObject.NULL)
        .put("class", className ?: JSONObject.NULL)
        .put("screen", JSONObject().put("width", screenWidth).put("height", screenHeight))
        .put("timestampMs", timestampMs)
        .put("fingerprint", fingerprint)
        .put("controller", controller)
        .put("windows", JSONArray().also { array -> windows.forEach { array.put(it.toJson()) } })
        .put("nodes", JSONArray().also { array -> nodes.forEach { array.put(it.toJson()) } })
}

enum class RelativeDirection { ABOVE, BELOW, LEFT_OF, RIGHT_OF, NEAR }

data class ElementSelector(
    val resourceId: String? = null,
    val text: String? = null,
    val textContains: String? = null,
    val contentDescription: String? = null,
    val contentDescriptionContains: String? = null,
    val className: String? = null,
    val role: String? = null,
    val ancestorText: String? = null,
    val descendantText: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val relativeToText: String? = null,
    val relativeDirection: RelativeDirection? = null,
    val fuzzyText: String? = null,
    val minFuzzyScore: Double = 0.72,
    val requireClickable: Boolean? = null,
    val requireEditable: Boolean? = null,
    val requireScrollable: Boolean? = null,
    val elementId: String? = null,
    val ref: String? = null,
    val path: String? = null,
) {
    fun isEmpty(): Boolean = resourceId == null && text == null && textContains == null &&
        contentDescription == null && contentDescriptionContains == null && className == null &&
        role == null && ancestorText == null && descendantText == null && x == null && y == null &&
        relativeToText == null && relativeDirection == null && fuzzyText == null &&
        requireClickable == null && requireEditable == null && requireScrollable == null &&
        elementId == null && ref == null && path == null

    companion object {
        fun fromJson(json: JSONObject?): ElementSelector {
            if (json == null) return ElementSelector()
            fun s(key: String): String? = json.optString(key).takeIf { it.isNotBlank() }
            val scoped = s("elementId") ?: s("ref")
            val mapped = scoped?.let { ObservationSelectorLookup.map(it) }
            if (mapped != null && !mapped.isEmpty()) return mapped
            val index = when {
                json.has("elementIndex") -> json.optInt("elementIndex")
                json.has("element_index") -> json.optInt("element_index")
                else -> -1
            }
            if (index > 0) {
                ObservationSelectorLookup.mapIndex(index)?.let { indexed ->
                    if (!indexed.isEmpty()) return indexed
                }
            }
            return ElementSelector(
                resourceId = s("resourceId") ?: mapped?.resourceId,
                text = s("text") ?: mapped?.text,
                textContains = s("textContains"),
                contentDescription = s("contentDescription") ?: mapped?.contentDescription,
                contentDescriptionContains = s("contentDescriptionContains"),
                className = s("class") ?: mapped?.className,
                role = s("role") ?: mapped?.role,
                ancestorText = s("ancestorText"),
                descendantText = s("descendantText") ?: mapped?.descendantText,
                x = if (json.has("x")) json.optInt("x") else mapped?.x,
                y = if (json.has("y")) json.optInt("y") else mapped?.y,
                relativeToText = s("relativeToText"),
                relativeDirection = s("relativeDirection")?.uppercase()?.let { runCatching { RelativeDirection.valueOf(it) }.getOrNull() },
                fuzzyText = s("fuzzyText"),
                minFuzzyScore = json.optDouble("minFuzzyScore", 0.72).coerceIn(0.0, 1.0),
                requireClickable = if (json.has("clickable")) json.optBoolean("clickable") else mapped?.requireClickable,
                requireEditable = if (json.has("editable")) json.optBoolean("editable") else mapped?.requireEditable,
                requireScrollable = if (json.has("scrollable")) json.optBoolean("scrollable") else mapped?.requireScrollable,
                elementId = scoped ?: mapped?.elementId,
                ref = s("ref"),
                path = s("path") ?: mapped?.path,
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        resourceId?.let { put("resourceId", it) }
        text?.let { put("text", it) }
        textContains?.let { put("textContains", it) }
        contentDescription?.let { put("contentDescription", it) }
        contentDescriptionContains?.let { put("contentDescriptionContains", it) }
        className?.let { put("class", it) }
        role?.let { put("role", it) }
        ancestorText?.let { put("ancestorText", it) }
        descendantText?.let { put("descendantText", it) }
        x?.let { put("x", it) }
        y?.let { put("y", it) }
        relativeToText?.let { put("relativeToText", it) }
        relativeDirection?.let { put("relativeDirection", it.name.lowercase()) }
        fuzzyText?.let { put("fuzzyText", it) }
        put("minFuzzyScore", minFuzzyScore)
        requireClickable?.let { put("clickable", it) }
        requireEditable?.let { put("editable", it) }
        requireScrollable?.let { put("scrollable", it) }
        elementId?.let { put("elementId", it) }
        ref?.let { put("ref", it) }
        path?.let { put("path", it) }
    }
}

data class SelectorMatch(val node: UiNodeSnapshot, val score: Double, val reasons: List<String>) {
    fun toJson(): JSONObject = JSONObject()
        .put("score", score)
        .put("reasons", JSONArray(reasons))
        .put("node", node.toJson())
}

enum class CapabilityStatus { AVAILABLE, MISSING_PERMISSION, UNSUPPORTED_ON_DEVICE, TEMPORARILY_UNAVAILABLE }

data class CapabilityState(val name: String, val status: CapabilityStatus, val detail: String? = null) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("status", status.name)
        .put("detail", detail ?: JSONObject.NULL)
}

enum class PhoneToolErrorCode {
    UNKNOWN_TOOL,
    INVALID_REQUEST,
    CAPABILITY_UNAVAILABLE,
    ACCESSIBILITY_NOT_CONNECTED,
    HUMAN_HAS_CONTROL,
    FRESH_OBSERVATION_REQUIRED,
    ELEMENT_NOT_FOUND,
    STALE_ELEMENT,
    ACTION_FAILED,
    TIMEOUT,
    ASSERTION_FAILED,
    DUPLICATE_ACTION,
    APP_NOT_FOUND,
    NOTIFICATION_NOT_FOUND,
    SECURITY_RESTRICTION,
    POLICY_DENIED,
    INTERNAL_ERROR,
    WORKSPACE_SCOPE_CONFLICT,
    TARGET_SCOPE_MISMATCH,
}

data class PhoneToolError(val code: PhoneToolErrorCode, val message: String) {
    fun toJson(): JSONObject = JSONObject().put("code", code.name).put("message", message)
}

data class PhoneToolRequest(
    val commandId: String,
    val tool: String,
    val params: JSONObject = JSONObject(),
) {
    companion object {
        fun fromJson(json: JSONObject): PhoneToolRequest = PhoneToolRequest(
            commandId = json.optString("id").ifBlank { "cmd-${System.nanoTime()}" },
            tool = json.optString("tool", json.optString("action")),
            params = com.cyclone.mobile.runtime.session.ExecutionRequestScope.merge(json, json.optJSONObject("params") ?: JSONObject()),
        )
    }
}

data class PhoneToolResult(
    val commandId: String,
    val tool: String,
    val ok: Boolean,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val attempts: Int = 1,
    val beforeFingerprint: String? = null,
    val afterFingerprint: String? = null,
    val payload: Any? = null,
    val error: PhoneToolError? = null,
) {
    fun toJson(): JSONObject {
        val renderedPayload = when {
            tool != "phone.click" -> payload
            payload is JSONObject && payload.optJSONObject("humanGesture") != null -> payload
            !ok && HumanGestureDispatch.peekTrace(commandId) == null -> payload
            else -> {
                val base = if (payload is JSONObject) JSONObject(payload.toString()) else JSONObject()
                base.put("humanGesture", serializedClickEvidence(base))
            }
        }
        val finalizedPayload = addRuntimeEvidence(renderedPayload)
        return JSONObject()
            .put("commandId", commandId)
            .put("tool", tool)
            .put("ok", ok)
            .put("startedAtMs", startedAtMs)
            .put("finishedAtMs", finishedAtMs)
            .put("durationMs", finishedAtMs - startedAtMs)
            .put("attempts", attempts)
            .put("beforeFingerprint", beforeFingerprint ?: JSONObject.NULL)
            .put("afterFingerprint", afterFingerprint ?: JSONObject.NULL)
            .put("payload", finalizedPayload ?: JSONObject.NULL)
            .put("error", error?.toJson() ?: JSONObject.NULL)
    }

    private fun serializedClickEvidence(base: JSONObject): JSONObject {
        val trace = HumanGestureDispatch.peekTrace(commandId)
        val sessionId = base.optString("sessionId").takeIf { it.isNotBlank() }
        val displayId = if (base.has("displayId")) base.optInt("displayId") else 0
        return if (trace == null) {
            JSONObject()
                .put("requestedHumanize", JSONObject.NULL)
                .put("resolvedProfile", JSONObject.NULL)
                .put("profileSource", "not_applied_semantic")
                .put("appliedProfile", "none")
                .put("dispatchMode", "semantic_action")
                .put("interactionMode", "semantic")
                .put("correctedOrRejected", false)
                .put("completed", true)
                .put("reason", JSONObject.NULL)
                .put("durationMs", JSONObject.NULL)
                .put("sessionId", sessionId ?: "default-foreground")
                .put("displayId", displayId)
        } else {
            JSONObject()
                .put("requestedHumanize", JSONObject.NULL)
                .put("resolvedProfile", trace.profile.name.lowercase())
                .put("profileSource", "android_dispatch_trace")
                .put("appliedProfile", trace.profile.name.lowercase())
                .put("dispatchMode", trace.dispatchMode)
                .put("interactionMode", "coordinate")
                .put("correctedOrRejected", !trace.accepted)
                .put("completed", trace.accepted)
                .put("reason", trace.reason ?: JSONObject.NULL)
                .put("durationMs", trace.durationMs)
                .put("sessionId", sessionId ?: "default-foreground")
                .put("displayId", if (trace.displayId > 0) trace.displayId else displayId)
        }
    }

    private fun addRuntimeEvidence(value: Any?): Any? {
        if (value !is JSONObject) return value
        val humanGesture = value.optJSONObject("humanGesture") ?: return value
        val out = JSONObject(value.toString())
        val evidence = out.getJSONObject("humanGesture")
        val dispatchMode = evidence.optString("dispatchMode")
        val backend = if (dispatchMode == "workspace_endpoint_duration") {
            "workspace_endpoint_duration"
        } else {
            "accessibility_dispatch_gesture"
        }
        val capabilities = HumanGestureRuntimeCapabilities.toJson(accessibilityConnected = true)
        evidence
            .put("backend", backend)
            .put("controlVersion", capabilities.getString("controlVersion"))
            .put("traceVersion", capabilities.getString("traceVersion"))
            .put("synthesisVersion", capabilities.getString("synthesisVersion"))
        return out
    }
}

object PhoneToolNames {
    val all = setOf(
        "workspace.list",
        "workspace.register",
        "workspace.switch",
        "phone.workspace_switch",
        "workspace.pause",
        "workspace.release",
        "workspace.arm",
        "workspace.next",

        "phone.observe",
        "phone.screenshot",
        "phone.find",
        "phone.click",
        "phone.long_press",
        "phone.tap",
        "phone.type",
        "phone.replace_text",
        "phone.scroll",
        "phone.swipe",
        "phone.back",
        "phone.home",
        "phone.open_app",
        "phone.open_notification",
        "phone.wait_for",
        "phone.assert",
        "phone.get_notifications",
        "phone.get_current_app",
        "phone.get_clipboard",
        "phone.set_clipboard",
        "phone.share",
        "phone.launch_intent",
        "phone.capabilities",
    )
}
