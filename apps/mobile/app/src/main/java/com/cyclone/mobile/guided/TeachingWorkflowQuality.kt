package com.cyclone.mobile.guided

import org.json.JSONObject
import kotlin.math.roundToInt

enum class WorkflowCompileGate { APPROVED_FOR_REVIEW, NEEDS_REPAIR, REJECTED }

data class TeachingStepQuality(
    val stepId: String,
    val score: Double,
    val selectorScore: Double,
    val pageAnchorScore: Double,
    val verifierScore: Double,
    val evidenceScore: Double,
    val blockers: List<String>,
    val repairs: List<String>,
)

data class TeachingWorkflowQualityReport(
    val score: Double,
    val gate: WorkflowCompileGate,
    val stepReports: List<TeachingStepQuality>,
    val blockers: List<String>,
    val repairs: List<String>,
) {
    val scorePercent: Int get() = (score * 100).roundToInt().coerceIn(0, 100)
}

/**
 * Pure quality gate for the existing Teach -> Automation compiler. It does not execute actions or
 * create a second routine format. Coordinates and unverified transitions remain review evidence,
 * never learned truth.
 */
object TeachingWorkflowQuality {
    private val sensitiveAssignment = Regex(
        "(?i)(password|passcode|passwd|otp|verification.?code|api.?key|token|secret|cvv|pin)\\s*[:=]",
    )

    fun evaluate(session: RoutineTeachingSession): TeachingWorkflowQualityReport {
        val actions = session.steps.filter { it.kind != "page" }
        if (actions.isEmpty()) {
            return TeachingWorkflowQualityReport(
                score = 0.0,
                gate = WorkflowCompileGate.REJECTED,
                stepReports = emptyList(),
                blockers = listOf("No replayable action evidence was recorded"),
                repairs = listOf("Record at least one harmless semantic action"),
            )
        }
        val reports = actions.map(::evaluateStep)
        val sensitive = actions.any(::containsSensitivePlaintext)
        val score = reports.map { it.score }.average().coerceIn(0.0, 1.0)
        val blockers = reports.flatMap { it.blockers }.distinct()
        val repairs = reports.flatMap { it.repairs }.distinct()
        val gate = when {
            sensitive -> WorkflowCompileGate.REJECTED
            reports.any { it.score < 0.35 } || score < 0.55 -> WorkflowCompileGate.NEEDS_REPAIR
            else -> WorkflowCompileGate.APPROVED_FOR_REVIEW
        }
        return TeachingWorkflowQualityReport(
            score = score,
            gate = gate,
            stepReports = reports,
            blockers = if (sensitive) listOf("Sensitive plaintext resembles a credential or verification value") + blockers else blockers,
            repairs = repairs,
        )
    }

    fun repairSelector(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val source = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val repaired = JSONObject()
        // Stable semantic attributes are deliberately ordered ahead of fuzzy/structural fallbacks.
        listOf(
            "resourceId", "contentDescription", "text", "role", "class", "className",
            "relativeToText", "relativeDirection", "textContains", "contentDescriptionContains",
            "clickable", "editable", "scrollable",
        ).forEach { key -> if (source.has(key) && source.opt(key) != null) repaired.put(key, source.opt(key)) }
        val hasSemantic = repaired.keys().asSequence().any { it !in setOf("clickable", "editable", "scrollable") }
        if (!hasSemantic) {
            // Coordinates survive only when there is no semantic alternative and are scored as weak.
            listOf("x", "y").forEach { key -> if (source.has(key)) repaired.put(key, source.opt(key)) }
        }
        return repaired.takeIf { it.length() > 0 }?.toString()
    }

    private fun evaluateStep(step: RoutineTeachingStep): TeachingStepQuality {
        val selector = step.selectorJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val selectorRequired = step.kind !in setOf("guided_back", "guided_home", "guided_wait")
        val selectorScore = when {
            !selectorRequired -> 0.9
            selector == null -> 0.0
            selector.optString("resourceId").isNotBlank() -> 1.0
            selector.optString("contentDescription").isNotBlank() -> 0.88
            selector.optString("text").isNotBlank() -> 0.78
            selector.optString("relativeToText").isNotBlank() -> 0.72
            selector.optString("role").isNotBlank() &&
                (selector.optString("class").isNotBlank() || selector.optString("className").isNotBlank()) -> 0.62
            selector.optString("textContains").isNotBlank() || selector.optString("contentDescriptionContains").isNotBlank() -> 0.55
            selector.has("x") && selector.has("y") -> 0.15
            else -> 0.25
        }
        val pageAnchorScore = when {
            !step.packageName.isNullOrBlank() && !step.pageKey.isNullOrBlank() -> 1.0
            !step.packageName.isNullOrBlank() || !step.pageKey.isNullOrBlank() -> 0.55
            else -> 0.0
        }
        val verifierScore = when {
            step.verificationSucceeded == true && !step.afterFingerprint.isNullOrBlank() -> 1.0
            !step.semanticSignal.isNullOrBlank() && !step.afterFingerprint.isNullOrBlank() -> 0.85
            !step.afterFingerprint.isNullOrBlank() && step.afterFingerprint != step.beforeFingerprint -> 0.75
            !step.afterUiPath.isNullOrBlank() || !step.uiSnapshotPath.isNullOrBlank() -> 0.55
            step.kind in setOf("guided_wait", "guided_assert") -> 0.5
            else -> 0.1
        }
        val evidenceScore = listOfNotNull(
            step.beforeFingerprint?.let { 0.2 },
            step.afterFingerprint?.let { 0.25 },
            step.selectorJson?.let { 0.2 },
            step.pageKey?.let { 0.15 },
            step.demonstratedDurationMs?.let { 0.05 },
            step.actionSucceeded?.let { 0.1 },
            step.confidence?.let { 0.05 },
        ).sum().coerceIn(0.0, 1.0)
        val score = (selectorScore * 0.36 + pageAnchorScore * 0.18 + verifierScore * 0.31 + evidenceScore * 0.15)
            .coerceIn(0.0, 1.0)
        val blockers = buildList {
            if (selectorRequired && selectorScore == 0.0) add("Step ${step.index} has no replayable selector")
            if (step.actionSucceeded == false) add("Step ${step.index} recorded an unsuccessful action")
            if (step.verificationSucceeded == false) add("Step ${step.index} failed post-action verification")
        }
        val repairs = buildList {
            if (selectorRequired && selectorScore < 0.55) add("Repair step ${step.index} with a resource id, label, role, or relative semantic selector")
            if (pageAnchorScore < 0.55) add("Capture app and page identity for step ${step.index}")
            if (verifierScore < 0.55) add("Capture an authoritative after-state verifier for step ${step.index}")
        }
        return TeachingStepQuality(
            stepId = step.id,
            score = score,
            selectorScore = selectorScore,
            pageAnchorScore = pageAnchorScore,
            verifierScore = verifierScore,
            evidenceScore = evidenceScore,
            blockers = blockers,
            repairs = repairs,
        )
    }

    private fun containsSensitivePlaintext(step: RoutineTeachingStep): Boolean =
        sequenceOf(step.summary, step.note, step.selectorJson.orEmpty())
            .any { sensitiveAssignment.containsMatchIn(it) }
}
