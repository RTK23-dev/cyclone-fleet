package com.cyclone.mobile.mind

import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.ai.OpenRouterReasoningContract
import com.cyclone.mobile.ai.ProviderCancellation
import com.cyclone.mobile.ai.ProviderFailure
import com.cyclone.mobile.ai.ProviderFailureClass
import com.cyclone.mobile.ai.ProviderLifecycleException
import com.cyclone.mobile.ai.ProviderRequestPurpose
import com.cyclone.mobile.ai.ProviderRequests
import com.cyclone.mobile.ai.model.ModelRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class MindUsage(val promptTokens: Int = 0, val completionTokens: Int = 0, val costUsd: Double = 0.0) {
    operator fun plus(other: MindUsage) = MindUsage(promptTokens + other.promptTokens,
        completionTokens + other.completionTokens, costUsd + other.costUsd)
}

data class MindModelReply(
    val text: String,
    val toolCalls: List<MindToolCall>,
    val reasoningDetails: JSONArray? = null,
    val usage: MindUsage = MindUsage(),
    val latencyMs: Long = 0,
)

data class MindModelRequest(
    val messages: JSONArray,
    val tools: List<MindToolSpec>,
    val nativeTools: Boolean,
    val budgetMs: Long,
)

/** Typed failures so the loop can decide: wait and retry, switch to the backup model, or stop honestly. */
sealed class MindModelError(message: String) : Exception(message) {
    class RateLimited(message: String, val retryAfterMs: Long = 2_000) : MindModelError(message)
    class Transient(message: String) : MindModelError(message)
    class Deadline(message: String) : MindModelError(message)
    class Cancelled : MindModelError("The mission was stopped.")
    class ToolsUnsupported(message: String) : MindModelError(message)
    class Fatal(message: String) : MindModelError(message)
}

interface MindModel {
    val id: String
    val label: String
    val vision: Boolean
    fun complete(request: MindModelRequest): MindModelReply
}

/** OpenRouter chat completions with native tool calling, a mission-sized time budget and usage accounting. */
class OpenRouterMindModel(
    private val apiKey: String,
    override val id: String,
    override val label: String,
    override val vision: Boolean,
    private val reasoningEffort: String?,
    private val traceId: String,
    private val cancellation: ProviderCancellation,
    private val cancelled: () -> Boolean,
    private val onPhase: (String, Long) -> Unit = { _, _ -> },
) : MindModel {
    override fun complete(request: MindModelRequest): MindModelReply {
        val body = body(request)
        val http = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Mind")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val context = ProviderRequests.context(traceId, apiKey, id, ProviderRequestPurpose.MISSION, request.budgetMs,
            cancellation, externallyCancelled = cancelled, onPhase = onPhase)
        val started = System.currentTimeMillis()
        val reply = try {
            ProviderRequests.execute(http, context, ProviderRequests.missionHttp)
        } catch (error: ProviderLifecycleException) {
            throw when (error.reason) {
                "provider.cancelled" -> MindModelError.Cancelled()
                "provider.deadline" -> MindModelError.Deadline("$label did not answer within ${request.budgetMs / 1000} s.")
                "provider.cooldown", "provider.request_in_progress" -> MindModelError.RateLimited(ProviderRequests.message(error.reason))
                else -> MindModelError.Transient(ProviderRequests.message(error.reason))
            }
        }
        val json = runCatching { JSONObject(reply.body) }.getOrNull()
        val embedded = json?.optJSONObject("error")
        if (reply.status !in 200..299 || embedded != null) {
            val status = if (reply.status in 200..299) embedded?.optInt("code", 500) ?: 500 else reply.status
            throw classify(status, reply.body)
        }
        return parse(json ?: throw MindModelError.Transient("$label returned an unreadable response."), System.currentTimeMillis() - started)
    }

    private fun classify(status: Int, body: String): MindModelError {
        val text = body.lowercase()
        if (status in setOf(400, 404, 422) && ("tool" in text && ("support" in text || "not allowed" in text || "unsupported" in text))) {
            return MindModelError.ToolsUnsupported("$label does not accept native tools on this route.")
        }
        val failure = ProviderFailure.classify(status, body, id)
        return when {
            failure.failureClass == ProviderFailureClass.RATE_LIMITED -> MindModelError.RateLimited(failure.userMessage)
            failure.retryable || status in setOf(0, 500, 502, 503, 504) -> MindModelError.Transient(failure.userMessage)
            else -> MindModelError.Fatal(failure.userMessage)
        }
    }

    internal fun body(request: MindModelRequest): JSONObject {
        val profile = ModelRegistry.resolve(id)
        val catalog = OpenRouterCatalogStore.lookup(id)
        val maximum = catalog?.maxOutputTokens ?: 16_384
        val messages = if (promptCaching(id)) withCacheBreakpoints(request.messages) else request.messages
        val body = JSONObject().put("model", id).put("messages", messages).put("stream", false)
            .put("max_tokens", 8_192.coerceAtMost(maximum.coerceAtLeast(1_024)))
            .put("provider", JSONObject().put("sort", "latency").put("allow_fallbacks", profile?.allowProviderFallbacks ?: true))
            .put("usage", JSONObject().put("include", true))
        if (request.nativeTools && request.tools.isNotEmpty()) {
            body.put("tools", JSONArray().also { array -> request.tools.forEach { array.put(it.toWire()) } })
            body.put("tool_choice", "auto")
            body.put("parallel_tool_calls", false)
        }
        return OpenRouterReasoningContract.apply(body, catalog?.reasoning, reasoningEffort)
    }

    companion object {
        /**
         * Anthropic routes cache only at explicit breakpoints. A mission re-sends its whole conversation every turn, so
         * one breakpoint on the system prompt (tools + instructions) and one on the newest user message make every turn
         * after the first mostly a cache read. Other providers cache automatically and get the messages unchanged.
         */
        fun promptCaching(modelId: String): Boolean = modelId.startsWith("anthropic/")

        fun withCacheBreakpoints(messages: JSONArray): JSONArray {
            val out = JSONArray(messages.toString())
            val marker = JSONObject().put("type", "ephemeral")
            fun mark(index: Int) {
                val message = out.optJSONObject(index) ?: return
                when (val content = message.opt("content")) {
                    is String -> if (content.isNotEmpty()) message.put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", content).put("cache_control", marker)))
                    is JSONArray -> {
                        for (i in content.length() - 1 downTo 0) {
                            val part = content.optJSONObject(i) ?: continue
                            if (part.optString("type") == "text") { part.put("cache_control", marker); break }
                        }
                    }
                }
            }
            (0 until out.length()).firstOrNull { out.optJSONObject(it)?.optString("role") == "system" }?.let(::mark)
            (out.length() - 1 downTo 0).firstOrNull { out.optJSONObject(it)?.optString("role") == "user" }?.let(::mark)
            return out
        }

        /** Pure parser shared with tests: native tool_calls, or the JSON envelope used when tools are not native. */
        fun parse(json: JSONObject, latencyMs: Long = 0): MindModelReply {
            val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw MindModelError.Transient("The model returned no message.")
            val text = message.optString("content").takeUnless { message.isNull("content") }.orEmpty()
            val calls = mutableListOf<MindToolCall>()
            message.optJSONArray("tool_calls")?.let { array ->
                for (i in 0 until array.length()) {
                    val call = array.optJSONObject(i) ?: continue
                    val function = call.optJSONObject("function") ?: continue
                    val name = function.optString("name").trim()
                    if (name.isBlank()) continue
                    calls += MindToolCall(call.optString("id").ifBlank { "call_${i}_${name}" }, name, function.optString("arguments", "{}"))
                }
            }
            val envelope = if (calls.isEmpty()) envelopeCalls(text) else null
            val usage = json.optJSONObject("usage")?.let {
                MindUsage(it.optInt("prompt_tokens"), it.optInt("completion_tokens"), it.optDouble("cost", 0.0).takeIf { c -> !c.isNaN() } ?: 0.0)
            } ?: MindUsage()
            return MindModelReply(
                text = envelope?.first ?: text,
                toolCalls = envelope?.second ?: calls,
                reasoningDetails = message.optJSONArray("reasoning_details"),
                usage = usage,
                latencyMs = latencyMs,
            )
        }

        /** {"say": "...", "calls": [{"tool": "...", "arguments": {...}}]} inside the text, for non-native routes. */
        internal fun envelopeCalls(text: String): Pair<String, List<MindToolCall>>? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val json = runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull() ?: return null
            val array = json.optJSONArray("calls") ?: json.optJSONObject("call")?.let { JSONArray().put(it) } ?: return null
            val calls = (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                val name = row.optString("tool").ifBlank { row.optString("name") }.trim()
                if (name.isBlank()) null else MindToolCall("env_${i}_$name", name, (row.optJSONObject("arguments") ?: JSONObject()).toString())
            }
            if (calls.isEmpty()) return null
            return json.optString("say") to calls
        }
    }
}
