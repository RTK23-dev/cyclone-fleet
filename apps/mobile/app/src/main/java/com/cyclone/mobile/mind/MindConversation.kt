package com.cyclone.mobile.mind

import org.json.JSONArray
import org.json.JSONObject

/** One tool call the model asked for. [arguments] is the raw JSON text the model produced. */
data class MindToolCall(val id: String, val name: String, val arguments: String) {
    fun argumentsJson(): JSONObject? = runCatching { JSONObject(arguments.ifBlank { "{}" }) }.getOrNull()
}

/**
 * The single continuous conversation of a mission: what the owner asked, everything the model said and did, and
 * every result it got back. This is the Mind's working memory; nothing outside it decides for the model.
 */
sealed class MindMessage {
    abstract fun chars(): Int

    data class System(val text: String) : MindMessage() {
        override fun chars() = text.length
    }

    /** The owner (goal, answers) or the harness speaking to the model. [imageDataUrl] carries a screenshot. */
    data class User(val text: String, val imageDataUrl: String? = null, val origin: Origin = Origin.OWNER) : MindMessage() {
        enum class Origin { OWNER, HARNESS, SCREEN }
        override fun chars() = text.length + if (imageDataUrl != null) IMAGE_CHAR_COST else 0
    }

    /**
     * What the model produced. [reasoningDetails] is provider reasoning state kept only in memory for continuity; it is
     * never written to the journal, diagnostics or Glass.
     */
    data class Assistant(
        val text: String,
        val toolCalls: List<MindToolCall>,
        val reasoningDetails: JSONArray? = null,
    ) : MindMessage() {
        override fun chars() = text.length + toolCalls.sumOf { it.name.length + it.arguments.length }
    }

    /** A tool result. [brief] replaces [full] once the conversation is compacted. */
    data class Tool(
        val callId: String,
        val name: String,
        val full: String,
        val brief: String,
        val compacted: Boolean = false,
    ) : MindMessage() {
        val content: String get() = if (compacted) brief else full
        override fun chars() = content.length
    }

    companion object {
        const val IMAGE_CHAR_COST = 4_000
    }
}

class MindConversation(initial: List<MindMessage> = emptyList()) {
    private val messages = initial.toMutableList()

    fun all(): List<MindMessage> = messages.toList()
    fun size(): Int = messages.size
    fun add(message: MindMessage) { messages += message }
    fun replaceFirst(message: MindMessage) { if (messages.isEmpty()) messages += message else messages[0] = message }
    fun chars(): Int = messages.sumOf { it.chars() }
    /** Provider reasoning state only makes sense to the model that produced it; drop it when the model changes. */
    fun forgetReasoning() {
        messages.indices.forEach { index ->
            val message = messages[index]
            if (message is MindMessage.Assistant && message.reasoningDetails != null) messages[index] = message.copy(reasoningDetails = null)
        }
    }

    fun lastAssistant(): MindMessage.Assistant? = messages.lastOrNull { it is MindMessage.Assistant } as MindMessage.Assistant?

    /**
     * Keep the conversation inside the model's context: older tool results shrink to their brief form, and only the
     * newest screenshot stays attached. The system prompt, the owner's words and the model's own turns are never
     * dropped: the mind keeps its train of thought.
     */
    /**
     * Returns how many tool results were shortened by this call. Alpha 93: once over [maxChars], it shrinks to [target]
     * (60% by default) in one go, so the next turns re-send an unchanged prefix (provider prompt caching) instead of
     * shortening one old result every turn at the ceiling.
     */
    fun compact(maxChars: Int, keepRecent: Int = 8, target: Int = maxChars * 6 / 10): Int {
        val latestImage = messages.indexOfLast { it is MindMessage.User && it.imageDataUrl != null }
        messages.indices.forEach { index ->
            val message = messages[index]
            if (message is MindMessage.User && message.imageDataUrl != null && index != latestImage) {
                messages[index] = message.copy(imageDataUrl = null, text = message.text + " [earlier screenshot removed]")
            }
        }
        if (chars() <= maxChars) return 0
        val protectedFrom = (messages.size - keepRecent).coerceAtLeast(0)
        var index = 0
        var shortened = 0
        while (chars() > target.coerceAtMost(maxChars) && index < protectedFrom) {
            val message = messages[index]
            if (message is MindMessage.Tool && !message.compacted) {
                messages[index] = message.copy(compacted = true)
                shortened++
            }
            index++
        }
        return shortened
    }

    /**
     * Plan 37 (D10): folds messages [from, to) without removing any. Tool results show their one-line brief (their full
     * text stays in [MindMessage.Tool.full] for recall), screenshots are dropped, provider reasoning is dropped and the
     * model's own words are trimmed to [keepText] characters. Indices never move, so tool calls keep their results.
     * Returns how many tool results were folded.
     */
    fun fold(from: Int, to: Int, keepText: Int): Int {
        var folded = 0
        for (index in from.coerceAtLeast(0) until to.coerceAtMost(messages.size)) {
            val message = messages[index]
            messages[index] = when (message) {
                is MindMessage.Tool -> if (message.compacted) message else { folded++; message.copy(compacted = true) }
                is MindMessage.User -> if (message.imageDataUrl != null) message.copy(imageDataUrl = null, text = message.text + " [earlier screenshot removed]") else message
                is MindMessage.Assistant -> message.copy(reasoningDetails = null,
                    text = if (message.text.length > keepText) message.text.take(keepText) + "…" else message.text)
                is MindMessage.System -> message
            }
        }
        return folded
    }

    /** Characters of tool results from [from] on that are still shown in full. */
    fun unfoldedToolChars(from: Int): Int =
        messages.drop(from.coerceAtLeast(0)).sumOf { if (it is MindMessage.Tool && !it.compacted) it.full.length else 0 }

    /**
     * The wire with a trailing message that is rebuilt every turn and never stored (plan 37 §2: the live state). The
     * stored conversation stays a byte-exact prefix, so provider prompt caching keeps working.
     */
    fun toWire(nativeTools: Boolean, tail: String?): JSONArray {
        val wire = toWire(nativeTools)
        if (!tail.isNullOrBlank()) wire.put(JSONObject().put("role", "user").put("content", tail))
        return wire
    }

    /** OpenAI/OpenRouter chat messages. With [nativeTools] false, tool traffic is rendered as plain text turns. */
    fun toWire(nativeTools: Boolean): JSONArray {
        val out = JSONArray()
        val pendingImages = mutableListOf<MindMessage.User>()
        fun flushImages() {
            pendingImages.forEach { out.put(userJson(it)) }
            pendingImages.clear()
        }
        messages.forEach { message ->
            if (message !is MindMessage.Tool) flushImages()
            when (message) {
                is MindMessage.System -> out.put(JSONObject().put("role", "system").put("content", message.text))
                is MindMessage.User -> if (message.origin == MindMessage.User.Origin.SCREEN && nativeTools &&
                    out.length() > 0 && out.getJSONObject(out.length() - 1).optString("role") == "tool") {
                    // A screenshot belongs after every tool result of that turn: tool messages must follow their call.
                    pendingImages += message
                } else out.put(userJson(message))
                is MindMessage.Assistant -> out.put(assistantJson(message, nativeTools))
                is MindMessage.Tool -> out.put(
                    if (nativeTools) JSONObject().put("role", "tool").put("tool_call_id", message.callId).put("content", message.content)
                    else JSONObject().put("role", "user").put("content", "RESULT of ${message.name}:\n${message.content}"),
                )
            }
        }
        flushImages()
        return out
    }

    private fun userJson(message: MindMessage.User): JSONObject {
        val json = JSONObject().put("role", "user")
        if (message.imageDataUrl == null) return json.put("content", message.text)
        return json.put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", message.text))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", message.imageDataUrl))))
    }

    private fun assistantJson(message: MindMessage.Assistant, nativeTools: Boolean): JSONObject {
        val json = JSONObject().put("role", "assistant")
        if (!nativeTools) {
            val calls = JSONArray()
            message.toolCalls.forEach { calls.put(JSONObject().put("tool", it.name).put("arguments", it.argumentsJson() ?: JSONObject())) }
            val text = if (calls.length() == 0) message.text else JSONObject().put("say", message.text).put("calls", calls).toString()
            return json.put("content", text)
        }
        json.put("content", message.text.ifBlank { JSONObject.NULL })
        if (message.toolCalls.isNotEmpty()) {
            json.put("tool_calls", JSONArray().also { calls ->
                message.toolCalls.forEach { call ->
                    calls.put(JSONObject().put("id", call.id).put("type", "function")
                        .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments.ifBlank { "{}" })))
                }
            })
        }
        message.reasoningDetails?.let { json.put("reasoning_details", it) }
        return json
    }

    /** Journal form: everything needed to resume, minus provider reasoning state and screenshots. */
    fun toJournal(): JSONArray = JSONArray().also { out ->
        messages.forEach { message ->
            out.put(when (message) {
                is MindMessage.System -> JSONObject().put("t", "system").put("text", message.text)
                is MindMessage.User -> JSONObject().put("t", "user").put("text", message.text).put("origin", message.origin.name)
                    .put("hadImage", message.imageDataUrl != null)
                is MindMessage.Assistant -> JSONObject().put("t", "assistant").put("text", message.text)
                    .put("calls", JSONArray().also { calls ->
                        message.toolCalls.forEach { calls.put(JSONObject().put("id", it.id).put("name", it.name).put("args", it.arguments)) }
                    })
                is MindMessage.Tool -> JSONObject().put("t", "tool").put("id", message.callId).put("name", message.name)
                    .put("full", message.full).put("brief", message.brief).put("compacted", message.compacted)
            })
        }
    }

    companion object {
        fun fromJournal(array: JSONArray): MindConversation = MindConversation((0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            when (row.optString("t")) {
                "system" -> MindMessage.System(row.optString("text"))
                "user" -> MindMessage.User(
                    row.optString("text") + if (row.optBoolean("hadImage")) " [screenshot not kept]" else "",
                    origin = runCatching { MindMessage.User.Origin.valueOf(row.optString("origin")) }.getOrDefault(MindMessage.User.Origin.OWNER),
                )
                "assistant" -> MindMessage.Assistant(row.optString("text"), row.optJSONArray("calls")?.let { calls ->
                    (0 until calls.length()).mapNotNull { i ->
                        calls.optJSONObject(i)?.let { MindToolCall(it.optString("id"), it.optString("name"), it.optString("args")) }
                    }
                }.orEmpty())
                "tool" -> MindMessage.Tool(row.optString("id"), row.optString("name"), row.optString("full"),
                    row.optString("brief"), row.optBoolean("compacted"))
                else -> null
            }
        })
    }
}
