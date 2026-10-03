package com.cyclone.mobile.applearner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cyclone.mobile.AccessibilityRoles
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * V2.8 changes the learning unit from Accessibility event/screenshot to a stable semantic page.
 * Dynamic content is deliberately removed from page identity so a clock, counter, order number,
 * feed item, battery percentage, etc. does not create a new Screen every time it changes.
 */
data class PageControl(
    val key: String,
    val label: String,
    val semanticName: String,
    val role: String,
    val selector: JSONObject,
    val androidActions: List<String>,
    val risk: ActionRisk,
    val expectedEffect: String? = null,
    val confidence: Double = 0.60,
)

data class PageContext(
    val pageKey: String,
    val packageName: String,
    val className: String?,
    val title: String,
    val structuralKey: String,
    val contentKey: String,
    val controls: List<PageControl>,
    val observationCount: Int,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val previewPath: String? = null,
    val isNew: Boolean = false,
    // Transient capture provenance; the learned page store does not persist this as current state.
    val observation: com.cyclone.mobile.runtime.session.ObservationIdentity? = null,
) {
    fun toAgentJson(goal: String? = null, maxControls: Int = 36): JSONObject = JSONObject()
        .put("protocol", "cyclone-page-context-v28")
        .put("observation", observation?.toJson() ?: JSONObject.NULL)
        .put("pageKey", pageKey)
        .put("package", packageName)
        .put("class", className ?: JSONObject.NULL)
        .put("title", title)
        .put("goal", goal ?: JSONObject.NULL)
        .put("observationCount", observationCount)
        .put("isNewPage", isNew)
        .put("controls", JSONArray().also { array ->
            controls.take(maxControls).forEach { control ->
                array.put(JSONObject()
                    .put("id", control.key)
                    .put("label", control.label)
                    .put("semanticName", control.semanticName)
                    .put("role", control.role)
                    .put("selector", control.selector)
                    .put("androidActions", JSONArray(control.androidActions))
                    .put("risk", control.risk.name)
                    .put("expectedEffect", control.expectedEffect ?: JSONObject.NULL)
                    .put("confidence", control.confidence))
            }
        })
}

object PageAwarenessRuntime {
    @Volatile private var initialized = false
    lateinit var store: PageAwarenessStore
        private set

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        store = PageAwarenessStore(context.applicationContext)
        initialized = true
    }

    fun capture(context: Context, snapshot: JSONObject, previewPath: String? = null): PageContext {
        initialize(context)
        return store.capture(snapshot, previewPath)
    }

    fun current(context: Context, snapshot: JSONObject): PageContext = capture(context, snapshot, null)

    fun recordTransition(
        context: Context,
        from: PageContext,
        action: PageControl?,
        rawTool: String,
        rawParams: JSONObject,
        to: PageContext,
        success: Boolean,
    ) {
        initialize(context)
        store.recordTransition(from, action, rawTool, rawParams, to, success)
    }
}

class PageAwarenessStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "cyclone_page_awareness_v28.db",
    null,
    1,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE pages(
              page_key TEXT PRIMARY KEY,
              package_name TEXT NOT NULL,
              class_name TEXT,
              title TEXT NOT NULL,
              structural_key TEXT NOT NULL,
              content_key TEXT NOT NULL,
              controls_json TEXT NOT NULL,
              observation_count INTEGER NOT NULL,
              first_seen_at INTEGER NOT NULL,
              last_seen_at INTEGER NOT NULL,
              preview_path TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX pages_package_seen ON pages(package_name, last_seen_at DESC)")
        db.execSQL(
            """
            CREATE TABLE page_transitions(
              id TEXT PRIMARY KEY,
              from_page_key TEXT NOT NULL,
              action_key TEXT,
              tool TEXT NOT NULL,
              params_json TEXT NOT NULL,
              to_page_key TEXT NOT NULL,
              success_count INTEGER NOT NULL,
              failure_count INTEGER NOT NULL,
              confidence REAL NOT NULL,
              last_seen_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX page_transition_from ON page_transitions(from_page_key, action_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun capture(snapshot: JSONObject, previewPath: String? = null): PageContext {
        val derived = PageSignatureEngine.fromSnapshot(snapshot)
        val existing = get(derived.pageKey)
        val now = System.currentTimeMillis()
        val mergedPreview = existing?.previewPath ?: previewPath
        val count = (existing?.observationCount ?: 0) + 1
        val first = existing?.firstSeenAt ?: now
        val controls = mergeControls(existing?.controls.orEmpty(), derived.controls)
        val page = derived.copy(
            controls = controls,
            observationCount = count,
            firstSeenAt = first,
            lastSeenAt = now,
            previewPath = mergedPreview,
            isNew = existing == null,
        )
        writableDatabase.insertWithOnConflict(
            "pages",
            null,
            ContentValues().apply {
                put("page_key", page.pageKey)
                put("package_name", page.packageName)
                put("class_name", page.className)
                put("title", page.title)
                put("structural_key", page.structuralKey)
                put("content_key", page.contentKey)
                put("controls_json", controlsJson(page.controls).toString())
                put("observation_count", page.observationCount)
                put("first_seen_at", page.firstSeenAt)
                put("last_seen_at", page.lastSeenAt)
                put("preview_path", page.previewPath)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        return page
    }

    @Synchronized
    fun attachPreview(pageKey: String, path: String) {
        if (path.isBlank()) return
        writableDatabase.update(
            "pages",
            ContentValues().apply { put("preview_path", path) },
            "page_key=? AND (preview_path IS NULL OR preview_path='')",
            arrayOf(pageKey),
        )
    }

    @Synchronized
    fun get(pageKey: String): PageContext? = readableDatabase.rawQuery(
        "SELECT * FROM pages WHERE page_key=? LIMIT 1",
        arrayOf(pageKey),
    ).use { c ->
        if (!c.moveToFirst()) return null
        pageFromCursor(c, isNew = false)
    }

    @Synchronized
    fun listPages(packageName: String, limit: Int = 200): List<PageContext> = readableDatabase.rawQuery(
        "SELECT * FROM pages WHERE package_name=? ORDER BY last_seen_at DESC LIMIT ?",
        arrayOf(packageName, limit.coerceIn(1, 1000).toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(pageFromCursor(c, false)) } }

    @Synchronized
    fun recordTransition(
        from: PageContext,
        action: PageControl?,
        rawTool: String,
        rawParams: JSONObject,
        to: PageContext,
        success: Boolean,
    ) {
        val actionKey = action?.key ?: PageSignatureEngine.actionKey(rawTool, rawParams)
        val id = sha256("${from.pageKey}|$actionKey|${to.pageKey}").take(28)
        val existing = readableDatabase.rawQuery(
            "SELECT success_count,failure_count FROM page_transitions WHERE id=? LIMIT 1",
            arrayOf(id),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getInt(1) else 0 to 0 }
        val successes = existing.first + if (success) 1 else 0
        val failures = existing.second + if (success) 0 else 1
        val evidence = successes + failures
        val confidence = if (evidence == 0) 0.5 else ((successes + 1.0) / (evidence + 2.0)).coerceIn(.05, .98)
        val safeParams = PageSignatureEngine.safeParams(rawTool, rawParams)
        writableDatabase.insertWithOnConflict(
            "page_transitions",
            null,
            ContentValues().apply {
                put("id", id)
                put("from_page_key", from.pageKey)
                put("action_key", actionKey)
                put("tool", rawTool)
                put("params_json", safeParams.toString())
                put("to_page_key", to.pageKey)
                put("success_count", successes)
                put("failure_count", failures)
                put("confidence", confidence)
                put("last_seen_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun transitionHints(pageKey: String, limit: Int = 30): JSONArray = readableDatabase.rawQuery(
        "SELECT action_key,tool,to_page_key,success_count,failure_count,confidence FROM page_transitions WHERE from_page_key=? ORDER BY confidence DESC,last_seen_at DESC LIMIT ?",
        arrayOf(pageKey, limit.coerceIn(1, 100).toString()),
    ).use { c ->
        JSONArray().also { out ->
            while (c.moveToNext()) {
                out.put(JSONObject()
                    .put("actionKey", c.getString(0))
                    .put("tool", c.getString(1))
                    .put("toPageKey", c.getString(2))
                    .put("successes", c.getInt(3))
                    .put("failures", c.getInt(4))
                    .put("confidence", c.getDouble(5)))
            }
        }
    }

    private fun pageFromCursor(c: android.database.Cursor, isNew: Boolean): PageContext = PageContext(
        pageKey = c.getString(c.getColumnIndexOrThrow("page_key")),
        packageName = c.getString(c.getColumnIndexOrThrow("package_name")),
        className = c.getColumnIndexOrThrow("class_name").let { if (c.isNull(it)) null else c.getString(it) },
        title = c.getString(c.getColumnIndexOrThrow("title")),
        structuralKey = c.getString(c.getColumnIndexOrThrow("structural_key")),
        contentKey = c.getString(c.getColumnIndexOrThrow("content_key")),
        controls = parseControls(c.getString(c.getColumnIndexOrThrow("controls_json"))),
        observationCount = c.getInt(c.getColumnIndexOrThrow("observation_count")),
        firstSeenAt = c.getLong(c.getColumnIndexOrThrow("first_seen_at")),
        lastSeenAt = c.getLong(c.getColumnIndexOrThrow("last_seen_at")),
        previewPath = c.getColumnIndexOrThrow("preview_path").let { if (c.isNull(it)) null else c.getString(it) },
        isNew = isNew,
    )

    private fun mergeControls(old: List<PageControl>, fresh: List<PageControl>): List<PageControl> {
        val byKey = LinkedHashMap<String, PageControl>()
        old.forEach { byKey[it.key] = it }
        fresh.forEach { control ->
            val previous = byKey[control.key]
            byKey[control.key] = if (previous == null) control else control.copy(
                expectedEffect = previous.expectedEffect ?: control.expectedEffect,
                confidence = maxOf(previous.confidence, control.confidence),
            )
        }
        return byKey.values.take(80)
    }

    private fun controlsJson(controls: List<PageControl>): JSONArray = JSONArray().also { arr ->
        controls.forEach { c -> arr.put(JSONObject()
            .put("key", c.key)
            .put("label", c.label)
            .put("semanticName", c.semanticName)
            .put("role", c.role)
            .put("selector", c.selector)
            .put("androidActions", JSONArray(c.androidActions))
            .put("risk", c.risk.name)
            .put("expectedEffect", c.expectedEffect ?: JSONObject.NULL)
            .put("confidence", c.confidence)) }
    }

    private fun parseControls(raw: String): List<PageControl> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val j = array.optJSONObject(i) ?: continue
                add(PageControl(
                    key = j.optString("key"),
                    label = j.optString("label"),
                    semanticName = j.optString("semanticName"),
                    role = j.optString("role"),
                    selector = j.optJSONObject("selector") ?: JSONObject(),
                    androidActions = (j.optJSONArray("androidActions") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } },
                    risk = runCatching { ActionRisk.valueOf(j.optString("risk")) }.getOrDefault(ActionRisk.UNKNOWN),
                    expectedEffect = j.optString("expectedEffect").takeIf { it.isNotBlank() && it != "null" },
                    confidence = j.optDouble("confidence", .60),
                ))
            }
        }
    }.getOrDefault(emptyList())
}

object PageSignatureEngine {
    private val dynamicNumber = Regex("(?<![A-Za-z])[-+]?\\d[\\d.,:/%-]*(?![A-Za-z])")
    private val longId = Regex("\\b[A-Za-z0-9_-]{9,}\\b")
    private val whitespace = Regex("\\s+")

    fun fromSnapshot(snapshot: JSONObject): PageContext {
        val packageName = snapshot.optString("package").ifBlank { "unknown" }
        val className = snapshot.optString("class").takeIf { it.isNotBlank() }
        val nodes = snapshot.optJSONArray("nodes") ?: JSONArray()
        val structuralParts = mutableListOf<String>()
        val contentParts = mutableListOf<String>()
        val controls = mutableListOf<PageControl>()
        val titleCandidates = mutableListOf<String>()
        var selectedTab = ""
        var paneTitle = ""
        var firstHeading = ""

        for (i in 0 until nodes.length()) {
            val probe = nodes.optJSONObject(i) ?: continue
            if (!probe.optBoolean("visibleToUser", true)) continue
            val probeRole = probe.optString("role").lowercase(Locale.US)
            val probeText = probe.optString("text").trim().ifBlank { probe.optString("contentDescription").trim() }
            val probeResource = probe.optString("resourceId").substringAfterLast('/').lowercase(Locale.US)
            if (selectedTab.isBlank() && probeRole == "tab" && (probe.optBoolean("selected") || probe.optBoolean("checked"))) {
                selectedTab = probeText
            }
            if (paneTitle.isBlank() && probeResource in setOf("action_bar_title", "toolbar_title", "panetitle", "pane_title", "title")) {
                paneTitle = probeText
            }
            if (firstHeading.isBlank() && probeText.length in 2..80 && probe.optInt("depth", 99) <= 4 &&
                probeRole in setOf("text", "generic", "") && !probe.optBoolean("clickable")
            ) {
                firstHeading = probeText
            }
        }

        for (i in 0 until minOf(nodes.length(), 450)) {
            val node = nodes.optJSONObject(i) ?: continue
            val resource = node.optString("resourceId").substringAfterLast('/').trim()
            val role = node.optString("role").ifBlank { node.optString("class").substringAfterLast('.') }.lowercase(Locale.US)
            val text = node.optString("text").trim()
            val description = node.optString("contentDescription").trim()
            val path = node.optString("path")
            val interactive = node.optBoolean("clickable") || node.optBoolean("editable") || node.optBoolean("scrollable") ||
                node.optBoolean("longClickable") || node.optBoolean("checkable") || role in setOf("button", "tab", "switch", "checkbox", "edit_text", "textbox")
            val bounds = node.optJSONObject("bounds")
            val boundsWidth = if (bounds == null) 0 else bounds.optInt("right") - bounds.optInt("left")
            val boundsHeight = if (bounds == null) 0 else bounds.optInt("bottom") - bounds.optInt("top")
            if (!AccessibilityRoles.isPublishedInteractive(
                    node.optBoolean("visibleToUser", true), interactive, boundsWidth, boundsHeight,
                )
            ) continue
            val ownLabel = text.ifBlank { description }.ifBlank { resource.replace('_', ' ') }.trim()
            val inheritedLabel = if (interactive && ownLabel.isBlank()) descendantLabel(node, nodes) else ""
            val label = ownLabel.ifBlank { inheritedLabel }
            val stableLabel = normalizeLabel(label)

            // Structure favours IDs/roles/path. Dynamic visible values only contribute normalized tokens.
            if (resource.isNotBlank() || interactive) {
                structuralParts += listOf(resource.lowercase(Locale.US), role, path, stableLabel.take(72)).joinToString("|")
            }
            if (stableLabel.isNotBlank()) contentParts += stableLabel.take(100)
            if (!interactive && text.length in 2..80 && node.optInt("depth", 99) <= 5) titleCandidates += text

            if (interactive && label.isNotBlank()) {
                val selector = JSONObject().apply {
                    node.optString("resourceId").takeIf { it.isNotBlank() }?.let { put("resourceId", it) }
                    text.takeIf { it.isNotBlank() && !ActionSafetyPolicy.looksSensitiveField(node) }?.let { put("text", it.take(160)) }
                    description.takeIf { it.isNotBlank() && !ActionSafetyPolicy.looksSensitiveField(node) }?.let { put("contentDescription", it.take(160)) }
                    inheritedLabel.takeIf { it.isNotBlank() }?.let { put("descendantText", it.take(160)) }
                    role.takeIf { it.isNotBlank() }?.let { put("role", it) }
                    if (node.optBoolean("clickable")) put("clickable", true)
                    if (node.optBoolean("editable")) put("editable", true)
                    if (node.optBoolean("scrollable")) put("scrollable", true)
                }
                val actions = node.optJSONArray("actions")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter(String::isNotBlank) }.orEmpty()
                val semantic = semanticName(label, role)
                val key = sha256("$resource|$role|$stableLabel|$path|${node.optString("id")}").take(18)
                controls += PageControl(
                    key = key,
                    label = label.take(140),
                    semanticName = semantic,
                    role = role,
                    selector = selector,
                    androidActions = actions,
                    risk = ActionSafetyPolicy.classify(label, resource, description),
                    confidence = if (resource.isNotBlank()) .82 else if (text.isNotBlank() || description.isNotBlank()) .72 else .58,
                )
            }
        }

        val compactStructure = structuralParts.distinct().take(180).joinToString("\n")
        val identityMix = listOf(selectedTab, paneTitle, firstHeading).filter { it.isNotBlank() }.joinToString("|")
        val structuralKey = sha256("$packageName|${className.orEmpty()}|$identityMix|$compactStructure").take(28)
        val contentKey = sha256(contentParts.distinct().take(120).joinToString("|")).take(20)
        val pageKey = "$packageName:${className?.substringAfterLast('.') ?: "page"}:$structuralKey"
        val title = paneTitle.takeIf { it.isNotBlank() }
            ?: selectedTab.takeIf { it.isNotBlank() }
            ?: firstHeading.takeIf { it.isNotBlank() }
            ?: titleCandidates.firstOrNull { normalizeLabel(it).isNotBlank() }
            ?.take(80)
            ?: controls.firstOrNull()?.label?.take(80)
            ?: className?.substringAfterLast('.')
            ?: packageName.substringAfterLast('.')

        val now = System.currentTimeMillis()
        return PageContext(
            pageKey = pageKey,
            packageName = packageName,
            className = className,
            title = title,
            structuralKey = structuralKey,
            contentKey = contentKey,
            controls = controls.distinctBy { it.key }.take(80),
            observationCount = 1,
            firstSeenAt = now,
            lastSeenAt = now,
            isNew = true,
        )
    }

    fun normalizeLabel(value: String): String {
        val lower = value.lowercase(Locale.US)
            .replace(dynamicNumber, " # ")
            .replace(longId) { token -> if (token.value.any(Char::isDigit)) " <id> " else token.value }
            .replace(Regex("[€$£¥]\\s*#"), " <amount> ")
            .replace(Regex("\\b(today|yesterday|tomorrow|just now|seconds?|minutes?|hours?|days?)\\b"), " <time> ")
            .replace(Regex("[^a-z0-9_<># ]+"), " ")
            .replace(whitespace, " ")
            .trim()
        return lower.take(140)
    }

    /** Promote the visible label inside an unlabeled actionable container (common in Settings). */
    private fun descendantLabel(parent: JSONObject, nodes: JSONArray): String {
        val parentPath = parent.optString("path").trimEnd('/')
        if (parentPath.isBlank()) return ""
        val prefix = "$parentPath/"
        for (index in 0 until nodes.length()) {
            val candidate = nodes.optJSONObject(index) ?: continue
            if (!candidate.optBoolean("visibleToUser", true)) continue
            val candidatePath = candidate.optString("path")
            if (!candidatePath.startsWith(prefix)) continue
            val label = candidate.optString("text").trim()
                .ifBlank { candidate.optString("contentDescription").trim() }
                .ifBlank { candidate.optString("resourceId").substringAfterLast('/').replace('_', ' ').trim() }
            if (label.isNotBlank() && label != "<redacted>") return label.take(160)
        }
        return ""
    }

    fun semanticName(label: String, role: String): String {
        val base = normalizeLabel(label)
            .replace("<id>", "item")
            .replace("#", "item")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(whitespace, " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() }
            .take(5)
            .joinToString("_")
        return listOf(role.takeIf { it in setOf("tab", "switch", "checkbox", "textbox", "edit_text") }, base.ifBlank { "control" })
            .filterNotNull().joinToString("_").take(80)
    }

    fun actionKey(tool: String, params: JSONObject): String {
        val selector = params.optJSONObject("selector") ?: params
        val target = listOf(
            selector.optString("resourceId"),
            selector.optString("text"),
            selector.optString("contentDescription"),
            selector.optString("role"),
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        return sha256("$tool|${normalizeLabel(target)}").take(18)
    }

    fun safeParams(tool: String, params: JSONObject): JSONObject {
        if (tool == "phone.type" || tool == "phone.replace_text" || tool == "phone.set_clipboard") {
            return JSONObject().put("redacted", true)
        }
        val safe = JSONObject(params.toString())
        listOf("text", "value", "clipboard", "pngBase64", "token", "password", "apiKey").forEach(safe::remove)
        return safe
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
