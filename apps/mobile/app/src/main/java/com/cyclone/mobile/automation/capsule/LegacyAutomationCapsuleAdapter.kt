package com.cyclone.mobile.automation.capsule

import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.FailureAction
import com.cyclone.mobile.automation.Selector
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.platform.capability.CapabilityId

sealed interface RoutineMigrationResult {
    data class Ready(
        val capsule: CycloneRoutineCapsule,
        val warnings: List<String> = emptyList(),
    ) : RoutineMigrationResult

    data class Blocked(val reasons: List<String>) : RoutineMigrationResult {
        init { require(reasons.isNotEmpty()) }
    }
}

/**
 * A deliberately narrow migration boundary for the existing AutomationDefinition model.
 * It performs no save, delete, policy decision, or execution. Unsupported behavior is blocked
 * instead of being guessed into a runnable capsule.
 */
object LegacyAutomationCapsuleAdapter {
    fun migrate(
        source: AutomationDefinition,
        createdAtEpochMillis: Long,
        author: String,
    ): RoutineMigrationResult {
        require(createdAtEpochMillis >= 0)
        require(author.isNotBlank())
        val reasons = mutableListOf<String>()
        if (source.id.isBlank()) reasons += "Legacy automation needs a source id"
        if (source.name.isBlank() && source.description.isBlank()) reasons += "Legacy automation needs a readable intent"
        if (source.variables.map { it.name }.distinct().size != source.variables.size) {
            reasons += "Legacy automation has duplicate variable names"
        }
        if (source.steps.size > MAXIMUM_MIGRATED_STEPS) {
            reasons += "Legacy automation exceeds the bounded capsule step limit"
        }
        val inputs = source.variables.mapNotNull { variable ->
            if (!FIELD_NAME.matches(variable.name)) {
                reasons += "Variable '${variable.name}' cannot be represented as a capsule input"
                null
            } else {
                RoutineInput(
                    name = variable.name,
                    type = if (variable.secret) RoutineInputType.SECRET_REFERENCE else RoutineInputType.TEXT,
                    required = variable.defaultValue == null || variable.secret,
                    sensitive = variable.secret,
                    description = if (variable.secret) "Secret reference required by the migrated routine" else "Input used by the migrated routine",
                )
            }
        }
        val inputsByName = inputs.associateBy { it.name }
        val steps = source.steps.mapIndexedNotNull { index, sourceStep ->
            adaptStep(index, sourceStep, inputsByName, reasons)
        }
        if (source.conditions.isNotEmpty()) reasons += "Top-level legacy conditions require explicit review"
        if (source.steps.isEmpty()) reasons += "Legacy automation has no steps"
        if (reasons.isNotEmpty()) return RoutineMigrationResult.Blocked(reasons.distinct().sorted())

        val linkedSteps = steps.mapIndexed { index, step ->
            step.copy(nextStepIds = steps.getOrNull(index + 1)?.let { listOf(it.id) }.orEmpty())
        }
        val actionSteps = linkedSteps.filter { it.action != null }
        val capabilities = actionSteps.map { requireNotNull(it.action).capabilityId }.toSet()
        val verifications = actionSteps.mapIndexed { index, step ->
            RoutineVerification(
                id = requireNotNull(step.verificationIds.singleOrNull()),
                kind = VerificationKind.ACTION_RESULT_OK,
                expectedReference = "typed-action:${requireNotNull(step.action).operation}:$index",
            )
        }
        return RoutineMigrationResult.Ready(
            CycloneRoutineCapsule(
                schemaVersion = 1,
                routineId = RoutineId("legacy.${routineIdSegment(source.id)}"),
                routineVersion = RoutineVersion(source.version.coerceAtLeast(0), 0, 0),
                intent = source.description.ifBlank { source.name },
                inputs = inputs,
                requiredCapabilities = capabilities,
                requiredPackages = source.steps.mapNotNull { it.parameters["package"] }
                    .filter(PACKAGE_NAME::matches)
                    .toSet(),
                graph = RoutineGraph(
                    entryStepId = linkedSteps.first().id,
                    steps = linkedSteps,
                    maximumTransitions = linkedSteps.size.coerceAtLeast(1) * 11,
                ),
                verification = verifications,
                policyRequirements = capabilities.map { capability ->
                    RoutinePolicyRequirement(
                        capabilityId = capability,
                        policyCategory = "routine",
                        confirmation = RoutineConfirmation.WHEN_POLICY_REQUIRES,
                    )
                },
                provenance = RoutineProvenance(
                    sourceType = "legacy.automation",
                    sourceId = source.id,
                    createdAtEpochMillis = createdAtEpochMillis,
                    author = author,
                ),
                compatibility = RoutineCompatibility(1, 2),
            ),
            warnings = buildList {
                if (!source.enabled) add("The source automation is disabled; migration does not enable it")
                if (source.verification.isNotEmpty()) add("Legacy verification was replaced by typed action-result evidence")
            }.sorted(),
        )
    }

    private fun adaptStep(
        index: Int,
        source: StepDefinition,
        inputs: Map<String, RoutineInput>,
        reasons: MutableList<String>,
    ): RoutineStep? {
        val stepId = RoutineStepId("legacyStep$index")
        if (source.name.isBlank()) {
            reasons += "Step $index needs a readable name"
            return null
        }
        if (source.type == StepType.REQUEST_HUMAN_TAKEOVER) {
            return RoutineStep(stepId, source.name, RoutineStepKind.WAIT_FOR_USER)
        }
        if (source.type != StepType.PHONE_TOOL) {
            reasons += "Step '${source.name}' uses unsupported legacy type ${source.type}"
            return null
        }
        val tool = source.parameters["tool"]
        if (tool == null || !SUPPORTED_PHONE_TOOLS.contains(tool)) {
            reasons += "Step '${source.name}' does not declare a supported typed phone capability"
            return null
        }
        if (source.recovery.maxRetries !in 0..10) {
            reasons += "Step '${source.name}' recovery exceeds the capsule bound"
            return null
        }
        if (tool == "phone.open_app" && !PACKAGE_NAME.matches(source.parameters["package"].orEmpty())) {
            reasons += "Step '${source.name}' needs a valid declared package"
            return null
        }
        val arguments = linkedMapOf<String, RoutineArgument>()
        for ((key, value) in source.parameters.toSortedMap()) {
            if (key == "tool") continue
            val argument = argument(value, inputs)
            if (argument == null) {
                reasons += "Step '${source.name}' references an unknown or partial input in '$key'"
            } else if (tool == "phone.type" && argument is RoutineArgument.NonSensitiveLiteral) {
                reasons += "Step '${source.name}' persists typed text; replace it with an input reference"
            } else {
                arguments[key] = argument
            }
        }
        selectorArguments(source.selector).forEach { (key, value) ->
            val argument = runCatching { RoutineArgument.NonSensitiveLiteral(value) }.getOrNull()
            if (argument == null) {
                reasons += "Step '${source.name}' contains sensitive selector material in '$key'"
            } else {
                arguments[key] = argument
            }
        }
        if (reasons.isNotEmpty()) return null
        val capability = CapabilityId(tool)
        val verificationId = "actionResult$index"
        return RoutineStep(
            id = stepId,
            name = source.name,
            kind = RoutineStepKind.ACTION_PROPOSAL,
            action = RoutineActionProposal(capability, tool.substringAfterLast('.'), arguments),
            verificationIds = listOf(verificationId),
            recovery = RoutineRecoveryPlan(
                maximumAttempts = source.recovery.maxRetries,
                sequence = if (source.recovery.maxRetries == 0) emptyList() else {
                    recoverySequence(source.recovery.onFailure, source.recovery.refreshBeforeRetry)
                },
            ),
        )
    }

    private fun argument(value: String, inputs: Map<String, RoutineInput>): RoutineArgument? {
        val match = INPUT_REFERENCE.matchEntire(value)
        if (match != null) {
            val input = inputs[match.groupValues[1]] ?: return null
            return if (input.sensitive) RoutineArgument.SecretReference(input.name) else RoutineArgument.InputReference(input.name)
        }
        if (INPUT_FRAGMENT.containsMatchIn(value)) return null
        return runCatching { RoutineArgument.NonSensitiveLiteral(value) }.getOrNull()
    }

    private fun selectorArguments(selector: Selector?): Map<String, String> {
        if (selector == null) return emptyMap()
        return buildMap {
            selector.resourceId?.let { put("selectorResourceId", it) }
            selector.text?.let { put("selectorText", it) }
            selector.partialText?.let { put("selectorPartialText", it) }
            selector.contentDescription?.let { put("selectorContentDescription", it) }
            selector.contentDescriptionContains?.let { put("selectorContentDescriptionContains", it) }
            selector.role?.let { put("selectorRole", it) }
            selector.className?.let { put("selectorClassName", it) }
            selector.ancestor?.let { put("selectorAncestor", it) }
            selector.descendant?.let { put("selectorDescendant", it) }
            selector.relativePosition?.let { put("selectorRelativePosition", it) }
            selector.relativeToText?.let { put("selectorRelativeToText", it) }
            selector.relativeDirection?.let { put("selectorRelativeDirection", it) }
            selector.fuzzyText?.let { put("selectorFuzzyText", it) }
            selector.minFuzzyScore?.let { put("selectorMinFuzzyScore", it.toString()) }
            selector.requireClickable?.let { put("selectorRequireClickable", it.toString()) }
            selector.requireEditable?.let { put("selectorRequireEditable", it.toString()) }
            selector.requireScrollable?.let { put("selectorRequireScrollable", it.toString()) }
            selector.x?.let { put("selectorX", it.toString()) }
            selector.y?.let { put("selectorY", it.toString()) }
        }
    }

    private fun recoverySequence(onFailure: FailureAction, refresh: Boolean): List<RecoveryPrimitive> {
        val sequence = buildList {
            if (refresh) add(RecoveryPrimitive.REOBSERVE)
            add(
                when (onFailure) {
                    FailureAction.RETRY -> RecoveryPrimitive.RETRY_SELECTOR
                    FailureAction.GO_BACK -> RecoveryPrimitive.RETURN_TO_KNOWN_PAGE
                    FailureAction.RESTART_APP -> RecoveryPrimitive.RETURN_TO_KNOWN_PAGE
                    FailureAction.REQUEST_AI_HELP -> RecoveryPrimitive.REPLAN
                    FailureAction.REQUEST_HUMAN -> RecoveryPrimitive.HUMAN_TAKEOVER
                    FailureAction.ABORT -> RecoveryPrimitive.REOBSERVE
                },
            )
        }
        return sequence.distinct()
    }

    private fun routineIdSegment(sourceId: String): String {
        val normalized = sourceId.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return normalized.ifBlank { "unknown" }.take(96).trimEnd('-').ifBlank { "unknown" }
    }

    private val FIELD_NAME = Regex("[a-z][A-Za-z0-9_.-]*")
    private val PACKAGE_NAME = Regex("[a-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
    private val INPUT_REFERENCE = Regex("\\$\\{([a-z][A-Za-z0-9_.-]*)}")
    private val INPUT_FRAGMENT = Regex("\\$\\{")
    private val SUPPORTED_PHONE_TOOLS = setOf(
        "phone.back",
        "phone.click",
        "phone.home",
        "phone.open_app",
        "phone.open_notification",
        "phone.scroll",
        "phone.type",
        "phone.wait_for",
    )
    private const val MAXIMUM_MIGRATED_STEPS = 900
}
