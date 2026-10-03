package com.cyclone.mobile.automation.skill

import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationStore
import com.cyclone.mobile.automation.FailureAction
import com.cyclone.mobile.automation.Selector
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerType
import com.cyclone.mobile.brain.graphv2.InMemoryTemporalGraphStore
import com.cyclone.mobile.brain.graphv2.SkillGraphProjector
import com.cyclone.mobile.brain.graphv2.TemporalGraphStore

/**
 * Compiles a verified 2+ step path into a disabled draft in [AutomationStore].
 *
 * Unverified steps and steps missing after-state do not write. Secrets are stripped
 * to slot names. Duplicate compiles of the same (app, goal, start page) update the
 * existing automation id — they do not mint a second app-graph node.
 *
 * Overlay DONE and MCP `phone_skill_save` call this same function through [SkillDraftSink].
 */
class SkillCompiler(
    private val store: AutomationStore,
    val graph: TemporalGraphStore = InMemoryTemporalGraphStore(),
) {
    fun compile(input: SkillCompileInput): SkillCompileResult {
        if (input.app.isBlank() || input.goal.isBlank() || input.startPageKey.isBlank()) {
            return SkillCompileResult.Rejected("app, goal, and start pageKey are required")
        }
        if (input.steps.size < 2) {
            return SkillCompileResult.Rejected("need 2+ steps to compile a skill")
        }
        val unverified = input.steps.indexOfFirst { !it.verified }
        if (unverified >= 0) {
            return SkillCompileResult.Rejected("unverified step at index $unverified; no durable write")
        }
        val missingAfter = input.steps.indexOfFirst { it.afterPageKey.isNullOrBlank() }
        if (missingAfter >= 0) {
            return SkillCompileResult.Rejected("missing after-state on step $missingAfter; no durable write")
        }

        val safeParams = SkillSecrets.strip(input.params)
        val compiledSteps = input.steps.mapIndexed { index, draft ->
            SkillCompiledStep(
                id = "step-${index + 1}",
                whenClause = draft.whenClause,
                thenClause = draft.thenClause,
                checkClause = draft.checkClause,
                action = draft.action,
                selectors = draft.selectors.sortedByDescending { it.confidence },
                verifiers = draft.verifiers,
                params = SkillSecrets.strip(draft.params),
                beforePageKey = draft.beforePageKey,
                afterPageKey = draft.afterPageKey,
            )
        }
        val traces = input.steps.mapNotNull { it.evidenceTrace?.takeIf(String::isNotBlank) }
        val capsule = SkillCapsule(
            id = stableId(input.app, input.goal, input.startPageKey),
            app = input.app,
            goal = input.goal,
            whenPage = SkillWhen(input.startPageKey, input.preconditions),
            steps = compiledSteps,
            selectors = compiledSteps.flatMap { it.selectors },
            verifiers = compiledSteps.flatMap { it.verifiers },
            params = safeParams,
            evidence = SkillEvidence(traces),
            status = SkillCapsuleStatus.DRAFT,
            enabled = false,
        )

        val existing = store.getAutomation(capsule.id)
        val automation = toDisabledAutomation(capsule, existing)
        store.saveAutomation(automation)
        projectGraph(input, compiledSteps)
        return SkillCompileResult.DraftWritten(capsule)
    }

    fun lowerFailedEdge(packageName: String, fromPageKey: String, toPageKey: String, evidenceId: String, nowEpochMillis: Long) {
        SkillGraphProjector.lowerEdgeOnly(graph, packageName, fromPageKey, toPageKey, evidenceId, nowEpochMillis)
    }

    fun listDrafts(): List<AutomationDefinition> = store.listAutomations().filter(SkillDraftListing::isDraftSkill)

    private fun projectGraph(input: SkillCompileInput, steps: List<SkillCompiledStep>) {
        var previous = input.startPageKey
        steps.forEachIndexed { index, step ->
            val from = step.beforePageKey?.takeIf { it.isNotBlank() } ?: previous
            val to = step.afterPageKey ?: return@forEachIndexed
            SkillGraphProjector.recordVerifiedHop(
                graph = graph,
                packageName = input.app,
                fromPageKey = from,
                toPageKey = to,
                evidenceId = "compile:${stableId(input.app, input.goal, input.startPageKey)}:hop:$index:${input.nowEpochMillis}",
                observedAtEpochMillis = input.nowEpochMillis,
            )
            previous = to
        }
    }

    private fun toDisabledAutomation(capsule: SkillCapsule, existing: AutomationDefinition?): AutomationDefinition {
        val steps = capsule.steps.map { step ->
            val top = step.selectors.firstOrNull()
            StepDefinition(
                id = step.id,
                name = "${step.whenClause} → ${step.thenClause} → ${step.checkClause}",
                type = StepType.PHONE_TOOL,
                parameters = step.params + mapOf(
                    "tool" to step.action,
                    "afterPageKey" to (step.afterPageKey ?: ""),
                ),
                selector = top?.let { selectorFromRanked(it) },
            )
        }
        return AutomationDefinition(
            id = capsule.id,
            name = "Draft skill · ${capsule.goal}",
            description = DESCRIPTION_MARKER +
                " status=${capsule.status.name.lowercase()} app=${capsule.app} pageKey=${capsule.whenPage.pageKey}." +
                " Review it in Automations before it can run alone.",
            enabled = false,
            version = (existing?.version ?: 0) + 1,
            trigger = TriggerDefinition(TriggerType.CYCLONE_REMOTE, mapOf("key" to "skill.draft")),
            steps = steps,
            failureBehavior = FailureAction.ABORT,
        )
    }

    private fun selectorFromRanked(selector: RankedSelector): Selector = when (selector.kind.lowercase()) {
        "resourceid", "resource_id" -> Selector(resourceId = selector.value)
        "contentdescription", "content_description" -> Selector(contentDescription = selector.value)
        "partialtext", "partial_text", "textcontains" -> Selector(partialText = selector.value)
        "role" -> Selector(role = selector.value)
        else -> Selector(text = selector.value)
    }

    companion object {
        const val ID_PREFIX = "skill."
        const val DESCRIPTION_MARKER = "cyclone-skill-capsule-v4"
        const val COMPILE_FUNCTION = "SkillCompiler.compile"

        fun stableId(app: String, goal: String, startPageKey: String): String {
            val slug = listOf(app, goal, startPageKey).joinToString(".") { part ->
                part.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "x" }
            }
            return ID_PREFIX + slug
        }
    }
}
