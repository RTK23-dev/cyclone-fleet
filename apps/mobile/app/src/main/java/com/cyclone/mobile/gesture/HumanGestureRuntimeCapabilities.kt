package com.cyclone.mobile.gesture

import com.cyclone.mobile.gesture.diagnostics.HumanGestureTraceAdapter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone-authoritative Human Gesture capability facts exposed through phone.capabilities.
 * This describes what each Android execution backend can actually represent; it is not a planner promise.
 */
object HumanGestureRuntimeCapabilities {
    const val CONTROL_VERSION = "cyclone.human_gesture.control.v1"

    fun toJson(accessibilityConnected: Boolean): JSONObject = JSONObject()
        .put("runtimeAvailable", accessibilityConnected)
        .put("controlVersion", CONTROL_VERSION)
        .put("traceVersion", HumanGestureTraceAdapter.SCHEMA)
        .put("traceSchema", HumanGestureTraceAdapter.SCHEMA)
        .put("synthesisVersion", HumanGestureTraceAdapter.ENGINE_VERSION)
        .put("supportedProfiles", JSONArray(listOf("auto", "off", "light", "normal")))
        .put("actions", JSONObject()
            .put("clickFallback", action("light", "semantic_first_then_synthesized_touch"))
            .put("tap", action("light", "synthesized_touch"))
            .put("longPressFallback", action("light", "semantic_first_then_synthesized_touch"))
            .put("swipe", action("normal", "synthesized_touch"))
            .put("scroll", action("normal", "semantic_first_then_safe_grounded_fallback")))
        .put("executionPlanes", JSONObject()
            .put("foregroundDisplay0", JSONObject()
                .put("backend", "accessibility_dispatch_gesture")
                .put("cubicPath", true)
                .put("humanGesture", true))
            .put("namedVirtualDisplay", JSONObject()
                .put("backend", "accessibility_dispatch_gesture")
                .put("cubicPath", true)
                .put("humanGesture", true)
                .put("displayTarget", "gesture_description_set_display_id"))
            .put("layer2", JSONObject()
                .put("backend", "accessibility_dispatch_gesture")
                .put("cubicPath", true)
                .put("humanGesture", true)
                .put("ownership", "layer2_display0_mutation_lease")))

    private fun action(autoProfile: String, mode: String): JSONObject = JSONObject()
        .put("humanizeAccepted", true)
        .put("autoProfile", autoProfile)
        .put("foregroundMode", mode)
        .put("offMode", "legacy_straight")
}
