package com.cyclone.mobile.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cyclone.mobile.CycloneAccessibilityService
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

data class AiTraceSession(
    val id: String,
    val goal: String,
    val model: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val result: String?,
    val decisions: Int,
)

data class AiTraceEvent(
    val id: String,
    val sessionId: String,
    val timestampMs: Long,
    val kind: String,
    val displayText: String,
    val code: String?,
    val ok: Boolean?,
    val detail: String?,
)

/**
 * User-visible decision history. This is intentionally NOT a hidden-chain-of-thought recorder.
 * It stores compact status/decision summaries, phone-tool names, verification outcomes and failures.
 * Typed values, screenshots, credentials and provider-private reasoning are not persisted here.
 */
object AgentTraceRuntime {
    @Volatile private var initialized = false
    lateinit var store: AgentTraceStore
        private set

    /** True once the trace database is open; readers (Glass `runs.*`) check this instead of touching [store]. */
    fun isReady(): Boolean = initialized

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        store = AgentTraceStore(context.applicationContext)
        initialized = true
    }

    fun start(context: Context, goal: String, model: String): String {
        initialize(context)
        val id = store.startSession(goal, model)
        CycloneAccessibilityService.instance?.let { AiTraceOverlayV27Runtime.startTask(it, id) }
        return id
    }

    fun event(
        context: Context,
        sessionId: String,
        kind: String,
        displayText: String,
        code: String? = null,
        ok: Boolean? = null,
        detail: String? = null,
    ) {
        initialize(context)
        store.append(sessionId, kind, displayText, code, ok, detail)
        when (kind) {
            "GATE_SUSPEND" -> store.setLiveStatus(sessionId, "SUSPENDED", displayText)
            "GATE_RESUME" -> store.setLiveStatus(sessionId, "RUNNING", null)
        }
    }

    fun finish(context: Context, sessionId: String, status: String, result: String, decisions: Int) {
        initialize(context)
        val ok = status == "COMPLETED"
        if (!store.finishSession(sessionId, status, result, decisions)) return
        runCatching { AgentRunDiagnosticV39.ensureCanonical(context.applicationContext, sessionId) }
        AiTraceOverlayV27Runtime.finishTask(sessionId, ok, result)

        // Consolidation may append useful learning events. Refresh the same canonical file afterward;
        // the stable session-id filename guarantees one diagnostic artifact per run.
        MissionLearningConsolidatorV292.enqueue(context, sessionId) { compiled ->
            AiTraceOverlayV27Runtime.compilationComplete(sessionId, compiled.summary)
            runCatching { AgentRunDiagnosticV39.ensureCanonical(context.applicationContext, sessionId) }
            TaskResultNotifierV292.notify(context, sessionId, ok, result, compiled.summary)
        }
        runCatching { com.cyclone.mobile.brain.UserMdCompiler.enqueue(context.applicationContext, sessionId) }
    }
}

object AiTraceBus {
    private val listeners = CopyOnWriteArrayList<(AiTraceEvent) -> Unit>()
    @Volatile var latest: AiTraceEvent? = null
        private set

    fun publish(event: AiTraceEvent) {
        latest = event
        listeners.forEach { listener -> runCatching { listener(event) } }
    }

    fun subscribe(listener: (AiTraceEvent) -> Unit): () -> Unit {
        listeners += listener
        latest?.let(listener)
        return { listeners -= listener }
    }
}

class AgentTraceStore(context: Context) : SQLiteOpenHelper(context, "cyclone_ai_history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions(
              id TEXT PRIMARY KEY,
              goal TEXT NOT NULL,
              model TEXT NOT NULL,
              status TEXT NOT NULL,
              started_at INTEGER NOT NULL,
              ended_at INTEGER,
              result TEXT,
              decisions INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE events(
              id TEXT PRIMARY KEY,
              session_id TEXT NOT NULL,
              timestamp_ms INTEGER NOT NULL,
              kind TEXT NOT NULL,
              display_text TEXT NOT NULL,
              code TEXT,
              ok INTEGER,
              detail TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX events_session_time ON events(session_id, timestamp_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun startSession(goal: String, model: String): String {
        val id = "ai-${UUID.randomUUID()}"
        writableDatabase.insertOrThrow(
            "sessions",
            null,
            ContentValues().apply {
                put("id", id)
                put("goal", TracePrivacy.clean(goal).take(500))
                put("model", model.take(180))
                put("status", "RUNNING")
                put("started_at", System.currentTimeMillis())
                put("decisions", 0)
            },
        )
        append(id, "START", "Starting task", code = "task.start", detail = "Model: ${model.take(120)}")
        return id
    }

    fun append(
        sessionId: String,
        kind: String,
        displayText: String,
        code: String? = null,
        ok: Boolean? = null,
        detail: String? = null,
    ): AiTraceEvent {
        val event = AiTraceEvent(
            id = "evt-${UUID.randomUUID()}",
            sessionId = sessionId,
            timestampMs = System.currentTimeMillis(),
            kind = kind.take(40),
            displayText = TracePrivacy.clean(displayText).take(360),
            code = code?.take(120),
            ok = ok,
            detail = detail?.let(TracePrivacy::clean)?.take(1200),
        )
        writableDatabase.insertOrThrow(
            "events",
            null,
            ContentValues().apply {
                put("id", event.id)
                put("session_id", event.sessionId)
                put("timestamp_ms", event.timestampMs)
                put("kind", event.kind)
                put("display_text", event.displayText)
                put("code", event.code)
                if (event.ok == null) putNull("ok") else put("ok", if (event.ok) 1 else 0)
                put("detail", event.detail)
            },
        )
        AiTraceBus.publish(event)
        return event
    }

    /** Update a non-terminal task state without closing the run. */
    fun setLiveStatus(id: String, status: String, result: String?) {
        writableDatabase.update(
            "sessions",
            ContentValues().apply {
                put("status", status.take(40))
                putNull("ended_at")
                if (result == null) putNull("result") else put("result", TracePrivacy.clean(result).take(1500))
            },
            "id=? AND ended_at IS NULL",
            arrayOf(id),
        )
    }

    fun finishSession(id: String, status: String, result: String, decisions: Int): Boolean {
        val changed = writableDatabase.update(
            "sessions",
            ContentValues().apply {
                put("status", status.take(40))
                put("ended_at", System.currentTimeMillis())
                put("result", TracePrivacy.clean(result).take(1500))
                put("decisions", decisions.coerceAtLeast(0))
            },
            "id=? AND ended_at IS NULL",
            arrayOf(id),
        )
        if (changed == 0) return false
        append(
            id,
            if (status == "COMPLETED") "DONE" else "STOPPED",
            if (status == "COMPLETED") "Task finished" else "Task stopped",
            code = "task.finish",
            ok = status == "COMPLETED",
            detail = result,
        )
        return true
    }

    fun session(id: String): AiTraceSession? {
        readableDatabase.query(
            "sessions",
            arrayOf("id", "goal", "model", "status", "started_at", "ended_at", "result", "decisions"),
            "id=?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { c ->
            if (!c.moveToFirst()) return null
            return AiTraceSession(
                id = c.getString(0), goal = c.getString(1), model = c.getString(2), status = c.getString(3),
                startedAt = c.getLong(4), endedAt = if (c.isNull(5)) null else c.getLong(5),
                result = if (c.isNull(6)) null else c.getString(6), decisions = c.getInt(7),
            )
        }
    }

    fun listSessions(limit: Int = 40): List<AiTraceSession> {
        val out = mutableListOf<AiTraceSession>()
        readableDatabase.query(
            "sessions",
            arrayOf("id", "goal", "model", "status", "started_at", "ended_at", "result", "decisions"),
            null,
            null,
            null,
            null,
            "started_at DESC",
            limit.coerceIn(1, 200).toString(),
        ).use { c ->
            while (c.moveToNext()) {
                out += AiTraceSession(
                    id = c.getString(0), goal = c.getString(1), model = c.getString(2), status = c.getString(3),
                    startedAt = c.getLong(4), endedAt = if (c.isNull(5)) null else c.getLong(5),
                    result = if (c.isNull(6)) null else c.getString(6), decisions = c.getInt(7),
                )
            }
        }
        return out
    }

    fun events(sessionId: String): List<AiTraceEvent> {
        val out = mutableListOf<AiTraceEvent>()
        readableDatabase.query(
            "events",
            arrayOf("id", "session_id", "timestamp_ms", "kind", "display_text", "code", "ok", "detail"),
            "session_id=?",
            arrayOf(sessionId),
            null,
            null,
            "timestamp_ms ASC",
        ).use { c ->
            while (c.moveToNext()) {
                out += AiTraceEvent(
                    id = c.getString(0), sessionId = c.getString(1), timestampMs = c.getLong(2), kind = c.getString(3),
                    displayText = c.getString(4), code = if (c.isNull(5)) null else c.getString(5),
                    ok = if (c.isNull(6)) null else c.getInt(6) == 1,
                    detail = if (c.isNull(7)) null else c.getString(7),
                )
            }
        }
        return out
    }
}

object TraceHumanizer {
    fun decision(tool: String, params: JSONObject, providedSummary: String?): String {
        if (tool == "phone.type") return "Filling the requested field without storing its contents"
        val clean = providedSummary?.trim().orEmpty()
        if (clean.isNotBlank()) return TracePrivacy.clean(clean).take(260)
        val verb = when (tool) {
            "phone.observe" -> "Checking the current screen before acting"
            "phone.find" -> "Looking for the safest matching control"
            "phone.click" -> "Opening the selected control using Android's semantic UI"
            "phone.scroll" -> "Scrolling to find the next relevant control"
            "phone.swipe" -> "Using a learned directional gesture on this page"
            "phone.open_app" -> "Opening the app needed for this task"
            "phone.launch_intent" -> "Opening the requested Android destination"
            "phone.open_notification" -> "Opening the relevant notification"
            "phone.wait_for" -> "Waiting for the expected screen state"
            "phone.assert" -> "Verifying the expected result before continuing"
            "phone.screenshot" -> "Structured UI is not enough, so checking the screen visually"
            "phone.back" -> "Going back one screen"
            "phone.home" -> "Returning to the Android home screen"
            else -> "Using ${tool.removePrefix("phone.").replace('_', ' ')}"
        }
        val selector = params.optJSONObject("selector")
        val target = selector?.optString("text").orEmpty()
            .ifBlank { selector?.optString("contentDescription").orEmpty() }
            .ifBlank { selector?.optString("resourceId").orEmpty().substringAfterLast('/') }
        return if (target.isBlank()) verb else "$verb: ${target.take(80)}"
    }

    fun result(tool: String, ok: Boolean): String = when {
        ok && tool == "phone.observe" -> "Current screen understood"
        ok && tool == "phone.assert" -> "Verification passed"
        ok -> "Action completed and Cyclone will verify the new state"
        tool == "phone.assert" -> "Verification failed; the expected state was not found"
        else -> "That action did not succeed; Cyclone will use the fresh screen state to recover"
    }
}

object TracePrivacy {
    private val email = Regex("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}")
    private val secretAssignments = Regex("(?i)(password|passwd|token|api[_ -]?key|secret|otp|2fa|pin)\\s*[:=]\\s*[^,;\\s}]+")
    private val bearer = Regex("(?i)bearer\\s+[a-z0-9._~+/-]{8,}")
    private val longBase64 = Regex("[A-Za-z0-9+/]{180,}={0,2}")
    // Digits inside words/hex ids (page keys) are not card numbers; timestamps and counters are not either.
    private val paymentCard = Regex("(?<![\\p{L}\\p{N}_])(?:\\d[ -]?){12,18}\\d(?![\\p{L}\\p{N}_])")
    private val groupedCard = Regex("^\\d{4}([ -])\\d{4}\\1\\d{4}\\1\\d{1,7}$")
    private val numericField = Regex("(?i)(?:ms|at|time|timestamp|generation|gen|id|span|start|end|elapsed|duration|count|size|bytes)[\"']?\\s*[:=]\\s*[\"']?$")
    private val usSsn = Regex("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)")

    fun clean(value: String): String = value
        .replace(email) { com.cyclone.mobile.agent.plan.DestinationAuthority.maskEmail(it.value) }
        .replace(secretAssignments) { "${it.groupValues[1]}=[REDACTED]" }
        .replace(bearer, "Bearer [REDACTED]")
        .replace(usSsn, "[IDENTIFIER_REDACTED]")
        .replace(paymentCard) { match -> if (looksLikeCard(value, match)) "[PAYMENT_REDACTED]" else match.value }
        .replace(longBase64, "[BINARY_REDACTED]")
        .replace(Regex("(?s)\\\"pngBase64\\\"\\s*:\\s*\\\".*?\\\""), "\"pngBase64\":\"[REDACTED]\"")

    /**
     * A card number is Luhn-valid or written in card groups (4-4-4-…); a millisecond timestamp or counter in a known
     * numeric field is neither redacted nor mangled (alpha.22 reports showed readAtMs as [PAYMENT_REDACTED]).
     */
    private fun looksLikeCard(source: String, match: MatchResult): Boolean {
        val raw = match.value.trim()
        if (groupedCard.matches(raw)) return true
        val before = source.substring(maxOf(0, match.range.first - 24), match.range.first)
        if (numericField.containsMatchIn(before)) return false
        return luhn(raw.filter(Char::isDigit))
    }

    internal fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        digits.reversed().forEachIndexed { index, char ->
            var d = char - '0'
            if (index % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }
}
