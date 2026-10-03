package com.cyclone.mobile

import org.json.JSONArray
import org.json.JSONObject

data class PhoneToolDefinition(
    val name: String,
    val mutating: Boolean,
    val requiredCapability: String? = null,
    val description: String,
    val parameters: JSONObject? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("mutating", mutating)
        .put("requiredCapability", requiredCapability ?: JSONObject.NULL)
        .put("description", description)
        .put("parameters", parameters ?: JSONObject.NULL)
}

object PhoneToolRegistry {
    private fun humanizeParameters(): JSONObject = JSONObject().put(
        "humanize",
        JSONObject()
            .put("type", "string")
            .put("enum", JSONArray(listOf("auto", "off", "light", "normal")))
            .put("default", "auto")
            .put("unknownValues", "reject"),
    )

    val definitions: List<PhoneToolDefinition> = listOf(
        PhoneToolDefinition("workspace.list", false, description = "List durable Layer 2 profiles, armed jobs and global mutation owner"),
        PhoneToolDefinition("workspace.register", true, description = "Register id, label, appPackage, androidUserId; display 0 only"),
        PhoneToolDefinition("workspace.switch", true, description = "Release input, launch target and verify package/user before granting a new workspace generation; never bypass GATE"),
        PhoneToolDefinition("phone.workspace_switch", true, description = "Alias for workspace.switch"),
        PhoneToolDefinition("workspace.pause", true, description = "Revoke current mutation lease"),
        PhoneToolDefinition("workspace.release", true, description = "Pause all queued work and return to ordinary foreground mode"),
        PhoneToolDefinition("workspace.arm", true, description = "Arm one ephemeral job per workspace with id and goal"),
        PhoneToolDefinition("workspace.next", true, description = "Claim the next round-robin job; observe then act with returned workspaceId/workspaceGeneration"),
        PhoneToolDefinition("phone.observe", false, "accessibility", "A11y-first observation: Page Card with stable elementIndex. Vision/screenshot only when the tree is useless"),
        PhoneToolDefinition("phone.screenshot", false, "screenshot", "Capture the screen or a cropped region; base64 is opt-in"),
        PhoneToolDefinition("phone.find", false, "accessibility", "Resolve stable selectors against the current normalized UI snapshot"),
        PhoneToolDefinition("phone.click", true, "accessibility", "Click a current observation-scoped element. Semantic ACTION_CLICK/ACTION_SELECT remains first; only the grounded coordinate fallback uses bounded Human Gesture. Fallback waits for Android gesture completion, then Fast Path settles 300ms and fingerprints; Unchanged is verified=false and must not retry via a second click channel", humanizeParameters()),
        PhoneToolDefinition("phone.long_press", true, "accessibility", "Long-press a selected element; semantic ACTION_LONG_CLICK first, coordinate fallback uses bounded Human Gesture", humanizeParameters()),
        PhoneToolDefinition("phone.tap", true, "accessibility", "Tap screen coordinates; auto resolves to LIGHT Human Gesture and off preserves the straight compatibility path", humanizeParameters()),
        PhoneToolDefinition("phone.type", true, "accessibility", "Set text on a selected or focused editable element"),
        PhoneToolDefinition("phone.replace_text", true, "accessibility", "Replace text on a selected or focused editable element"),
        PhoneToolDefinition("phone.scroll", true, "accessibility", "Scroll semantically first; when unsupported, safe grounded coordinate fallback uses auto=NORMAL Human Gesture", humanizeParameters()),
        PhoneToolDefinition("phone.swipe", true, "accessibility", "Dispatch a coordinate swipe gesture; auto resolves to NORMAL Human Gesture and off preserves the straight compatibility path", humanizeParameters()),
        PhoneToolDefinition("phone.back", true, "accessibility", "Perform Android Back"),
        PhoneToolDefinition("phone.home", true, "accessibility", "Perform Android Home"),
        PhoneToolDefinition("phone.open_app", true, "app_launch", "Planner landing: launch an installed package through its launcher intent before hunting icons"),
        PhoneToolDefinition("phone.open_notification", true, "notification_listener", "Open a retained notification through its content PendingIntent"),
        PhoneToolDefinition("phone.wait_for", false, "accessibility", "Wait locally for a selector/package/text/fingerprint condition without LLM polling"),
        PhoneToolDefinition("phone.assert", false, "accessibility", "Assert a current UI condition once"),
        PhoneToolDefinition("phone.get_notifications", false, "notification_listener", "Return retained notification metadata and action titles"),
        PhoneToolDefinition("phone.get_current_app", false, null, "Return last observed package/class/controller state"),
        PhoneToolDefinition("phone.get_clipboard", false, "clipboard", "Read clipboard text when Android permits it"),
        PhoneToolDefinition("phone.set_clipboard", true, "clipboard", "Write clipboard text"),
        PhoneToolDefinition("phone.share", true, "intent_launch", "Open Android ACTION_SEND for text, optionally scoped to a package"),
        PhoneToolDefinition("phone.launch_intent", true, "intent_launch", "Planner landing: open an allowlisted URI with ACTION_VIEW before hunting a browser icon"),
        PhoneToolDefinition("phone.set_alarm", true, "intent_launch", "Ask the clock app to create an alarm (hour 0-23, minute 0-59, optional label) and show it; prove it on the Clock screen"),
        PhoneToolDefinition("phone.set_timer", true, "intent_launch", "Ask the clock app to start a timer (seconds 1-86400, optional label) and show it; prove the countdown on the Clock screen"),
        PhoneToolDefinition("phone.open_settings", true, "intent_launch", "Open an allowlisted Android Settings page (page key, optional app package for app pages); navigation only"),
        PhoneToolDefinition("phone.tap_point", true, "accessibility", "Vision fallback: tap screen pixels when accessibility exposes no control; whatever is under the point gets the same GATE check as a labelled click", humanizeParameters()),
        PhoneToolDefinition("phone.submit_text", true, "accessibility", "Press the keyboard action key (Enter/Search/Go) on a current observation-scoped editable element; non-search fields need GATE approval"),
        PhoneToolDefinition("phone.capabilities", false, null, "Return runtime capability availability and missing-permission states"),
    )

    private val byName = definitions.associateBy { it.name }

    fun definition(name: String): PhoneToolDefinition? = byName[name]
    fun isMutating(name: String): Boolean = byName[name]?.mutating == true
    fun toJson(): JSONArray = JSONArray().also { array -> definitions.forEach { array.put(it.toJson()) } }
}
