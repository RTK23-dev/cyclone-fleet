package com.cyclone.mobile.guided

import android.content.Context
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.FailureAction
import com.cyclone.mobile.automation.RecoveryPolicy
import com.cyclone.mobile.automation.Selector
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerType
import org.json.JSONObject

/**
 * Converts the passive Follow Me timeline into a deterministic, disabled-for-review automation.
 * No model call is needed. Explicit manual-recorder output remains authoritative; this compiler only
 * fills the gap where the user demonstrated a normal sequence without placing guided recorder steps.
 */
object TeachingRoutineCompilerV292 {
    private sealed interface Evidence {
        val at: Long
        data class Step(val value: RoutineTeachingStep) : Evidence { override val at: Long = value.createdAt }
        data class Gesture(val value: TeachingGestureEvidence) : Evidence { override val at: Long = value.timestampMs }
    }

    fun compileAndSave(context: Context, session: RoutineTeachingSession): AutomationDefinition? {
        if (session.copiedAutomationId != null || session.optimizedAutomationId != null) return null
        val quality = TeachingWorkflowQuality.evaluate(session)
        if (quality.gate == WorkflowCompileGate.REJECTED) return null
        AutomationRuntime.initialize(context)
        val gestures = TeachingGestureEvidenceV292.list(context, session.id)
        val merged = buildList<Evidence> {
            session.steps.filter { it.kind != "page" }.forEach { add(Evidence.Step(it)) }
            gestures.forEach { add(Evidence.Gesture(it)) }
        }.sortedBy(Evidence::at)
        if (merged.isEmpty()) return null

        val steps = mutableListOf<StepDefinition>()
        merged.forEach { evidence ->
            when (evidence) {
                is Evidence.Gesture -> {
                    val g = evidence.value
                    steps += StepDefinition(
                        name = "Swipe ${g.direction} to ${g.toTitle.ifBlank { "next page" }}",
                        type = StepType.PHONE_TOOL,
                        parameters = buildMap {
                            put("tool", "phone.swipe")
                            listOf("x1", "y1", "x2", "y2", "durationMs").forEach { key ->
                                if (g.params.has(key)) put(key, g.params.opt(key).toString())
                            }
                        },
                        recovery = RecoveryPolicy(maxRetries = 1, onFailure = FailureAction.REQUEST_AI_HELP),
                    )
                }
                is Evidence.Step -> {
                    val s = evidence.value
                    if (s.kind == "scroll" && gestures.any { kotlin.math.abs(it.timestampMs - s.createdAt) < 1_200L }) return@forEach
                    stepFromTeaching(s)?.let(steps::add)
                }
            }
        }
        if (steps.isEmpty()) return null

        val marker = "[Follow Me session ${session.id}]"
        val existing = AutomationRuntime.store.listAutomations().firstOrNull { marker in it.description }
        val qualitySummary = "Quality ${quality.scorePercent}/100 · ${quality.gate.name}. " +
            if (quality.repairs.isEmpty()) "Semantic selectors and after-state evidence are present."
            else "Repair before enabling: ${quality.repairs.take(3).joinToString("; ")}."
        val automation = AutomationDefinition(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = existing?.name ?: "Learned · ${session.name.removePrefix("Teach ").ifBlank { "Phone routine" }}",
            description = "$marker Consolidated locally from ${steps.size} demonstrated action${if (steps.size == 1) "" else "s"}. $qualitySummary Review before enabling. Cyclone will request AI recovery only if a learned step no longer matches the live UI.",
            enabled = false,
            version = (existing?.version ?: 0) + 1,
            trigger = TriggerDefinition(TriggerType.MANUAL),
            steps = steps,
            failureBehavior = FailureAction.REQUEST_AI_HELP,
        )
        AutomationRuntime.store.saveAutomation(automation)
        return automation
    }

    private fun stepFromTeaching(step: RoutineTeachingStep): StepDefinition? {
        val selector = step.selectorJson?.let(::selectorFromJson)
        val recovery = RecoveryPolicy(maxRetries = 1, onFailure = FailureAction.REQUEST_AI_HELP)
        return when {
            step.kind == "click" -> StepDefinition(
                name = step.title.ifBlank { "Tap learned control" },
                type = StepType.PHONE_TOOL,
                parameters = mapOf("tool" to "phone.click"),
                selector = selector,
                recovery = recovery,
            )
            step.kind == "long_click" -> StepDefinition(
                name = step.title.ifBlank { "Hold learned control" },
                type = StepType.PHONE_TOOL,
                parameters = mapOf("tool" to "phone.long_press", "durationMs" to "650"),
                selector = selector,
                recovery = recovery,
            )
            step.kind.startsWith("guided_") -> guidedStep(step, selector, recovery)
            step.kind == "scroll" && selector != null -> StepDefinition(
                name = step.title.ifBlank { "Scroll" },
                type = StepType.PHONE_TOOL,
                parameters = mapOf("tool" to "phone.scroll", "direction" to "forward"),
                selector = selector,
                recovery = recovery,
            )
            else -> null
        }
    }

    private fun guidedStep(step: RoutineTeachingStep, selector: Selector?, recovery: RecoveryPolicy): StepDefinition? {
        val kind = step.kind.removePrefix("guided_")
        return when (kind) {
            "tap" -> StepDefinition(
                name = step.title,
                type = StepType.PHONE_TOOL,
                parameters = mapOf("tool" to "phone.click"),
                selector = selector,
                recovery = recovery,
            )
            "hold" -> StepDefinition(
                name = step.title,
                type = StepType.PHONE_TOOL,
                parameters = mapOf("tool" to "phone.long_press", "durationMs" to (step.demonstratedDurationMs ?: 650L).toString()),
                selector = selector,
                recovery = recovery,
            )
            "back" -> StepDefinition(name = step.title, type = StepType.PHONE_TOOL, parameters = mapOf("tool" to "phone.back"), recovery = recovery)
            "home" -> StepDefinition(name = step.title, type = StepType.PHONE_TOOL, parameters = mapOf("tool" to "phone.home"), recovery = recovery)
            "wait" -> StepDefinition(name = step.title, type = StepType.DELAY, parameters = mapOf("ms" to (step.demonstratedDurationMs ?: 1000L).toString()))
            "assert" -> StepDefinition(
                name = step.title,
                type = StepType.PHONE_TOOL,
                parameters = mapOf("tool" to "phone.assert", "type" to "selector_exists"),
                selector = selector,
                recovery = recovery,
            )
            else -> null
        }
    }

    private fun selectorFromJson(raw: String): Selector {
        val repaired = TeachingWorkflowQuality.repairSelector(raw) ?: raw
        val json = runCatching { JSONObject(repaired) }.getOrElse { JSONObject() }
        return Selector(
            resourceId = json.optString("resourceId").takeIf(String::isNotBlank),
            text = json.optString("text").takeIf(String::isNotBlank),
            partialText = json.optString("textContains").takeIf(String::isNotBlank),
            contentDescription = json.optString("contentDescription").takeIf(String::isNotBlank),
            contentDescriptionContains = json.optString("contentDescriptionContains").takeIf(String::isNotBlank),
            role = json.optString("role").takeIf(String::isNotBlank),
            className = json.optString("class").takeIf(String::isNotBlank),
            x = json.optInt("x").takeIf { json.has("x") },
            y = json.optInt("y").takeIf { json.has("y") },
            requireClickable = json.optBoolean("clickable").takeIf { json.has("clickable") },
            requireEditable = json.optBoolean("editable").takeIf { json.has("editable") },
            requireScrollable = json.optBoolean("scrollable").takeIf { json.has("scrollable") },
        )
    }
}
