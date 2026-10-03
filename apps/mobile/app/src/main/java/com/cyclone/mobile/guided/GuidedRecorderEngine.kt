package com.cyclone.mobile.guided

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.UiSnapshot
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.RecoveryPolicy
import com.cyclone.mobile.automation.Selector
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerType
import com.cyclone.mobile.ai.GuidedWorkflowOptimizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.hypot

enum class GuidedActionKind { TAP, HOLD, SWIPE, WAIT, BACK, HOME, ASSERT }

data class GuidedPlacement(
    val x1: Int? = null,
    val y1: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val durationMs: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        x1?.let { put("x1", it) }; y1?.let { put("y1", it) }
        x2?.let { put("x2", it) }; y2?.let { put("y2", it) }
        durationMs?.let { put("durationMs", it) }
    }
}

data class GuidedStepEvidence(
    val id: String = UUID.randomUUID().toString(),
    val kind: GuidedActionKind,
    val placement: GuidedPlacement,
    val selector: Selector?,
    val targetNode: UiNodeSnapshot?,
    val nearbyNodes: List<UiNodeSnapshot>,
    val beforeScreenshot: String,
    val afterScreenshot: String,
    val beforeUi: String,
    val afterUi: String,
    val beforeFingerprint: String,
    val afterFingerprint: String,
    val packageName: String?,
    val capturedAtMs: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name.lowercase())
        .put("placement", placement.toJson())
        .put("selector", selector?.toJson() ?: JSONObject.NULL)
        .put("target", targetNode?.toJson() ?: JSONObject.NULL)
        .put("nearby", JSONArray().also { array -> nearbyNodes.forEach { array.put(it.toJson()) } })
        .put("beforeScreenshot", beforeScreenshot)
        .put("afterScreenshot", afterScreenshot)
        .put("beforeUi", beforeUi)
        .put("afterUi", afterUi)
        .put("beforeFingerprint", beforeFingerprint)
        .put("afterFingerprint", afterFingerprint)
        .put("package", packageName ?: JSONObject.NULL)
        .put("capturedAtMs", capturedAtMs)
}

private fun Selector.toJson(): JSONObject = JSONObject().apply {
    resourceId?.let { put("resourceId", it) }
    text?.let { put("text", it) }
    partialText?.let { put("textContains", it) }
    contentDescription?.let { put("contentDescription", it) }
    contentDescriptionContains?.let { put("contentDescriptionContains", it) }
    role?.let { put("role", it) }
    className?.let { put("class", it) }
    ancestor?.let { put("ancestorText", it) }
    descendant?.let { put("descendantText", it) }
    fuzzyText?.let { put("fuzzyText", it) }
    minFuzzyScore?.let { put("minFuzzyScore", it) }
    requireClickable?.let { put("clickable", it) }
    requireEditable?.let { put("editable", it) }
    requireScrollable?.let { put("scrollable", it) }
    x?.let { put("x", it) }; y?.let { put("y", it) }
}

/**
 * Records explicit user demonstrations. Every step stores before/after screenshots and full
 * Accessibility snapshots, then compiles to a deterministic Automation Studio workflow.
 * OpenRouter optimization is optional; the raw copied workflow never depends on AI.
 */
class GuidedRecorderEngine(
    private val service: CycloneAccessibilityService,
) {
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private var sessionId: String? = null
    private var sessionName: String = "Guided workflow"
    private var sessionStartedAt: Long = 0L
    private val evidence = mutableListOf<GuidedStepEvidence>()
    private var previousController: DeviceState.Controller = DeviceState.Controller.AGENT

    fun isRecording(): Boolean = synchronized(lock) { sessionId != null }
    fun stepCount(): Int = synchronized(lock) { evidence.size }
    fun currentName(): String = synchronized(lock) { sessionName }

    fun start(name: String) {
        synchronized(lock) {
            evidence.clear()
            sessionId = UUID.randomUUID().toString()
            sessionName = name.trim().ifBlank { "Guided workflow" }
            sessionStartedAt = System.currentTimeMillis()
            previousController = DeviceState.controller
        }
        // Recording is a human-driven mode. Lock normal AI tools so it cannot fight the user.
        DeviceState.setController(DeviceState.Controller.HUMAN)
        persistManifest()
        DeviceState.addLog("Guided recorder started: $sessionName")
    }

    fun rename(name: String) = synchronized(lock) {
        if (name.isNotBlank()) sessionName = name.trim()
        persistManifest()
    }

    fun cancel() {
        synchronized(lock) {
            sessionId = null
            evidence.clear()
        }
        restoreController()
        DeviceState.addLog("Guided recorder cancelled")
    }

    fun undo(): Boolean = synchronized(lock) {
        if (evidence.isEmpty()) false else {
            evidence.removeAt(evidence.lastIndex)
            persistManifest()
            true
        }
    }

    fun recordPlacement(
        kind: GuidedActionKind,
        placement: GuidedPlacement,
        onComplete: (Result<GuidedStepEvidence>) -> Unit,
    ) {
        require(kind in setOf(GuidedActionKind.TAP, GuidedActionKind.HOLD, GuidedActionKind.SWIPE, GuidedActionKind.ASSERT))
        captureStep(kind, placement, onComplete)
    }

    fun recordWait(ms: Long, onComplete: (Result<GuidedStepEvidence>) -> Unit) =
        captureStep(GuidedActionKind.WAIT, GuidedPlacement(durationMs = ms.coerceIn(100, 120_000)), onComplete)

    fun recordSystem(kind: GuidedActionKind, onComplete: (Result<GuidedStepEvidence>) -> Unit) {
        require(kind == GuidedActionKind.BACK || kind == GuidedActionKind.HOME)
        captureStep(kind, GuidedPlacement(), onComplete)
    }

    fun finish(
        name: String,
        optimizeWithAi: Boolean,
        selectedModelId: String?,
        onComplete: (Result<FinishResult>) -> Unit,
    ) {
        val snapshot = synchronized(lock) {
            if (sessionId == null) return onComplete(Result.failure(IllegalStateException("Recorder is not active")))
            sessionName = name.trim().ifBlank { sessionName }
            evidence.toList()
        }
        val deterministic = compileDeterministic(sessionName, snapshot)
        AutomationRuntime.store.saveAutomation(deterministic)
        val manifest = persistManifest(final = true)
        synchronized(lock) { sessionId = null }
        restoreController()

        if (!optimizeWithAi) {
            DeviceState.addLog("Guided workflow saved: ${deterministic.name} (${snapshot.size} steps)")
            onComplete(Result.success(FinishResult(deterministic, null, manifest)))
            return
        }

        background.execute {
            val optimized = GuidedWorkflowOptimizer.optimizeAndSave(
                service.applicationContext,
                manifest,
                selectedModelId,
            ).getOrNull()
            DeviceState.addLog(
                if (optimized != null) "Guided workflow AI optimization saved disabled: ${optimized.name}"
                else "Guided workflow saved; AI optimization unavailable or rejected",
            )
            main.post { onComplete(Result.success(FinishResult(deterministic, optimized, manifest))) }
        }
    }

    data class FinishResult(
        val copiedWorkflow: AutomationDefinition,
        val optimizedProposal: AutomationDefinition?,
        val manifestFile: File,
    )

    private fun captureStep(
        kind: GuidedActionKind,
        placement: GuidedPlacement,
        onComplete: (Result<GuidedStepEvidence>) -> Unit,
    ) {
        val id = synchronized(lock) { sessionId }
            ?: return onComplete(Result.failure(IllegalStateException("Recorder is not active")))
        val dir = sessionDir(id)
        val stepId = "step-${evidence.size + 1}-${System.currentTimeMillis()}"
        val before = runCatching { service.observe(markFresh = false) }
            .getOrElse { return onComplete(Result.failure(it)) }
        val target = targetNode(before, placement)
        val selector = target?.let(::selectorForNode)
            ?: placement.x1?.let { x -> placement.y1?.let { y -> Selector(x = x, y = y) } }
        val nearby = target?.let { node -> nearbyNodes(before, node).take(16) }.orEmpty()
        val beforeUi = File(dir, "$stepId-before-ui.json").also { it.writeText(before.toJson().toString(2)) }

        captureScreenshot(File(dir, "$stepId-before.png")) { beforeShot ->
            beforeShot.onFailure { return@captureScreenshot onComplete(Result.failure(it)) }
            main.post {
                perform(kind, placement)
                val settleMs = when (kind) {
                    GuidedActionKind.WAIT -> placement.durationMs ?: 1_000L
                    GuidedActionKind.ASSERT -> 120L
                    else -> 500L
                }.coerceIn(100L, 120_000L)
                main.postDelayed({
                    val after = runCatching { service.observe(markFresh = false) }
                        .getOrElse { return@postDelayed onComplete(Result.failure(it)) }
                    val afterUi = File(dir, "$stepId-after-ui.json").also { it.writeText(after.toJson().toString(2)) }
                    captureScreenshot(File(dir, "$stepId-after.png")) { afterShot ->
                        afterShot.onFailure { return@captureScreenshot onComplete(Result.failure(it)) }
                        val item = GuidedStepEvidence(
                            id = stepId,
                            kind = kind,
                            placement = placement,
                            selector = selector,
                            targetNode = target,
                            nearbyNodes = nearby,
                            beforeScreenshot = beforeShot.getOrThrow().absolutePath,
                            afterScreenshot = afterShot.getOrThrow().absolutePath,
                            beforeUi = beforeUi.absolutePath,
                            afterUi = afterUi.absolutePath,
                            beforeFingerprint = before.fingerprint,
                            afterFingerprint = after.fingerprint,
                            packageName = before.packageName,
                        )
                        synchronized(lock) { evidence += item }
                        persistManifest()
                        DeviceState.addLog("Guided step ${evidence.size}: ${kind.name.lowercase()}")
                        main.post { onComplete(Result.success(item)) }
                    }
                }, settleMs)
            }
        }
    }

    private fun perform(kind: GuidedActionKind, p: GuidedPlacement) {
        when (kind) {
            GuidedActionKind.TAP -> service.guidedTap(p.x1!!.toFloat(), p.y1!!.toFloat())
            GuidedActionKind.HOLD -> service.guidedLongPress(p.x1!!.toFloat(), p.y1!!.toFloat(), p.durationMs ?: 700L)
            GuidedActionKind.SWIPE -> service.guidedSwipe(
                p.x1!!.toFloat(), p.y1!!.toFloat(), p.x2!!.toFloat(), p.y2!!.toFloat(), p.durationMs ?: 350L,
            )
            GuidedActionKind.BACK -> service.guidedBack()
            GuidedActionKind.HOME -> service.guidedHome()
            GuidedActionKind.WAIT, GuidedActionKind.ASSERT -> Unit
        }
    }

    private fun captureScreenshot(destination: File, callback: (Result<File>) -> Unit) {
        service.takeScreenshot(null) { result ->
            callback(result.mapCatching { artifact ->
                destination.parentFile?.mkdirs()
                artifact.file.copyTo(destination, overwrite = true)
                destination
            })
        }
    }

    private fun targetNode(snapshot: UiSnapshot, placement: GuidedPlacement): UiNodeSnapshot? {
        val x = placement.x1 ?: return null
        val y = placement.y1 ?: return null
        return snapshot.nodes.asSequence()
            .filter { it.visibleToUser && it.enabled && it.bounds.contains(x, y) }
            .sortedWith(
                compareByDescending<UiNodeSnapshot> { it.clickable || it.longClickable || it.editable || it.checkable }
                    .thenBy { (it.bounds.width * it.bounds.height).coerceAtLeast(1) }
                    .thenByDescending { it.depth },
            )
            .firstOrNull()
    }

    private fun selectorForNode(node: UiNodeSnapshot): Selector {
        val hasSemanticAnchor = node.resourceId.isNotBlank() || node.text.isNotBlank() || node.contentDescription.isNotBlank()
        return Selector(
            resourceId = node.resourceId.takeIf { it.isNotBlank() },
            text = node.text.takeIf { it.isNotBlank() && it.length <= 180 },
            contentDescription = node.contentDescription.takeIf { it.isNotBlank() && it.length <= 180 },
            role = node.role.takeIf { it.isNotBlank() },
            className = node.className.takeIf { it.isNotBlank() },
            requireClickable = node.clickable.takeIf { it },
            requireEditable = node.editable.takeIf { it },
            requireScrollable = node.scrollable.takeIf { it },
            x = node.bounds.centerX.toInt().takeUnless { hasSemanticAnchor },
            y = node.bounds.centerY.toInt().takeUnless { hasSemanticAnchor },
        )
    }

    private fun nearbyNodes(snapshot: UiSnapshot, target: UiNodeSnapshot): List<UiNodeSnapshot> = snapshot.nodes
        .asSequence()
        .filter { it.visibleToUser && it.id != target.id && (it.text.isNotBlank() || it.contentDescription.isNotBlank() || it.resourceId.isNotBlank()) }
        .sortedBy {
            hypot(
                (it.bounds.centerX - target.bounds.centerX).toDouble(),
                (it.bounds.centerY - target.bounds.centerY).toDouble(),
            )
        }
        .take(24)
        .toList()

    private fun compileDeterministic(name: String, items: List<GuidedStepEvidence>): AutomationDefinition {
        val steps = items.mapIndexed { index, e ->
            val baseRecovery = RecoveryPolicy(maxRetries = 1)
            when (e.kind) {
                GuidedActionKind.TAP -> if (hasSemanticSelector(e.selector)) {
                    StepDefinition(name = "${index + 1}. Tap ${label(e)}", type = StepType.PHONE_TOOL,
                        parameters = mapOf("tool" to "phone.click"), selector = e.selector, recovery = baseRecovery)
                } else {
                    StepDefinition(name = "${index + 1}. Tap position", type = StepType.PHONE_TOOL,
                        parameters = mapOf("tool" to "phone.tap", "x" to e.placement.x1.toString(), "y" to e.placement.y1.toString()), recovery = baseRecovery)
                }
                GuidedActionKind.HOLD -> StepDefinition(
                    name = "${index + 1}. Hold ${label(e)}",
                    type = StepType.PHONE_TOOL,
                    parameters = buildMap {
                        put("tool", "phone.long_press")
                        put("durationMs", (e.placement.durationMs ?: 700L).toString())
                        if (!hasSemanticSelector(e.selector)) {
                            put("x", e.placement.x1.toString()); put("y", e.placement.y1.toString())
                        }
                    },
                    selector = e.selector.takeIf(::hasSemanticSelector),
                    recovery = baseRecovery,
                )
                GuidedActionKind.SWIPE -> StepDefinition(
                    name = "${index + 1}. Swipe",
                    type = StepType.PHONE_TOOL,
                    parameters = mapOf(
                        "tool" to "phone.swipe",
                        "x1" to e.placement.x1.toString(), "y1" to e.placement.y1.toString(),
                        "x2" to e.placement.x2.toString(), "y2" to e.placement.y2.toString(),
                        "durationMs" to (e.placement.durationMs ?: 350L).toString(),
                    ),
                    recovery = baseRecovery,
                )
                GuidedActionKind.WAIT -> StepDefinition(
                    name = "${index + 1}. Wait ${(e.placement.durationMs ?: 1_000L) / 1000.0}s",
                    type = StepType.DELAY,
                    parameters = mapOf("ms" to (e.placement.durationMs ?: 1_000L).toString()),
                )
                GuidedActionKind.BACK -> StepDefinition(name = "${index + 1}. Back", type = StepType.PHONE_TOOL, parameters = mapOf("tool" to "phone.back"))
                GuidedActionKind.HOME -> StepDefinition(name = "${index + 1}. Home", type = StepType.PHONE_TOOL, parameters = mapOf("tool" to "phone.home"))
                GuidedActionKind.ASSERT -> StepDefinition(
                    name = "${index + 1}. Check ${label(e)}",
                    type = StepType.PHONE_TOOL,
                    parameters = mapOf("tool" to "phone.assert", "type" to "selector_exists"),
                    selector = e.selector,
                    recovery = RecoveryPolicy(maxRetries = 1),
                )
            }
        }
        return AutomationDefinition(
            name = name,
            description = "Taught directly on-device with Cyclone Guided Recorder. ${items.size} demonstrated steps; screenshots and UI evidence retained locally.",
            enabled = true,
            trigger = TriggerDefinition(TriggerType.MANUAL),
            steps = steps,
        )
    }

    private fun hasSemanticSelector(selector: Selector?): Boolean = selector != null && (
        !selector.resourceId.isNullOrBlank() || !selector.text.isNullOrBlank() || !selector.contentDescription.isNullOrBlank()
    )

    private fun label(e: GuidedStepEvidence): String = e.targetNode?.let { node ->
        node.text.takeIf { it.isNotBlank() } ?: node.contentDescription.takeIf { it.isNotBlank() }
        ?: node.resourceId.substringAfterLast('/').takeIf { it.isNotBlank() }
    }?.take(36) ?: "element"

    private fun restoreController() {
        if (previousController == DeviceState.Controller.AGENT) DeviceState.setController(DeviceState.Controller.AGENT)
        else DeviceState.setController(DeviceState.Controller.HUMAN)
    }

    private fun sessionDir(id: String): File = File(service.filesDir, "guided-recordings/$id").apply { mkdirs() }

    private fun persistManifest(final: Boolean = false): File {
        val snapshot = synchronized(lock) {
            val id = sessionId ?: evidence.firstOrNull()?.id?.substringBefore("-step") ?: "last"
            Triple(id, sessionName, evidence.toList())
        }
        val dir = sessionDir(snapshot.first)
        val manifest = File(dir, "manifest.json")
        val root = JSONObject()
            .put("protocol", "cyclone-guided-recording-v1")
            .put("id", snapshot.first)
            .put("name", snapshot.second)
            .put("createdAtMs", sessionStartedAt)
            .put("updatedAtMs", System.currentTimeMillis())
            .put("final", final)
            .put("steps", JSONArray().also { array -> snapshot.third.forEach { array.put(it.toJson()) } })
        manifest.writeText(root.toString(2))
        return manifest
    }
}
