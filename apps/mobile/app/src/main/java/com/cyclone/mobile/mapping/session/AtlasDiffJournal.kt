package com.cyclone.mobile.mapping.session

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object AtlasStructuralIds {
    private val SAFE = Regex("^[A-Za-z0-9._:-]{1,180}$")
    private val SCREEN_PREFIXES = listOf("page:", "screen:")

    fun valid(value: String): Boolean = SAFE.matches(value)

    fun screen(value: String): Boolean =
        valid(value) && SCREEN_PREFIXES.any(value::startsWith)

    fun edge(value: String): Boolean =
        valid(value) && value.startsWith("edge:")

    fun require(value: String, label: String) {
        if (!valid(value)) {
            throw MappingSessionException("INVALID_REQUEST", label + " must be a structural identifier.")
        }
    }

    fun requireScreen(value: String, label: String) {
        if (!screen(value)) {
            throw MappingSessionException("INVALID_REQUEST", label + " must use page:/screen: structural identity.")
        }
    }
}

enum class AtlasStructuralEntity(val wireValue: String) {
    PLACE("place"),
    SCREEN("screen"),
    EDGE("edge"),
}

enum class AtlasStructuralChangeKind(val wireValue: String) {
    UPSERT("upsert"),
    REMOVE("remove"),
}

data class AtlasStructuralChange(
    val entity: AtlasStructuralEntity,
    val change: AtlasStructuralChangeKind,
    val id: String,
    val mapStatus: String? = null,
    val fromScreenId: String? = null,
    val toScreenId: String? = null,
    val layoutX: Double? = null,
    val layoutY: Double? = null,
) {
    init {
        AtlasStructuralIds.require(id, "id")
        if (mapStatus != null && mapStatus !in MAP_STATUSES) {
            throw MappingSessionException("INVALID_REQUEST", "Invalid Atlas mapStatus.")
        }
        fromScreenId?.let { AtlasStructuralIds.require(it, "fromScreenId") }
        toScreenId?.let { AtlasStructuralIds.require(it, "toScreenId") }
        if ((fromScreenId == null) != (toScreenId == null)) {
            throw MappingSessionException("INVALID_REQUEST", "Edge topology requires both endpoints.")
        }
        if ((layoutX == null) != (layoutY == null)) {
            throw MappingSessionException("INVALID_REQUEST", "Atlas layout requires x and y.")
        }
        if (layoutX != null && (!layoutX.isFinite() || !layoutY!!.isFinite())) {
            throw MappingSessionException("INVALID_REQUEST", "Atlas layout must be finite.")
        }
        when (entity) {
            AtlasStructuralEntity.PLACE -> {
                if (id != "place" || fromScreenId != null || layoutX != null) {
                    throw MappingSessionException("INVALID_REQUEST", "Place diff uses literal place id and status only.")
                }
            }
            AtlasStructuralEntity.SCREEN -> {
                AtlasStructuralIds.requireScreen(id, "screen id")
                if (fromScreenId != null || mapStatus != null) {
                    throw MappingSessionException("INVALID_REQUEST", "Screen diff carries only id/layout.")
                }
            }
            AtlasStructuralEntity.EDGE -> {
                if (!AtlasStructuralIds.edge(id)) {
                    throw MappingSessionException("INVALID_REQUEST", "Edge diff id must use edge: structural identity.")
                }
                fromScreenId?.let { AtlasStructuralIds.requireScreen(it, "fromScreenId") }
                toScreenId?.let { AtlasStructuralIds.requireScreen(it, "toScreenId") }
                if (mapStatus != null || layoutX != null) {
                    throw MappingSessionException("INVALID_REQUEST", "Edge diff carries only topology.")
                }
            }
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("entity", entity.wireValue)
        .put("change", change.wireValue)
        .put("id", id)
        .apply {
            mapStatus?.let { put("mapStatus", it) }
            if (fromScreenId != null) {
                put("fromScreenId", fromScreenId)
                put("toScreenId", toScreenId)
            }
            if (layoutX != null) {
                put("layout", JSONObject().put("x", layoutX).put("y", layoutY))
            }
        }

    companion object {
        private val MAP_STATUSES = setOf("unmapped", "partial", "mapped", "stale", "blocked")

        fun fromJson(json: JSONObject): AtlasStructuralChange = AtlasStructuralChange(
            entity = AtlasStructuralEntity.entries.first { it.wireValue == json.getString("entity") },
            change = AtlasStructuralChangeKind.entries.first { it.wireValue == json.getString("change") },
            id = json.getString("id"),
            mapStatus = json.optString("mapStatus").takeIf { it.isNotBlank() },
            fromScreenId = json.optString("fromScreenId").takeIf { it.isNotBlank() },
            toScreenId = json.optString("toScreenId").takeIf { it.isNotBlank() },
            layoutX = json.optJSONObject("layout")?.getDouble("x"),
            layoutY = json.optJSONObject("layout")?.getDouble("y"),
        )
    }
}

data class AtlasDiffResult(
    val placeId: String,
    val persona: String,
    val since: String?,
    val cursor: String,
    val resyncRequired: Boolean,
    val changes: List<Pair<String, AtlasStructuralChange>>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("placeId", placeId)
        .put("persona", persona)
        .put("since", since ?: JSONObject.NULL)
        .put("cursor", cursor)
        .put("resyncRequired", resyncRequired)
        .put("changes", JSONArray().also { out ->
            changes.forEach { (entryCursor, change) ->
                out.put(change.toJson().put("cursor", entryCursor))
            }
        })
}

/**
 * Phone-local bounded structural journal.
 *
 * The epoch is persisted with each place/persona stream. A cursor from a lost/other epoch is
 * explicitly rejected with resyncRequired instead of being treated as a valid numeric offset.
 */
class AtlasDiffJournal(
    private val file: File,
    private val maxEntriesPerStream: Int = 256,
) {
    private data class Entry(val sequence: Long, val changes: List<AtlasStructuralChange>)
    private data class Stream(
        val placeId: String,
        val persona: String,
        val epoch: String,
        var sequence: Long,
        val entries: ArrayDeque<Entry>,
    )

    private val lock = Any()
    private val streams = linkedMapOf<String, Stream>()

    init {
        require(maxEntriesPerStream in 4..4096)
        load()
    }

    fun append(placeId: String, persona: String, changes: List<AtlasStructuralChange>): String = synchronized(lock) {
        requirePlaceId(placeId)
        requirePersona(persona)
        val stream = stream(placeId, persona)
        if (changes.isEmpty()) return@synchronized currentCursor(stream)
        stream.sequence += 1L
        stream.entries.addLast(Entry(stream.sequence, changes.toList()))
        while (stream.entries.size > maxEntriesPerStream) stream.entries.removeFirst()
        persist()
        cursor(stream, stream.sequence)
    }

    fun diff(placeId: String, persona: String, since: String?): AtlasDiffResult = synchronized(lock) {
        requirePlaceId(placeId)
        requirePersona(persona)
        val stream = stream(placeId, persona)
        val current = currentCursor(stream)

        // Null is the bootstrap: establish a phone-issued cursor after the caller recovered truth
        // with atlas.get. It intentionally returns no fabricated history.
        if (since == null) {
            persist()
            return@synchronized AtlasDiffResult(placeId, persona, null, current, false, emptyList())
        }

        val parsed = parseCursor(since)
        if (parsed == null || parsed.first != stream.epoch) {
            return@synchronized AtlasDiffResult(placeId, persona, since, current, true, emptyList())
        }
        val requestedSequence = parsed.second
        val earliestRecoverable = (stream.entries.firstOrNull()?.sequence ?: (stream.sequence + 1L)) - 1L
        if (requestedSequence < earliestRecoverable || requestedSequence > stream.sequence) {
            return@synchronized AtlasDiffResult(placeId, persona, since, current, true, emptyList())
        }

        val changes = buildList {
            stream.entries.filter { it.sequence > requestedSequence }.forEach { entry ->
                val entryCursor = cursor(stream, entry.sequence)
                entry.changes.forEach { add(entryCursor to it) }
            }
        }
        AtlasDiffResult(placeId, persona, since, current, false, changes)
    }

    private fun stream(placeId: String, persona: String): Stream {
        val key = key(placeId, persona)
        return streams.getOrPut(key) {
            Stream(
                placeId = placeId,
                persona = persona,
                epoch = UUID.randomUUID().toString().replace("-", "").take(20),
                sequence = 0L,
                entries = ArrayDeque(),
            )
        }
    }

    private fun currentCursor(stream: Stream): String = cursor(stream, stream.sequence)
    private fun cursor(stream: Stream, sequence: Long): String =
        "c1:" + stream.epoch + ":" + sequence

    private fun parseCursor(value: String): Pair<String, Long>? {
        val parts = value.split(':')
        if (parts.size != 3 || parts[0] != "c1" || !parts[1].matches(Regex("[a-f0-9]{20}"))) return null
        val sequence = parts[2].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        return parts[1] to sequence
    }

    private fun key(placeId: String, persona: String): String = persona + "\u0000" + placeId

    private fun persist() {
        file.parentFile?.mkdirs()
        val root = JSONObject()
            .put("version", 1)
            .put("streams", JSONArray().also { out ->
                streams.values.sortedWith(compareBy({ it.persona }, { it.placeId })).forEach { stream ->
                    out.put(JSONObject()
                        .put("placeId", stream.placeId)
                        .put("persona", stream.persona)
                        .put("epoch", stream.epoch)
                        .put("sequence", stream.sequence)
                        .put("entries", JSONArray().also { entries ->
                            stream.entries.forEach { entry ->
                                entries.put(JSONObject()
                                    .put("sequence", entry.sequence)
                                    .put("changes", JSONArray().also { changes ->
                                        entry.changes.forEach { changes.put(it.toJson()) }
                                    }))
                            }
                        }))
                }
            })
        val parent = file.parentFile ?: file.absoluteFile.parentFile
        val tmp = File(parent, file.name + ".tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            tmp.delete()
            throw IllegalStateException("Atlas diff journal could not replace its previous file.")
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IllegalStateException("Atlas diff journal could not be committed.")
        }
    }

    private fun load() = synchronized(lock) {
        if (!file.exists()) return@synchronized
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.optInt("version") == 1)
            val array = root.getJSONArray("streams")
            repeat(array.length()) { index ->
                val json = array.getJSONObject(index)
                val placeId = json.getString("placeId")
                val persona = json.getString("persona")
                requirePlaceId(placeId)
                requirePersona(persona)
                val epoch = json.getString("epoch")
                require(epoch.matches(Regex("[a-f0-9]{20}")))
                val sequence = json.getLong("sequence")
                require(sequence >= 0L)
                val entries = ArrayDeque<Entry>()
                val encoded = json.getJSONArray("entries")
                repeat(encoded.length()) { entryIndex ->
                    val item = encoded.getJSONObject(entryIndex)
                    val itemSequence = item.getLong("sequence")
                    require(itemSequence in 1..sequence)
                    val rawChanges = item.getJSONArray("changes")
                    val changes = buildList {
                        repeat(rawChanges.length()) { changeIndex ->
                            add(AtlasStructuralChange.fromJson(rawChanges.getJSONObject(changeIndex)))
                        }
                    }
                    entries.addLast(Entry(itemSequence, changes))
                }
                require(entries.zipWithNext().all { (a, b) -> a.sequence < b.sequence })
                streams[key(placeId, persona)] = Stream(placeId, persona, epoch, sequence, entries)
            }
        }.onFailure {
            // Corrupt local history is not recovered by guessing. Resetting creates a new epoch,
            // so every old client cursor receives resyncRequired and falls back to atlas.get.
            streams.clear()
        }
    }
}
