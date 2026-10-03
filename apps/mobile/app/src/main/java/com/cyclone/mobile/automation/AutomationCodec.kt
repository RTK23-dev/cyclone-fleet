package com.cyclone.mobile.automation

import org.json.JSONArray
import org.json.JSONObject

object AutomationCodec {
    fun automationToJson(value: AutomationDefinition): JSONObject = JSONObject().apply {
        put("id", value.id)
        put("name", value.name)
        put("description", value.description)
        put("enabled", value.enabled)
        put("version", value.version)
        put("trigger", triggerToJson(value.trigger))
        put("conditions", JSONArray().apply { value.conditions.forEach { put(conditionToJson(it)) } })
        put("variables", JSONArray().apply { value.variables.forEach { variable ->
            put(JSONObject().put("name", variable.name).put("defaultValue", variable.defaultValue ?: JSONObject.NULL).put("secret", variable.secret))
        } })
        put("steps", JSONArray().apply { value.steps.forEach { put(stepToJson(it)) } })
        put("verification", JSONArray().apply { value.verification.forEach { put(conditionToJson(it)) } })
        put("failureBehavior", value.failureBehavior.name)
        put("outputVariables", JSONArray(value.outputVariables))
        put("appPackages", JSONArray(value.appPackages))
        put("categories", JSONArray(value.categories))
        put("associationVersion", value.associationVersion)
    }

    fun automationFromJson(json: JSONObject): AutomationDefinition = AutomationDefinition(
        id = json.getString("id"),
        name = json.getString("name"),
        description = json.optString("description"),
        enabled = json.optBoolean("enabled", true),
        version = json.optInt("version", 1),
        trigger = triggerFromJson(json.getJSONObject("trigger")),
        conditions = json.optJSONArray("conditions").toConditions(),
        variables = json.optJSONArray("variables").toVariables(),
        steps = json.optJSONArray("steps").toSteps(),
        verification = json.optJSONArray("verification").toConditions(),
        failureBehavior = enumOrDefault(json.optString("failureBehavior"), FailureAction.ABORT),
        outputVariables = json.optJSONArray("outputVariables").toStringList(),
        appPackages = json.optJSONArray("appPackages").toStringList(),
        categories = json.optJSONArray("categories").toStringList(),
        associationVersion = json.optInt("associationVersion", if (json.has("appPackages")) 1 else 0)
    ).let { if (json.has("appPackages")) it else it.copy(appPackages = RoutineAssociations.infer(it), associationVersion = 1) }

    fun skillToJson(value: SkillDefinition): JSONObject = JSONObject().apply {
        put("id", value.id)
        put("name", value.name)
        put("description", value.description)
        put("inputs", JSONArray(value.inputs))
        put("outputs", JSONArray(value.outputs))
        put("steps", JSONArray().apply { value.steps.forEach { put(stepToJson(it)) } })
        put("enabled", value.enabled)
        put("version", value.version)
    }

    fun skillFromJson(json: JSONObject): SkillDefinition = SkillDefinition(
        id = json.getString("id"),
        name = json.getString("name"),
        description = json.optString("description"),
        inputs = json.optJSONArray("inputs").toStringList(),
        outputs = json.optJSONArray("outputs").toStringList(),
        steps = json.optJSONArray("steps").toSteps(),
        enabled = json.optBoolean("enabled", true),
        version = json.optInt("version", 1)
    )

    fun stepToJson(value: StepDefinition): JSONObject = JSONObject().apply {
        put("id", value.id)
        put("name", value.name)
        put("type", value.type.name)
        put("parameters", mapToJson(value.parameters))
        value.selector?.let { put("selector", selectorToJson(it)) }
        put("confirmationRequired", value.confirmationRequired)
        put("recovery", JSONObject().apply {
            put("maxRetries", value.recovery.maxRetries)
            put("retryDelayMs", value.recovery.retryDelayMs)
            put("refreshBeforeRetry", value.recovery.refreshBeforeRetry)
            put("onFailure", value.recovery.onFailure.name)
        })
    }

    fun stepFromJson(json: JSONObject): StepDefinition {
        val recovery = json.optJSONObject("recovery")
        return StepDefinition(
            id = json.getString("id"),
            name = json.getString("name"),
            type = enumOrDefault(json.optString("type"), StepType.PHONE_TOOL),
            parameters = jsonToMap(json.optJSONObject("parameters")),
            selector = json.optJSONObject("selector")?.let(::selectorFromJson),
            confirmationRequired = json.optBoolean("confirmationRequired", false),
            recovery = RecoveryPolicy(
                maxRetries = recovery?.optInt("maxRetries", 0) ?: 0,
                retryDelayMs = recovery?.optLong("retryDelayMs", 500) ?: 500,
                refreshBeforeRetry = recovery?.optBoolean("refreshBeforeRetry", true) ?: true,
                onFailure = enumOrDefault(recovery?.optString("onFailure"), FailureAction.ABORT)
            )
        )
    }

    private fun triggerToJson(value: TriggerDefinition) = JSONObject().put("type", value.type.name).put("parameters", mapToJson(value.parameters))
    private fun triggerFromJson(json: JSONObject) = TriggerDefinition(enumOrDefault(json.optString("type"), TriggerType.MANUAL), jsonToMap(json.optJSONObject("parameters")))
    private fun conditionToJson(value: ConditionDefinition) = JSONObject().put("id", value.id).put("left", value.left).put("operator", value.operator.name).put("right", value.right ?: JSONObject.NULL)
    private fun conditionFromJson(json: JSONObject) = ConditionDefinition(json.getString("id"), json.getString("left"), enumOrDefault(json.optString("operator"), ConditionOperator.EQUALS), json.optString("right").takeIf { json.has("right") && !json.isNull("right") })

    private fun selectorToJson(value: Selector) = JSONObject().apply {
        value.resourceId?.let { put("resourceId", it) }
        value.text?.let { put("text", it) }
        value.partialText?.let { put("partialText", it) }
        value.contentDescription?.let { put("contentDescription", it) }
        value.contentDescriptionContains?.let { put("contentDescriptionContains", it) }
        value.role?.let { put("role", it) }
        value.className?.let { put("className", it) }
        value.ancestor?.let { put("ancestor", it) }
        value.descendant?.let { put("descendant", it) }
        value.relativePosition?.let { put("relativePosition", it) }
        value.relativeToText?.let { put("relativeToText", it) }
        value.relativeDirection?.let { put("relativeDirection", it) }
        value.fuzzyText?.let { put("fuzzyText", it) }
        value.minFuzzyScore?.let { put("minFuzzyScore", it) }
        value.requireClickable?.let { put("requireClickable", it) }
        value.requireEditable?.let { put("requireEditable", it) }
        value.requireScrollable?.let { put("requireScrollable", it) }
        value.x?.let { put("x", it) }
        value.y?.let { put("y", it) }
    }

    private fun selectorFromJson(json: JSONObject) = Selector(
        resourceId = json.optNullableString("resourceId"),
        text = json.optNullableString("text"),
        partialText = json.optNullableString("partialText") ?: json.optNullableString("textContains"),
        contentDescription = json.optNullableString("contentDescription"),
        contentDescriptionContains = json.optNullableString("contentDescriptionContains"),
        role = json.optNullableString("role"),
        className = json.optNullableString("className") ?: json.optNullableString("class"),
        ancestor = json.optNullableString("ancestor") ?: json.optNullableString("ancestorText"),
        descendant = json.optNullableString("descendant") ?: json.optNullableString("descendantText"),
        relativePosition = json.optNullableString("relativePosition"),
        relativeToText = json.optNullableString("relativeToText"),
        relativeDirection = json.optNullableString("relativeDirection"),
        fuzzyText = json.optNullableString("fuzzyText"),
        minFuzzyScore = json.optDouble("minFuzzyScore").takeIf { json.has("minFuzzyScore") },
        requireClickable = json.optBoolean("requireClickable").takeIf { json.has("requireClickable") },
        requireEditable = json.optBoolean("requireEditable").takeIf { json.has("requireEditable") },
        requireScrollable = json.optBoolean("requireScrollable").takeIf { json.has("requireScrollable") },
        x = json.optInt("x").takeIf { json.has("x") },
        y = json.optInt("y").takeIf { json.has("y") }
    )

    private fun mapToJson(map: Map<String, String>) = JSONObject().apply { map.forEach { (key, value) -> put(key, value) } }
    private fun jsonToMap(json: JSONObject?): Map<String, String> {
        if (json == null) return emptyMap()
        return buildMap { json.keys().forEach { key -> put(key, json.optString(key)) } }
    }

    private fun JSONArray?.toConditions(): List<ConditionDefinition> = if (this == null) emptyList() else List(length()) { conditionFromJson(getJSONObject(it)) }
    private fun JSONArray?.toSteps(): List<StepDefinition> = if (this == null) emptyList() else List(length()) { stepFromJson(getJSONObject(it)) }
    private fun JSONArray?.toVariables(): List<VariableDefinition> = if (this == null) emptyList() else List(length()) {
        val json = getJSONObject(it)
        VariableDefinition(json.getString("name"), json.optNullableString("defaultValue"), json.optBoolean("secret", false))
    }
    private fun JSONArray?.toStringList(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
    private fun JSONObject.optNullableString(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T =
        runCatching { enumValueOf<T>(raw.orEmpty()) }.getOrDefault(fallback)
}
