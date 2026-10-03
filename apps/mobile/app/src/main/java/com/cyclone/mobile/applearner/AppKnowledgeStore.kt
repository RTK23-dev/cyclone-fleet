package com.cyclone.mobile.applearner

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class AppKnowledgeStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "cyclone_app_knowledge_v1.db",
    null,
    1,
) {
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE apps(
                package_name TEXT PRIMARY KEY,
                label TEXT NOT NULL,
                version_name TEXT,
                version_code INTEGER,
                knowledge_state TEXT NOT NULL,
                confidence REAL NOT NULL,
                last_learned_at INTEGER NOT NULL,
                last_verified_at INTEGER,
                instruction_summary TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE screens(
                id TEXT PRIMARY KEY,
                package_name TEXT NOT NULL,
                identity_name TEXT NOT NULL,
                title TEXT NOT NULL,
                purpose TEXT NOT NULL,
                semantic_fp TEXT NOT NULL,
                structural_fp TEXT NOT NULL,
                anchors_json TEXT NOT NULL,
                class_name TEXT,
                title_hints_json TEXT NOT NULL,
                knowledge_state TEXT NOT NULL,
                confidence REAL NOT NULL,
                app_version TEXT,
                last_seen_at INTEGER NOT NULL,
                last_verified_at INTEGER,
                screenshot_path TEXT,
                dynamic_json TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_screens_package ON screens(package_name)")
        db.execSQL("CREATE INDEX idx_screens_semantic ON screens(package_name, semantic_fp)")
        db.execSQL("""
            CREATE TABLE actions(
                id TEXT PRIMARY KEY,
                package_name TEXT NOT NULL,
                screen_id TEXT NOT NULL,
                semantic_name TEXT NOT NULL,
                label TEXT NOT NULL,
                android_actions_json TEXT NOT NULL,
                selector_json TEXT NOT NULL,
                risk TEXT NOT NULL,
                required_input TEXT,
                knowledge_state TEXT NOT NULL,
                confidence REAL NOT NULL,
                last_success_at INTEGER,
                last_failure_at INTEGER,
                alternatives_json TEXT NOT NULL,
                failure_count INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_actions_screen ON actions(screen_id)")
        db.execSQL("""
            CREATE TABLE transitions(
                id TEXT PRIMARY KEY,
                package_name TEXT NOT NULL,
                from_screen_id TEXT NOT NULL,
                action_id TEXT NOT NULL,
                to_screen_id TEXT NOT NULL,
                knowledge_state TEXT NOT NULL,
                confidence REAL NOT NULL,
                observed_count INTEGER NOT NULL,
                successful_count INTEGER NOT NULL,
                last_observed_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_transitions_from ON transitions(package_name, from_screen_id)")
        db.execSQL("""
            CREATE TABLE sessions(
                id TEXT PRIMARY KEY,
                package_name TEXT NOT NULL,
                mode TEXT NOT NULL,
                instruction TEXT NOT NULL,
                state TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                summary_json TEXT NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun upsertApp(app: LearnedApp) {
        writableDatabase.insertWithOnConflict("apps", null, ContentValues().apply {
            put("package_name", app.packageName)
            put("label", app.label)
            put("version_name", app.versionName)
            app.versionCode?.let { put("version_code", it) } ?: putNull("version_code")
            put("knowledge_state", app.knowledgeState.name)
            put("confidence", app.confidence)
            put("last_learned_at", app.lastLearnedAt)
            app.lastVerifiedAt?.let { put("last_verified_at", it) } ?: putNull("last_verified_at")
            put("instruction_summary", app.instructionSummary)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun upsertScreen(screen: LearnedScreen) {
        writableDatabase.insertWithOnConflict("screens", null, ContentValues().apply {
            put("id", screen.id)
            put("package_name", screen.packageName)
            put("identity_name", screen.identity)
            put("title", screen.title)
            put("purpose", screen.purpose)
            put("semantic_fp", screen.recognition.semanticFingerprint)
            put("structural_fp", screen.recognition.structuralFingerprint)
            put("anchors_json", JSONArray(screen.recognition.stableAnchors).toString())
            put("class_name", screen.recognition.className)
            put("title_hints_json", JSONArray(screen.recognition.titleHints).toString())
            put("knowledge_state", screen.knowledgeState.name)
            put("confidence", screen.confidence)
            put("app_version", screen.appVersion)
            put("last_seen_at", screen.lastSeenAt)
            screen.lastVerifiedAt?.let { put("last_verified_at", it) } ?: putNull("last_verified_at")
            put("screenshot_path", screen.screenshotPath)
            put("dynamic_json", JSONObject(screen.sampleDynamicData).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun upsertAction(action: LearnedAction) {
        val existing = findEquivalentAction(action.screenId, action.semanticName, action.selectorJson)
        val value = if (existing == null) action else action.copy(
            id = existing.id,
            confidence = maxOf(existing.confidence, action.confidence),
            lastSuccessAt = existing.lastSuccessAt,
            lastFailureAt = existing.lastFailureAt,
            alternativeSelectors = (existing.alternativeSelectors + action.alternativeSelectors).distinct().take(6),
            failureCount = existing.failureCount,
        )
        writableDatabase.insertWithOnConflict("actions", null, actionValues(value), SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun upsertTransition(transition: LearnedTransition) {
        val existing = readableDatabase.rawQuery(
            "SELECT * FROM transitions WHERE package_name=? AND from_screen_id=? AND action_id=? AND to_screen_id=? LIMIT 1",
            arrayOf(transition.packageName, transition.fromScreenId, transition.actionId, transition.toScreenId),
        ).use { cursor -> if (cursor.moveToFirst()) transitionFrom(cursor) else null }
        val merged = if (existing == null) transition else transition.copy(
            id = existing.id,
            observedCount = existing.observedCount + 1,
            successfulCount = existing.successfulCount + transition.successfulCount.coerceAtMost(1),
            confidence = ((existing.confidence * existing.observedCount) + transition.confidence) / (existing.observedCount + 1),
            knowledgeState = if (existing.successfulCount + transition.successfulCount >= 2) KnowledgeState.VERIFIED else transition.knowledgeState,
        )
        writableDatabase.insertWithOnConflict("transitions", null, transitionValues(merged), SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun findBestScreenMatch(packageName: String, recognition: ScreenRecognition, threshold: Double = 0.68): Pair<LearnedScreen, Double>? {
        return listScreens(packageName)
            .map { it to ScreenSemanticizer.similarity(it.recognition, recognition) }
            .filter { it.second >= threshold }
            .maxByOrNull { it.second }
    }

    @Synchronized
    fun listApps(): List<LearnedApp> = readableDatabase.rawQuery(
        "SELECT * FROM apps ORDER BY last_learned_at DESC",
        emptyArray(),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(appFrom(cursor)) } }

    @Synchronized
    fun getApp(packageName: String): LearnedApp? = readableDatabase.rawQuery(
        "SELECT * FROM apps WHERE package_name=? LIMIT 1",
        arrayOf(packageName),
    ).use { cursor -> if (cursor.moveToFirst()) appFrom(cursor) else null }

    @Synchronized
    fun listScreens(packageName: String): List<LearnedScreen> = readableDatabase.rawQuery(
        "SELECT * FROM screens WHERE package_name=? ORDER BY last_seen_at ASC",
        arrayOf(packageName),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(screenFrom(cursor)) } }

    @Synchronized
    fun listActions(packageName: String): List<LearnedAction> = readableDatabase.rawQuery(
        "SELECT * FROM actions WHERE package_name=? ORDER BY screen_id, label",
        arrayOf(packageName),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(actionFrom(cursor)) } }

    @Synchronized
    fun listTransitions(packageName: String): List<LearnedTransition> = readableDatabase.rawQuery(
        "SELECT * FROM transitions WHERE package_name=? ORDER BY last_observed_at ASC",
        arrayOf(packageName),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(transitionFrom(cursor)) } }

    @Synchronized
    fun graph(packageName: String): AppGraphSnapshot? {
        val app = getApp(packageName) ?: return null
        return AppGraphSnapshot(app, listScreens(packageName), listActions(packageName), listTransitions(packageName))
    }

    @Synchronized
    fun getAction(id: String): LearnedAction? = readableDatabase.rawQuery(
        "SELECT * FROM actions WHERE id=? LIMIT 1", arrayOf(id),
    ).use { cursor -> if (cursor.moveToFirst()) actionFrom(cursor) else null }

    @Synchronized
    fun markActionSuccess(actionId: String, newSelectorJson: String? = null) {
        val action = getAction(actionId) ?: return
        val selectors = if (newSelectorJson.isNullOrBlank() || newSelectorJson == action.selectorJson) action.alternativeSelectors
        else (listOf(action.selectorJson) + action.alternativeSelectors).distinct().take(6)
        val updated = action.copy(
            selectorJson = newSelectorJson ?: action.selectorJson,
            lastSuccessAt = System.currentTimeMillis(),
            knowledgeState = KnowledgeState.VERIFIED,
            confidence = (action.confidence + 0.08).coerceAtMost(0.99),
            alternativeSelectors = selectors,
        )
        writableDatabase.update("actions", actionValues(updated), "id=?", arrayOf(actionId))
    }

    @Synchronized
    fun markActionFailure(actionId: String) {
        val action = getAction(actionId) ?: return
        val failures = action.failureCount + 1
        val updated = action.copy(
            lastFailureAt = System.currentTimeMillis(),
            failureCount = failures,
            knowledgeState = if (failures >= 2) KnowledgeState.STALE else action.knowledgeState,
            confidence = (action.confidence - 0.10).coerceAtLeast(0.05),
        )
        writableDatabase.update("actions", actionValues(updated), "id=?", arrayOf(actionId))
    }

    @Synchronized
    fun saveSession(id: String, packageName: String, mode: LearningMode, instruction: String, state: LearnerSessionState, startedAt: Long, endedAt: Long?, summary: JSONObject) {
        writableDatabase.insertWithOnConflict("sessions", null, ContentValues().apply {
            put("id", id); put("package_name", packageName); put("mode", mode.name); put("instruction", instruction)
            put("state", state.name); put("started_at", startedAt); endedAt?.let { put("ended_at", it) } ?: putNull("ended_at")
            put("summary_json", summary.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun mirror(packageName: String) {
        graph(packageName)?.let { ObsidianKnowledgeMirror(appContext).write(it) }
    }

    private fun findEquivalentAction(screenId: String, semanticName: String, selector: String): LearnedAction? = readableDatabase.rawQuery(
        "SELECT * FROM actions WHERE screen_id=? AND semantic_name=? AND selector_json=? LIMIT 1",
        arrayOf(screenId, semanticName, selector),
    ).use { cursor -> if (cursor.moveToFirst()) actionFrom(cursor) else null }

    private fun actionValues(action: LearnedAction) = ContentValues().apply {
        put("id", action.id); put("package_name", action.packageName); put("screen_id", action.screenId)
        put("semantic_name", action.semanticName); put("label", action.label); put("android_actions_json", JSONArray(action.androidActions).toString())
        put("selector_json", action.selectorJson); put("risk", action.risk.name); put("required_input", action.requiredInput)
        put("knowledge_state", action.knowledgeState.name); put("confidence", action.confidence)
        action.lastSuccessAt?.let { put("last_success_at", it) } ?: putNull("last_success_at")
        action.lastFailureAt?.let { put("last_failure_at", it) } ?: putNull("last_failure_at")
        put("alternatives_json", JSONArray(action.alternativeSelectors).toString()); put("failure_count", action.failureCount)
    }

    private fun transitionValues(t: LearnedTransition) = ContentValues().apply {
        put("id", t.id); put("package_name", t.packageName); put("from_screen_id", t.fromScreenId); put("action_id", t.actionId)
        put("to_screen_id", t.toScreenId); put("knowledge_state", t.knowledgeState.name); put("confidence", t.confidence)
        put("observed_count", t.observedCount); put("successful_count", t.successfulCount); put("last_observed_at", t.lastObservedAt)
    }

    private fun appFrom(c: Cursor) = LearnedApp(
        packageName = c.string("package_name"), label = c.string("label"), versionName = c.nullableString("version_name"),
        versionCode = c.nullableLong("version_code"), knowledgeState = enumValue(c.string("knowledge_state"), KnowledgeState.DISCOVERED),
        confidence = c.double("confidence"), lastLearnedAt = c.long("last_learned_at"), lastVerifiedAt = c.nullableLong("last_verified_at"),
        instructionSummary = c.string("instruction_summary"),
    )

    private fun screenFrom(c: Cursor) = LearnedScreen(
        id = c.string("id"), packageName = c.string("package_name"), identity = c.string("identity_name"), title = c.string("title"),
        purpose = c.string("purpose"), recognition = ScreenRecognition(
            semanticFingerprint = c.string("semantic_fp"), structuralFingerprint = c.string("structural_fp"),
            stableAnchors = JSONArray(c.string("anchors_json")).strings(), className = c.nullableString("class_name"),
            titleHints = JSONArray(c.string("title_hints_json")).strings(),
        ), knowledgeState = enumValue(c.string("knowledge_state"), KnowledgeState.DISCOVERED), confidence = c.double("confidence"),
        appVersion = c.nullableString("app_version"), lastSeenAt = c.long("last_seen_at"), lastVerifiedAt = c.nullableLong("last_verified_at"),
        screenshotPath = c.nullableString("screenshot_path"), sampleDynamicData = jsonStringMap(c.string("dynamic_json")),
    )

    private fun actionFrom(c: Cursor) = LearnedAction(
        id = c.string("id"), packageName = c.string("package_name"), screenId = c.string("screen_id"), semanticName = c.string("semantic_name"),
        label = c.string("label"), androidActions = JSONArray(c.string("android_actions_json")).strings(), selectorJson = c.string("selector_json"),
        risk = enumValue(c.string("risk"), ActionRisk.UNKNOWN), requiredInput = c.nullableString("required_input"),
        knowledgeState = enumValue(c.string("knowledge_state"), KnowledgeState.DISCOVERED), confidence = c.double("confidence"),
        lastSuccessAt = c.nullableLong("last_success_at"), lastFailureAt = c.nullableLong("last_failure_at"),
        alternativeSelectors = JSONArray(c.string("alternatives_json")).strings(), failureCount = c.int("failure_count"),
    )

    private fun transitionFrom(c: Cursor) = LearnedTransition(
        id = c.string("id"), packageName = c.string("package_name"), fromScreenId = c.string("from_screen_id"), actionId = c.string("action_id"),
        toScreenId = c.string("to_screen_id"), knowledgeState = enumValue(c.string("knowledge_state"), KnowledgeState.DISCOVERED),
        confidence = c.double("confidence"), observedCount = c.int("observed_count"), successfulCount = c.int("successful_count"),
        lastObservedAt = c.long("last_observed_at"),
    )

    private fun Cursor.index(name: String) = getColumnIndexOrThrow(name)
    private fun Cursor.string(name: String) = getString(index(name)).orEmpty()
    private fun Cursor.nullableString(name: String): String? = index(name).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(name: String) = getLong(index(name))
    private fun Cursor.nullableLong(name: String): Long? = index(name).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.int(name: String) = getInt(index(name))
    private fun Cursor.double(name: String) = getDouble(index(name))

    private fun JSONArray.strings(): List<String> = buildList { for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let(::add) }
    private fun jsonStringMap(raw: String): Map<String, String> = runCatching { JSONObject(raw) }.getOrNull()?.let { json ->
        buildMap { json.keys().forEach { key -> json.optString(key).takeIf { it.isNotBlank() }?.let { put(key, it) } } }
    }.orEmpty()

    private inline fun <reified T : Enum<T>> enumValue(raw: String, fallback: T): T = enumValues<T>().firstOrNull { it.name == raw } ?: fallback
}
