package com.cyclone.mobile.ai

import android.content.Context
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.guided.RoutineTeachingSession
import com.cyclone.mobile.guided.TeachingGestureEvidenceV292
import com.cyclone.mobile.guided.TeachingRoutineCompilerV292
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * V2.9.2 post-teaching consolidation.
 *
 * There is exactly one optional model call after a Follow Me session. The live teaching loop never
 * rapid-fires a model. Before the call Cyclone has already persisted deterministic App Graph/Brain
 * evidence. The model receives a compact semantic timeline + learned graph facts, then may add small
 * non-executable memory notes. A local compiler separately turns demonstrated actions/gestures into
 * one disabled-for-review automation. No model-written selector can silently become executable.
 */
object RoutineTeachingAnalyzer {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun analyze(context: Context, session: RoutineTeachingSession): String? {
        AppLearnerRuntime.initialize(context)
        AdaptiveBrainRuntime.initialize(context)

        // Deterministic first: materialize what the human actually demonstrated before asking a
        // model to summarize it. This is what makes Follow Me knowledge visibly come to life.
        val compiled = runCatching { TeachingRoutineCompilerV292.compileAndSave(context, session) }.getOrNull()
        val gestures = TeachingGestureEvidenceV292.list(context, session.id)
        val apiKey = OpenRouterSecretStore.read(context)
        if (apiKey.isBlank() || session.modelId.isBlank()) {
            return buildLocalSummary(session, gestures.size, compiled?.name)
        }

        val model = OpenRouterModelPresets.byId(session.modelId)
        val compact = JSONArray().also { array ->
            session.steps.take(140).forEach { step ->
                array.put(JSONObject()
                    .put("n", step.index)
                    .put("kind", step.kind)
                    .put("title", TracePrivacy.clean(step.title).take(180))
                    .put("app", step.packageName ?: JSONObject.NULL)
                    .put("page", step.pageTitle ?: JSONObject.NULL)
                    .put("native_signal", step.semanticSignal ?: JSONObject.NULL)
                    .put("replay", step.replayStrategy)
                    .put("actions", JSONArray(step.advertisedActions.take(12)))
                    .put("note", TracePrivacy.clean(step.note).take(500)))
            }
        }
        val gestureJson = JSONArray().also { arr ->
            gestures.take(80).forEach { g ->
                arr.put(JSONObject()
                    .put("direction", g.direction)
                    .put("app", g.packageName)
                    .put("from", g.fromTitle)
                    .put("to", g.toTitle)
                    .put("replay", JSONObject(g.params.toString())))
            }
        }
        val packages = session.steps.mapNotNull { it.packageName }.distinct().take(8)
        val graphFacts = JSONArray().also { arr ->
            packages.forEach { pkg ->
                val graph = AppLearnerRuntime.graph(pkg) ?: return@forEach
                arr.put(JSONObject()
                    .put("app", graph.app.label)
                    .put("package", pkg)
                    .put("screens", JSONArray(graph.screens.take(40).map { s -> JSONObject().put("title", s.title).put("pageKey", s.recognition.semanticFingerprint).put("confidence", s.confidence) }))
                    .put("actions", JSONArray(graph.actions.take(80).map { a -> JSONObject()
                        .put("label", a.label)
                        .put("name", a.semanticName)
                        .put("androidActions", JSONArray(a.androidActions))
                        .put("confidence", a.confidence) }))
                    .put("transitions", JSONArray(graph.transitions.take(100).map { t -> JSONObject()
                        .put("from", graph.screens.firstOrNull { it.id == t.fromScreenId }?.title ?: t.fromScreenId)
                        .put("action", graph.actions.firstOrNull { it.id == t.actionId }?.label ?: t.actionId)
                        .put("to", graph.screens.firstOrNull { it.id == t.toScreenId }?.title ?: t.toScreenId)
                        .put("observed", t.observedCount)
                        .put("confidence", t.confidence) }))
                )
            }
        }

        val prompt = """
You are Cyclone's V2.9.2 teaching consolidator. The user just demonstrated a phone routine.
The local system has ALREADY stored App Graph transitions, gesture evidence and evidence-backed Brain skills.
Your job is to consolidate that evidence into a few useful semantic memory updates, not to invent a new world.

Rules:
- One pass only. Do not ask questions or request another model call.
- Use only evidence in TIMELINE, GESTURES and APP_GRAPHS.
- Treat repeated user swipes/clicks as strong demonstrations when the before/after pages support them.
- Identify what Cyclone should try first next time, including directional swipes where demonstrated.
- Never invent selectors, buttons, successful actions, credentials or payment/authentication steps.
- Do not output hidden chain-of-thought. `ai_note` is a concise evidence-based explanation of what changed.
- Memory updates are non-executable semantic facts. Executable confidence remains controlled by real phone evidence.
- Maximum 4 memory_updates and 3 speedups.
Return strict JSON only:
{
  "ai_note":"what Cyclone understood from this teaching round and why it is now more useful",
  "memory_updates":["small reusable fact"],
  "reusable_skills":["evidence-backed skill"],
  "speedups":["safe optimization"],
  "uncertain":["only genuinely uncertain area"],
  "next_training":"optional single useful suggestion"
}
""".trimIndent()
        val userPayload = JSONObject()
            .put("session", session.name)
            .put("apps", session.appsSeen)
            .put("pages", session.pagesSeen)
            .put("actions", session.actionsSeen)
            .put("paths", session.pathsLearned)
            .put("timeline", compact)
            .put("gestures", gestureJson)
            .put("app_graphs", graphFacts)
            .put("locally_compiled_automation", compiled?.name ?: JSONObject.NULL)

        val body = JSONObject()
            .put("model", model.id)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content", userPayload.toString())))
            .put("temperature", 0.03)
            .put("max_tokens", 1900)
            .put("reasoning", JSONObject().put("effort", model.reasoningEffort).put("exclude", true))
            .put("provider", JSONObject().put("sort", "latency").put("allow_fallbacks", true))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("stream", false)
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Mobile V2.9.2 Teaching Consolidator")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val parsed = runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                val raw = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                JSONObject(clean)
            }
        }.getOrNull() ?: return buildLocalSummary(session, gestures.size, compiled?.name)

        val existing = AdaptiveBrainRuntime.store.listNotes(250).map { normalize(it.text) }.toMutableSet()
        val applied = mutableListOf<String>()
        fun applyArray(name: String, source: String, limit: Int) {
            val array = parsed.optJSONArray(name) ?: return
            for (i in 0 until minOf(array.length(), limit)) {
                val note = TracePrivacy.clean(array.optString(i)).trim().take(650)
                if (note.length < 8 || !existing.add(normalize(note))) continue
                AdaptiveBrainRuntime.addUserNote(context, note, source)
                applied += note
            }
        }
        applyArray("memory_updates", "TEACHING_CONSOLIDATION", 4)
        applyArray("reusable_skills", "TEACHING_CONSOLIDATION", 3)
        applyArray("speedups", "TEACHING_OPTIMIZATION", 3)
        AdaptiveBrainRuntime.store.writeMirror()

        return buildString {
            appendLine(parsed.optString("ai_note").ifBlank { "Cyclone consolidated the demonstrated pages, actions and gesture transitions into its existing Brain." })
            appendLine()
            appendLine("Applied to Cyclone Brain: ${applied.size} new evidence-based note${if (applied.size == 1) "" else "s"}.")
            compiled?.let { appendLine("Created/updated reviewable automation: ${it.name} (${it.steps.size} steps).") }
            if (gestures.isNotEmpty()) appendLine("Directional gestures retained: ${gestures.joinToString { "swipe ${it.direction}: ${it.fromTitle.ifBlank { "page" }} → ${it.toTitle.ifBlank { "page" }}" }.take(900)}")
            val uncertain = parsed.optJSONArray("uncertain")
            if (uncertain != null && uncertain.length() > 0) {
                appendLine("\nStill uncertain:")
                for (i in 0 until minOf(uncertain.length(), 3)) appendLine("• ${TracePrivacy.clean(uncertain.optString(i)).take(400)}")
            }
            parsed.optString("next_training").takeIf(String::isNotBlank)?.let { appendLine("\nIf you teach one more thing: ${TracePrivacy.clean(it).take(500)}") }
        }.trim()
    }

    private fun buildLocalSummary(session: RoutineTeachingSession, gestures: Int, automationName: String?): String = buildString {
        append("Cyclone consolidated ${session.pagesSeen} pages, ${session.actionsSeen} observed actions and ${session.pathsLearned} navigation paths into its existing local knowledge.")
        if (gestures > 0) append(" $gestures directional swipe transition${if (gestures == 1) " was" else "s were"} retained as reusable gesture evidence.")
        automationName?.let { append(" Reviewable automation '$it' was created/updated locally without another model call.") }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim()
}
