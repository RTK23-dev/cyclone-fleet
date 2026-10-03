package com.cyclone.mobile.ai

import android.content.Context
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.brain.CycloneBrainRuntime
import com.cyclone.mobile.ai.model.BoundedJsonRepair
import com.cyclone.mobile.ai.model.ModelRegistry
import com.cyclone.mobile.ai.model.StructuredOutputMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class OpenRouterModelPreset(
    val id: String,
    val label: String,
    val vision: Boolean,
    val reasoningEffort: String = "medium",
)

/** Typed Cyclone registry projected into the legacy OpenRouter preset surface. */
object OpenRouterModelPresets {
    val GPT_5_6_LUNA = ModelRegistry.preset(ModelRegistry.GPT_5_6_LUNA)
    val GEMINI_3_8_FLASH = ModelRegistry.preset(ModelRegistry.GEMINI_3_8_FLASH)
    val GLM_5_3_FLASH = ModelRegistry.preset(ModelRegistry.GLM_5_3_FLASH)
    val MUSE_SPARK_1_3 = ModelRegistry.preset(ModelRegistry.MUSE_SPARK_1_3)
    val GPT_5_6_SOL = ModelRegistry.preset(ModelRegistry.GPT_5_6_SOL)
    val GPT_6_ASTRA = ModelRegistry.preset(ModelRegistry.GPT_6_ASTRA)
    val CLAUDE_FABLE_5_1 = ModelRegistry.preset(ModelRegistry.CLAUDE_FABLE_5_1)
    val MUSE_SPARK_1_3_CONTRIBUTOR = ModelRegistry.preset(ModelRegistry.MUSE_SPARK_1_3_CONTRIBUTOR)

    /** Legacy default for programmatic callers; UI choices come from OpenRouterCatalogStore. */
    val DEFAULT = GPT_5_6_LUNA
    val all = ModelRegistry.all.map(ModelRegistry::preset)

    // Compatibility names used by older screens; both resolve to current curated endpoints.
    val GEMINI_3_6_FLASH = GEMINI_3_8_FLASH
    val DEEPSEEK_V4_FLASH = GLM_5_3_FLASH

    // Unknown custom slugs remain accepted, but vision support is not assumed.
    fun byId(id: String): OpenRouterModelPreset {
        val canonical = ModelRegistry.resolve(id)?.openRouterSlug ?: id.trim()
        return OpenRouterCatalogStore.lookup(canonical)?.preset()
            ?: ModelRegistry.resolve(id)?.let(ModelRegistry::preset)
            ?: OpenRouterModelPreset(canonical, canonical.ifBlank { "Choose model" }, false)
    }
}

data class QuickAgentConfig(
    val model: OpenRouterModelPreset = OpenRouterModelPresets.DEFAULT,
    val visionModel: OpenRouterModelPreset = model,
    /**
     * Legacy compatibility knob for older workflow/tests. Foreground CycloneLocalAgent execution
     * no longer uses provider-call count as a task termination budget.
     */
    val maxDecisions: Int = 6,
    val safeMode: Boolean = true,
    val accessProfile: CycloneAiAccessProfile = if (safeMode) CycloneAiAccessProfile.BALANCED else CycloneAiAccessProfile.FULL,
    val providerSort: String = "latency",
    val attachment: com.cyclone.mobile.ui.overlay.TaskAttachment? = null,
)

data class QuickAgentResult(
    val ok: Boolean,
    val message: String,
    val decisions: Int,
    val model: String,
    val workflowId: String? = null,
    /** Persistent local task id; present for foreground task sessions and GATE resume. */
    val taskId: String? = null,
    /** CycloneTaskClassification name. Null for legacy one-shot workflow compilation. */
    val classification: String? = null,
    /** Overlay GATE wire value when a deterministic Android boundary can name one. */
    val gateClass: String? = null,
)

/**
 * Compatibility facade. Foreground phone execution is owned by OpenRouterAdaptiveAgent, which is
 * page-aware in V2.8. This class still owns the one-shot workflow compiler used by the UI.
 */
class OpenRouterQuickAgent(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun execute(goal: String, config: QuickAgentConfig, onProgress: (String) -> Unit = {}): QuickAgentResult =
        OpenRouterAdaptiveAgent(context).execute(goal, config, onProgress)

    suspend fun buildWorkflow(goal: String, config: QuickAgentConfig, onProgress: (String) -> Unit = {}): QuickAgentResult = withContext(Dispatchers.IO) {
        val apiKey = OpenRouterSecretStore.read(context)
        if (apiKey.isBlank()) return@withContext QuickAgentResult(false, "Add an OpenRouter API key first.", 0, config.model.id)
        if (goal.isBlank()) return@withContext QuickAgentResult(false, "Describe the workflow you want Cyclone to build.", 0, config.model.id)

        val traceId = AgentTraceRuntime.start(context, "Build workflow: $goal", config.model.id)
        val environment = MobileContextHarness.observe(context, goal)
        onProgress("Designing deterministic workflow…")
        AgentTraceRuntime.event(context, traceId, "DECISION", "Turning the current page into a reviewable deterministic workflow", code = "workflow.design")
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", WORKFLOW_PROMPT))
            .put(JSONObject().put("role", "user").put("content", "REQUEST:\n$goal\n\nCURRENT_PHONE_CONTEXT:\n$environment"))
        val response = chat(apiKey, config.model, messages, config.providerSort, jsonMode = true)
        val raw = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (raw.isBlank()) {
            val result = QuickAgentResult(false, apiError(response), 1, config.model.id)
            return@withContext completeTrace(traceId, result)
        }
        val repaired = BoundedJsonRepair.extractSingleObject(raw) ?: stripCodeFence(raw)
        val proposal = runCatching { JSONObject(repaired) }.getOrElse {
            return@withContext completeTrace(traceId, QuickAgentResult(false, "The model returned invalid workflow JSON: ${it.message}", 1, config.model.id))
        }
        val result = AutomationRuntime.importAiProposal(context, proposal).fold(
            onSuccess = { automation ->
                AgentTraceRuntime.event(context, traceId, "RESULT", "A deterministic workflow was created and left disabled for review", code = "workflow.saved", ok = true, detail = automation.name)
                QuickAgentResult(true, "Workflow '${automation.name}' was created disabled for review.", 1, config.model.id, automation.id)
            },
            onFailure = {
                AgentTraceRuntime.event(context, traceId, "ERROR", "Cyclone's typed workflow validator rejected the proposal", code = "workflow.rejected", ok = false, detail = it.message)
                QuickAgentResult(false, "Workflow was rejected by Cyclone's validator: ${it.message}", 1, config.model.id)
            },
        )
        completeTrace(traceId, result)
    }

    private fun completeTrace(traceId: String, result: QuickAgentResult): QuickAgentResult {
        val status = if (result.ok) "COMPLETED" else "FAILED"
        AgentTraceRuntime.finish(context, traceId, status, result.message, result.decisions)
        AgentTraceRuntime.store.listSessions(100).firstOrNull { it.id == traceId }?.let { session ->
            runCatching { CycloneBrainRuntime.record(context, session, AgentTraceRuntime.store.events(traceId)) }
        }
        return result
    }

    private fun chat(
        apiKey: String,
        model: OpenRouterModelPreset,
        messages: JSONArray,
        providerSort: String,
        jsonMode: Boolean,
    ): JSONObject {
        val maxTokens = 8192
        val body = com.cyclone.mobile.ai.model.PortableModelRequest.body(model.id, messages,
            emptyList(), maxTokens)
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Mobile V2.8")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse {
                JSONObject().put("error", JSONObject().put("message", text.ifBlank { "HTTP ${response.code}" }))
            }
            if (!response.isSuccessful) {
                val error = json.optJSONObject("error") ?: JSONObject().put("message", "HTTP ${response.code}")
                if (!error.has("code")) error.put("code", response.code)
                json.put("error", error)
            }
            json.put("_cycloneMeta", JSONObject()
                .put("httpStatus", response.code)
                .put("requestId", response.header("x-request-id").orEmpty())
                .put("provider", json.optString("provider")))
            json
        }
    }

    companion object {
        private val WORKFLOW_PROMPT = """
You convert a natural-language Android request into ONE Cyclone Automation Studio proposal as strict JSON.
Return JSON only. The result is compiled and forced disabled until human review.
Prefer known semantic PageContext/App Graph selectors over coordinates. Never include passwords, tokens, API keys or credentials.
Use this shape:
{
  "name":"...",
  "description":"...",
  "trigger":{"type":"manual|notification|schedule|app_opened|cyclone_remote|websocket|calendar_time", "params":{}},
  "steps":[
    {"type":"phone_tool","tool":"phone.open_app|phone.click|phone.type|phone.scroll|phone.back|phone.home|phone.open_notification|phone.wait_for|phone.assert", "params":{}, "selector":{"resourceId":"...","text":"...","textContains":"...","contentDescription":"...","role":"..."}, "recovery":{"maxRetries":1,"onFailure":"request_ai_help"}, "confirmation":"required"}
  ],
  "verification":{"type":"selector_exists","selector":{"textContains":"..."}},
  "recovery":{"onFailure":"request_ai_help"}
}
Consequential actions require confirmation. If context is insufficient, create a safe exploratory skeleton instead of brittle invented coordinates.
""".trimIndent()

        internal fun stripCodeFence(raw: String): String = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        private fun apiError(response: JSONObject): String {
            val error = response.optJSONObject("error") ?: return "OpenRouter request failed."
            val meta = response.optJSONObject("_cycloneMeta")
            val status = meta?.optInt("httpStatus", error.optInt("code", 500)) ?: error.optInt("code", 500)
            return ProviderFailure.classify(
                httpStatus = status,
                rawBody = error.toString(),
                providerName = meta?.optString("provider"),
                requestId = meta?.optString("requestId"),
            ).userMessage
        }
    }
}

internal object SafeModeGuard {
    fun allowed(tool: String, params: JSONObject): Boolean =
        CycloneAiAccessPolicy.evaluate(CycloneAiAccessProfile.BALANCED, tool, params).allowed
}
