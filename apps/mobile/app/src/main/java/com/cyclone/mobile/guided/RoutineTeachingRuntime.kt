package com.cyclone.mobile.guided

import android.content.Context
import android.content.Intent
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.UiSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * V2.9 persistent teaching history.
 *
 * A teaching session is the human-auditable bridge between Follow Me, explicit guided placements,
 * App Learner and Automation Studio. Runtime data is structured JSON; Report.md is the readable
 * Obsidian-compatible mirror. Sensitive typed field values are never accepted by this API.
 */
data class RoutineTeachingStep(
    val id: String = UUID.randomUUID().toString(),
    val index: Int,
    val kind: String,
    val title: String,
    val summary: String,
    val packageName: String? = null,
    val pageTitle: String? = null,
    val pageKey: String? = null,
    val selectorJson: String? = null,
    val advertisedActions: List<String> = emptyList(),
    val semanticSignal: String? = null,
    val replayStrategy: String = "OBSERVE",
    val demonstratedDurationMs: Long? = null,
    val optimizedDurationMs: Long? = null,
    val beforeFingerprint: String? = null,
    val afterFingerprint: String? = null,
    val expectedResult: String? = null,
    val verifier: String? = null,
    val actionSucceeded: Boolean? = null,
    val verificationSucceeded: Boolean? = null,
    val fallbackPathUsed: String? = null,
    val confidence: Double? = null,
    val screenshotPath: String? = null,
    val beforeScreenshotPath: String? = null,
    val afterScreenshotPath: String? = null,
    val uiSnapshotPath: String? = null,
    val beforeUiPath: String? = null,
    val afterUiPath: String? = null,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("index", index)
        .put("kind", kind)
        .put("title", title)
        .put("summary", summary)
        .put("packageName", packageName ?: JSONObject.NULL)
        .put("pageTitle", pageTitle ?: JSONObject.NULL)
        .put("pageKey", pageKey ?: JSONObject.NULL)
        .put("selectorJson", selectorJson ?: JSONObject.NULL)
        .put("advertisedActions", JSONArray(advertisedActions))
        .put("semanticSignal", semanticSignal ?: JSONObject.NULL)
        .put("replayStrategy", replayStrategy)
        .put("demonstratedDurationMs", demonstratedDurationMs ?: JSONObject.NULL)
        .put("optimizedDurationMs", optimizedDurationMs ?: JSONObject.NULL)
        .put("beforeFingerprint", beforeFingerprint ?: JSONObject.NULL)
        .put("afterFingerprint", afterFingerprint ?: JSONObject.NULL)
        .put("expectedResult", expectedResult ?: JSONObject.NULL)
        .put("verifier", verifier ?: JSONObject.NULL)
        .put("actionSucceeded", actionSucceeded ?: JSONObject.NULL)
        .put("verificationSucceeded", verificationSucceeded ?: JSONObject.NULL)
        .put("fallbackPathUsed", fallbackPathUsed ?: JSONObject.NULL)
        .put("confidence", confidence ?: JSONObject.NULL)
        .put("screenshotPath", screenshotPath ?: JSONObject.NULL)
        .put("beforeScreenshotPath", beforeScreenshotPath ?: JSONObject.NULL)
        .put("afterScreenshotPath", afterScreenshotPath ?: JSONObject.NULL)
        .put("uiSnapshotPath", uiSnapshotPath ?: JSONObject.NULL)
        .put("beforeUiPath", beforeUiPath ?: JSONObject.NULL)
        .put("afterUiPath", afterUiPath ?: JSONObject.NULL)
        .put("note", note)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(j: JSONObject): RoutineTeachingStep = RoutineTeachingStep(
            id = j.optString("id").ifBlank { UUID.randomUUID().toString() },
            index = j.optInt("index"),
            kind = j.optString("kind"),
            title = j.optString("title"),
            summary = j.optString("summary"),
            packageName = j.optString("packageName").takeIf { it.isNotBlank() },
            pageTitle = j.optString("pageTitle").takeIf { it.isNotBlank() },
            pageKey = j.optString("pageKey").takeIf { it.isNotBlank() },
            selectorJson = j.optString("selectorJson").takeIf { it.isNotBlank() },
            advertisedActions = j.optJSONArray("advertisedActions")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty(),
            semanticSignal = j.optString("semanticSignal").takeIf { it.isNotBlank() },
            replayStrategy = j.optString("replayStrategy", "OBSERVE"),
            demonstratedDurationMs = j.optLong("demonstratedDurationMs", -1L).takeIf { it >= 0 },
            optimizedDurationMs = j.optLong("optimizedDurationMs", -1L).takeIf { it >= 0 },
            beforeFingerprint = j.optString("beforeFingerprint").takeIf { it.isNotBlank() },
            afterFingerprint = j.optString("afterFingerprint").takeIf { it.isNotBlank() },
            expectedResult = j.optString("expectedResult").takeIf { it.isNotBlank() },
            verifier = j.optString("verifier").takeIf { it.isNotBlank() },
            actionSucceeded = j.optBoolean("actionSucceeded").takeIf { j.has("actionSucceeded") && !j.isNull("actionSucceeded") },
            verificationSucceeded = j.optBoolean("verificationSucceeded").takeIf { j.has("verificationSucceeded") && !j.isNull("verificationSucceeded") },
            fallbackPathUsed = j.optString("fallbackPathUsed").takeIf { it.isNotBlank() },
            confidence = j.optDouble("confidence", Double.NaN).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0),
            screenshotPath = j.optString("screenshotPath").takeIf { it.isNotBlank() },
            beforeScreenshotPath = j.optString("beforeScreenshotPath").takeIf { it.isNotBlank() },
            afterScreenshotPath = j.optString("afterScreenshotPath").takeIf { it.isNotBlank() },
            uiSnapshotPath = j.optString("uiSnapshotPath").takeIf { it.isNotBlank() },
            beforeUiPath = j.optString("beforeUiPath").takeIf { it.isNotBlank() },
            afterUiPath = j.optString("afterUiPath").takeIf { it.isNotBlank() },
            note = j.optString("note"),
            createdAt = j.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}

data class RoutineTeachingSession(
    val id: String,
    val name: String,
    val modelId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: String = "ACTIVE",
    val summary: String = "",
    val appsSeen: Int = 0,
    val pagesSeen: Int = 0,
    val actionsSeen: Int = 0,
    val pathsLearned: Int = 0,
    val copiedAutomationId: String? = null,
    val optimizedAutomationId: String? = null,
    val aiAnalysis: String = "",
    val steps: List<RoutineTeachingStep> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("modelId", modelId)
        .put("startedAt", startedAt)
        .put("endedAt", endedAt ?: JSONObject.NULL)
        .put("status", status)
        .put("summary", summary)
        .put("appsSeen", appsSeen)
        .put("pagesSeen", pagesSeen)
        .put("actionsSeen", actionsSeen)
        .put("pathsLearned", pathsLearned)
        .put("copiedAutomationId", copiedAutomationId ?: JSONObject.NULL)
        .put("optimizedAutomationId", optimizedAutomationId ?: JSONObject.NULL)
        .put("aiAnalysis", aiAnalysis)
        .put("steps", JSONArray().also { a -> steps.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(j: JSONObject): RoutineTeachingSession = RoutineTeachingSession(
            id = j.optString("id"),
            name = j.optString("name", "Routine teaching"),
            modelId = j.optString("modelId"),
            startedAt = j.optLong("startedAt"),
            endedAt = j.optLong("endedAt", -1L).takeIf { it >= 0 },
            status = j.optString("status", "COMPLETE"),
            summary = j.optString("summary"),
            appsSeen = j.optInt("appsSeen"),
            pagesSeen = j.optInt("pagesSeen"),
            actionsSeen = j.optInt("actionsSeen"),
            pathsLearned = j.optInt("pathsLearned"),
            copiedAutomationId = j.optString("copiedAutomationId").takeIf { it.isNotBlank() },
            optimizedAutomationId = j.optString("optimizedAutomationId").takeIf { it.isNotBlank() },
            aiAnalysis = j.optString("aiAnalysis"),
            steps = j.optJSONArray("steps")?.let { array ->
                (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(RoutineTeachingStep::fromJson) }
            }.orEmpty(),
        )
    }
}

object RoutineTeachingRuntime {
    private lateinit var appContext: Context
    private var initialized = false
    @Volatile private var activeId: String? = null
    @Volatile private var lastPageKey: String? = null

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        rootDir().mkdirs()
        initialized = true
    }

    @Synchronized
    fun start(context: Context, name: String, modelId: String): RoutineTeachingSession {
        initialize(context)
        activeId?.let { existing ->
            load(existing)?.takeIf { it.status == "ACTIVE" }?.let {
                save(it.copy(status = "INTERRUPTED", endedAt = System.currentTimeMillis(), summary = "Teaching session interrupted by a new session."))
            }
        }
        val session = RoutineTeachingSession(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Teach my routine" },
            modelId = modelId,
            startedAt = System.currentTimeMillis(),
        )
        activeId = session.id
        lastPageKey = null
        sessionDir(session.id).mkdirs()
        save(session)
        writeReport(session)
        return session
    }

    fun activeSessionId(): String? = activeId
    fun activeSession(): RoutineTeachingSession? = activeId?.let(::load)

    @Synchronized
    fun updateModel(modelId: String) {
        val session = activeSession() ?: return
        save(session.copy(modelId = modelId))
    }

    @Synchronized
    fun recordHumanAction(
        packageName: String,
        label: String,
        kind: String,
        selector: JSONObject,
        advertisedActions: List<String>,
        pageTitle: String?,
        pageKey: String?,
    ) {
        val session = activeSession() ?: return
        val optimized = optimization(kind, advertisedActions)
        val step = RoutineTeachingStep(
            index = session.steps.size + 1,
            kind = kind,
            title = when (kind) {
                "long_click" -> "Held $label"
                "scroll" -> "Scrolled $label"
                else -> "Tapped $label"
            },
            summary = optimized.explanation,
            packageName = packageName,
            pageTitle = pageTitle,
            pageKey = pageKey,
            selectorJson = selector.toString(),
            advertisedActions = advertisedActions,
            semanticSignal = optimized.signal,
            replayStrategy = optimized.strategy,
            optimizedDurationMs = optimized.optimizedDurationMs,
            expectedResult = "A fresh semantic after-state consistent with the demonstrated action",
            verifier = "semantic_after_state",
            actionSucceeded = true,
            fallbackPathUsed = optimized.strategy.takeUnless { it == "OBSERVE" },
            confidence = 0.62,
        )
        append(step, captureCurrent = true)
    }

    @Synchronized
    fun recordPage(snapshot: UiSnapshot, pageKey: String, title: String) {
        val session = activeSession() ?: return
        if (pageKey == lastPageKey) return
        lastPageKey = pageKey
        val index = session.steps.size + 1
        val stepId = UUID.randomUUID().toString()
        val dir = sessionDir(session.id)
        val uiFile = File(dir, "step-%03d-%s-ui.json".format(index, stepId.take(8))).also {
            it.writeText(snapshot.toJson().toString(2))
        }
        val step = RoutineTeachingStep(
            id = stepId,
            index = index,
            kind = "page",
            title = "Page: $title",
            summary = "Cyclone captured one stable semantic page with its visible controls and Android accessibility actions.",
            packageName = snapshot.packageName,
            pageTitle = title,
            pageKey = pageKey,
            replayStrategy = "PAGE_CONTEXT",
            afterFingerprint = snapshot.fingerprint,
            expectedResult = "Semantic page remains observable",
            verifier = "page_fingerprint",
            verificationSucceeded = true,
            confidence = 0.9,
            uiSnapshotPath = uiFile.absolutePath,
        )
        append(step, captureCurrent = true)
    }

    @Synchronized
    fun recordGuidedStep(evidence: GuidedStepEvidence) {
        val session = activeSession() ?: return
        val actions = evidence.targetNode?.actions.orEmpty()
        val optimized = optimization(evidence.kind.name.lowercase(), actions)
        val target = evidence.targetNode
        val label = target?.text?.takeIf(String::isNotBlank)
            ?: target?.contentDescription?.takeIf(String::isNotBlank)
            ?: target?.resourceId?.substringAfterLast('/')?.takeIf(String::isNotBlank)
            ?: evidence.kind.name.lowercase().replace('_', ' ')
        val step = RoutineTeachingStep(
            index = session.steps.size + 1,
            kind = "guided_${evidence.kind.name.lowercase()}",
            title = when (evidence.kind) {
                GuidedActionKind.HOLD -> "Guided hold: $label"
                GuidedActionKind.SWIPE -> "Guided swipe"
                GuidedActionKind.WAIT -> "Guided wait"
                GuidedActionKind.ASSERT -> "Guided check: $label"
                GuidedActionKind.BACK -> "Guided Back"
                GuidedActionKind.HOME -> "Guided Home"
                GuidedActionKind.TAP -> "Guided tap: $label"
            },
            summary = optimized.explanation,
            packageName = evidence.packageName,
            selectorJson = evidence.selector?.let { selectorToJson(it).toString() },
            advertisedActions = actions,
            semanticSignal = optimized.signal,
            replayStrategy = optimized.strategy,
            demonstratedDurationMs = evidence.placement.durationMs,
            optimizedDurationMs = optimized.optimizedDurationMs,
            beforeFingerprint = evidence.beforeFingerprint,
            afterFingerprint = evidence.afterFingerprint,
            expectedResult = "Observed semantic state after the guided ${evidence.kind.name.lowercase()} action",
            verifier = if (evidence.afterFingerprint != null) "page_fingerprint" else "action_result",
            actionSucceeded = true,
            verificationSucceeded = evidence.afterFingerprint != null,
            fallbackPathUsed = optimized.strategy.takeUnless { it == "OBSERVE" },
            confidence = if (evidence.afterFingerprint != null) 0.88 else 0.58,
            beforeScreenshotPath = evidence.beforeScreenshot,
            afterScreenshotPath = evidence.afterScreenshot,
            beforeUiPath = evidence.beforeUi,
            afterUiPath = evidence.afterUi,
        )
        append(step, captureCurrent = false)
    }

    @Synchronized
    fun updateNote(sessionId: String, stepId: String, note: String): RoutineTeachingSession? {
        val session = load(sessionId) ?: return null
        val clean = redactTeachingText(note).trim().take(2_000)
        val updated = session.copy(steps = session.steps.map { if (it.id == stepId) it.copy(note = clean) else it })
        save(updated)
        writeReport(updated)
        return updated
    }

    @Synchronized
    fun finish(
        appsSeen: Int,
        pagesSeen: Int,
        actionsSeen: Int,
        pathsLearned: Int,
        copiedAutomationId: String? = null,
        optimizedAutomationId: String? = null,
        aiAnalysis: String = "",
    ): RoutineTeachingSession? {
        val session = activeSession() ?: return null
        val summary = "Learned $pagesSeen semantic pages, observed $actionsSeen user actions and retained $pathsLearned reusable navigation paths across $appsSeen apps."
        val done = session.copy(
            endedAt = System.currentTimeMillis(),
            status = "COMPLETE",
            summary = summary,
            appsSeen = appsSeen,
            pagesSeen = pagesSeen,
            actionsSeen = actionsSeen,
            pathsLearned = pathsLearned,
            copiedAutomationId = copiedAutomationId,
            optimizedAutomationId = optimizedAutomationId,
            aiAnalysis = redactTeachingText(aiAnalysis).take(6_000),
        )
        save(done)
        writeReport(done)
        activeId = null
        lastPageKey = null
        return done
    }

    @Synchronized
    fun saveAiAnalysis(sessionId: String, analysis: String): RoutineTeachingSession? {
        val session = load(sessionId) ?: return null
        val updated = session.copy(aiAnalysis = redactTeachingText(analysis).trim().take(12_000))
        save(updated)
        writeReport(updated)
        return updated
    }

    fun listSessions(limit: Int = 100): List<RoutineTeachingSession> {
        if (!initialized) return emptyList()
        return rootDir().listFiles().orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .mapNotNull { load(it.name) }
            .sortedByDescending { it.startedAt }
            .take(limit)
            .toList()
    }

    fun load(sessionId: String): RoutineTeachingSession? {
        if (!initialized) return null
        val file = File(sessionDir(sessionId), "session.json")
        if (!file.exists()) return null
        return runCatching { RoutineTeachingSession.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun launchReport(context: Context, sessionId: String?) {
        val intent = Intent(context, RoutineTeachingHistoryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        sessionId?.let { intent.putExtra(RoutineTeachingHistoryActivity.EXTRA_SESSION_ID, it) }
        context.startActivity(intent)
    }

    private data class Optimization(
        val signal: String?,
        val strategy: String,
        val optimizedDurationMs: Long?,
        val explanation: String,
    )

    private fun optimization(kind: String, advertised: List<String>): Optimization {
        val k = kind.lowercase()
        return when {
            (k.contains("hold") || k.contains("long")) && "ACTION_LONG_CLICK" in advertised -> Optimization(
                "ACTION_LONG_CLICK", "SEMANTIC_LONG_CLICK", 0L,
                "Android exposes a native long-click action here. Cyclone should replay the semantic action instead of reproducing the human hold duration.",
            )
            (k.contains("tap") || k.contains("click")) && "ACTION_CLICK" in advertised -> Optimization(
                "ACTION_CLICK", "SEMANTIC_CLICK", 0L,
                "Android exposes a native click action. Cyclone can target the semantic control directly instead of replaying screen coordinates.",
            )
            k.contains("swipe") && "ACTION_SCROLL_FORWARD" in advertised -> Optimization(
                "ACTION_SCROLL_FORWARD", "SEMANTIC_SCROLL", 0L,
                "The demonstrated gesture is backed by a semantic scroll action, so future replay can scroll instantly through Android accessibility rather than copying gesture timing.",
            )
            k.contains("swipe") && "ACTION_SCROLL_BACKWARD" in advertised -> Optimization(
                "ACTION_SCROLL_BACKWARD", "SEMANTIC_SCROLL", 0L,
                "The demonstrated gesture is backed by a semantic scroll action, so future replay can use Android's native scroll signal.",
            )
            k.contains("wait") -> Optimization(null, "CONDITION_OR_DELAY", null,
                "The human wait is evidence. Cyclone should prefer waiting for the learned next-page condition and keep the fixed timer only as a fallback.")
            else -> Optimization(null, "GESTURE_FALLBACK", null,
                "Cyclone stored the demonstrated movement plus the surrounding UI evidence. Future optimization should prefer a semantic selector when one becomes available.")
        }
    }

    private fun append(step: RoutineTeachingStep, captureCurrent: Boolean) {
        val session = activeSession() ?: return
        val updated = session.copy(steps = session.steps + step.copy(index = session.steps.size + 1))
        save(updated)
        writeReport(updated)
        if (captureCurrent) captureEvidenceAsync(updated.id, step.id)
    }

    private fun captureEvidenceAsync(sessionId: String, stepId: String) {
        val service = CycloneAccessibilityService.instance ?: return
        val snapshot = runCatching { service.observe(markFresh = false) }.getOrNull()
        val dir = sessionDir(sessionId)
        val uiPath = snapshot?.let { snap ->
            File(dir, "$stepId-ui.json").also { it.writeText(snap.toJson().toString(2)) }.absolutePath
        }
        service.takeScreenshot(null) { result ->
            val copied = result.getOrNull()?.file?.let { source ->
                runCatching {
                    File(dir, "$stepId.png").also { source.copyTo(it, overwrite = true) }.absolutePath
                }.getOrNull()
            }
            synchronized(this) {
                val session = load(sessionId) ?: return@synchronized
                val updated = session.copy(steps = session.steps.map { step ->
                    if (step.id != stepId) step else step.copy(
                        screenshotPath = copied ?: step.screenshotPath,
                        uiSnapshotPath = uiPath ?: step.uiSnapshotPath,
                        afterFingerprint = snapshot?.fingerprint ?: step.afterFingerprint,
                        verificationSucceeded = snapshot?.fingerprint?.let { it != step.beforeFingerprint }
                            ?: step.verificationSucceeded,
                        confidence = when {
                            snapshot == null -> step.confidence
                            snapshot.fingerprint != step.beforeFingerprint -> maxOf(step.confidence ?: 0.0, 0.78)
                            else -> maxOf(step.confidence ?: 0.0, 0.55)
                        },
                    )
                })
                save(updated)
                writeReport(updated)
            }
        }
    }

    private fun save(session: RoutineTeachingSession) {
        val dir = sessionDir(session.id).also(File::mkdirs)
        val temp = File(dir, "session.json.tmp")
        temp.writeText(session.toJson().toString(2))
        temp.copyTo(File(dir, "session.json"), overwrite = true)
        temp.delete()
    }

    private fun redactTeachingText(value: String): String = value
        .replace(
            Regex("(?i)(password|passcode|passwd|otp|verification.?code|api.?key|token|secret|cvv|pin)\\s*[:=]\\s*[^,;\\s}]+"),
        ) { "${it.groupValues[1]}=[REDACTED]" }
        .replace(Regex("(?i)bearer\\s+[a-z0-9._~+/-]{8,}"), "Bearer [REDACTED]")
        .replace(Regex("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)"), "[PAYMENT_REDACTED]")

    private fun writeReport(session: RoutineTeachingSession) {
        val dir = sessionDir(session.id).also(File::mkdirs)
        val text = buildString {
            appendLine("# ${session.name}")
            appendLine()
            appendLine("- Status: ${session.status}")
            appendLine("- Model: ${session.modelId}")
            appendLine("- Started: ${formatTime(session.startedAt)}")
            session.endedAt?.let { appendLine("- Ended: ${formatTime(it)}") }
            appendLine("- Apps: ${session.appsSeen}")
            appendLine("- Semantic pages: ${session.pagesSeen}")
            appendLine("- User actions: ${session.actionsSeen}")
            appendLine("- Learned paths: ${session.pathsLearned}")
            appendLine()
            if (session.summary.isNotBlank()) {
                appendLine("## What Cyclone learned")
                appendLine(session.summary)
                appendLine()
            }
            if (session.aiAnalysis.isNotBlank()) {
                appendLine("## Selected-model analysis")
                appendLine(session.aiAnalysis)
                appendLine()
            }
            appendLine("## Timeline")
            session.steps.forEach { step ->
                appendLine("### ${step.index}. ${step.title}")
                appendLine(step.summary)
                step.packageName?.let { appendLine("- App: `$it`") }
                step.pageTitle?.let { appendLine("- Page: $it") }
                step.semanticSignal?.let { appendLine("- Native signal: `$it`") }
                appendLine("- Replay: ${step.replayStrategy}")
                if (step.note.isNotBlank()) appendLine("- User correction: ${step.note}")
                appendLine()
            }
        }
        File(dir, "Report.md").writeText(text)

        val mirror = File(appContext.filesDir, "Cyclone Brain/Routine Teachings/${safeName(session.name)}-${session.id.take(8)}")
        mirror.mkdirs()
        File(mirror, "Report.md").writeText(text)
        File(mirror, "session.json").writeText(session.toJson().toString(2))
    }

    private fun selectorToJson(selector: com.cyclone.mobile.automation.Selector): JSONObject = JSONObject().apply {
        selector.resourceId?.let { put("resourceId", it) }
        selector.text?.let { put("text", it) }
        selector.partialText?.let { put("textContains", it) }
        selector.contentDescription?.let { put("contentDescription", it) }
        selector.contentDescriptionContains?.let { put("contentDescriptionContains", it) }
        selector.role?.let { put("role", it) }
        selector.className?.let { put("class", it) }
        selector.fuzzyText?.let { put("fuzzyText", it) }
        selector.requireClickable?.let { put("clickable", it) }
        selector.requireEditable?.let { put("editable", it) }
        selector.requireScrollable?.let { put("scrollable", it) }
        selector.x?.let { put("x", it) }
        selector.y?.let { put("y", it) }
    }

    private fun formatTime(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
    private fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').take(80).ifBlank { "Routine" }
    private fun rootDir(): File = File(appContext.filesDir, "cyclone-teaching-sessions")
    private fun sessionDir(id: String): File = File(rootDir(), id)
}
