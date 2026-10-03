package com.cyclone.mobile.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.brain.BrainContextFormatter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One compact learning pass after an entire AI phone mission is finished.
 *
 * Foreground navigation remains page/event driven and never asks a model for every Android action.
 * This worker runs at most once for a trace session, after the result is already durable. It can add
 * semantic recovery/optimization notes to the existing Adaptive Brain, but it cannot change selectors
 * or executable confidence. Real phone success/failure evidence remains authoritative.
 */
object MissionLearningConsolidatorV292 {
    data class Result(val updates: Int, val summary: String, val usedModel: Boolean)

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = mutableSetOf<String>()
    private val completed = mutableSetOf<String>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(55, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Synchronized
    fun enqueue(context: Context, sessionId: String, callback: (Result) -> Unit = {}) {
        if (sessionId in completed || !inFlight.add(sessionId)) return
        val app = context.applicationContext
        executor.submit {
            val result = runCatching { consolidate(app, sessionId) }.getOrElse {
                Result(0, "Result compiled locally. No additional model lesson was stored.", false)
            }
            synchronized(this) {
                inFlight.remove(sessionId)
                completed.add(sessionId)
            }
            main.post { callback(result) }
        }
    }

    private fun consolidate(context: Context, sessionId: String): Result {
        AgentTraceRuntime.initialize(context)
        AdaptiveBrainRuntime.initialize(context)
        val session = AgentTraceRuntime.store.listSessions(200).firstOrNull { it.id == sessionId }
            ?: return Result(0, "Task result saved.", false)
        val events = AgentTraceRuntime.store.events(sessionId)
        val outcomeEvents = events.filter { it.kind in setOf("PAGE", "BRAIN", "REPLAY", "DECISION", "RESULT", "RECOVERY", "BOUNDARY", "VISION", "DONE", "STOPPED", "ACTION_REQUESTED", "ANDROID_EXECUTION", "AFTER_OBSERVATION", "VERIFICATION", "MODEL_CONTEXT") }
        val failed = outcomeEvents.filter { it.kind in setOf("ANDROID_EXECUTION", "VERIFICATION") && it.ok == false }
        val passed = outcomeEvents.filter { it.kind == "VERIFICATION" && it.ok == true }

        val key = OpenRouterSecretStore.read(context)
        if (!context.getSharedPreferences("cyclone_ai", Context.MODE_PRIVATE).getBoolean("cloud_brain_refinement", false) ||
            key.isBlank() || session.model.isBlank() || session.decisions <= 0) {
            val summary = buildString {
                append("Saved ${passed.size} verified signal${if (passed.size == 1) "" else "s"}")
                if (failed.isNotEmpty()) append(" and ${failed.size} failure/recovery signal${if (failed.size == 1) "" else "s"}")
                append(" to Cyclone's existing local evidence.")
            }
            AgentTraceRuntime.event(context, sessionId, "LEARNING", summary, code = "v292.compile.local", ok = true)
            return Result(0, summary, false)
        }

        val compact = JSONArray().also { array ->
            outcomeEvents.takeLast(55).forEach { event ->
                array.put(JSONObject()
                    .put("kind", event.kind)
                    .put("summary", TracePrivacy.clean(event.displayText).take(260))
                    .put("code", event.code ?: JSONObject.NULL)
                    .put("ok", event.ok ?: JSONObject.NULL)
                    .put("detail", event.detail?.let(TracePrivacy::clean)?.take(500) ?: JSONObject.NULL))
            }
        }
        val brain = BrainContextFormatter.forQuestion(context, session.goal).take(9_000)
        val system = """
You are Cyclone's V2.9.2 mission consolidator. The phone mission is already over.
Use only the evidence-backed task timeline and existing Cyclone Brain supplied below.
Return strict JSON only:
{"summary":"concise result understanding","memory_updates":["small reusable lesson"],"recovery_hint":"optional next-time recovery"}
Rules:
- maximum 3 memory_updates
- derive lessons only from verified successes/failures in the supplied evidence
- if a failed route was followed by a successful different route, prefer the successful recovery lesson
- do not invent selectors, pages, credentials, authentication steps or successful actions
- never output hidden chain-of-thought; summary is a user-facing evidence explanation
- do not change executable confidence; real phone evidence owns confidence
- prefer knowledge that helps the next run avoid rediscovery or repeated mistakes
""".trimIndent()
        val user = JSONObject()
            .put("goal", TracePrivacy.clean(session.goal))
            .put("status", session.status)
            .put("result", TracePrivacy.clean(session.result.orEmpty()).take(1200))
            .put("events", compact)
            .put("brain", brain)

        val model = OpenRouterModelPresets.byId(session.model)
        val body = com.cyclone.mobile.ai.model.PortableModelRequest.body(model.id,
            JSONArray().put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user.toString())),
            emptyList())
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Mobile V2.9.2 Mission Consolidator")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        AgentTraceRuntime.event(context, sessionId, "LEARNING", "Optional cloud refinement: one additional provider request",
            code = "v398.compile.request", ok = null, detail = "Model: ${model.id}; task outcome is already final.")
        val parsed = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val envelope = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull() ?: return@use null
            AgentTraceRuntime.event(context, sessionId, "LEARNING", "Cloud refinement usage",
                code = "v398.compile.usage", ok = true, detail = envelope.optJSONObject("usage")?.let {
                    "prompt_tokens=${it.optLong("prompt_tokens")}; completion_tokens=${it.optLong("completion_tokens")}; cost=${it.optDouble("cost", Double.NaN)}"
                } ?: "Provider did not return usage; cost is unknown.")
            val raw = envelope.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            runCatching { JSONObject(stripFence(raw)) }.getOrNull()
        } ?: run {
            val summary = "Task result saved; post-task model consolidation was unavailable, so no speculative lesson was added."
            AgentTraceRuntime.event(context, sessionId, "LEARNING", summary, code = "v292.compile.model_unavailable", ok = true)
            return Result(0, summary, true)
        }

        val existing = AdaptiveBrainRuntime.store.listNotes(260).map { normalize(it.text) }.toMutableSet()
        val applied = mutableListOf<String>()
        val updates = parsed.optJSONArray("memory_updates") ?: JSONArray()
        for (i in 0 until minOf(updates.length(), 3)) {
            val lesson = TracePrivacy.clean(updates.optString(i)).trim().take(650)
            if (lesson.length < 8 || !existing.add(normalize(lesson))) continue
            AdaptiveBrainRuntime.addUserNote(context, lesson, "MISSION_CONSOLIDATION")
            applied += lesson
        }
        val recovery = TracePrivacy.clean(parsed.optString("recovery_hint")).trim().take(650)
        if (recovery.length >= 8) {
            val note = "Recovery hint for '${TracePrivacy.clean(session.goal).take(180)}': $recovery"
            if (existing.add(normalize(note))) {
                AdaptiveBrainRuntime.addUserNote(context, note, "MISSION_RECOVERY")
                applied += note
            }
        }
        AdaptiveBrainRuntime.store.writeMirror()

        val summary = TracePrivacy.clean(parsed.optString("summary")).trim().take(900).ifBlank {
            "Cyclone consolidated the verified task evidence into ${applied.size} reusable Brain update${if (applied.size == 1) "" else "s"}."
        }
        AgentTraceRuntime.event(
            context, sessionId, "LEARNING",
            "$summary · ${applied.size} Brain update${if (applied.size == 1) "" else "s"} applied",
            code = "v292.compile.complete", ok = true,
            detail = applied.joinToString(" | ").take(1200),
        )
        return Result(applied.size, summary, true)
    }

    private fun stripFence(value: String): String = value.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    private fun normalize(value: String): String = value.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim()
}
