package com.cyclone.mobile.mind

import org.json.JSONArray
import org.json.JSONObject

/** A capability offered to the model, described like an API: name, what it does, and its JSON arguments. */
data class MindToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject = objectSchema(),
) {
    init { require(NAME.matches(name)) { "tool names must match ${NAME.pattern}" } }

    fun toWire(): JSONObject = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", description).put("parameters", parameters))

    fun toText(): String = "- $name: $description\n  arguments: $parameters"

    companion object {
        private val NAME = Regex("^[a-z][a-z0-9_]{1,63}$")

        fun objectSchema(vararg properties: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
            JSONObject().put("type", "object")
                .put("properties", JSONObject().also { props -> properties.forEach { (key, schema) -> props.put(key, schema) } })
                .put("required", JSONArray(required))
                .put("additionalProperties", false)

        fun string(description: String, enum: List<String>? = null): JSONObject = JSONObject().put("type", "string")
            .put("description", description).also { json -> enum?.let { json.put("enum", JSONArray(it)) } }
        fun integer(description: String, min: Int? = null, max: Int? = null): JSONObject = JSONObject().put("type", "integer")
            .put("description", description).also { json -> min?.let { json.put("minimum", it) }; max?.let { json.put("maximum", it) } }
        fun boolean(description: String): JSONObject = JSONObject().put("type", "boolean").put("description", description)
        fun array(description: String, items: JSONObject): JSONObject =
            JSONObject().put("type", "array").put("description", description).put("items", items)
    }
}

/** How a mission ends when a tool decides it. */
enum class MindEnding { COMPLETED, GAVE_UP }

/**
 * What a tool returns to the model. [text] is the full result (usually including the new screen), [brief] is the
 * one-line version kept once old turns are compacted. [imageDataUrl] attaches a screenshot for vision models.
 * [changedScreen] marks a page-changing action: further calls from the same model turn are not run, because they were
 * decided against a screen that no longer exists. [ownerWaitMs] is time spent waiting for the owner, which does not
 * count against the mission's working time.
 */
data class MindToolResult(
    val text: String,
    val brief: String = text.lineSequence().firstOrNull().orEmpty().take(200),
    val ok: Boolean = true,
    val imageDataUrl: String? = null,
    val ending: MindEnding? = null,
    val summary: String? = null,
    val evidence: String? = null,
    val changedScreen: Boolean = false,
    val ownerWaitMs: Long = 0,
    /** Moves made from the learned map without a model decision (go_to). */
    val mapMoves: Int = 0,
) {
    companion object {
        fun error(message: String) = MindToolResult("ERROR: $message", "ERROR: ${message.take(160)}", ok = false)
    }
}

/** The phone (or a fake in tests) as seen by the Mind. Implementations route every mutation through the harness. */
interface MindToolbox {
    fun specs(): List<MindToolSpec>
    fun execute(call: MindToolCall, arguments: JSONObject): MindToolResult
    /** A compact description of the current phone state for the opening message; never secrets. */
    fun situation(): String = ""
    /** Plan 37 W3: the owner said something during the mission (a message); a toolbox may listen for memory requests. */
    fun onOwnerMessage(text: String) {}
    /** Plan 38: the owner changed the task (Steer). Returns the new goal version. */
    fun onSteer(text: String): Int = 0
}
