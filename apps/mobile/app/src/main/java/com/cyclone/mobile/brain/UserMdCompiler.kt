package com.cyclone.mobile.brain

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.ai.TracePrivacy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * After a run ends, compile tiny personal facts into user.md.
 * The live task agent never calls this. Local extract is the default; a cheap model
 * only compresses People/Apps after several new facts, and never touches # Me.
 */
object UserMdCompiler {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = mutableSetOf<String>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .build()

    fun enqueue(context: Context, sessionId: String) {
        val app = context.applicationContext
        UserMdRuntime.initialize(app)
        if (!UserMdRuntime.enabled) return
        if (!inFlight.add(sessionId)) return
        executor.submit {
            runCatching { ingest(app, sessionId) }
            synchronized(this) { inFlight.remove(sessionId) }
            main.post { }
        }
    }

    internal fun ingest(context: Context, sessionId: String) {
        AgentTraceRuntime.initialize(context)
        val session = AgentTraceRuntime.store.listSessions(80).firstOrNull { it.id == sessionId } ?: return
        val events = AgentTraceRuntime.store.events(sessionId)
        val packages = events.mapNotNull { event ->
            Regex("com\\.[a-z0-9.]+").find(event.detail.orEmpty() + " " + event.displayText)?.value
        }.toSet()
        val extracted = UserMdDocument.extract(session.goal, packages)
        if (extracted.people.isEmpty() && extracted.apps.isEmpty()) return
        var next = UserMdRuntime.document
        extracted.people.forEach { next = next.upsertPerson(it.name, it.apps) }
        extracted.apps.forEach { next = next.upsertApp(it.first, it.second) }
        if (next == UserMdRuntime.document) return
        UserMdRuntime.replace(next, context)
        UserMdRuntime.bumpPending(context)
        AgentTraceRuntime.event(
            context, sessionId, "LEARNING",
            "Updated user.md from finished run",
            code = "user_md.local",
            ok = true,
            detail = extracted.people.joinToString { "${it.name}:${it.apps.joinToString("/")}" }.take(240),
        )
        if (UserMdRuntime.pendingCompress(context) >= 5) compress(context, sessionId)
    }

    private fun compress(context: Context, sessionId: String) {
        val key = OpenRouterSecretStore.read(context)
        if (key.isBlank()) return
        val current = UserMdRuntime.document
        if (current.people.isEmpty()) return
        val system = """
You compress Cyclone user.md People and Apps only. Return JSON, no markdown.
Schema: {"people":[{"name":"Jacob","apps":["Instagram","WhatsApp"]}],"apps":[{"job":"hotels","app":"Maps"}]}
Rules: keep user facts, merge duplicates, max 16 people, max 12 apps, never invent contacts, never include secrets.
""".trimIndent()
        val user = JSONObject()
            .put("people", JSONArray(current.people.map { JSONObject().put("name", it.name).put("apps", JSONArray(it.apps)) }))
            .put("apps", JSONArray(current.apps.map { JSONObject().put("job", it.first).put("app", it.second) }))
        val body = JSONObject()
            .put("model", "google/gemini-3.8-flash")
            .put("max_tokens", 400)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user.toString())),
            )
        val parsed = runCatching {
            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .header("HTTP-Referer", "https://github.com/premiumcentraal-boop/Cyclone")
                .header("X-Title", "Cyclone user.md")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val raw = JSONObject(response.body?.string().orEmpty())
                    .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                JSONObject(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())
            }
        }.getOrNull() ?: return
        var next = current.copy(people = emptyList(), apps = emptyList())
        val people = parsed.optJSONArray("people") ?: JSONArray()
        for (i in 0 until minOf(people.length(), 16)) {
            val row = people.optJSONObject(i) ?: continue
            val apps = row.optJSONArray("apps") ?: JSONArray()
            next = next.upsertPerson(
                TracePrivacy.clean(row.optString("name")),
                (0 until minOf(apps.length(), 4)).map { TracePrivacy.clean(apps.optString(it)) },
            )
        }
        val jobs = parsed.optJSONArray("apps") ?: JSONArray()
        for (i in 0 until minOf(jobs.length(), 12)) {
            val row = jobs.optJSONObject(i) ?: continue
            next = next.upsertApp(row.optString("job"), row.optString("app"))
        }
        next = next.copy(me = current.me, cues = current.cues)
        UserMdRuntime.replace(next, context)
        UserMdRuntime.clearPending(context)
        AgentTraceRuntime.event(context, sessionId, "LEARNING", "Compressed user.md", code = "user_md.compress", ok = true)
    }
}
