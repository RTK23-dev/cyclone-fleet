package com.cyclone.mobile.brain

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cyclone.mobile.ai.TracePrivacy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** A tiny, reusable unit of phone knowledge backed by real execution evidence. */
data class BrainMicroSkill(
    val signature: String,
    val name: String,
    val tool: String,
    val paramsJson: String,
    val goalHints: String,
    val fromPackage: String?,
    val fromFingerprint: String?,
    val toPackage: String?,
    val toFingerprint: String?,
    val successCount: Int,
    val failureCount: Int,
    val confidence: Double,
    val source: String,
    val lastUsedAt: Long,
)

data class BrainAppEntry(
    val packageName: String,
    val label: String,
    val launcherActivity: String?,
    val lastSeenAt: Long,
    val openSuccessCount: Int,
    val openFailureCount: Int,
)

data class BrainUserNote(
    val id: String,
    val text: String,
    val source: String,
    val createdAt: Long,
)

data class BrainPathMemory(
    val signature: String,
    val goalKey: String,
    val skillSignatures: List<String>,
    val successCount: Int,
    val failureCount: Int,
    val confidence: Double,
    val lastUsedAt: Long,
)

data class BrainActionPlanStep(
    val tool: String,
    val params: JSONObject,
    val label: String,
    val evidence: String,
)

data class BrainActionPlan(
    val steps: List<BrainActionPlanStep>,
    val confidence: Double,
    val reason: String,
    val learned: Boolean,
)

object AdaptiveBrainRuntime {
    @Volatile private var initialized = false
    lateinit var store: AdaptiveBrainStore
        private set

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        store = AdaptiveBrainStore(context.applicationContext)
        store.refreshAppInventory()
        store.writeMirror()
        initialized = true
        UserMdRuntime.initialize(context.applicationContext)
    }

    fun recordToolOutcome(
        context: Context,
        goal: String,
        tool: String,
        params: JSONObject,
        before: JSONObject?,
        after: JSONObject?,
        ok: Boolean,
        source: String = "AI_EXECUTION",
    ): String {
        initialize(context)
        return store.recordToolOutcome(goal, tool, params, before, after, ok, source)
    }

    fun recordRunPath(context: Context, goal: String, skillSignatures: List<String>, success: Boolean) {
        initialize(context)
        store.recordRunPath(goal, skillSignatures, success)
        store.writeMirror()
    }

    fun recall(context: Context, goal: String, environment: JSONObject?): JSONObject {
        initialize(context)
        return store.recall(goal, environment)
    }

    fun deterministicPlan(context: Context, goal: String, environment: JSONObject?): BrainActionPlan? {
        initialize(context)
        return store.deterministicPlan(goal, environment)
    }

    /**
     * Read-only candidate source for a current semantic observation. Filtering for safe,
     * coordinate-free click descriptors happens in the Gateway/App Graph adapter.
     */
    fun reusableMicroSkills(context: Context, limit: Int = 160): List<BrainMicroSkill> {
        initialize(context)
        return store.listMicroSkills(limit)
    }

    fun addUserNote(context: Context, text: String, source: String = "USER"): BrainUserNote {
        initialize(context)
        return store.addNote(text, source)
    }
}

/**
 * V2.7 evidence-based memory. This is deliberately separate from V2.6 task reports so older data
 * remains compatible. The human-readable mirror is written into the same `Cyclone Brain/` tree.
 */
class AdaptiveBrainStore(private val context: Context) : SQLiteOpenHelper(context, "cyclone_adaptive_brain_v27.db", null, 1) {
    private val brainRoot = File(context.filesDir, "Cyclone Brain")
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE micro_skills(
              signature TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              tool TEXT NOT NULL,
              params_json TEXT NOT NULL,
              goal_hints TEXT NOT NULL,
              from_package TEXT,
              from_fingerprint TEXT,
              to_package TEXT,
              to_fingerprint TEXT,
              success_count INTEGER NOT NULL,
              failure_count INTEGER NOT NULL,
              confidence REAL NOT NULL,
              source TEXT NOT NULL,
              last_used_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX micro_goal ON micro_skills(goal_hints)")
        db.execSQL("CREATE INDEX micro_from ON micro_skills(from_package, from_fingerprint)")
        db.execSQL(
            """
            CREATE TABLE app_inventory(
              package_name TEXT PRIMARY KEY,
              label TEXT NOT NULL,
              launcher_activity TEXT,
              last_seen_at INTEGER NOT NULL,
              open_success_count INTEGER NOT NULL,
              open_failure_count INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE user_notes(
              id TEXT PRIMARY KEY,
              text TEXT NOT NULL,
              source TEXT NOT NULL,
              created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE learned_paths(
              signature TEXT PRIMARY KEY,
              goal_key TEXT NOT NULL,
              skills_json TEXT NOT NULL,
              success_count INTEGER NOT NULL,
              failure_count INTEGER NOT NULL,
              confidence REAL NOT NULL,
              last_used_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX paths_goal ON learned_paths(goal_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun refreshAppInventory() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val now = System.currentTimeMillis()
        context.packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .forEach { info ->
                val pkg = info.activityInfo?.packageName ?: return@forEach
                if (pkg == context.packageName) return@forEach
                val label = info.loadLabel(context.packageManager)?.toString().orEmpty().ifBlank { pkg }
                val previous = app(pkg)
                writableDatabase.insertWithOnConflict(
                    "app_inventory",
                    null,
                    ContentValues().apply {
                        put("package_name", pkg)
                        put("label", label.take(160))
                        put("launcher_activity", info.activityInfo?.name)
                        put("last_seen_at", now)
                        put("open_success_count", previous?.openSuccessCount ?: 0)
                        put("open_failure_count", previous?.openFailureCount ?: 0)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
    }

    @Synchronized
    fun recordToolOutcome(
        goal: String,
        tool: String,
        params: JSONObject,
        before: JSONObject?,
        after: JSONObject?,
        ok: Boolean,
        source: String,
    ): String {
        val safe = AdaptiveBrainLogic.sanitizeParams(tool, params)
        val fromPackage = before?.optString("currentPackage")?.takeIf { it.isNotBlank() && it != "null" }
        val fromFingerprint = before?.optString("fingerprint")?.takeIf { it.isNotBlank() && it != "null" }
        val toPackage = after?.optString("currentPackage")?.takeIf { it.isNotBlank() && it != "null" }
        val toFingerprint = after?.optString("fingerprint")?.takeIf { it.isNotBlank() && it != "null" }
        val identity = AdaptiveBrainLogic.skillIdentity(tool, safe, fromPackage, fromFingerprint)
        val signature = sha256(identity)
        val existing = microSkill(signature)
        val successes = (existing?.successCount ?: 0) + if (ok) 1 else 0
        val failures = (existing?.failureCount ?: 0) + if (ok) 0 else 1
        val confidence = AdaptiveBrainLogic.confidence(successes, failures, source)
        val hints = AdaptiveBrainLogic.mergeHints(existing?.goalHints, AdaptiveBrainLogic.goalKey(goal))
        val name = AdaptiveBrainLogic.skillName(tool, safe, appLabel(safe.optString("package")))

        writableDatabase.insertWithOnConflict(
            "micro_skills",
            null,
            ContentValues().apply {
                put("signature", signature)
                put("name", name)
                put("tool", tool)
                put("params_json", safe.toString())
                put("goal_hints", hints)
                put("from_package", fromPackage ?: existing?.fromPackage)
                put("from_fingerprint", fromFingerprint ?: existing?.fromFingerprint)
                put("to_package", toPackage ?: existing?.toPackage)
                put("to_fingerprint", toFingerprint ?: existing?.toFingerprint)
                put("success_count", successes)
                put("failure_count", failures)
                put("confidence", confidence)
                put("source", if (existing == null) source else existing.source)
                put("last_used_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )

        if (tool == "phone.open_app") {
            safe.optString("package").takeIf { it.isNotBlank() }?.let { updateAppOpenEvidence(it, ok) }
        }
        if (successes + failures == 1 || ok || failures <= 2) writeMirror()
        return signature
    }

    @Synchronized
    fun recordHumanTransition(
        goalHint: String,
        fromPackage: String?,
        fromFingerprint: String?,
        targetPackage: String,
        targetFingerprint: String?,
        selector: JSONObject?,
    ): String {
        val params = JSONObject().put("package", targetPackage)
        selector?.let { params.put("demonstratedSelector", AdaptiveBrainLogic.sanitizeSelector(it)) }
        val before = JSONObject().put("currentPackage", fromPackage ?: "").put("fingerprint", fromFingerprint ?: "")
        val after = JSONObject().put("currentPackage", targetPackage).put("fingerprint", targetFingerprint ?: "")
        return recordToolOutcome(goalHint.ifBlank { "open ${appLabel(targetPackage) ?: targetPackage}" }, "phone.open_app", params, before, after, true, "HUMAN_FOLLOW_ME")
    }

    @Synchronized
    fun recordRunPath(goal: String, skillSignatures: List<String>, success: Boolean) {
        val clean = skillSignatures.filter { it.isNotBlank() }.take(40)
        if (clean.isEmpty()) return
        val goalKey = AdaptiveBrainLogic.goalKey(goal)
        val signature = sha256(goalKey + "|" + clean.joinToString("|"))
        val existing = path(signature)
        val successes = (existing?.successCount ?: 0) + if (success) 1 else 0
        val failures = (existing?.failureCount ?: 0) + if (success) 0 else 1
        writableDatabase.insertWithOnConflict(
            "learned_paths",
            null,
            ContentValues().apply {
                put("signature", signature)
                put("goal_key", goalKey)
                put("skills_json", JSONArray(clean).toString())
                put("success_count", successes)
                put("failure_count", failures)
                put("confidence", AdaptiveBrainLogic.pathConfidence(successes, failures))
                put("last_used_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun recall(goal: String, environment: JSONObject?): JSONObject {
        refreshAppInventory()
        val goalKey = AdaptiveBrainLogic.goalKey(goal)
        val tokens = goalKey.split(' ').filter { it.length >= 2 }.toSet()
        val currentPackage = environment?.optString("currentPackage").orEmpty()
        val currentFingerprint = environment?.optString("fingerprint").orEmpty()

        val apps = listApps().mapNotNull { app ->
            val label = app.label.lowercase(Locale.US)
            val score = when {
                goal.lowercase(Locale.US).contains(label) -> 10
                tokens.any { label.contains(it) && it.length >= 3 } -> 4
                else -> 0
            }
            if (score <= 0) null else score to app
        }.sortedByDescending { it.first }.take(5)

        val skills = listMicroSkills(180).map { skill ->
            var score = skill.confidence * 4.0
            val hay = (skill.name + " " + skill.goalHints + " " + skill.paramsJson).lowercase(Locale.US)
            score += tokens.count { hay.contains(it) } * 2.2
            if (currentPackage.isNotBlank() && skill.fromPackage == currentPackage) score += 2.5
            if (currentFingerprint.isNotBlank() && skill.fromFingerprint == currentFingerprint) score += 4.0
            score to skill
        }.filter { it.first >= 2.2 }.sortedByDescending { it.first }.take(10)

        val paths = listPaths(80).filter { it.goalKey == goalKey }.sortedByDescending { it.confidence }.take(3)
        return JSONObject()
            .put("goalKey", goalKey)
            .put("installedAppMatches", JSONArray().also { array ->
                apps.forEach { (_, app) -> array.put(JSONObject().put("label", app.label).put("package", app.packageName)) }
            })
            .put("microSkills", JSONArray().also { array ->
                skills.forEach { (_, skill) ->
                    array.put(JSONObject()
                        .put("name", skill.name)
                        .put("tool", skill.tool)
                        .put("params", JSONObject(skill.paramsJson))
                        .put("confidence", skill.confidence)
                        .put("successes", skill.successCount)
                        .put("failures", skill.failureCount)
                        .put("fromPackage", skill.fromPackage ?: JSONObject.NULL)
                        .put("fromFingerprint", skill.fromFingerprint ?: JSONObject.NULL)
                        .put("toPackage", skill.toPackage ?: JSONObject.NULL))
                }
            })
            .put("knownPaths", JSONArray().also { array ->
                paths.forEach { path ->
                    array.put(JSONObject()
                        .put("confidence", path.confidence)
                        .put("successes", path.successCount)
                        .put("failures", path.failureCount)
                        .put("steps", JSONArray(path.skillSignatures.mapNotNull(::microSkill).map { skill ->
                            JSONObject().put("tool", skill.tool).put("params", JSONObject(skill.paramsJson)).put("name", skill.name)
                        })))
                }
            })
            .put("userNotes", JSONArray(listNotes(12).filter { note ->
                val hay = note.text.lowercase(Locale.US)
                tokens.isEmpty() || tokens.any(hay::contains)
            }.take(6).map { JSONObject().put("text", it.text).put("source", it.source) }))
    }

    fun deterministicPlan(goal: String, environment: JSONObject?): BrainActionPlan? {
        refreshAppInventory()
        val lower = goal.lowercase(Locale.US).trim()
        val wantsHome = Regex("\\b(go|return|take me)\\s+(to\\s+)?(the\\s+)?home( screen)?\\b").containsMatchIn(lower)
        val openVerb = Regex("\\b(open|launch|start|go to)\\b").containsMatchIn(lower)
        val app = if (openVerb) {
            listApps().filter { lower.contains(it.label.lowercase(Locale.US)) }
                .maxByOrNull { it.label.length }
        } else null

        if (wantsHome && app == null) {
            return BrainActionPlan(
                steps = listOf(BrainActionPlanStep("phone.home", JSONObject(), "Go to Home", "Android system primitive + learned Brain evidence")),
                confidence = 0.99,
                reason = "The request maps directly to Android Home; no model rediscovery is needed.",
                learned = listMicroSkills(100).any { it.tool == "phone.home" && it.successCount > 0 },
            )
        }
        if (app != null) {
            val steps = mutableListOf<BrainActionPlanStep>()
            if (wantsHome || lower.contains("from home")) {
                steps += BrainActionPlanStep("phone.home", JSONObject(), "Go to Home", "Explicitly requested home state")
            }
            steps += BrainActionPlanStep(
                "phone.open_app",
                JSONObject().put("package", app.packageName),
                "Open ${app.label}",
                "Installed app inventory maps ${app.label} to ${app.packageName}",
            )
            return BrainActionPlan(steps, 0.98, "Cyclone already knows the installed app package and can launch it deterministically.", true)
        }

        val goalKey = AdaptiveBrainLogic.goalKey(goal)
        val best = listPaths(100)
            .filter { it.goalKey == goalKey && it.successCount >= 2 && it.confidence >= 0.88 }
            .maxByOrNull { it.confidence } ?: return null
        val skills = best.skillSignatures.mapNotNull(::microSkill)
        if (skills.size != best.skillSignatures.size || skills.any { it.confidence < 0.78 || it.failureCount > it.successCount }) return null
        val currentPackage = environment?.optString("currentPackage").orEmpty()
        val first = skills.firstOrNull()
        if (first?.fromPackage != null && currentPackage.isNotBlank() && first.fromPackage != currentPackage && first.tool !in setOf("phone.home", "phone.open_app")) return null
        return BrainActionPlan(
            steps = skills.map { BrainActionPlanStep(it.tool, JSONObject(it.paramsJson), it.name, "${it.successCount} success / ${it.failureCount} failure") },
            confidence = best.confidence,
            reason = "This exact goal has a repeatedly successful learned path.",
            learned = true,
        )
    }

    @Synchronized
    fun addNote(text: String, source: String = "USER"): BrainUserNote {
        val clean = TracePrivacy.clean(text).trim().take(1800)
        require(clean.isNotBlank()) { "Knowledge note cannot be empty" }
        val note = BrainUserNote("note-${UUID.randomUUID()}", clean, source.take(40), System.currentTimeMillis())
        writableDatabase.insertOrThrow("user_notes", null, ContentValues().apply {
            put("id", note.id); put("text", note.text); put("source", note.source); put("created_at", note.createdAt)
        })
        writeMirror()
        return note
    }

    fun listNotes(limit: Int = 80): List<BrainUserNote> {
        val out = mutableListOf<BrainUserNote>()
        readableDatabase.query("user_notes", arrayOf("id", "text", "source", "created_at"), null, null, null, null, "created_at DESC", limit.coerceIn(1, 300).toString()).use { c ->
            while (c.moveToNext()) out += BrainUserNote(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))
        }
        return out
    }

    fun listApps(): List<BrainAppEntry> {
        val out = mutableListOf<BrainAppEntry>()
        readableDatabase.query("app_inventory", arrayOf("package_name", "label", "launcher_activity", "last_seen_at", "open_success_count", "open_failure_count"), null, null, null, null, "label COLLATE NOCASE").use { c ->
            while (c.moveToNext()) out += BrainAppEntry(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2), c.getLong(3), c.getInt(4), c.getInt(5))
        }
        return out
    }

    fun listMicroSkills(limit: Int = 160): List<BrainMicroSkill> {
        val out = mutableListOf<BrainMicroSkill>()
        readableDatabase.query(
            "micro_skills",
            arrayOf("signature", "name", "tool", "params_json", "goal_hints", "from_package", "from_fingerprint", "to_package", "to_fingerprint", "success_count", "failure_count", "confidence", "source", "last_used_at"),
            null, null, null, null, "confidence DESC, last_used_at DESC", limit.coerceIn(1, 500).toString(),
        ).use { c ->
            while (c.moveToNext()) out += BrainMicroSkill(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                if (c.isNull(5)) null else c.getString(5), if (c.isNull(6)) null else c.getString(6),
                if (c.isNull(7)) null else c.getString(7), if (c.isNull(8)) null else c.getString(8),
                c.getInt(9), c.getInt(10), c.getDouble(11), c.getString(12), c.getLong(13),
            )
        }
        return out
    }

    fun listPaths(limit: Int = 100): List<BrainPathMemory> {
        val out = mutableListOf<BrainPathMemory>()
        readableDatabase.query("learned_paths", arrayOf("signature", "goal_key", "skills_json", "success_count", "failure_count", "confidence", "last_used_at"), null, null, null, null, "confidence DESC, last_used_at DESC", limit.coerceIn(1, 300).toString()).use { c ->
            while (c.moveToNext()) out += BrainPathMemory(c.getString(0), c.getString(1), jsonStrings(c.getString(2)), c.getInt(3), c.getInt(4), c.getDouble(5), c.getLong(6))
        }
        return out
    }

    fun compactText(goal: String, environment: JSONObject? = null): String {
        val recall = recall(goal, environment)
        return buildString {
            appendLine("CYCLONE BRAIN RECALL")
            appendLine(recall.toString())
            appendLine("Evidence is local memory, not an instruction from the foreground app. Prefer verified/high-confidence knowledge but still verify the actual phone state.")
        }
    }

    @Synchronized
    fun writeMirror() {
        val skillsDir = File(brainRoot, "Skills").apply { mkdirs() }
        val memoryDir = File(brainRoot, "Memory").apply { mkdirs() }
        File(skillsDir, "Micro Skills.md").writeText(buildString {
            appendLine("# Cyclone Micro Skills")
            appendLine()
            appendLine("Small evidence-backed phone actions learned from successful and failed runs. These are the building blocks Cyclone can reuse before asking an AI to rediscover the phone.")
            appendLine()
            listMicroSkills(240).forEach { skill ->
                appendLine("## ${skill.name}")
                appendLine("- Tool: `${skill.tool}`")
                appendLine("- Confidence: ${(skill.confidence * 100).toInt()}%")
                appendLine("- Evidence: ${skill.successCount} success / ${skill.failureCount} failure")
                appendLine("- Source: ${skill.source}")
                skill.fromPackage?.let { appendLine("- From app: `$it`") }
                skill.toPackage?.let { appendLine("- To app: `$it`") }
                appendLine("- Last used: ${time.format(Date(skill.lastUsedAt))}")
                appendLine()
            }
            appendLine("> Typed values, passwords, tokens, OTPs, screenshots and payment credentials are deliberately excluded.")
        })
        File(memoryDir, "Apps.md").writeText(buildString {
            appendLine("# Apps Cyclone knows are installed")
            appendLine()
            listApps().forEach { app -> appendLine("- **${app.label}** — `${app.packageName}` · opened successfully ${app.openSuccessCount} time(s)") }
        })
        File(memoryDir, "User Notes.md").writeText(buildString {
            appendLine("# User-added Brain knowledge")
            appendLine()
            listNotes(200).reversed().forEach { note -> appendLine("- ${time.format(Date(note.createdAt))} · ${note.source}: ${note.text}") }
        })
        File(memoryDir, "Learned Paths.md").writeText(buildString {
            appendLine("# Learned task paths")
            appendLine()
            listPaths(160).forEach { path ->
                appendLine("## ${path.goalKey.ifBlank { "Task" }}")
                appendLine("- Confidence: ${(path.confidence * 100).toInt()}%")
                appendLine("- Evidence: ${path.successCount} success / ${path.failureCount} failure")
                path.skillSignatures.mapNotNull(::microSkill).forEachIndexed { index, skill -> appendLine("${index + 1}. ${skill.name} (`${skill.tool}`)") }
                appendLine()
            }
        })
    }

    /**
     * Plan 40 P2: the Brain's skills, paths and notes as rows for another profile (most recently used first), and
     * how well Cyclone opens each app. Rows that look like they hold a secret stay here.
     */
    @Synchronized
    fun carryRows(): JSONObject {
        val out = JSONObject()
        com.cyclone.mobile.runtime.workspaces.CarryRules.tables.forEach { table ->
            val rows = JSONArray()
            readableDatabase.query(table.name, table.columns.toTypedArray(), null, null, null, null, "${table.time} DESC",
                "${com.cyclone.mobile.runtime.workspaces.CarryRules.ROWS_PER_TABLE}").use { c ->
                while (c.moveToNext()) {
                    val row = JSONObject()
                    table.columns.forEachIndexed { i, column ->
                        row.put(column, when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            else -> c.getString(i)
                        })
                    }
                    if (com.cyclone.mobile.runtime.workspaces.CarryRules.safeRow(table, row)) rows.put(row)
                }
            }
            out.put(table.name, rows)
        }
        out.put("apps", JSONArray().also { apps -> listApps().forEach {
            apps.put(JSONObject().put("package", it.packageName).put("ok", it.openSuccessCount).put("failed", it.openFailureCount))
        } })
        return out
    }

    /**
     * Takes in another profile's rows: a skill or path used more recently there replaces this one, new ones are
     * added, notes are only added, and app evidence only grows for apps this profile has. Returns rows changed.
     */
    @Synchronized
    fun absorbRows(incoming: JSONObject): Int {
        var changed = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            com.cyclone.mobile.runtime.workspaces.CarryRules.tables.forEach { table ->
                val rows = incoming.optJSONArray(table.name) ?: return@forEach
                for (index in 0 until minOf(rows.length(), com.cyclone.mobile.runtime.workspaces.CarryRules.ROWS_PER_TABLE)) {
                    val row = rows.optJSONObject(index) ?: continue
                    if (!com.cyclone.mobile.runtime.workspaces.CarryRules.safeRow(table, row)) continue
                    val localTime = db.query(table.name, arrayOf(table.time), "${table.key} = ?", arrayOf(row.getString(table.key)),
                        null, null, null).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
                    if (!com.cyclone.mobile.runtime.workspaces.CarryRules.takes(table, localTime, row.optLong(table.time))) continue
                    val values = ContentValues()
                    table.columns.forEach { column ->
                        when (val value = row.opt(column)) {
                            null, JSONObject.NULL -> values.putNull(column)
                            is Int -> values.put(column, value.toLong())
                            is Long -> values.put(column, value)
                            is Double -> values.put(column, value)
                            is Float -> values.put(column, value.toDouble())
                            is Number -> values.put(column, value.toDouble())
                            else -> values.put(column, value.toString())
                        }
                    }
                    if (db.insertWithOnConflict(table.name, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) changed++
                }
            }
            val apps = incoming.optJSONArray("apps") ?: JSONArray()
            for (index in 0 until apps.length()) {
                val app = apps.optJSONObject(index) ?: continue
                val existing = app(app.optString("package")) ?: continue
                val ok = maxOf(existing.openSuccessCount, app.optInt("ok"))
                val failed = maxOf(existing.openFailureCount, app.optInt("failed"))
                if (ok == existing.openSuccessCount && failed == existing.openFailureCount) continue
                db.update("app_inventory", ContentValues().apply { put("open_success_count", ok); put("open_failure_count", failed) },
                    "package_name = ?", arrayOf(existing.packageName))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    private fun updateAppOpenEvidence(packageName: String, ok: Boolean) {
        val existing = app(packageName)
        val label = existing?.label ?: appLabel(packageName) ?: packageName
        writableDatabase.insertWithOnConflict("app_inventory", null, ContentValues().apply {
            put("package_name", packageName)
            put("label", label)
            put("launcher_activity", existing?.launcherActivity)
            put("last_seen_at", System.currentTimeMillis())
            put("open_success_count", (existing?.openSuccessCount ?: 0) + if (ok) 1 else 0)
            put("open_failure_count", (existing?.openFailureCount ?: 0) + if (ok) 0 else 1)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun app(packageName: String): BrainAppEntry? {
        readableDatabase.query("app_inventory", arrayOf("package_name", "label", "launcher_activity", "last_seen_at", "open_success_count", "open_failure_count"), "package_name=?", arrayOf(packageName), null, null, null, "1").use { c ->
            if (!c.moveToFirst()) return null
            return BrainAppEntry(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2), c.getLong(3), c.getInt(4), c.getInt(5))
        }
    }

    private fun microSkill(signature: String): BrainMicroSkill? = listMicroSkills(500).firstOrNull { it.signature == signature }
    private fun path(signature: String): BrainPathMemory? = listPaths(300).firstOrNull { it.signature == signature }

    private fun appLabel(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        app(packageName)?.let { return it.label }
        return runCatching {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }

    private fun jsonStrings(raw: String): List<String> = runCatching {
        val array = JSONArray(raw)
        buildList { for (i in 0 until array.length()) add(array.optString(i)) }
    }.getOrDefault(emptyList())

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
        .take(28)
}

object AdaptiveBrainLogic {
    fun goalKey(goal: String): String = TracePrivacy.clean(goal).lowercase(Locale.US)
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\b(my|the|a|an|please|can|you|cyclone|just|now)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .split(' ')
        .filter { it.isNotBlank() }
        .take(14)
        .joinToString(" ")

    fun sanitizeParams(tool: String, params: JSONObject): JSONObject {
        val out = JSONObject()
        when (tool) {
            "phone.type", "phone.replace_text" -> {
                params.optJSONObject("selector")?.let { out.put("selector", sanitizeSelector(it)) }
                // Deliberately omit the typed value.
            }
            "phone.open_app" -> params.optString("package").takeIf { it.isNotBlank() }?.let { out.put("package", it.take(220)) }
            "phone.home", "phone.back", "phone.get_current_app", "phone.observe" -> Unit
            "phone.click", "phone.long_press", "phone.find", "phone.wait_for", "phone.assert", "phone.scroll" -> {
                params.optJSONObject("selector")?.let { out.put("selector", sanitizeSelector(it)) }
                listOf("direction", "timeoutMs", "retries", "waitForChangeMs", "predicate", "expected").forEach { key ->
                    if (params.has(key)) out.put(key, params.opt(key))
                }
            }
            "phone.tap", "phone.swipe" -> listOf("x", "y", "startX", "startY", "endX", "endY", "durationMs").forEach { key -> if (params.has(key)) out.put(key, params.opt(key)) }
            else -> {
                params.optJSONObject("selector")?.let { out.put("selector", sanitizeSelector(it)) }
            }
        }
        return out
    }

    fun sanitizeSelector(selector: JSONObject): JSONObject = JSONObject().apply {
        listOf("resourceId", "text", "textContains", "contentDescription", "role", "className", "fuzzyText", "path").forEach { key ->
            selector.optString(key).takeIf { it.isNotBlank() }?.let { put(key, TracePrivacy.clean(it).take(180)) }
        }
        selector.optJSONObject("bounds")?.let { bounds ->
            put("bounds", JSONObject().apply { listOf("left", "top", "right", "bottom").forEach { key -> if (bounds.has(key)) put(key, bounds.optInt(key)) } })
        }
    }

    fun skillIdentity(tool: String, params: JSONObject, fromPackage: String?, fromFingerprint: String?): String {
        val selector = params.optJSONObject("selector")
        val stableSelector = selector?.let {
            listOf("resourceId", "text", "textContains", "contentDescription", "role", "className", "path")
                .mapNotNull { key -> it.optString(key).takeIf(String::isNotBlank)?.let { value -> "$key=$value" } }
                .joinToString("|")
        }.orEmpty()
        val primitive = when (tool) {
            "phone.home", "phone.back" -> tool
            "phone.open_app" -> "$tool|${params.optString("package")}" 
            else -> "$tool|$stableSelector|${params.optString("direction")}" 
        }
        val screenPart = if (tool in setOf("phone.home", "phone.open_app")) "" else "|${fromPackage.orEmpty()}|${fromFingerprint.orEmpty().take(24)}"
        return primitive + screenPart
    }

    fun skillName(tool: String, params: JSONObject, appLabel: String?): String {
        val selector = params.optJSONObject("selector")
        val target = selector?.optString("text").orEmpty()
            .ifBlank { selector?.optString("contentDescription").orEmpty() }
            .ifBlank { selector?.optString("resourceId").orEmpty().substringAfterLast('/') }
        return when (tool) {
            "phone.home" -> "Go to Android Home"
            "phone.back" -> "Go back one screen"
            "phone.open_app" -> "Open ${appLabel ?: params.optString("package").ifBlank { "app" }}"
            "phone.click" -> "Open ${target.ifBlank { "known control" }}"
            "phone.long_press" -> "Press and hold ${target.ifBlank { "known control" }}"
            "phone.scroll" -> "Scroll ${params.optString("direction").ifBlank { "screen" }}"
            "phone.wait_for" -> "Wait for ${target.ifBlank { "expected screen" }}"
            "phone.assert" -> "Check ${target.ifBlank { "expected state" }}"
            "phone.type", "phone.replace_text" -> "Fill a known input field"
            else -> tool.removePrefix("phone.").replace('_', ' ').replaceFirstChar(Char::uppercase)
        }
    }

    fun mergeHints(existing: String?, current: String): String = (existing.orEmpty().split('|') + current)
        .map(String::trim).filter(String::isNotBlank).distinct().takeLast(10).joinToString(" | ")

    fun confidence(successCount: Int, failureCount: Int, source: String): Double {
        val evidence = successCount + failureCount
        if (evidence <= 0) return 0.05
        val ratio = successCount.toDouble() / evidence
        val evidenceBoost = successCount.coerceAtMost(5) * 0.055
        val sourceAdjustment = if (source.startsWith("HUMAN")) -0.05 else 0.0
        return (0.50 + ratio * 0.28 + evidenceBoost - failureCount.coerceAtMost(5) * 0.075 + sourceAdjustment).coerceIn(0.05, 0.97)
    }

    fun pathConfidence(successCount: Int, failureCount: Int): Double {
        val total = successCount + failureCount
        if (total <= 0) return 0.05
        val ratio = successCount.toDouble() / total
        return (0.45 + ratio * 0.30 + successCount.coerceAtMost(5) * 0.065 - failureCount.coerceAtMost(5) * 0.08).coerceIn(0.05, 0.98)
    }
}
