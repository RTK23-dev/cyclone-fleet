package com.cyclone.mobile.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.guided.RoutineTeachingRuntime
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class TeachingCorrectionAiNote(
    val aiNote: String,
    val updates: List<String>,
    val createdAt: Long,
)

/**
 * A correction is no longer just a dead text annotation. V2.9.2 inspects the saved semantic context
 * around the corrected step once, explains what it changed, and appends only compact semantic facts
 * to the existing Brain. Raw screenshots/typed values are not uploaded and model output cannot edit
 * executable selectors or confidence directly.
 */
object TeachingCorrectionAssistantV292 {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(55, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun latest(context: Context, sessionId: String, stepId: String): TeachingCorrectionAiNote? {
        val file = file(context, sessionId, stepId)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            TeachingCorrectionAiNote(
                aiNote = json.optString("aiNote"),
                updates = json.optJSONArray("updates")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty(),
                createdAt = json.optLong("createdAt"),
            )
        }.getOrNull()
    }

    fun applyAsync(
        context: Context,
        sessionId: String,
        stepId: String,
        correction: String,
        modelId: String,
        callback: (Result<TeachingCorrectionAiNote>) -> Unit,
    ) {
        val app = context.applicationContext
        executor.submit {
            val result = runCatching { apply(app, sessionId, stepId, correction, modelId) }
            main.post { callback(result) }
        }
    }

    private fun apply(context: Context, sessionId: String, stepId: String, correction: String, modelId: String): TeachingCorrectionAiNote {
        val cleanCorrection = TracePrivacy.clean(correction).trim().take(1200)
        require(cleanCorrection.isNotBlank()) { "Correction is empty" }
        val session = RoutineTeachingRuntime.load(sessionId) ?: error("Teaching session not found")
        val index = session.steps.indexOfFirst { it.id == stepId }
        require(index >= 0) { "Teaching step not found" }
        val step = session.steps[index]

        AdaptiveBrainRuntime.initialize(context)
        AppLearnerRuntime.initialize(context)
        val neighbors = session.steps.subList((index - 3).coerceAtLeast(0), (index + 4).coerceAtMost(session.steps.size))
        val graph = step.packageName?.let(AppLearnerRuntime::graph)
        val localContext = JSONObject()
            .put("session", session.name)
            .put("correction", cleanCorrection)
            .put("corrected_step", stepJson(step))
            .put("nearby_timeline", JSONArray(neighbors.map(::stepJson)))
            .put("app_graph", graph?.let { g -> JSONObject()
                .put("app", g.app.label)
                .put("screens", JSONArray(g.screens.take(30).map { s -> JSONObject().put("title", s.title).put("purpose", s.purpose).put("confidence", s.confidence) }))
                .put("actions", JSONArray(g.actions.take(60).map { a -> JSONObject().put("label", a.label).put("name", a.semanticName).put("androidActions", JSONArray(a.androidActions)).put("confidence", a.confidence) }))
                .put("transitions", JSONArray(g.transitions.take(80).map { t -> JSONObject()
                    .put("from", g.screens.firstOrNull { it.id == t.fromScreenId }?.title ?: "unknown")
                    .put("action", g.actions.firstOrNull { it.id == t.actionId }?.label ?: "unknown")
                    .put("to", g.screens.firstOrNull { it.id == t.toScreenId }?.title ?: "unknown")
                    .put("confidence", t.confidence) }))
            } ?: JSONObject.NULL)
            .put("brain_recall", AdaptiveBrainRuntime.store.recall(cleanCorrection, null))

        val key = OpenRouterSecretStore.read(context)
        val parsed = if (key.isBlank() || modelId.isBlank()) null else ask(key, OpenRouterModelPresets.byId(modelId), localContext)
        val aiNote = TracePrivacy.clean(parsed?.optString("ai_note").orEmpty()).trim().take(1200).ifBlank {
            "Correction saved. Cyclone linked it to '${step.title}' and the surrounding teaching context. No cloud analysis was available, so executable knowledge remains unchanged until verified by phone evidence."
        }
        val proposed = parsed?.optJSONArray("memory_updates") ?: JSONArray()
        val existing = AdaptiveBrainRuntime.store.listNotes(250).map { normalize(it.text) }.toMutableSet()
        val applied = mutableListOf<String>()
        for (i in 0 until minOf(proposed.length(), 4)) {
            val note = TracePrivacy.clean(proposed.optString(i)).trim().take(650)
            if (note.length < 8 || !existing.add(normalize(note))) continue
            AdaptiveBrainRuntime.addUserNote(context, note, "AI_TEACHING_CORRECTION")
            applied += note
        }
        // The user's own correction is always durable and has higher authority than the AI summary.
        val userFact = "Teaching correction for ${step.packageName ?: "phone"} / ${step.pageTitle ?: step.title}: $cleanCorrection"
        if (existing.add(normalize(userFact))) {
            AdaptiveBrainRuntime.addUserNote(context, userFact, "USER_TEACHING_CORRECTION")
            applied.add(0, userFact)
        }
        AdaptiveBrainRuntime.store.writeMirror()

        val result = TeachingCorrectionAiNote(aiNote, applied, System.currentTimeMillis())
        val out = file(context, sessionId, stepId)
        out.parentFile?.mkdirs()
        out.writeText(JSONObject()
            .put("aiNote", result.aiNote)
            .put("updates", JSONArray(result.updates))
            .put("createdAt", result.createdAt)
            .toString(2))
        return result
    }

    private fun ask(key: String, model: OpenRouterModelPreset, localContext: JSONObject): JSONObject? {
        val system = """
You are Cyclone's teaching-correction assistant. A user corrected one step in a saved phone teaching round.
Inspect ONLY the supplied semantic teaching context and existing App Graph/Brain facts.
Return strict JSON: {"ai_note":"what you understood and what changed","memory_updates":["small semantic fact"]}
Rules:
- maximum 4 memory updates
- do not invent selectors, screen states or successful actions
- do not expose hidden chain-of-thought; ai_note is a concise evidence-based explanation
- never include passwords, OTPs, tokens, typed field values, payment/auth details
- executable confidence is changed only by real phone evidence, not by this note
""".trimIndent()
        val body = JSONObject()
            .put("model", model.id)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", localContext.toString())))
            .put("temperature", 0.03)
            .put("max_tokens", 1000)
            .put("reasoning", JSONObject().put("effort", model.reasoningEffort).put("exclude", true))
            .put("provider", JSONObject().put("sort", "latency").put("allow_fallbacks", true))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("stream", false)
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Mobile V2.9.2 Teaching Correction")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val raw = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                ?.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            runCatching { JSONObject(clean) }.getOrNull()
        }
    }

    private fun stepJson(step: com.cyclone.mobile.guided.RoutineTeachingStep): JSONObject = JSONObject()
        .put("n", step.index)
        .put("kind", step.kind)
        .put("title", TracePrivacy.clean(step.title).take(180))
        .put("app", step.packageName ?: JSONObject.NULL)
        .put("page", step.pageTitle ?: JSONObject.NULL)
        .put("replay", step.replayStrategy)
        .put("nativeSignal", step.semanticSignal ?: JSONObject.NULL)
        .put("advertisedActions", JSONArray(step.advertisedActions.take(12)))
        .put("existingNote", TracePrivacy.clean(step.note).take(500))

    private fun file(context: Context, sessionId: String, stepId: String): File =
        File(context.filesDir, "cyclone-v292-teaching-corrections/$sessionId/$stepId.json")

    private fun normalize(value: String): String = value.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim()
}
