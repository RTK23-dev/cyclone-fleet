package com.cyclone.mobile.fastpath

import org.json.JSONArray
import org.json.JSONObject

enum class FastPathToolRole { PLANNER, UI, SHARED }

/**
 * Planner vs UI-subagent split. ClosePaw-shaped: planner lands (open_app/intent/wait/finish),
 * UI sub-agent operates the current tree (get_tree + index click/type/swipe).
 */
object FastPathSurface {
    val PLANNER_PHONE_TOOLS = setOf(
        "phone.open_app",
        "phone.launch_intent",
        "phone.wait_for",
        "phone.home",
    )
    val UI_PHONE_TOOLS = setOf(
        "phone.click",
        "phone.long_press",
        "phone.type",
        "phone.replace_text",
        "phone.scroll",
        "phone.back",
        "phone.submit_text",
        "phone.tap_point",
    )
    val PLANNER_MCP_TOOLS = setOf(
        "phone_status",
        "phone_skill_run",
        "phone_skill_save",
        "phone_capabilities",
    )
    val UI_MCP_TOOLS = setOf(
        "phone_observe",
        "phone_locate",
        "phone_ui_search",
        "phone_inspect_element",
        "phone_act",
    )

    /** ClosePaw names mapped onto the existing Cyclone MCP / PhoneToolExecutor surface. */
    val CLOSEPAW_ALIASES = linkedMapOf(
        "open_app" to "phone.open_app / phone_act(tool=phone.open_app)",
        "intent" to "phone.launch_intent / phone_act(tool=phone.launch_intent)",
        "wait" to "phone.wait_for / phone_act(tool=phone.wait_for)",
        "finish" to "model status=done (not a phone mutation); phone_skill_save when 2+ steps verified",
        "get_tree" to "phone_observe / phone_locate Page Card (a11y-first, elementIndex)",
        "click" to "phone_act(tool=phone.click) with current elementId or elementIndex",
        "type" to "phone_act(tool=phone.type) with current elementId or elementIndex",
        "swipe" to "phone.scroll direction=forward|backward (phone.swipe has no safe MCP route)",
    )

    fun roleForPhoneTool(tool: String): FastPathToolRole = when (tool) {
        in PLANNER_PHONE_TOOLS -> FastPathToolRole.PLANNER
        in UI_PHONE_TOOLS -> FastPathToolRole.UI
        else -> FastPathToolRole.SHARED
    }

    fun roleForMcpTool(tool: String): FastPathToolRole = when (tool) {
        in PLANNER_MCP_TOOLS -> FastPathToolRole.PLANNER
        in UI_MCP_TOOLS -> FastPathToolRole.UI
        else -> FastPathToolRole.SHARED
    }

    fun toJson(): JSONObject = JSONObject()
        .put("plannerPhoneTools", JSONArray(PLANNER_PHONE_TOOLS.toList()))
        .put("uiPhoneTools", JSONArray(UI_PHONE_TOOLS.toList()))
        .put("plannerMcpTools", JSONArray(PLANNER_MCP_TOOLS.toList()))
        .put("uiMcpTools", JSONArray(UI_MCP_TOOLS.toList()))
        .put("closepawAliases", JSONObject().also { json ->
            CLOSEPAW_ALIASES.forEach { (alias, mapped) -> json.put(alias, mapped) }
        })
        .put(
            "rule",
            "Planner lands with open_app/intent/wait/finish. UI sub-agent uses get_tree + index click/type. " +
                "phone.click soft-success (performed + verified=false/UNCHANGED) is not a second click.",
        )
}
