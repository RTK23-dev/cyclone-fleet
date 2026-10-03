package com.cyclone.mobile.automation

import org.json.JSONArray
import org.json.JSONObject

/**
 * Strict boundary compiler for Hermes/Agent-3 workflow proposals.
 *
 * Agent 3 intentionally emits a review document rather than Agent 2's persisted JSON schema.
 * This compiler normalizes that document into typed AutomationDefinition objects and always
 * leaves the result disabled until a human explicitly enables it.
 */
object AutomationProposalCompiler {
    private val secretKeys = setOf("password", "passcode", "secret", "token", "api_key", "apikey", "credential")

    fun compile(document: JSONObject): AutomationDefinition {
        require(!containsLiteralSecret(document)) { "Workflow proposal contains a raw credential; use SecretReference" }
        val name = document.optString("name").trim()
        require(name.isNotBlank()) { "Workflow requires a non-empty name" }

        val triggerJson = document.optJSONObject("trigger") ?: error("Workflow requires a typed trigger")
        val trigger = compileTrigger(triggerJson)
        val stepsJson = document.optJSONArray("steps") ?: error("Workflow requires steps")
        require(stepsJson.length() > 0) { "Workflow requires at least one step" }

        val steps = buildList {
            for (index in 0 until stepsJson.length()) {
                val raw = stepsJson.optJSONObject(index) ?: error("Step $index must be an object")
                addAll(compileStep(raw, index))
            }
            addAll(compileVerificationSteps(document.opt("verification")))
        }

        return AutomationDefinition(
            id = document.optString("id").trim().ifBlank { java.util.UUID.randomUUID().toString() },
            name = name,
            description = document.optString("description").trim(),
            enabled = false,
            version = document.optInt("version", 1).coerceAtLeast(1),
            trigger = trigger,
            conditions = compileConditions(document.optJSONArray("conditions")),
            variables = compileVariables(document.optJSONArray("variables")),
            steps = steps,
            verification = compileVerificationConditions(document.opt("verification")),
            failureBehavior = compileFailureAction(document.optJSONObject("recovery")?.optString("onFailure") ?: document.optString("failureBehavior")),
            outputVariables = document.optJSONArray("outputVariables").toStringList()
        )
    }

    private fun compileTrigger(raw: JSONObject): TriggerDefinition {
        val type = when (raw.optString("type").trim().lowercase()) {
            "manual" -> TriggerType.MANUAL
            "notification" -> TriggerType.NOTIFICATION
            "schedule" -> TriggerType.SCHEDULE
            "app_opened", "app-opened", "app_open" -> TriggerType.APP_OPENED
            "cyclone_remote", "remote" -> TriggerType.CYCLONE_REMOTE
            "websocket", "web_socket" -> TriggerType.WEBSOCKET
            "calendar_time", "calendar", "time" -> TriggerType.CALENDAR_TIME
            else -> error("Unsupported trigger type: ${raw.optString("type")}")
        }
        val parameters = linkedMapOf<String, String>()
        raw.optJSONObject("params")?.let { parameters.putAll(stringMap(it)) }
        raw.optJSONObject("parameters")?.let { parameters.putAll(stringMap(it)) }
        raw.keys().forEach { key ->
            if (key != "type" && key != "params" && key != "parameters") scalarString(raw.opt(key))?.let { parameters[key] = it }
        }
        return TriggerDefinition(type, parameters)
    }

    private fun compileStep(raw: JSONObject, index: Int): List<StepDefinition> {
        val typeName = raw.optString("type").trim().lowercase()
        val paramsJson = raw.optJSONObject("params") ?: raw.optJSONObject("parameters") ?: JSONObject()
        val params = stringMap(paramsJson).toMutableMap()
        val explicitName = raw.optString("name").trim()
        val recovery = compileRecovery(raw.optJSONObject("recovery"))
        val confirmationRequired = when {
            raw.optString("confirmation").equals("required", ignoreCase = true) -> true
            raw.optBoolean("confirmationRequired", false) -> true
            raw.optBoolean("consequential", false) -> error("Consequential step $index must require confirmation")
            else -> false
        }

        fun step(
            type: StepType,
            name: String,
            parameters: Map<String, String> = params,
            selector: Selector? = null,
            confirmation: Boolean = confirmationRequired
        ) = StepDefinition(
            id = raw.optString("id").trim().ifBlank { java.util.UUID.randomUUID().toString() },
            name = explicitName.ifBlank { name },
            type = type,
            parameters = parameters,
            selector = selector,
            confirmationRequired = confirmation,
            recovery = recovery
        )

        return when (typeName) {
            "phone_tool" -> {
                val tool = raw.optString("tool").trim().ifBlank { params.remove("tool").orEmpty() }
                require(tool.startsWith("phone.")) { "Step $index must name a phone.* tool" }
                params["tool"] = tool
                val selectorJson = raw.optJSONObject("selector") ?: paramsJson.optJSONObject("selector")
                listOf(step(StepType.PHONE_TOOL, tool, params, selectorJson?.let(::compileSelector)))
            }
            "wait" -> {
                val condition = raw.optJSONObject("condition")
                if (condition != null) {
                    val waitParams = normalizePhoneCondition(condition).toMutableMap()
                    raw.optLong("timeoutMs", -1).takeIf { it >= 0 }?.let { waitParams["timeoutMs"] = it.toString() }
                    params["timeoutMs"]?.let { waitParams["timeoutMs"] = it }
                    listOf(step(StepType.PHONE_TOOL, "Wait for phone state", waitParams + ("tool" to "phone.wait_for"), condition.selectorCandidate()?.let(::compileSelector), confirmation = false))
                } else listOf(step(StepType.WAIT, "Wait"))
            }
            "condition" -> listOf(step(StepType.CONDITION, "Condition", normalizeConditionParameters(raw, params), confirmation = false))
            "branch" -> listOf(step(StepType.BRANCH, "Branch"))
            "repeat" -> listOf(step(StepType.REPEAT, "Repeat"))
            "set_variable", "variable_assignment" -> listOf(step(StepType.VARIABLE_ASSIGNMENT, "Set variable", mergeTopLevel(raw, params, "name", "value"), confirmation = false))
            "parse_text" -> listOf(step(StepType.PARSE_TEXT, "Parse text"))
            "regex_extract" -> listOf(step(StepType.REGEX_EXTRACT, "Extract text"))
            "delay" -> listOf(step(StepType.DELAY, "Delay"))
            "assertion" -> {
                val condition = raw.optJSONObject("condition")
                if (condition != null && !condition.has("left")) {
                    val assertParams = normalizePhoneCondition(condition) + ("tool" to "phone.assert")
                    listOf(step(StepType.PHONE_TOOL, "Assert phone state", assertParams, condition.selectorCandidate()?.let(::compileSelector), confirmation = false))
                } else listOf(step(StepType.ASSERTION, "Assertion", normalizeConditionParameters(raw, params), confirmation = false))
            }
            "invoke_skill" -> listOf(step(StepType.INVOKE_SKILL, "Invoke skill", mergeTopLevel(raw, params, "skillId"), confirmation = false))
            "http_request" -> listOf(step(StepType.HTTP_REQUEST, "HTTP request", mergeTopLevel(raw, params, "method", "url", "body")))
            "cyclone_event", "send_cyclone_event" -> listOf(step(StepType.SEND_CYCLONE_EVENT, "Send Cyclone event", mergeTopLevel(raw, params, "event")))
            "request_human_takeover" -> {
                val reason = raw.optString("reason").ifBlank { params["reason"] ?: "Human intervention required" }
                val takeover = step(StepType.REQUEST_HUMAN_TAKEOVER, "Request human takeover", params + ("reason" to reason), confirmation = false)
                val resume = raw.optJSONObject("resumeCondition") ?: error("Takeover step $index requires resumeCondition")
                val resumeCheck = StepDefinition(
                    name = "Verify takeover resume condition",
                    type = StepType.PHONE_TOOL,
                    parameters = normalizePhoneCondition(resume) + ("tool" to "phone.assert"),
                    selector = resume.selectorCandidate()?.let(::compileSelector),
                    confirmationRequired = false,
                    recovery = RecoveryPolicy(maxRetries = 1, onFailure = FailureAction.REQUEST_HUMAN)
                )
                listOf(takeover, resumeCheck)
            }
            else -> error("Unsupported step type at $index: $typeName")
        }
    }

    private fun compileSelector(raw: JSONObject): Selector = Selector(
        resourceId = raw.s("resourceId"),
        text = raw.s("text"),
        partialText = raw.s("textContains") ?: raw.s("partialText"),
        contentDescription = raw.s("contentDescription"),
        contentDescriptionContains = raw.s("contentDescriptionContains"),
        role = raw.s("role"),
        className = raw.s("class") ?: raw.s("className"),
        ancestor = raw.s("ancestorText") ?: raw.s("ancestor"),
        descendant = raw.s("descendantText") ?: raw.s("descendant"),
        relativePosition = buildRelativePosition(raw),
        relativeToText = raw.s("relativeToText"),
        relativeDirection = raw.s("relativeDirection"),
        fuzzyText = raw.s("fuzzyText"),
        minFuzzyScore = raw.optDouble("minFuzzyScore").takeIf { raw.has("minFuzzyScore") },
        requireClickable = booleanSelectorFlag(raw, "clickable", "requireClickable"),
        requireEditable = booleanSelectorFlag(raw, "editable", "requireEditable"),
        requireScrollable = booleanSelectorFlag(raw, "scrollable", "requireScrollable"),
        x = raw.optInt("x").takeIf { raw.has("x") },
        y = raw.optInt("y").takeIf { raw.has("y") }
    )

    private fun buildRelativePosition(raw: JSONObject): String? {
        val target = raw.s("relativeToText") ?: return raw.s("relativePosition")
        val direction = raw.s("relativeDirection")?.lowercase() ?: "near"
        return "$direction:$target"
    }

    private fun booleanSelectorFlag(raw: JSONObject, agent1Key: String, internalKey: String): Boolean? = when {
        raw.has(agent1Key) -> raw.optBoolean(agent1Key)
        raw.has(internalKey) -> raw.optBoolean(internalKey)
        else -> null
    }

    private fun compileConditions(raw: JSONArray?): List<ConditionDefinition> {
        if (raw == null) return emptyList()
        return buildList {
            for (index in 0 until raw.length()) {
                val item = raw.optJSONObject(index) ?: error("Condition $index must be an object")
                add(compileCondition(item, "Condition $index"))
            }
        }
    }

    private fun compileVerificationConditions(raw: Any?): List<ConditionDefinition> = when (raw) {
        is JSONObject -> if (raw.has("left")) listOf(compileCondition(raw, "Verification")) else emptyList()
        is JSONArray -> buildList {
            for (index in 0 until raw.length()) raw.optJSONObject(index)?.takeIf { it.has("left") }?.let { add(compileCondition(it, "Verification $index")) }
        }
        else -> emptyList()
    }

    private fun compileVerificationSteps(raw: Any?): List<StepDefinition> {
        fun screenAssertion(item: JSONObject): StepDefinition? {
            if (item.has("left")) return null
            if (item.length() == 0) return null
            return StepDefinition(
                name = "Verify automation result",
                type = StepType.PHONE_TOOL,
                parameters = normalizePhoneCondition(item) + ("tool" to "phone.assert"),
                selector = item.selectorCandidate()?.let(::compileSelector),
                recovery = RecoveryPolicy(maxRetries = 1)
            )
        }
        return when (raw) {
            is JSONObject -> listOfNotNull(screenAssertion(raw))
            is JSONArray -> buildList {
                for (index in 0 until raw.length()) raw.optJSONObject(index)?.let { screenAssertion(it)?.let(::add) }
            }
            else -> emptyList()
        }
    }

    private fun compileCondition(raw: JSONObject, label: String): ConditionDefinition {
        val left = raw.optString("left")
        require(left.isNotBlank()) { "$label requires left" }
        val operator = runCatching { ConditionOperator.valueOf(raw.optString("operator", "EQUALS").uppercase()) }
            .getOrElse { error("$label has unsupported operator ${raw.optString("operator")}") }
        return ConditionDefinition(
            id = raw.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            left = left,
            operator = operator,
            right = raw.opt("right")?.takeUnless { it === JSONObject.NULL }?.toString()
        )
    }

    private fun normalizeConditionParameters(raw: JSONObject, params: Map<String, String>): Map<String, String> {
        val condition = raw.optJSONObject("condition")
        return if (condition != null) stringMap(condition) else mergeTopLevel(raw, params, "left", "operator", "right")
    }

    private fun normalizePhoneCondition(raw: JSONObject): Map<String, String> = stringMap(raw).toMutableMap().apply {
        if (!containsKey("type")) {
            put("type", when {
                raw.has("package") && !raw.has("text") && !raw.has("resourceId") && !raw.has("selector") -> "package_equals"
                raw.has("from") && !raw.has("selector") -> "fingerprint_changed"
                else -> "selector_exists"
            })
        }
        raw.optJSONObject("selector")?.let { put("selectorJson", it.toString()) }
    }

    private fun JSONObject.selectorCandidate(): JSONObject? = optJSONObject("selector") ?: takeIf {
        listOf(
            "resourceId", "text", "textContains", "partialText", "contentDescription", "contentDescriptionContains",
            "class", "className", "role", "ancestorText", "descendantText", "relativeToText", "relativeDirection",
            "fuzzyText", "x", "y"
        ).any(::has)
    }

    private fun compileVariables(raw: JSONArray?): List<VariableDefinition> {
        if (raw == null) return emptyList()
        return buildList {
            for (index in 0 until raw.length()) {
                when (val value = raw.opt(index)) {
                    is String -> add(VariableDefinition(value))
                    is JSONObject -> {
                        val name = value.optString("name").trim()
                        require(name.isNotBlank()) { "Variable $index requires name" }
                        val secret = value.optBoolean("secret", false)
                        val default = value.opt("defaultValue")?.takeUnless { it === JSONObject.NULL }?.toString()
                        if (secret && !default.isNullOrBlank()) error("Secret variable $name cannot contain a literal default")
                        add(VariableDefinition(name, default, secret))
                    }
                    else -> error("Variable $index must be a string or object")
                }
            }
        }
    }

    private fun compileRecovery(raw: JSONObject?): RecoveryPolicy {
        if (raw == null) return RecoveryPolicy()
        return RecoveryPolicy(
            maxRetries = raw.optInt("maxRetries", raw.optInt("retry", 0)).coerceIn(0, 10),
            retryDelayMs = raw.optLong("retryDelayMs", 500).coerceIn(0, 60_000),
            refreshBeforeRetry = raw.optBoolean("refreshBeforeRetry", true),
            onFailure = compileFailureAction(raw.optString("onFailure"))
        )
    }

    private fun compileFailureAction(raw: String?): FailureAction = when (raw.orEmpty().trim().lowercase()) {
        "", "abort" -> FailureAction.ABORT
        "retry" -> FailureAction.RETRY
        "go_back", "back" -> FailureAction.GO_BACK
        "restart_app" -> FailureAction.RESTART_APP
        "request_ai_help", "ai_help" -> FailureAction.REQUEST_AI_HELP
        "request_human", "human" -> FailureAction.REQUEST_HUMAN
        else -> error("Unsupported failure action: $raw")
    }

    private fun containsLiteralSecret(value: Any?): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { key ->
            val child = value.opt(key)
            val normalized = key.lowercase().replace('-', '_')
            val explicitReference = child is JSONObject && (child.has("secretRef") || child.has("secretReference"))
            val schemaFlag = normalized == "secret" && child is Boolean
            (!schemaFlag && normalized in secretKeys && !explicitReference && child != null && child !== JSONObject.NULL && child.toString().isNotBlank() && child.toString() != "***") || containsLiteralSecret(child)
        }
        is JSONArray -> (0 until value.length()).any { containsLiteralSecret(value.opt(it)) }
        else -> false
    }

    private fun mergeTopLevel(raw: JSONObject, existing: Map<String, String>, vararg keys: String): Map<String, String> =
        existing.toMutableMap().apply {
            keys.forEach { key -> scalarString(raw.opt(key))?.let { put(key, it) } }
        }

    private fun stringMap(raw: JSONObject): Map<String, String> = buildMap {
        raw.keys().forEach { key -> scalarString(raw.opt(key))?.let { put(key, it) } }
    }

    private fun scalarString(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String, is Number, is Boolean -> value.toString()
        else -> null
    }

    private fun JSONObject.s(key: String): String? = optString(key).trim().takeIf { it.isNotBlank() }
    private fun JSONArray?.toStringList(): List<String> = if (this == null) emptyList() else buildList {
        for (index in 0 until length()) optString(index).takeIf { it.isNotBlank() }?.let(::add)
    }
}
