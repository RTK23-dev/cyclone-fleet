package com.cyclone.mobile.runtime.plane

import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A plane switch is a transaction (plan 25 §4.2): pause Cyclone at a step boundary, move the task, verify it is healthy
 * where it landed, then commit, or put it back where it was. Every phase is journaled so a process death mid-switch
 * ends either committed or rolled back, never half-moved. Pure: the phone is behind [PlanePort].
 */
enum class SwitchPhase { REQUESTED, PAUSED, MOVED, COMMITTED, ROLLED_BACK;
    val terminal: Boolean get() = this == COMMITTED || this == ROLLED_BACK
}

data class SwitchRequest(val missionId: String, val from: TaskPlane, val to: PlaneKind, val reason: String)

data class SwitchRecord(
    val id: String,
    val missionId: String,
    val from: TaskPlane,
    val to: PlaneKind,
    val reason: String,
    val phase: SwitchPhase,
    val landed: TaskPlane? = null,
    val detail: String? = null,
    val startedAtMs: Long,
    val updatedAtMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("missionId", missionId).put("from", from.toJson())
        .put("to", to.wire).put("reason", reason).put("phase", phase.name).put("landed", landed?.toJson() ?: JSONObject.NULL)
        .put("detail", detail ?: JSONObject.NULL).put("startedAt", startedAtMs).put("updatedAt", updatedAtMs)

    companion object {
        fun fromJson(json: JSONObject): SwitchRecord? = runCatching {
            SwitchRecord(
                json.getString("id"), json.getString("missionId"), TaskPlane.fromJson(json.getJSONObject("from"))!!,
                PlaneKind.fromWire(json.getString("to"))!!, json.optString("reason"), SwitchPhase.valueOf(json.getString("phase")),
                TaskPlane.fromJson(json.optJSONObject("landed")), json.optString("detail").takeUnless { json.isNull("detail") },
                json.optLong("startedAt"), json.optLong("updatedAt"),
            )
        }.getOrNull()
    }
}

sealed class SwitchOutcome {
    data class Committed(val plane: TaskPlane, val tookMs: Long) : SwitchOutcome()
    /** The switch failed and the task is on [plane] (where it was, unless restoring also failed). */
    data class RolledBack(val reason: String, val plane: TaskPlane, val tookMs: Long) : SwitchOutcome()
    data class Refused(val reason: String) : SwitchOutcome()
}

/** How a switch touches the phone. Production: [AndroidPlanePort]; tests: a scripted fake. */
interface PlanePort {
    /** Wait for any in-flight action to finish, then take input away from Cyclone on [from]. False if it could not. */
    fun pause(from: TaskPlane): Boolean
    /** Move the task to the other plane and return where it landed. Throws with a readable reason on failure. */
    fun move(from: TaskPlane, to: PlaneKind): TaskPlane
    /** Null when [plane] is healthy (task present, fresh screen); otherwise why not. */
    fun verify(plane: TaskPlane): String?
    /** After a failure: put the task back on [original]. True when it is there. */
    fun restore(original: TaskPlane, attempted: TaskPlane?): Boolean
    /** Give Cyclone input on [plane] again, with a new generation (old actions expire). */
    fun grant(plane: TaskPlane)
    /** Where the task actually is right now; null when it cannot be told. */
    fun locate(missionId: String): TaskPlane?
}

interface PlaneJournal {
    fun write(record: SwitchRecord)
    /** The last switch that did not reach a terminal phase (a process died during it), if any. */
    fun open(): SwitchRecord?
    fun recent(limit: Int = 20): List<SwitchRecord>
}

class PlaneSwitcher(
    private val port: PlanePort,
    private val journal: PlaneJournal,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val busy = AtomicBoolean(false)

    val switching: Boolean get() = busy.get()

    fun switch(request: SwitchRequest): SwitchOutcome {
        if (request.from.kind == request.to) return SwitchOutcome.Refused("The task is already ${request.to.label.lowercase()}.")
        if (!busy.compareAndSet(false, true)) return SwitchOutcome.Refused("A switch is already in progress.")
        val started = clock()
        var record = SwitchRecord(newId(), request.missionId, request.from, request.to, request.reason, SwitchPhase.REQUESTED,
            startedAtMs = started, updatedAtMs = started)
        fun advance(phase: SwitchPhase, landed: TaskPlane? = record.landed, detail: String? = record.detail) {
            record = record.copy(phase = phase, landed = landed, detail = detail, updatedAtMs = clock())
            runCatching { journal.write(record) }
        }
        try {
            runCatching { journal.write(record) }
            if (!runCatching { port.pause(request.from) }.getOrDefault(false)) {
                runCatching { port.grant(request.from) }
                advance(SwitchPhase.ROLLED_BACK, request.from, "Cyclone could not pause at a safe point.")
                return SwitchOutcome.RolledBack("Cyclone was in the middle of an action. Try again in a moment.", request.from, clock() - started)
            }
            advance(SwitchPhase.PAUSED)
            val landed = try {
                port.move(request.from, request.to)
            } catch (error: Exception) {
                return rollback(request, null, readable(error), started) { phase, plane, detail -> advance(phase, plane, detail) }
            }
            if (landed.kind != request.to) {
                return rollback(request, landed, "The task did not arrive ${request.to.label.lowercase()}.", started) { phase, plane, detail -> advance(phase, plane, detail) }
            }
            advance(SwitchPhase.MOVED, landed)
            val unhealthy = runCatching { port.verify(landed) }.getOrElse { readable(it) }
            if (unhealthy != null) {
                return rollback(request, landed, unhealthy, started) { phase, plane, detail -> advance(phase, plane, detail) }
            }
            runCatching { port.grant(landed) }.onFailure { error ->
                return rollback(request, landed, readable(error), started) { phase, plane, detail -> advance(phase, plane, detail) }
            }
            advance(SwitchPhase.COMMITTED, landed, null)
            return SwitchOutcome.Committed(landed, clock() - started)
        } finally {
            busy.set(false)
        }
    }

    private inline fun rollback(
        request: SwitchRequest,
        attempted: TaskPlane?,
        reason: String,
        started: Long,
        advance: (SwitchPhase, TaskPlane?, String?) -> Unit,
    ): SwitchOutcome {
        val back = runCatching { port.restore(request.from, attempted) }.getOrDefault(false)
        val where = if (back) request.from else runCatching { port.locate(request.missionId) }.getOrNull() ?: request.from
        runCatching { port.grant(where) }
        advance(SwitchPhase.ROLLED_BACK, where, reason)
        return SwitchOutcome.RolledBack(reason, where, clock() - started)
    }

    /**
     * After a restart: a switch that never finished is closed against where the task really is. Committed when the
     * task is on the requested plane, rolled back otherwise. Null when nothing was open.
     */
    fun recover(): SwitchOutcome? {
        val open = runCatching { journal.open() }.getOrNull() ?: return null
        val where = runCatching { port.locate(open.missionId) }.getOrNull() ?: open.landed ?: open.from
        runCatching { port.grant(where) }
        val done = open.copy(landed = where, updatedAtMs = clock(), detail = "Closed after a restart",
            phase = if (where.kind == open.to) SwitchPhase.COMMITTED else SwitchPhase.ROLLED_BACK)
        runCatching { journal.write(done) }
        return if (done.phase == SwitchPhase.COMMITTED) SwitchOutcome.Committed(where, done.updatedAtMs - open.startedAtMs)
        else SwitchOutcome.RolledBack("The switch was interrupted by a restart.", where, done.updatedAtMs - open.startedAtMs)
    }

    private fun readable(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).substringAfter(": ").take(200).ifBlank { "The switch failed." }
}

/** Append-only journal on disk, bounded; the last record per switch id wins. */
class FilePlaneJournal(private val file: File, private val maxLines: Int = 400) : PlaneJournal {
    private val lock = Any()

    override fun write(record: SwitchRecord) = synchronized(lock) {
        file.parentFile?.mkdirs()
        file.appendText(record.toJson().toString() + "\n")
        val lines = file.readLines()
        if (lines.size > maxLines) file.writeText(lines.takeLast(maxLines / 2).joinToString("\n", postfix = "\n"))
    }

    private fun latest(): List<SwitchRecord> = synchronized(lock) {
        if (!file.exists()) return emptyList()
        val byId = LinkedHashMap<String, SwitchRecord>()
        file.readLines().forEach { line -> runCatching { SwitchRecord.fromJson(JSONObject(line)) }.getOrNull()?.let { byId.remove(it.id); byId[it.id] = it } }
        byId.values.toList()
    }

    override fun open(): SwitchRecord? = latest().lastOrNull { !it.phase.terminal }

    override fun recent(limit: Int): List<SwitchRecord> = latest().takeLast(limit).reversed()
}
