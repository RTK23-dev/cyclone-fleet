package com.cyclone.mobile.brain

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cyclone.mobile.ai.AiTraceEvent
import com.cyclone.mobile.ai.AiTraceSession
import com.cyclone.mobile.ai.TracePrivacy
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BrainTaskReport(
    val id: String,
    val goal: String,
    val status: String,
    val model: String,
    val summary: String,
    val reusableSequence: String,
    val failureSummary: String,
    val createdAt: Long,
)

data class BrainRoutineMemory(
    val signature: String,
    val goalKey: String,
    val toolSequence: String,
    val successCount: Int,
    val failureCount: Int,
    val confidence: Double,
    val lastUsedAt: Long,
)

data class BrainStats(
    val reports: Int,
    val successfulTasks: Int,
    val reusableRoutines: Int,
)

object CycloneBrainRuntime {
    @Volatile private var initialized = false
    lateinit var store: CycloneBrainStore
        private set

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        store = CycloneBrainStore(context.applicationContext)
        initialized = true
    }

    fun record(context: Context, session: AiTraceSession, events: List<AiTraceEvent>) {
        initialize(context)
        store.recordSession(session, events)
    }
}

/**
 * Long-term local memory above App Learner and Automation Studio.
 *
 * V2.6 stores a compact post-task report and successful tool-pattern memory. It does not copy
 * passwords, typed field contents, screenshots, cookies or provider-private reasoning into Brain.
 */
class CycloneBrainStore(private val context: Context) : SQLiteOpenHelper(context, "cyclone_brain.db", null, 1) {
    private val brainRoot = File(context.filesDir, "Cyclone Brain")
    private val datePath = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE task_reports(
              id TEXT PRIMARY KEY,
              goal TEXT NOT NULL,
              status TEXT NOT NULL,
              model TEXT NOT NULL,
              summary TEXT NOT NULL,
              reusable_sequence TEXT NOT NULL,
              failure_summary TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE routine_memory(
              signature TEXT PRIMARY KEY,
              goal_key TEXT NOT NULL,
              tool_sequence TEXT NOT NULL,
              success_count INTEGER NOT NULL,
              failure_count INTEGER NOT NULL,
              confidence REAL NOT NULL,
              last_used_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX task_reports_created ON task_reports(created_at DESC)")
        db.execSQL("CREATE INDEX routine_goal ON routine_memory(goal_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun recordSession(session: AiTraceSession, events: List<AiTraceEvent>) {
        val logic = BrainLearningLogic.summarize(session, events)
        writableDatabase.insertWithOnConflict(
            "task_reports",
            null,
            ContentValues().apply {
                put("id", session.id)
                put("goal", TracePrivacy.clean(session.goal).take(500))
                put("status", session.status)
                put("model", session.model.take(180))
                put("summary", logic.summary.take(1800))
                put("reusable_sequence", logic.toolSequence.take(1200))
                put("failure_summary", logic.failureSummary.take(1500))
                put("created_at", session.endedAt ?: session.startedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        if (logic.signature.isNotBlank()) updateRoutine(logic.goalKey, logic.signature, logic.toolSequence, session.status == "COMPLETED")
        writeTaskMarkdown(session, logic)
        writeOverview()
    }

    private fun updateRoutine(goalKey: String, signature: String, sequence: String, success: Boolean) {
        val existing = routine(signature)
        val successCount = (existing?.successCount ?: 0) + if (success) 1 else 0
        val failureCount = (existing?.failureCount ?: 0) + if (success) 0 else 1
        val confidence = BrainLearningLogic.confidence(successCount, failureCount)
        writableDatabase.insertWithOnConflict(
            "routine_memory",
            null,
            ContentValues().apply {
                put("signature", signature)
                put("goal_key", goalKey)
                put("tool_sequence", sequence)
                put("success_count", successCount)
                put("failure_count", failureCount)
                put("confidence", confidence)
                put("last_used_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun stats(): BrainStats {
        fun count(sql: String): Int = readableDatabase.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return BrainStats(
            reports = count("SELECT COUNT(*) FROM task_reports"),
            successfulTasks = count("SELECT COUNT(*) FROM task_reports WHERE status='COMPLETED'"),
            reusableRoutines = count("SELECT COUNT(*) FROM routine_memory WHERE confidence >= 0.60"),
        )
    }

    fun listReports(limit: Int = 30): List<BrainTaskReport> {
        val out = mutableListOf<BrainTaskReport>()
        readableDatabase.query(
            "task_reports",
            arrayOf("id", "goal", "status", "model", "summary", "reusable_sequence", "failure_summary", "created_at"),
            null, null, null, null, "created_at DESC", limit.coerceIn(1, 200).toString(),
        ).use { c ->
            while (c.moveToNext()) {
                out += BrainTaskReport(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getLong(7))
            }
        }
        return out
    }

    fun listRoutines(limit: Int = 40): List<BrainRoutineMemory> {
        val out = mutableListOf<BrainRoutineMemory>()
        readableDatabase.query(
            "routine_memory",
            arrayOf("signature", "goal_key", "tool_sequence", "success_count", "failure_count", "confidence", "last_used_at"),
            null, null, null, null, "confidence DESC, last_used_at DESC", limit.coerceIn(1, 200).toString(),
        ).use { c ->
            while (c.moveToNext()) out += BrainRoutineMemory(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4), c.getDouble(5), c.getLong(6))
        }
        return out
    }

    fun bestRoutineFor(goal: String): BrainRoutineMemory? {
        val key = BrainLearningLogic.goalKey(goal)
        return listRoutines(100).firstOrNull { it.goalKey == key && it.confidence >= 0.70 }
    }

    private fun routine(signature: String): BrainRoutineMemory? {
        readableDatabase.query(
            "routine_memory",
            arrayOf("signature", "goal_key", "tool_sequence", "success_count", "failure_count", "confidence", "last_used_at"),
            "signature=?", arrayOf(signature), null, null, null, "1",
        ).use { c ->
            if (!c.moveToFirst()) return null
            return BrainRoutineMemory(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4), c.getDouble(5), c.getLong(6))
        }
    }

    private fun writeTaskMarkdown(session: AiTraceSession, logic: BrainLearningSummary) {
        val stamp = session.endedAt ?: session.startedAt
        val dir = File(brainRoot, "Task Reports/${datePath.format(Date(stamp))}").apply { mkdirs() }
        File(dir, "${safeName(session.id)}.md").writeText(buildString {
            appendLine("# Task report")
            appendLine()
            appendLine("Goal: ${TracePrivacy.clean(session.goal)}")
            appendLine("Status: **${session.status}**")
            appendLine("Model: `${session.model}`")
            appendLine("Finished: ${dateTime.format(Date(stamp))}")
            appendLine()
            appendLine("## What Cyclone learned")
            appendLine(logic.summary)
            appendLine()
            appendLine("## Reusable path")
            appendLine(if (logic.toolSequence.isBlank()) "No stable reusable phone-tool sequence was captured." else "`${logic.toolSequence}`")
            appendLine()
            appendLine("## What went wrong")
            appendLine(logic.failureSummary.ifBlank { "No failure was recorded." })
            appendLine()
            appendLine("## Next optimization")
            appendLine(logic.optimization)
            appendLine()
            appendLine("> User-facing task memory only. Passwords, typed values, screenshots, authentication secrets and hidden model reasoning are excluded.")
        })
    }

    private fun writeOverview() {
        val stats = stats()
        val memoryDir = File(brainRoot, "Memory").apply { mkdirs() }
        File(memoryDir, "Overview.md").writeText(buildString {
            appendLine("# Cyclone Brain")
            appendLine()
            appendLine("Persistent local memory for learned apps, workflows, skills and post-task improvements.")
            appendLine()
            appendLine("- Task reports: ${stats.reports}")
            appendLine("- Successful tasks: ${stats.successfulTasks}")
            appendLine("- Reusable routine memories: ${stats.reusableRoutines}")
            appendLine()
            appendLine("## Strongest routine memories")
            listRoutines(20).forEach { routine ->
                appendLine("- `${routine.goalKey}` — ${(routine.confidence * 100).toInt()}% · ${routine.successCount} successes / ${routine.failureCount} failures")
            }
            appendLine()
            appendLine("App-specific semantic maps continue to live under `Apps/` and remain owned by App Learner.")
        })
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
}

data class BrainLearningSummary(
    val goalKey: String,
    val signature: String,
    val toolSequence: String,
    val summary: String,
    val failureSummary: String,
    val optimization: String,
)

object BrainLearningLogic {
    fun summarize(session: AiTraceSession, events: List<AiTraceEvent>): BrainLearningSummary {
        val tools = events.mapNotNull { it.code?.takeIf { code -> code.startsWith("phone.") } }
            .filterNot { it in setOf("phone.observe", "phone.find") }
        val failures = events.filter { it.ok == false }.map { it.displayText }.distinct().take(6)
        val sequence = tools.joinToString(" → ")
        val signature = if (tools.isEmpty()) "" else sha256(goalKey(session.goal) + "|" + tools.joinToString("|"))
        val summary = when {
            session.status == "COMPLETED" && tools.isNotEmpty() -> "Cyclone completed the task using ${tools.size} reusable phone actions. The successful sequence was saved as local routine evidence so future runs can prefer known app knowledge and deterministic workflows."
            session.status == "COMPLETED" -> "Cyclone completed the task without a reusable phone-action sequence. The outcome and verification evidence were still saved locally."
            else -> "Cyclone did not complete the task. The failure points were saved so the next attempt can avoid repeating the same unsuccessful path."
        }
        val failureSummary = failures.joinToString("\n") { "- $it" }
        val optimization = when {
            session.status != "COMPLETED" -> "Start from the last verified state, try known App Graph alternatives first, and use AI recovery only for the unknown part."
            tools.size >= 2 -> "If this route repeats successfully, promote it toward a deterministic Skill/Automation instead of asking a model to rediscover every step."
            else -> "Keep the learned result and reuse it when the same goal appears again."
        }
        return BrainLearningSummary(goalKey(session.goal), signature, sequence, summary, failureSummary, optimization)
    }

    fun goalKey(goal: String): String = goal.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\b(my|the|a|an|please|can|you|cyclone|latest|newest)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .split(' ')
        .filter { it.isNotBlank() }
        .take(10)
        .joinToString(" ")

    fun confidence(successCount: Int, failureCount: Int): Double {
        val evidence = successCount + failureCount
        if (evidence <= 0) return 0.0
        val successRatio = successCount.toDouble() / evidence.toDouble()
        val evidenceBoost = (evidence.coerceAtMost(5) * 0.06)
        return (0.42 + successRatio * 0.36 + evidenceBoost - failureCount.coerceAtMost(4) * 0.05).coerceIn(0.05, 0.96)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(24)
}
