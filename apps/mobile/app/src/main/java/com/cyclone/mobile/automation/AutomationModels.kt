package com.cyclone.mobile.automation

import java.util.UUID

enum class TriggerType { MANUAL, NOTIFICATION, SCHEDULE, APP_OPENED, CYCLONE_REMOTE, WEBSOCKET, CALENDAR_TIME }
enum class StepType { PHONE_TOOL, WAIT, CONDITION, BRANCH, REPEAT, VARIABLE_ASSIGNMENT, PARSE_TEXT, REGEX_EXTRACT, DELAY, ASSERTION, INVOKE_SKILL, STOCK_SKILL, RUN_GROUNDED_SKILL, HTTP_REQUEST, SEND_CYCLONE_EVENT, REQUEST_HUMAN_TAKEOVER }
enum class RunState { PENDING, RUNNING, SUCCESS, FAILED, WAITING, WAITING_FOR_HUMAN, SKIPPED }
enum class ConditionOperator { EQUALS, NOT_EQUALS, CONTAINS, NOT_CONTAINS, MATCHES, EXISTS, GREATER_THAN, LESS_THAN }
enum class FailureAction { RETRY, GO_BACK, RESTART_APP, REQUEST_AI_HELP, REQUEST_HUMAN, ABORT }

data class Selector(
    val resourceId: String? = null,
    val text: String? = null,
    val partialText: String? = null,
    val contentDescription: String? = null,
    val contentDescriptionContains: String? = null,
    val role: String? = null,
    val className: String? = null,
    val ancestor: String? = null,
    val descendant: String? = null,
    val relativePosition: String? = null,
    val relativeToText: String? = null,
    val relativeDirection: String? = null,
    val fuzzyText: String? = null,
    val minFuzzyScore: Double? = null,
    val requireClickable: Boolean? = null,
    val requireEditable: Boolean? = null,
    val requireScrollable: Boolean? = null,
    val x: Int? = null,
    val y: Int? = null
)

data class VariableDefinition(val name: String, val defaultValue: String? = null, val secret: Boolean = false)
data class SecretReference(val key: String)
data class RecoveryPolicy(
    val maxRetries: Int = 0,
    val retryDelayMs: Long = 500,
    val refreshBeforeRetry: Boolean = true,
    val onFailure: FailureAction = FailureAction.ABORT
)

data class TriggerDefinition(
    val type: TriggerType,
    val parameters: Map<String, String> = emptyMap()
)

data class ConditionDefinition(
    val id: String = UUID.randomUUID().toString(),
    val left: String,
    val operator: ConditionOperator,
    val right: String? = null
)

data class StepDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: StepType,
    val parameters: Map<String, String> = emptyMap(),
    val selector: Selector? = null,
    val confirmationRequired: Boolean = false,
    val recovery: RecoveryPolicy = RecoveryPolicy()
)

data class AutomationDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val version: Int = 1,
    val trigger: TriggerDefinition,
    val conditions: List<ConditionDefinition> = emptyList(),
    val variables: List<VariableDefinition> = emptyList(),
    val steps: List<StepDefinition>,
    val verification: List<ConditionDefinition> = emptyList(),
    val failureBehavior: FailureAction = FailureAction.ABORT,
    val outputVariables: List<String> = emptyList(),
    val appPackages: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val associationVersion: Int = 0
)

data class SkillDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val inputs: List<String> = emptyList(),
    val outputs: List<String> = emptyList(),
    val steps: List<StepDefinition>,
    val enabled: Boolean = true,
    val version: Int = 1
)

data class TriggerEvent(
    val type: TriggerType,
    val payload: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

data class RunStepRecord(
    val stepId: String,
    val name: String,
    val state: RunState,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val attempt: Int = 0,
    val message: String? = null,
    val output: Map<String, String> = emptyMap()
)

data class AutomationRun(
    val id: String = UUID.randomUUID().toString(),
    val automationId: String,
    val automationName: String,
    val state: RunState = RunState.PENDING,
    val trigger: TriggerEvent,
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val steps: List<RunStepRecord> = emptyList(),
    val variables: Map<String, String> = emptyMap(),
    val error: String? = null
)

data class Checkpoint(
    val runId: String,
    val automationId: String,
    val nextStepIndex: Int,
    val variables: Map<String, String>,
    val waitingForHuman: Boolean,
    val updatedAt: Long = System.currentTimeMillis()
)
