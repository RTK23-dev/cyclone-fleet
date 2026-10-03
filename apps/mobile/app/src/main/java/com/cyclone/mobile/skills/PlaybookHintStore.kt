package com.cyclone.mobile.skills

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap

/**
 * Per-package NL playbook store. Successful Fast Path runs merge by selector fingerprint;
 * a user override replaces the matching playbook and is preferred at compile time.
 *
 * Secrets never persist. Coordinate-only steps are rejected before write.
 */
class PlaybookHintStore internal constructor(
    private val persist: PlaybookPersist,
) {
    fun recordSuccess(hint: PlaybookHint, nowMs: Long = hint.lastSuccessAtMs): PlaybookHint? {
        val sanitized = sanitize(hint, nowMs) ?: return null
        synchronized(this) {
            val existing = persist.load().toMutableMap()
            val prior = existing[sanitized.mergeKey]
            val merged = if (prior == null) {
                sanitized
            } else {
                prior.copy(
                    goal = sanitized.goal,
                    nlPlaybook = preferPlaybook(prior, sanitized),
                    successCount = prior.successCount + sanitized.successCount,
                    lastSuccessAtMs = maxOf(prior.lastSuccessAtMs, sanitized.lastSuccessAtMs),
                    source = if (prior.source == PlaybookSource.USER_OVERRIDE) PlaybookSource.USER_OVERRIDE else sanitized.source,
                    userOverride = prior.userOverride || sanitized.userOverride,
                    steps = if (prior.userOverride) prior.steps else sanitized.steps,
                )
            }
            existing[merged.mergeKey] = merged
            persist.save(existing)
            return merged
        }
    }

    fun mergeUserOverride(hint: PlaybookHint, nowMs: Long = hint.lastSuccessAtMs): PlaybookHint? {
        val sanitized = sanitize(hint.copy(source = PlaybookSource.USER_OVERRIDE, userOverride = true), nowMs)
            ?: return null
        synchronized(this) {
            val existing = persist.load().toMutableMap()
            val prior = existing[sanitized.mergeKey]
            val merged = sanitized.copy(
                successCount = maxOf(sanitized.successCount, prior?.successCount ?: 0, SkillRouteCompiler.MIN_SUCCESSES),
                lastSuccessAtMs = nowMs,
                source = PlaybookSource.USER_OVERRIDE,
                userOverride = true,
            )
            existing[merged.mergeKey] = merged
            persist.save(existing)
            return merged
        }
    }

    fun list(packageName: String? = null): List<PlaybookHint> = synchronized(this) {
        persist.load().values
            .filter { packageName == null || it.packageName == packageName }
            .sortedWith(
                compareByDescending<PlaybookHint> { it.userOverride }
                    .thenByDescending { it.successCount }
                    .thenByDescending { it.lastSuccessAtMs },
            )
    }

    fun find(
        packageName: String,
        goal: String,
        startPageKey: String,
        sessionId: String,
        displayId: Int,
    ): List<PlaybookHint> {
        val signature = PlaybookGoal.signature(goal)
        return list(packageName).filter {
            it.goalSignature == signature &&
                it.startPageKey == startPageKey &&
                it.sessionId == sessionId &&
                it.displayId == displayId
        }
    }

    fun clear() = synchronized(this) { persist.save(emptyMap()) }

    private fun sanitize(hint: PlaybookHint, nowMs: Long): PlaybookHint? {
        if (!PlaybookSafety.goalAllowed(hint.goal)) return null
        if (!PlaybookSafety.workspaceDisplayLegal(hint.sessionId, hint.displayId)) return null
        if (hint.steps.size < 2) return null
        val steps = hint.steps.map { step ->
            if (!PlaybookSafety.toolAllowed(step.tool)) return null
            val safeParams = PlaybookSafety.stripParams(step.params)
            if (!PlaybookSafety.paramsSafe(safeParams)) return null
            step.copy(params = safeParams, nl = step.nl.take(240))
        }
        return hint.copy(
            goalSignature = PlaybookGoal.signature(hint.goal),
            nlPlaybook = hint.nlPlaybook.ifBlank { PlaybookGoal.nlPlaybook(steps) }.take(1_200),
            steps = steps,
            lastSuccessAtMs = nowMs,
            successCount = hint.successCount.coerceAtLeast(1),
        )
    }

    private fun preferPlaybook(prior: PlaybookHint, incoming: PlaybookHint): String {
        if (incoming.userOverride) return incoming.nlPlaybook
        if (prior.userOverride) return prior.nlPlaybook
        return if (incoming.nlPlaybook.length >= prior.nlPlaybook.length) incoming.nlPlaybook else prior.nlPlaybook
    }

    companion object {
        fun inMemory(): PlaybookHintStore = PlaybookHintStore(MemoryPlaybookPersist())

        fun files(directory: File): PlaybookHintStore = PlaybookHintStore(FilePlaybookPersist(directory))
    }
}

internal interface PlaybookPersist {
    fun load(): Map<String, PlaybookHint>
    fun save(values: Map<String, PlaybookHint>)
}

internal class MemoryPlaybookPersist : PlaybookPersist {
    private val data = LinkedHashMap<String, PlaybookHint>()
    override fun load(): Map<String, PlaybookHint> = LinkedHashMap(data)
    override fun save(values: Map<String, PlaybookHint>) {
        data.clear()
        data.putAll(values)
    }
}

internal class FilePlaybookPersist(private val directory: File) : PlaybookPersist {
    private val file get() = File(directory, "playbooks.json")

    override fun load(): Map<String, PlaybookHint> {
        if (!file.isFile) return emptyMap()
        val parsed = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyMap()
        val array = parsed.optJSONArray("playbooks") ?: return emptyMap()
        val out = LinkedHashMap<String, PlaybookHint>()
        for (i in 0 until array.length()) {
            val hint = runCatching { hintFromJson(array.getJSONObject(i)) }.getOrNull() ?: continue
            out[hint.mergeKey] = hint
        }
        return out
    }

    override fun save(values: Map<String, PlaybookHint>) {
        directory.mkdirs()
        val array = JSONArray()
        values.values.forEach { array.put(it.toJson()) }
        file.writeText(JSONObject().put("playbooks", array).toString())
    }
}

internal fun hintFromJson(json: JSONObject): PlaybookHint {
    val stepsJson = json.getJSONArray("steps")
    val steps = buildList {
        for (i in 0 until stepsJson.length()) {
            val step = stepsJson.getJSONObject(i)
            val selector = SemanticSelector.fromJson(step.optJSONObject("selector"))
                ?: error("playbook step missing semantic selector")
            val paramsObj = step.optJSONObject("params") ?: JSONObject()
            val params = buildMap {
                paramsObj.keys().forEach { key ->
                    val value = paramsObj.optString(key)
                    if (value.isNotBlank()) put(key, value)
                }
            }
            add(
                PlaybookHintStep(
                    nl = step.getString("nl"),
                    tool = step.getString("tool"),
                    selector = selector,
                    beforePageKey = step.getString("beforePageKey"),
                    afterPageKey = step.getString("afterPageKey"),
                    expectedPageChange = step.optBoolean("expectedPageChange", true),
                    params = PlaybookSafety.stripParams(params),
                ),
            )
        }
    }
    return PlaybookHint(
        packageName = json.getString("packageName"),
        goal = json.getString("goal"),
        goalSignature = json.optString("goalSignature").ifBlank { PlaybookGoal.signature(json.getString("goal")) },
        startPageKey = json.getString("startPageKey"),
        sessionId = json.getString("sessionId"),
        displayId = json.getInt("displayId"),
        steps = steps,
        nlPlaybook = json.optString("nlPlaybook").ifBlank { PlaybookGoal.nlPlaybook(steps) },
        successCount = json.optInt("successCount", 1),
        source = runCatching { PlaybookSource.valueOf(json.optString("source", PlaybookSource.FAST_PATH.name)) }
            .getOrDefault(PlaybookSource.FAST_PATH),
        lastSuccessAtMs = json.optLong("lastSuccessAtMs"),
        userOverride = json.optBoolean("userOverride"),
    )
}
