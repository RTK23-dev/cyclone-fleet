package com.cyclone.mobile.mind.mission

import com.cyclone.mobile.mind.MindPlanStep
import com.cyclone.mobile.mind.MindUsage
import org.json.JSONArray
import org.json.JSONObject

enum class MissionStatus(val live: Boolean, val resumable: Boolean) {
    RUNNING(true, false),
    WAITING(true, false),
    COMPLETED(false, false),
    GAVE_UP(false, false),
    FAILED(false, true),
    CANCELLED(false, false),
    /** Working time or turns ran out; the conversation is intact and can continue. */
    PAUSED(false, true),
    /** The app or phone stopped mid-mission. */
    INTERRUPTED(false, true),
}

data class MissionEvent(val atMs: Long, val text: String, val ok: Boolean = true) {
    fun toJson(): JSONObject = JSONObject().put("at", atMs).put("text", text).put("ok", ok)
    companion object {
        fun fromJson(json: JSONObject) = MissionEvent(json.optLong("at"), json.optString("text"), json.optBoolean("ok", true))
    }
}

/** A mission as the owner sees it. The conversation itself lives next to it in the store. */
data class Mission(
    val id: String,
    val goal: String,
    val status: MissionStatus,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val modelId: String,
    val modelLabel: String,
    val turns: Int = 0,
    val workingMs: Long = 0,
    val usage: MindUsage = MindUsage(),
    val summary: String = "",
    val evidence: String = "",
    val plan: List<MindPlanStep> = emptyList(),
    val events: List<MissionEvent> = emptyList(),
    val traceId: String? = null,
    val nativeTools: Boolean = true,
    val resumes: Int = 0,
    val waitingFor: String? = null,
    /** Set when Cyclone Lab started this mission: the experiment run and the variant that produced it. */
    val lab: com.cyclone.mobile.mind.lab.MissionLab? = null,
    /** What the mission did (tool counts, failures, waits); see MissionMetrics. */
    val metrics: JSONObject? = null,
    /** Set when the owner pressed Learn on this run: what Cyclone learned from it. */
    val learned: com.cyclone.mobile.mind.learn.LearnReport? = null,
    /** Plan 38: the plan's version (1 until the plan diverts), its label and earlier versions for "See v1". */
    val planVersion: Int = 1,
    val planLabel: String? = null,
    val planHistory: List<List<MindPlanStep>> = emptyList(),
    /** Plan 38: the owner paused the mission; it holds at its next step until resumed. */
    val paused: Boolean = false,
) {
    fun withEvent(event: MissionEvent): Mission = copy(events = (events + event).takeLast(MAX_EVENTS), updatedAtMs = event.atMs)

    fun toJson(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("id", id).put("goal", goal).put("status", status.name)
        .put("createdAt", createdAtMs).put("updatedAt", updatedAtMs)
        .put("modelId", modelId).put("modelLabel", modelLabel)
        .put("turns", turns).put("workingMs", workingMs)
        .put("usage", JSONObject().put("prompt", usage.promptTokens).put("completion", usage.completionTokens).put("cost", usage.costUsd))
        .put("summary", summary).put("evidence", evidence)
        .put("plan", stepsJson(plan))
        .put("planVersion", planVersion)
        .put("planLabel", planLabel ?: JSONObject.NULL)
        .put("planHistory", JSONArray().also { array -> planHistory.forEach { array.put(stepsJson(it)) } })
        .put("paused", paused)
        .put("events", JSONArray().also { array -> events.forEach { array.put(it.toJson()) } })
        .put("traceId", traceId ?: JSONObject.NULL)
        .put("nativeTools", nativeTools)
        .put("resumes", resumes)
        .put("waitingFor", waitingFor ?: JSONObject.NULL)
        .put("lab", lab?.toJson() ?: JSONObject.NULL)
        .put("metrics", metrics ?: JSONObject.NULL)
        .put("learned", learned?.toJson() ?: JSONObject.NULL)

    companion object {
        const val SCHEMA = "cyclone-mission-v1"
        const val MAX_EVENTS = 60

        fun stepsJson(steps: List<MindPlanStep>): JSONArray = JSONArray().also { array ->
            steps.forEach { array.put(JSONObject().put("step", it.text).put("status", it.status).put("note", it.note ?: JSONObject.NULL).put("branch", it.branch)) }
        }

        fun steps(array: JSONArray?): List<MindPlanStep> = array?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let {
                MindPlanStep(it.optString("step"), it.optString("status"), it.optString("note").takeUnless { _ -> it.isNull("note") || it.optString("note").isBlank() },
                    it.optBoolean("branch")) } }
        }.orEmpty()

        fun fromJson(json: JSONObject): Mission {
            val usage = json.optJSONObject("usage")
            return Mission(
                id = json.getString("id"),
                goal = json.optString("goal"),
                status = runCatching { MissionStatus.valueOf(json.optString("status")) }.getOrDefault(MissionStatus.INTERRUPTED),
                createdAtMs = json.optLong("createdAt"),
                updatedAtMs = json.optLong("updatedAt"),
                modelId = json.optString("modelId"),
                modelLabel = json.optString("modelLabel"),
                turns = json.optInt("turns"),
                workingMs = json.optLong("workingMs"),
                usage = MindUsage(usage?.optInt("prompt") ?: 0, usage?.optInt("completion") ?: 0, usage?.optDouble("cost", 0.0) ?: 0.0),
                summary = json.optString("summary"),
                evidence = json.optString("evidence"),
                plan = steps(json.optJSONArray("plan")),
                planVersion = json.optInt("planVersion", 1).coerceAtLeast(1),
                planLabel = json.optString("planLabel").takeUnless { json.isNull("planLabel") || it.isBlank() },
                planHistory = json.optJSONArray("planHistory")?.let { a -> (0 until a.length()).map { steps(a.optJSONArray(it)) } }.orEmpty(),
                paused = json.optBoolean("paused"),
                events = json.optJSONArray("events")?.let { array ->
                    (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(MissionEvent::fromJson) }
                }.orEmpty(),
                traceId = json.optString("traceId").takeUnless { json.isNull("traceId") || it.isBlank() },
                nativeTools = json.optBoolean("nativeTools", true),
                resumes = json.optInt("resumes"),
                waitingFor = json.optString("waitingFor").takeUnless { json.isNull("waitingFor") || it.isBlank() },
                lab = com.cyclone.mobile.mind.lab.MissionLab.fromJson(json.optJSONObject("lab")),
                metrics = json.optJSONObject("metrics"),
                learned = com.cyclone.mobile.mind.learn.LearnReport.fromJson(json.optJSONObject("learned")),
            )
        }
    }
}
