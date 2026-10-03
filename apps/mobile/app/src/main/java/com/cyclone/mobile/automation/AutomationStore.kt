package com.cyclone.mobile.automation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Existing Automation Studio persistence. V4 skill compile writes disabled drafts here —
 * no second JSON brain and no parallel routines file.
 */
class AutomationStore internal constructor(private val prefs: AutomationPrefs) {
    private val mutableRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = mutableRevision
    constructor(context: Context) : this(SharedAutomationPrefs(context))

    @Synchronized fun listAutomations(): List<AutomationDefinition> = decodeArray(KEY_AUTOMATIONS, AutomationCodec::automationFromJson)
    @Synchronized fun getAutomation(id: String): AutomationDefinition? = listAutomations().firstOrNull { it.id == id }
    @Synchronized fun saveAutomation(value: AutomationDefinition) {
        val associated = if (value.associationVersion == 0) value.copy(appPackages = value.appPackages.ifEmpty { RoutineAssociations.infer(value) }, associationVersion = 1) else value
        val persisted = if (isSkillCapsule(associated)) associated.copy(enabled = false) else associated
        replaceById(KEY_AUTOMATIONS, persisted.id, AutomationCodec.automationToJson(persisted))
    }
    @Synchronized fun deleteAutomation(id: String) = deleteById(KEY_AUTOMATIONS, id)

    @Synchronized fun listSkills(): List<SkillDefinition> = decodeArray(KEY_SKILLS, AutomationCodec::skillFromJson)
    @Synchronized fun getSkill(id: String): SkillDefinition? = listSkills().firstOrNull { it.id == id }
    @Synchronized fun saveSkill(value: SkillDefinition) = replaceById(KEY_SKILLS, value.id, AutomationCodec.skillToJson(value))
    @Synchronized fun deleteSkill(id: String) = deleteById(KEY_SKILLS, id)

    @Synchronized fun appendRun(run: AutomationRun) {
        val existing = rawArray(KEY_RUNS).let { array ->
            buildList { for (i in 0 until array.length()) add(array.getJSONObject(i)) }
        }.filterNot { it.optString("id") == run.id }.toMutableList()
        existing.add(runToJson(run))
        val trimmed = existing.takeLast(MAX_RUNS)
        prefs.putString(KEY_RUNS, JSONArray(trimmed).toString())
        mutableRevision.value += 1
    }

    @Synchronized fun listRuns(limit: Int = 50): List<AutomationRun> {
        val array = rawArray(KEY_RUNS)
        val values = buildList {
            for (i in 0 until array.length()) runCatching { runFromJson(array.getJSONObject(i)) }.getOrNull()?.let(::add)
        }
        return values.takeLast(limit.coerceAtLeast(1)).reversed()
    }

    @Synchronized fun getRun(id: String): AutomationRun? = listRuns(MAX_RUNS).firstOrNull { it.id == id }

    @Synchronized fun saveCheckpoint(checkpoint: Checkpoint) {
        val all = rawObject(KEY_CHECKPOINTS)
        all.put(checkpoint.runId, checkpointToJson(checkpoint))
        prefs.putString(KEY_CHECKPOINTS, all.toString())
        mutableRevision.value += 1
    }

    @Synchronized fun getCheckpoint(runId: String): Checkpoint? = rawObject(KEY_CHECKPOINTS).optJSONObject(runId)?.let(::checkpointFromJson)

    @Synchronized fun deleteCheckpoint(runId: String) {
        val all = rawObject(KEY_CHECKPOINTS)
        all.remove(runId)
        prefs.putString(KEY_CHECKPOINTS, all.toString())
        mutableRevision.value += 1
    }

    @Synchronized fun exportAutomation(id: String): String? = getAutomation(id)?.let { AutomationCodec.automationToJson(it).toString(2) }

    @Synchronized fun importAutomation(raw: String): AutomationDefinition = AutomationCodec.automationFromJson(JSONObject(raw)).also(::saveAutomation)

    private fun isSkillCapsule(value: AutomationDefinition): Boolean =
        value.id.startsWith("skill.") || value.description.contains("cyclone-skill-capsule-v4")

    private fun <T> decodeArray(key: String, decoder: (JSONObject) -> T): List<T> {
        val array = rawArray(key)
        return buildList {
            for (i in 0 until array.length()) runCatching { decoder(array.getJSONObject(i)) }.getOrNull()?.let(::add)
        }
    }

    private fun replaceById(key: String, id: String, value: JSONObject) {
        val array = rawArray(key)
        val values = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) array.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let(values::add)
        values.add(value)
        prefs.putString(key, JSONArray(values).toString())
        mutableRevision.value += 1
    }

    private fun deleteById(key: String, id: String) {
        val array = rawArray(key)
        val values = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) array.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let(values::add)
        prefs.putString(key, JSONArray(values).toString())
        mutableRevision.value += 1
    }

    private fun rawArray(key: String) = runCatching { JSONArray(prefs.getString(key, "[]") ?: "[]") }.getOrElse { JSONArray() }
    private fun rawObject(key: String) = runCatching { JSONObject(prefs.getString(key, "{}") ?: "{}") }.getOrElse { JSONObject() }

    private fun runToJson(run: AutomationRun) = JSONObject().apply {
        put("id", run.id)
        put("automationId", run.automationId)
        put("automationName", run.automationName)
        put("state", run.state.name)
        put("trigger", JSONObject().put("type", run.trigger.type.name).put("payload", JSONObject(run.trigger.payload)).put("timestamp", run.trigger.timestamp))
        put("startedAt", run.startedAt)
        put("endedAt", run.endedAt ?: JSONObject.NULL)
        put("error", run.error ?: JSONObject.NULL)
        put("variables", JSONObject(run.variables))
        put("steps", JSONArray().apply { run.steps.forEach { step ->
            put(JSONObject().apply {
                put("stepId", step.stepId); put("name", step.name); put("state", step.state.name)
                put("startedAt", step.startedAt ?: JSONObject.NULL); put("endedAt", step.endedAt ?: JSONObject.NULL)
                put("attempt", step.attempt); put("message", step.message ?: JSONObject.NULL); put("output", JSONObject(step.output))
            })
        } })
    }

    private fun runFromJson(json: JSONObject): AutomationRun {
        val trigger = json.getJSONObject("trigger")
        val stepsJson = json.optJSONArray("steps") ?: JSONArray()
        val steps = List(stepsJson.length()) { index ->
            val step = stepsJson.getJSONObject(index)
            RunStepRecord(
                stepId = step.getString("stepId"), name = step.getString("name"), state = enumValueOf(step.getString("state")),
                startedAt = step.optLongOrNull("startedAt"), endedAt = step.optLongOrNull("endedAt"), attempt = step.optInt("attempt"),
                message = step.optStringOrNull("message"), output = step.optJSONObject("output").toStringMap()
            )
        }
        return AutomationRun(
            id = json.getString("id"), automationId = json.getString("automationId"), automationName = json.getString("automationName"),
            state = enumValueOf(json.getString("state")), trigger = TriggerEvent(enumValueOf(trigger.getString("type")), trigger.optJSONObject("payload").toStringMap(), trigger.optLong("timestamp")),
            startedAt = json.optLong("startedAt"), endedAt = json.optLongOrNull("endedAt"), steps = steps,
            variables = json.optJSONObject("variables").toStringMap(), error = json.optStringOrNull("error")
        )
    }

    private fun checkpointToJson(value: Checkpoint) = JSONObject().put("runId", value.runId).put("automationId", value.automationId)
        .put("nextStepIndex", value.nextStepIndex).put("variables", JSONObject(value.variables)).put("waitingForHuman", value.waitingForHuman).put("updatedAt", value.updatedAt)

    private fun checkpointFromJson(json: JSONObject) = Checkpoint(json.getString("runId"), json.getString("automationId"), json.getInt("nextStepIndex"), json.optJSONObject("variables").toStringMap(), json.optBoolean("waitingForHuman"), json.optLong("updatedAt"))

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        return buildMap { keys().forEach { key -> put(key, optString(key)) } }
    }
    private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
    private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)

    companion object {
        private const val KEY_AUTOMATIONS = "automations"
        private const val KEY_SKILLS = "skills"
        private const val KEY_RUNS = "runs"
        private const val KEY_CHECKPOINTS = "checkpoints"
        private const val MAX_RUNS = 100

        /** JVM/unit tests and compile path. Same class as production; no second store. */
        fun inMemory(): AutomationStore = AutomationStore(MemoryAutomationPrefs())
    }
}

internal interface AutomationPrefs {
    fun getString(key: String, def: String?): String?
    fun putString(key: String, value: String)
}

private const val PREFS_FILE = "cyclone_automation_studio"

private class SharedAutomationPrefs(context: Context) : AutomationPrefs {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    override fun getString(key: String, def: String?): String? = prefs.getString(key, def)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

private class MemoryAutomationPrefs : AutomationPrefs {
    private val data = linkedMapOf<String, String>()
    override fun getString(key: String, def: String?): String? = data[key] ?: def
    override fun putString(key: String, value: String) {
        data[key] = value
    }
}
