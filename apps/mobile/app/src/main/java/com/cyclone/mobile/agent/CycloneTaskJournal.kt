package com.cyclone.mobile.agent

import android.content.Context
import com.cyclone.mobile.CycloneAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable, privacy-bounded checkpoint journal for the local agent state machine.
 *
 * Element IDs and raw observations are intentionally not stored here. A process restart restores the
 * goal and verified execution/recovery state, then the runtime must acquire a fresh observation before
 * taking another action. This makes checkpoints useful without resurrecting stale Android references.
 */
object CycloneTaskJournal {
    private const val DIRECTORY = "cyclone_agent_checkpoints"
    private const val MAX_FILES = 24

    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun save(state: CycloneTaskState) {
        val context = contextOrNull() ?: return
        val directory = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        val target = File(directory, safeName(state.taskId) + ".json")
        val temp = File(directory, target.name + ".tmp")
        runCatching {
            temp.writeText(toJson(state).toString())
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
            prune(directory)
        }
    }

    fun load(taskId: String): CycloneTaskState? {
        val context = contextOrNull() ?: return null
        val file = File(File(context.filesDir, DIRECTORY), safeName(taskId) + ".json")
        return read(file)
    }

    fun latestResumable(): CycloneTaskState? {
        val context = contextOrNull() ?: return null
        val directory = File(context.filesDir, DIRECTORY)
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == "json" }
            .sortedByDescending(File::lastModified)
            .mapNotNull(::read)
            .firstOrNull { it.currentStage != CycloneAgentStage.TERMINAL }
    }

    fun remove(taskId: String) {
        val context = contextOrNull() ?: return
        File(File(context.filesDir, DIRECTORY), safeName(taskId) + ".json").delete()
    }

    private fun contextOrNull(): Context? {
        appContext?.let { return it }
        val resolved = CycloneAccessibilityService.instance?.applicationContext ?: return null
        appContext = resolved
        return resolved
    }

    private fun toJson(state: CycloneTaskState): JSONObject = JSONObject()
        .put("schema", 2)
        .put("incident", state.incident?.toJson() ?: JSONObject.NULL)
        .put("executionSessionId", state.executionSessionId).put("executionDisplayId", state.executionDisplayId)
        .put("observationGeneration", state.observationGeneration).put("executionPackageName", state.executionPackageName)
        .put("taskId", state.taskId)
        .put("goal", state.goal.take(2_000))
        .put("currentStage", state.currentStage.name)
        .put("latestObservationIdentity", state.latestObservationIdentity ?: JSONObject.NULL)
        .put("latestPageIdentity", state.latestPageIdentity ?: JSONObject.NULL)
        .put("recentSuccessfulVerifiedActions", JSONArray(state.recentSuccessfulVerifiedActions.takeLast(24)))
        .put("recentFailedActions", JSONArray(state.recentFailedActions.takeLast(24)))
        .put("recoveryAttempts", JSONObject().also { out -> state.recoveryAttempts.forEach { (k, v) -> out.put(k.name, v) } })
        .put("visionUseState", JSONObject().also { out -> state.visionUseState.forEach { (k, v) -> out.put(k.take(200), v) } })
        .put("taskStartTimeMs", state.taskStartTimeMs)
        .put("lastVerifiedProgressTimeMs", state.lastVerifiedProgressTimeMs)
        .put("gateSuspended", state.gateSuspended)
        .put("requireFreshObservation", true)
        .put("finalClassification", state.finalClassification?.name ?: JSONObject.NULL)
        .put("modelTurns", state.modelTurns)
        .put("consecutiveRecoveryCyclesWithoutNewEvidence", state.consecutiveRecoveryCyclesWithoutNewEvidence)
        .put("repeatedIdenticalActionWithoutProgress", state.repeatedIdenticalActionWithoutProgress)
        .put("backtrackAttempts", state.backtrackAttempts)
        .put("staleTargetRetries", state.staleTargetRetries)
        .put("lastActionSignature", state.lastActionSignature?.take(200) ?: JSONObject.NULL)

    private fun read(file: File): CycloneTaskState? = runCatching {
        if (!file.isFile || file.length() > 256_000L) return@runCatching null
        val json = JSONObject(file.readText())
        if (json.optInt("schema") !in 1..2) return@runCatching null
        CycloneTaskState(
            incident = json.optJSONObject("incident")?.let(com.cyclone.mobile.agent.recovery.RecoveryIncident::fromJson),
            executionSessionId = json.optString("executionSessionId", "default-foreground"),
            executionDisplayId = json.optInt("executionDisplayId"),
            observationGeneration = json.optLong("observationGeneration"),
            executionPackageName = json.optString("executionPackageName"),
            taskId = json.getString("taskId"),
            goal = json.getString("goal"),
            currentStage = enumValueOf<CycloneAgentStage>(json.getString("currentStage")),
            latestObservationIdentity = json.optString("latestObservationIdentity").takeIf { it.isNotBlank() && it != "null" },
            latestPageIdentity = json.optString("latestPageIdentity").takeIf { it.isNotBlank() && it != "null" },
            recentSuccessfulVerifiedActions = strings(json.optJSONArray("recentSuccessfulVerifiedActions")),
            recentFailedActions = strings(json.optJSONArray("recentFailedActions")),
            recoveryAttempts = enumIntMap(json.optJSONObject("recoveryAttempts")),
            visionUseState = stringIntMap(json.optJSONObject("visionUseState")),
            taskStartTimeMs = json.optLong("taskStartTimeMs"),
            lastVerifiedProgressTimeMs = json.optLong("lastVerifiedProgressTimeMs"),
            gateSuspended = json.optBoolean("gateSuspended"),
            // Process death invalidates every Android target, even if the saved checkpoint was fresh.
            requireFreshObservation = true,
            finalClassification = json.optString("finalClassification")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { enumValueOf<CycloneTaskClassification>(it) },
            modelTurns = json.optInt("modelTurns"),
            consecutiveRecoveryCyclesWithoutNewEvidence = json.optInt("consecutiveRecoveryCyclesWithoutNewEvidence"),
            repeatedIdenticalActionWithoutProgress = json.optInt("repeatedIdenticalActionWithoutProgress"),
            backtrackAttempts = json.optInt("backtrackAttempts"),
            staleTargetRetries = json.optInt("staleTargetRetries"),
            lastActionSignature = json.optString("lastActionSignature").takeIf { it.isNotBlank() && it != "null" },
        )
    }.getOrNull()

    private fun strings(array: JSONArray?): List<String> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) array.optString(index).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun enumIntMap(json: JSONObject?): Map<CycloneRecoveryKind, Int> = buildMap {
        if (json == null) return@buildMap
        json.keys().forEach { key -> runCatching { enumValueOf<CycloneRecoveryKind>(key) }.getOrNull()?.let { put(it, json.optInt(key)) } }
    }

    private fun stringIntMap(json: JSONObject?): Map<String, Int> = buildMap {
        if (json == null) return@buildMap
        json.keys().forEach { key -> put(key, json.optInt(key)) }
    }

    private fun prune(directory: File) {
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .sortedByDescending(File::lastModified)
            .drop(MAX_FILES)
            .forEach(File::delete)
    }

    private fun safeName(taskId: String): String = taskId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
}
