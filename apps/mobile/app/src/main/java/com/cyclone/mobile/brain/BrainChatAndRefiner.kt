package com.cyclone.mobile.brain

import android.content.Context
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.TracePrivacy
import com.cyclone.mobile.applearner.AppLearnerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class BrainChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

object BrainChatRuntime {
    @Volatile private var initialized = false
    private lateinit var appContext: Context
    private lateinit var historyFile: File
    private val historyLock = Any()

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        AdaptiveBrainRuntime.initialize(appContext)
        AppLearnerRuntime.initialize(appContext)
        historyFile = File(appContext.filesDir, "Cyclone Brain/Memory/Brain Chat.jsonl").apply { parentFile?.mkdirs() }
        initialized = true
    }

    fun history(context: Context, limit: Int = 40): List<BrainChatMessage> {
        initialize(context)
        synchronized(historyLock) {
            if (!historyFile.exists()) return emptyList()
            return historyFile.readLines().takeLast(limit.coerceIn(1, 120)).mapNotNull { line ->
                runCatching {
                    val json = JSONObject(line)
                    BrainChatMessage(json.getString("id"), json.getString("role"), json.getString("text"), json.getLong("timestampMs"))
                }.getOrNull()
            }
        }
    }

    fun clearHistory(context: Context) {
        initialize(context)
        synchronized(historyLock) { historyFile.writeText("") }
        writeHumanMirror()
    }

    fun saveKnowledge(context: Context, text: String): BrainUserNote {
        initialize(context)
        val note = AdaptiveBrainRuntime.addUserNote(appContext, text, "USER_CHAT")
        append(BrainChatMessage(role = "assistant", text = "Saved to Cyclone Brain: ${note.text}"))
        writeHumanMirror()
        return note
    }

    suspend fun chat(context: Context, message: String, modelSlug: String): String = withContext(Dispatchers.IO) {
        initialize(context)
        val clean = TracePrivacy.clean(message).trim().take(1800)
        if (clean.isBlank()) return@withContext "Ask or teach the Brain something first."
        append(BrainChatMessage(role = "user", text = clean))

        val lower = clean.lowercase(Locale.US)
        val rememberPrefixes = listOf("remember that ", "remember ", "learn that ", "note that ", "add knowledge: ", "save this: ")
        val prefix = rememberPrefixes.firstOrNull(lower::startsWith)
        if (prefix != null) {
            val knowledge = clean.drop(prefix.length).trim().ifBlank { clean }
            AdaptiveBrainRuntime.addUserNote(appContext, knowledge, "USER_CHAT")
            val answer = "Saved. I added that to your local Cyclone Brain and it will be available to future AI runs."
            append(BrainChatMessage(role = "assistant", text = answer))
            writeHumanMirror()
            return@withContext answer
        }

        val localContext = BrainContextFormatter.forQuestion(appContext, clean)
        val key = OpenRouterSecretStore.read(appContext)
        val answer = if (key.isBlank()) {
            BrainContextFormatter.localAnswer(appContext, clean)
        } else {
            BrainOpenRouterClient.ask(
                key = key,
                model = modelSlug,
                system = """
You are Cyclone Brain Chat. Answer ONLY from the local Cyclone Brain context supplied below plus ordinary definitions needed to explain it.
Do not claim that an Android action was verified unless the context contains success evidence.
Do not expose passwords, tokens, typed field values or hidden chain-of-thought.
If the user is asking to modify executable behavior, explain what knowledge exists and recommend teaching/testing rather than inventing selectors.
Keep the answer concise and practical.
""".trimIndent(),
                user = "USER:\n$clean\n\nLOCAL CYCLONE BRAIN:\n$localContext",
            ).ifBlank { BrainContextFormatter.localAnswer(appContext, clean) }
        }
        append(BrainChatMessage(role = "assistant", text = TracePrivacy.clean(answer).take(2400)))
        writeHumanMirror()
        answer
    }

    private fun append(message: BrainChatMessage) {
        synchronized(historyLock) {
            historyFile.appendText(JSONObject()
                .put("id", message.id)
                .put("role", message.role)
                .put("text", message.text)
                .put("timestampMs", message.timestampMs)
                .toString() + "\n")
        }
    }

    private fun writeHumanMirror() {
        val file = File(appContext.filesDir, "Cyclone Brain/Memory/Brain Chat.md").apply { parentFile?.mkdirs() }
        val format = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        file.writeText(buildString {
            appendLine("# Cyclone Brain Chat")
            appendLine()
            appendLine("A human-readable copy of recent conversations with the local Brain. Executable skill confidence is still evidence-based and is not changed merely because the AI says something is true.")
            appendLine()
            history(appContext, 80).forEach { message ->
                appendLine("## ${if (message.role == "user") "You" else "Cyclone Brain"} · ${format.format(Date(message.timestampMs))}")
                appendLine(message.text)
                appendLine()
            }
        })
    }
}

object BrainContextFormatter {
    fun forQuestion(context: Context, question: String): String {
        AdaptiveBrainRuntime.initialize(context)
        AppLearnerRuntime.initialize(context)
        val adaptive = AdaptiveBrainRuntime.store
        val recall = adaptive.recall(question, null)
        val apps = AppLearnerRuntime.learnedApps().take(20)
        val oldReports = runCatching { CycloneBrainRuntime.store.listReports(8) }.getOrDefault(emptyList())
        return buildString {
            appendLine(adaptive.compactText(question))
            appendLine("APP LEARNER KNOWLEDGE:")
            apps.forEach { app ->
                val graph = AppLearnerRuntime.graph(app.packageName)
                appendLine("- ${app.label} (${app.packageName}) ${(app.confidence * 100).toInt()}%: ${graph?.screens?.size ?: 0} screens, ${graph?.transitions?.size ?: 0} paths")
            }
            appendLine("RECENT TASK REPORTS:")
            oldReports.forEach { report -> appendLine("- ${report.status}: ${report.goal} — ${report.summary.take(240)}") }
            appendLine("RETRIEVED JSON: ${recall.toString()}")
        }.take(14000)
    }

    fun localAnswer(context: Context, question: String): String {
        AdaptiveBrainRuntime.initialize(context)
        val recall = AdaptiveBrainRuntime.store.recall(question, null)
        val skills = recall.optJSONArray("microSkills") ?: JSONArray()
        val apps = recall.optJSONArray("installedAppMatches") ?: JSONArray()
        val notes = recall.optJSONArray("userNotes") ?: JSONArray()
        if (skills.length() == 0 && apps.length() == 0 && notes.length() == 0) {
            return "I don't have enough matching Brain knowledge yet. Use Follow Me, App Learner, or save a note so Cyclone can learn this area."
        }
        return buildString {
            if (apps.length() > 0) {
                append("Installed app matches: ")
                append((0 until apps.length()).mapNotNull { apps.optJSONObject(it)?.optString("label") }.joinToString(", "))
                appendLine(".")
            }
            if (skills.length() > 0) {
                appendLine("Relevant learned skills:")
                for (i in 0 until minOf(skills.length(), 5)) {
                    val s = skills.optJSONObject(i) ?: continue
                    appendLine("• ${s.optString("name")} — ${(s.optDouble("confidence") * 100).toInt()}% confidence (${s.optInt("successes")} success / ${s.optInt("failures")} failure)")
                }
            }
            if (notes.length() > 0) {
                appendLine("Relevant notes:")
                for (i in 0 until minOf(notes.length(), 4)) appendLine("• ${notes.optJSONObject(i)?.optString("text")}")
            }
        }.trim()
    }
}

/**
 * Runs after the foreground task is already complete. Deterministic evidence has already been
 * stored; this optional model pass only creates non-executable lessons/aliases for future recall.
 */
object BrainRefinementWorker {
    private val executor = Executors.newSingleThreadExecutor()

    fun enqueue(context: Context, goal: String, modelSlug: String, status: String, result: String) {
        val app = context.applicationContext
        executor.submit {
            runCatching {
                AdaptiveBrainRuntime.initialize(app)
                AdaptiveBrainRuntime.store.refreshAppInventory()
                AdaptiveBrainRuntime.store.writeMirror()
                val key = OpenRouterSecretStore.read(app)
                if (key.isBlank()) return@runCatching
                val compact = BrainContextFormatter.forQuestion(app, goal).take(10000)
                val raw = BrainOpenRouterClient.ask(
                    key,
                    modelSlug,
                    """
You are Cyclone's background memory refiner. The task is already over. Inspect the evidence-backed local Brain context and return strict JSON only:
{"lessons":["..."],"optimization":"..."}
Rules:
- maximum 3 lessons
- derive lessons only from evidence in the context and task result
- never invent selectors, passwords, credentials or successful actions
- do not change executable confidence; that is controlled only by real success/failure counts
- focus on small reusable facts, aliases, recovery hints and what should be tried first next time
""".trimIndent(),
                    "TASK: $goal\nSTATUS: $status\nRESULT: ${TracePrivacy.clean(result).take(1000)}\n\nBRAIN:\n$compact",
                    json = true,
                )
                val parsed = runCatching { JSONObject(stripFence(raw)) }.getOrNull() ?: return@runCatching
                val lessons = parsed.optJSONArray("lessons") ?: JSONArray()
                val existing = AdaptiveBrainRuntime.store.listNotes(120).map { normalize(it.text) }.toSet()
                for (i in 0 until minOf(lessons.length(), 3)) {
                    val lesson = TracePrivacy.clean(lessons.optString(i)).trim().take(600)
                    if (lesson.length < 8 || normalize(lesson) in existing) continue
                    AdaptiveBrainRuntime.addUserNote(app, lesson, "AI_REFINER")
                }
                val optimization = TracePrivacy.clean(parsed.optString("optimization")).trim().take(700)
                if (optimization.length >= 8 && normalize(optimization) !in existing) {
                    AdaptiveBrainRuntime.addUserNote(app, "Optimization: $optimization", "AI_REFINER")
                }
                AdaptiveBrainRuntime.store.writeMirror()
            }
        }
    }

    private fun normalize(value: String) = value.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim()
    private fun stripFence(value: String) = value.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}

private object BrainOpenRouterClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun ask(key: String, model: String, system: String, user: String, json: Boolean = false): String {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.1)
            .put("max_tokens", if (json) 700 else 900)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("provider", JSONObject().put("sort", "latency").put("allow_fallbacks", true))
            .put("stream", false)
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
            .header("X-Title", "Cyclone Mobile V2.7 Brain")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { response ->
            val parsed = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull() ?: return@use ""
            parsed.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        }
    }
}
