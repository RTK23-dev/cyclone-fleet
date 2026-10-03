package com.cyclone.mobile.automation

import java.util.concurrent.CopyOnWriteArrayList

data class PhoneToolRequest(val name: String, val arguments: Map<String, String> = emptyMap(), val selector: Selector? = null)
data class PhoneToolResult(val success: Boolean, val output: Map<String, String> = emptyMap(), val errorCode: String? = null, val message: String? = null)

fun interface PhoneToolGateway { fun execute(request: PhoneToolRequest): PhoneToolResult }
interface IntegrationGateway {
    fun refreshObservation(): Boolean = true
    fun goBack(): Boolean = false
    fun restartApp(packageName: String?): Boolean = false
    fun http(method: String, url: String, body: String?): PhoneToolResult = PhoneToolResult(false, errorCode = "HTTP_NOT_CONFIGURED")
    fun sendCycloneEvent(type: String, payload: Map<String, String>): PhoneToolResult = PhoneToolResult(false, errorCode = "CYCLONE_NOT_CONFIGURED")
    /** Starts one of the owner's saved skills as a Mind mission (grounded on the map). */
    fun runGroundedSkill(skillId: String): PhoneToolResult = PhoneToolResult(false, errorCode = "SKILLS_NOT_CONFIGURED")
}
fun interface ConfirmationGateway { fun confirm(step: StepDefinition, variables: Map<String, String>): Boolean }
fun interface TakeoverGateway { fun request(reason: String, runId: String, stepId: String): Boolean }
fun interface RunObserver { fun onRunUpdated(run: AutomationRun) }

class AutomationRunner(
    private val store: AutomationStore,
    private val phoneTools: PhoneToolGateway,
    private val integrations: IntegrationGateway,
    private val confirmations: ConfirmationGateway,
    private val takeover: TakeoverGateway,
    private val stockSkills: StockSkillGateway = StockSkillGateway.UNCONFIGURED,
    private val observers: List<RunObserver> = emptyList(),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = System::currentTimeMillis
) {
    fun run(automation: AutomationDefinition, trigger: TriggerEvent, resume: Checkpoint? = null): AutomationRun {
        val variables = automation.variables.associate { it.name to it.defaultValue.orEmpty() }.toMutableMap()
        variables.putAll(trigger.payload)
        resume?.variables?.let(variables::putAll)
        val previousRun = resume?.let { store.getRun(it.runId) }
        var run = AutomationRun(
            id = resume?.runId ?: java.util.UUID.randomUUID().toString(),
            automationId = automation.id,
            automationName = automation.name,
            state = RunState.RUNNING,
            trigger = previousRun?.trigger ?: trigger,
            startedAt = previousRun?.startedAt ?: now(),
            steps = previousRun?.steps ?: emptyList(),
            variables = variables.toMap()
        )

        if (resume != null && !integrations.refreshObservation()) {
            run = run.copy(
                state = RunState.WAITING_FOR_HUMAN,
                error = "resume_observation_failed",
                variables = variables.toMap()
            )
            publish(run)
            return run
        }
        publish(run)

        if (!automation.conditions.all { evaluate(it, variables) }) {
            run = run.copy(state = RunState.SKIPPED, endedAt = now(), variables = variables.toMap(), error = "automation_conditions_not_met")
            store.deleteCheckpoint(run.id)
            publish(run)
            return run
        }

        val records = previousRun?.steps?.toMutableList() ?: mutableListOf()
        var index = resume?.nextStepIndex ?: 0
        while (index < automation.steps.size) {
            val step = automation.steps[index]
            val resumingCurrentHumanStep = resume?.waitingForHuman == true && resume.nextStepIndex == index

            if (resumingCurrentHumanStep && step.type == StepType.REQUEST_HUMAN_TAKEOVER) {
                records.add(
                    RunStepRecord(
                        stepId = step.id,
                        name = step.name,
                        state = RunState.SUCCESS,
                        startedAt = now(),
                        endedAt = now(),
                        message = "human_takeover_completed"
                    )
                )
                index++
                run = run.copy(state = RunState.RUNNING, steps = records.toList(), variables = variables.toMap(), error = null)
                publish(run)
                continue
            }

            store.saveCheckpoint(Checkpoint(run.id, automation.id, index, variables.toMap(), waitingForHuman = false))
            val confirmationAlreadyApproved = resumingCurrentHumanStep && step.confirmationRequired
            if (step.confirmationRequired && !confirmationAlreadyApproved && !confirmations.confirm(step, variables)) {
                takeover.request("Confirmation required: ${step.name}", run.id, step.id)
                val record = RunStepRecord(step.id, step.name, RunState.WAITING_FOR_HUMAN, now(), now(), message = "confirmation_required")
                records.add(record)
                store.saveCheckpoint(Checkpoint(run.id, automation.id, index, variables.toMap(), waitingForHuman = true))
                run = run.copy(state = RunState.WAITING_FOR_HUMAN, steps = records.toList(), variables = variables.toMap(), error = null)
                publish(run)
                return run
            }

            val record = executeWithRecovery(run.id, step, variables)
            records.add(record)
            run = run.copy(
                steps = records.toList(),
                variables = variables.toMap(),
                state = if (record.state == RunState.WAITING_FOR_HUMAN) RunState.WAITING_FOR_HUMAN else RunState.RUNNING,
                error = null
            )
            publish(run)

            when (record.state) {
                RunState.SUCCESS, RunState.SKIPPED -> index++
                RunState.WAITING_FOR_HUMAN -> {
                    store.saveCheckpoint(Checkpoint(run.id, automation.id, index, variables.toMap(), waitingForHuman = true))
                    return run
                }
                else -> {
                    run = run.copy(state = RunState.FAILED, endedAt = now(), error = record.message ?: "step_failed", variables = variables.toMap())
                    if (automation.failureBehavior == FailureAction.REQUEST_HUMAN) takeover.request("Automation failed: ${step.name}", run.id, step.id)
                    store.deleteCheckpoint(run.id)
                    publish(run)
                    return run
                }
            }
        }

        val verified = automation.verification.all { evaluate(it, variables) }
        run = run.copy(
            state = if (verified) RunState.SUCCESS else RunState.FAILED,
            endedAt = now(),
            steps = records.toList(),
            variables = variables.toMap(),
            error = if (verified) null else "verification_failed"
        )
        store.deleteCheckpoint(run.id)
        publish(run)
        return run
    }

    private fun executeWithRecovery(runId: String, step: StepDefinition, variables: MutableMap<String, String>): RunStepRecord {
        val started = now()
        var attempt = 0
        var last: StepExecution
        do {
            attempt++
            last = executeStep(runId, step, variables, depth = 0)
            if (last.success || last.waitingForHuman) break
            if (attempt <= step.recovery.maxRetries) {
                if (step.recovery.refreshBeforeRetry) integrations.refreshObservation()
                sleep(step.recovery.retryDelayMs.coerceIn(0, 60_000))
            }
        } while (attempt <= step.recovery.maxRetries)

        if (!last.success && !last.waitingForHuman) {
            when (step.recovery.onFailure) {
                FailureAction.GO_BACK -> integrations.goBack()
                FailureAction.RESTART_APP -> integrations.restartApp(step.parameters["package"])
                FailureAction.REQUEST_AI_HELP -> integrations.sendCycloneEvent("automation.ai_help", mapOf("runId" to runId, "stepId" to step.id, "reason" to (last.message ?: "unknown")))
                FailureAction.REQUEST_HUMAN -> {
                    takeover.request("Step failed: ${step.name}", runId, step.id)
                    last = last.copy(waitingForHuman = true)
                }
                FailureAction.RETRY, FailureAction.ABORT -> Unit
            }
        }
        return RunStepRecord(
            stepId = step.id,
            name = step.name,
            state = when { last.success -> RunState.SUCCESS; last.waitingForHuman -> RunState.WAITING_FOR_HUMAN; else -> RunState.FAILED },
            startedAt = started,
            endedAt = now(),
            attempt = attempt,
            message = last.message,
            output = last.output
        )
    }

    private fun executeStep(runId: String, step: StepDefinition, variables: MutableMap<String, String>, depth: Int): StepExecution {
        if (depth > 6) return StepExecution(false, message = "skill_recursion_limit")
        return when (step.type) {
            StepType.PHONE_TOOL -> {
                val toolName = step.parameters["tool"] ?: step.name
                val result = phoneTools.execute(PhoneToolRequest(toolName, resolveMap(step.parameters - "tool", variables), step.selector))
                result.output.forEach { (key, value) -> variables[key] = value }
                StepExecution(result.success, output = result.output, message = result.message ?: result.errorCode)
            }
            StepType.WAIT, StepType.DELAY -> {
                val ms = resolve(step.parameters["ms"] ?: "500", variables).toLongOrNull()?.coerceIn(0, 300_000) ?: 500
                sleep(ms)
                StepExecution(true, message = "waited_${ms}ms")
            }
            StepType.CONDITION -> StepExecution(evaluate(step.parameters, variables), message = "condition")
            StepType.ASSERTION -> {
                val passed = evaluate(step.parameters, variables)
                StepExecution(passed, message = if (passed) "assertion_passed" else "assertion_failed")
            }
            StepType.VARIABLE_ASSIGNMENT -> {
                val target = step.parameters["name"] ?: return StepExecution(false, message = "missing_variable_name")
                variables[target] = resolve(step.parameters["value"].orEmpty(), variables)
                StepExecution(true, mapOf(target to variables.getValue(target)))
            }
            StepType.PARSE_TEXT -> {
                val source = resolve(step.parameters["source"].orEmpty(), variables)
                val delimiter = step.parameters["delimiter"] ?: " "
                val index = step.parameters["index"]?.toIntOrNull() ?: 0
                val target = step.parameters["target"] ?: "parsed"
                val parsed = source.split(delimiter).getOrNull(index) ?: return StepExecution(false, message = "parse_index_out_of_range")
                variables[target] = parsed
                StepExecution(true, mapOf(target to parsed))
            }
            StepType.REGEX_EXTRACT -> {
                val source = resolve(step.parameters["source"].orEmpty(), variables)
                val pattern = step.parameters["pattern"] ?: return StepExecution(false, message = "missing_regex_pattern")
                val group = step.parameters["group"]?.toIntOrNull() ?: 1
                val target = step.parameters["target"] ?: "match"
                val match = runCatching { Regex(pattern).find(source)?.groupValues?.getOrNull(group) }.getOrNull()
                    ?: return StepExecution(false, message = "regex_no_match")
                variables[target] = match
                StepExecution(true, mapOf(target to match))
            }
            StepType.INVOKE_SKILL -> invokeSkill(runId, step.parameters["skillId"], variables, depth + 1)
            StepType.RUN_GROUNDED_SKILL -> {
                val skillId = step.parameters["skillId"]?.takeIf { it.startsWith("you.") } ?: return StepExecution(false, message = "missing_skill_id")
                val result = integrations.runGroundedSkill(skillId)
                StepExecution(result.success, output = result.output, message = result.message ?: result.errorCode)
            }
            StepType.STOCK_SKILL -> {
                val skillId = step.parameters["skillId"] ?: return StepExecution(false, message = "missing_stock_skill_id")
                val arguments = resolveMap(step.parameters - "skillId", variables)
                val result = stockSkills.execute(StockSkillRequest(skillId, runId, step.id, arguments))
                result.output.forEach { (key, value) -> variables[key] = value }
                if (result.waitingForHuman) {
                    takeover.request(result.message ?: "Stock skill requires human review: ${step.name}", runId, step.id)
                }
                StepExecution(
                    success = result.success,
                    output = result.output,
                    waitingForHuman = result.waitingForHuman,
                    message = result.message,
                )
            }
            StepType.BRANCH -> {
                val skillId = if (evaluate(step.parameters, variables)) step.parameters["thenSkill"] else step.parameters["elseSkill"]
                if (skillId.isNullOrBlank()) StepExecution(true, message = "branch_noop") else invokeSkill(runId, skillId, variables, depth + 1)
            }
            StepType.REPEAT -> {
                val skillId = step.parameters["skillId"] ?: return StepExecution(false, message = "missing_skill_id")
                val count = resolve(step.parameters["count"] ?: "1", variables).toIntOrNull()?.coerceIn(0, 100) ?: 1
                repeat(count) {
                    val result = invokeSkill(runId, skillId, variables, depth + 1)
                    if (!result.success) return result
                }
                StepExecution(true, message = "repeated_$count")
            }
            StepType.HTTP_REQUEST -> {
                val result = integrations.http(step.parameters["method"] ?: "GET", resolve(step.parameters["url"].orEmpty(), variables), step.parameters["body"]?.let { resolve(it, variables) })
                result.output.forEach { (key, value) -> variables[key] = value }
                StepExecution(result.success, result.output, message = result.message ?: result.errorCode)
            }
            StepType.SEND_CYCLONE_EVENT -> {
                val type = step.parameters["event"] ?: "automation.event"
                val result = integrations.sendCycloneEvent(type, resolveMap(step.parameters - "event", variables))
                StepExecution(result.success, result.output, message = result.message ?: result.errorCode)
            }
            StepType.REQUEST_HUMAN_TAKEOVER -> {
                takeover.request(step.parameters["reason"] ?: step.name, runId, step.id)
                StepExecution(false, waitingForHuman = true, message = "takeover_requested")
            }
        }
    }

    private fun invokeSkill(runId: String, skillId: String?, variables: MutableMap<String, String>, depth: Int): StepExecution {
        val skill = skillId?.let(store::getSkill)?.takeIf { it.enabled } ?: return StepExecution(false, message = "skill_not_found")
        for (nested in skill.steps) {
            val result = executeStep(runId, nested, variables, depth)
            if (!result.success) return result
        }
        return StepExecution(true, skill.outputs.mapNotNull { key -> variables[key]?.let { key to it } }.toMap(), message = "skill_completed")
    }

    private fun evaluate(condition: ConditionDefinition, variables: Map<String, String>) = evaluate(mapOf("left" to condition.left, "operator" to condition.operator.name, "right" to condition.right.orEmpty()), variables)

    private fun evaluate(parameters: Map<String, String>, variables: Map<String, String>): Boolean {
        val left = resolve(parameters["left"].orEmpty(), variables)
        val right = resolve(parameters["right"].orEmpty(), variables)
        val operator = runCatching { ConditionOperator.valueOf(parameters["operator"] ?: "EQUALS") }.getOrDefault(ConditionOperator.EQUALS)
        return when (operator) {
            ConditionOperator.EQUALS -> left == right
            ConditionOperator.NOT_EQUALS -> left != right
            ConditionOperator.CONTAINS -> left.contains(right, ignoreCase = true)
            ConditionOperator.NOT_CONTAINS -> !left.contains(right, ignoreCase = true)
            ConditionOperator.MATCHES -> runCatching { Regex(right).containsMatchIn(left) }.getOrDefault(false)
            ConditionOperator.EXISTS -> left.isNotBlank()
            ConditionOperator.GREATER_THAN -> (left.toDoubleOrNull() ?: Double.NaN) > (right.toDoubleOrNull() ?: Double.NaN)
            ConditionOperator.LESS_THAN -> (left.toDoubleOrNull() ?: Double.NaN) < (right.toDoubleOrNull() ?: Double.NaN)
        }
    }

    private fun resolve(value: String, variables: Map<String, String>): String {
        var output = value
        variables.forEach { (key, replacement) -> output = output.replace("\${$key}", replacement) }
        if (output.firstOrNull() == '$' && !output.startsWith("\${")) output = variables[output.drop(1)] ?: output
        return output
    }

    private fun resolveMap(values: Map<String, String>, variables: Map<String, String>) = values.mapValues { resolve(it.value, variables) }

    private fun publish(run: AutomationRun) {
        store.appendRun(run)
        observers.forEach { it.onRunUpdated(run) }
    }

    private data class StepExecution(val success: Boolean, val output: Map<String, String> = emptyMap(), val waitingForHuman: Boolean = false, val message: String? = null)
}

class CollectingRunObserver : RunObserver {
    val updates = CopyOnWriteArrayList<AutomationRun>()
    override fun onRunUpdated(run: AutomationRun) { updates.add(run) }
}
