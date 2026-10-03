package com.cyclone.mobile.mind.mission

import com.cyclone.mobile.mind.MindCheckpoint
import com.cyclone.mobile.mind.MindConversation
import com.cyclone.mobile.mind.MindMessage
import com.cyclone.mobile.mind.MindUsage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The resumable part of a mission: its conversation (redacted, no images, no provider reasoning) and its counters. */
data class MissionJournal(
    val conversation: MindConversation,
    val turn: Int,
    val workingMs: Long,
    val usage: MindUsage,
    val nativeTools: Boolean,
    val modelId: String,
    /** Plan 37: the mission workspace, when the mission ran with one. */
    val workspace: JSONObject? = null,
) {
    fun checkpoint(): MindCheckpoint = MindCheckpoint(turn, workingMs, usage, nativeTools, modelId, conversation, workspace)
}

/**
 * Missions on disk, one metadata file and one journal per mission, written atomically after every turn. Secrets
 * never reach the store: the Secrets Card fills fields without the value entering the conversation, and everything
 * written passes through [MindRedaction] as a second line of defence.
 */
class MissionStore(private val root: File, private val keep: Int = 40) {
    init { root.mkdirs() }

    @Synchronized fun save(mission: Mission) = write(meta(mission.id), mission.toJson().toString())

    @Synchronized fun saveJournal(missionId: String, checkpoint: MindCheckpoint) {
        val json = JSONObject()
            .put("schema", JOURNAL_SCHEMA)
            .put("turn", checkpoint.turn)
            .put("workingMs", checkpoint.workingMs)
            .put("usage", JSONObject().put("prompt", checkpoint.usage.promptTokens).put("completion", checkpoint.usage.completionTokens)
                .put("cost", checkpoint.usage.costUsd))
            .put("nativeTools", checkpoint.nativeTools)
            .put("modelId", checkpoint.modelId)
            .put("conversation", redact(checkpoint.conversation).toJournal())
            .put("workspace", checkpoint.workspace?.let { runCatching { JSONObject(MindRedaction.scrub(it.toString())) }.getOrNull() } ?: JSONObject.NULL)
        write(journal(missionId), json.toString())
    }

    @Synchronized fun load(id: String): Mission? = read(meta(id))?.let { runCatching { Mission.fromJson(JSONObject(it)) }.getOrNull() }

    @Synchronized fun loadJournal(id: String): MissionJournal? {
        val json = read(journal(id))?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val usage = json.optJSONObject("usage")
        return MissionJournal(
            conversation = MindConversation.fromJournal(json.optJSONArray("conversation") ?: JSONArray()),
            turn = json.optInt("turn"),
            workingMs = json.optLong("workingMs"),
            usage = MindUsage(usage?.optInt("prompt") ?: 0, usage?.optInt("completion") ?: 0, usage?.optDouble("cost", 0.0) ?: 0.0),
            nativeTools = json.optBoolean("nativeTools", true),
            modelId = json.optString("modelId"),
            workspace = json.optJSONObject("workspace"),
        )
    }

    /** Newest first. */
    @Synchronized fun list(): List<Mission> = (root.listFiles { file -> file.name.endsWith(META_SUFFIX) } ?: emptyArray())
        .mapNotNull { file -> runCatching { Mission.fromJson(JSONObject(file.readText())) }.getOrNull() }
        .sortedByDescending { it.updatedAtMs }

    @Synchronized fun delete(id: String) {
        meta(id).delete()
        journal(id).delete()
        trail(id).delete()
    }

    /** What the mission saw and did, for Learn. Structure only (see MissionTrail). */
    @Synchronized fun saveTrail(trail: com.cyclone.mobile.mind.learn.MissionTrail) = write(trail(trail.missionId), trail.toJson().toString())

    @Synchronized fun loadTrail(id: String): com.cyclone.mobile.mind.learn.MissionTrail? =
        read(trail(id))?.let { runCatching { com.cyclone.mobile.mind.learn.MissionTrail.fromJson(JSONObject(it)) }.getOrNull() }

    /** After a restart nothing is running: live missions become interrupted, and old ones are pruned. */
    @Synchronized fun recover(now: Long, liveId: String? = null): List<Mission> {
        val all = list()
        all.filter { it.status.live && it.id != liveId }.forEach { save(it.copy(status = MissionStatus.INTERRUPTED, waitingFor = null, updatedAtMs = now)) }
        all.drop(keep).filter { !it.status.live }.forEach { delete(it.id) }
        return list()
    }

    private fun redact(conversation: MindConversation): MindConversation = MindConversation(conversation.all().map { message ->
        when (message) {
            is MindMessage.System -> message
            is MindMessage.User -> message.copy(text = MindRedaction.scrub(message.text), imageDataUrl = null)
            is MindMessage.Assistant -> message.copy(text = MindRedaction.scrub(message.text), reasoningDetails = null,
                toolCalls = message.toolCalls.map { call ->
                    val scrubbed = MindRedaction.scrub(call.arguments)
                    // Arguments must stay valid JSON so a resumed conversation can be sent back to the provider.
                    call.copy(arguments = if (runCatching { JSONObject(scrubbed.ifBlank { "{}" }) }.isSuccess) scrubbed
                        else JSONObject().put("redacted", true).toString())
                })
            is MindMessage.Tool -> message.copy(full = MindRedaction.scrub(message.full), brief = MindRedaction.scrub(message.brief))
        }
    })

    private fun meta(id: String) = File(root, safe(id) + META_SUFFIX)
    private fun journal(id: String) = File(root, safe(id) + JOURNAL_SUFFIX)
    private fun trail(id: String) = File(root, safe(id) + ".trail.json")
    private fun safe(id: String): String {
        require(ID.matches(id)) { "invalid mission id" }
        return id
    }

    private fun read(file: File): String? = file.takeIf { it.isFile }?.readText()

    private fun write(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "could not write ${file.name}" }
        }
    }

    companion object {
        const val JOURNAL_SCHEMA = "cyclone-mission-journal-v1"
        private const val META_SUFFIX = ".mission.json"
        private const val JOURNAL_SUFFIX = ".journal.json"
        private val ID = Regex("^[A-Za-z0-9_-]{6,80}$")
    }
}
