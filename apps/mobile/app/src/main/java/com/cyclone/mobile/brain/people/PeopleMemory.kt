package com.cyclone.mobile.brain.people

import com.cyclone.mobile.agent.nav.*
import com.cyclone.mobile.automation.skill.SkillSecrets
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Private, bounded hints, not identity proof or a saved click path. Mapping has no write entry point. */
class PeopleMemory(private val file: File) {
    data class Person(val displayName: String, val aliases: List<String>, val lastPlace: String,
        val threadLandmark: String, val verifiedAtMs: Long) {
        fun toJson() = JSONObject().put("displayName", displayName).put("aliases", JSONArray(aliases))
            .put("lastPlace", lastPlace).put("threadLandmark", threadLandmark).put("verifiedAtMs", verifiedAtMs)
    }

    @Synchronized fun find(name: String, place: String): List<Person> = read().filter {
        ClauseProof.samePlace(place, it.lastPlace) &&
            (it.displayName.equals(name, true) || it.aliases.any { alias -> alias.equals(name, true) })
    }

    /** Only the live Ask proof path may call this; a result-list label is deliberately insufficient. */
    @Synchronized fun observeOpenedThread(clause: TaskClause, screen: NavigationScreen, ledger: TaskLedger): Boolean {
        val name = clause.target ?: return false
        val place = screen.placeId ?: return false
        if (clause.capability != NavCapability.OPEN_DM || clause.status == ClauseStatus.FAILED ||
            screen.persona != AtlasPersona.LIVE || !safeName(name) || !PLACE.matches(place) ||
            !ROOM.matches(screen.roomId) || screen.readAtMs <= 0 ||
            ClauseProof.check(clause, screen, ledger, false) == null) return false
        val header = screen.page.title.takeIf { it.equals(name, true) } ?: name
        val row = Person(header, listOf(name).filter { it != header }, place, screen.roomId, screen.readAtMs)
        val rows = read().filterNot { it.displayName.equals(header, true) && ClauseProof.samePlace(it.lastPlace, place) } + row
        return runCatching {
            file.parentFile?.mkdirs()
            val temp = File.createTempFile("people-", ".tmp", file.parentFile)
            try {
                temp.writeText(JSONArray(rows.takeLast(256).map { it.toJson() }).toString())
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
            true
        }.getOrDefault(false)
    }

    fun contextFor(clause: TaskClause?): JSONObject? {
        if (clause?.capability !in setOf(NavCapability.OPEN_DM, NavCapability.SEARCH_PERSON)) return null
        val name = clause?.target ?: return null
        val place = clause.place ?: return null
        val matches = find(name, place)
        return JSONObject().put("name", name)
            .put("capability", if (matches.size == 1) "OPEN_DM" else "SEARCH_PERSON")
            .put("candidates", JSONArray(matches.take(8).map { it.toJson() }))
            .put("rule", "Hints only. Search if missing or ambiguous; verify the named thread and composer live. Never send a message to prove opening it.")
    }

    private fun read(): List<Person> = runCatching {
        if (!file.isFile || file.length() > 256_000) return emptyList()
        val rows = JSONArray(file.readText())
        (0 until minOf(rows.length(), 256)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val name = row.optString("displayName")
            val place = row.optString("lastPlace")
            val room = row.optString("threadLandmark")
            val at = row.optLong("verifiedAtMs")
            if (!safeName(name) || !PLACE.matches(place) || !ROOM.matches(room) || at <= 0) return@mapNotNull null
            val aliases = row.optJSONArray("aliases") ?: JSONArray()
            Person(name, (0 until minOf(aliases.length(), 8)).mapNotNull { i ->
                (aliases.opt(i) as? String)?.takeIf(::safeName)
            }, place, room, at)
        }
    }.getOrDefault(emptyList())

    private fun safeName(value: String) = value.length in 1..80 && NAME.matches(value) &&
        !SkillSecrets.isSecretKey(value) && !SkillSecrets.isSecretValue(value)

    companion object {
        private val NAME = Regex("[\\p{L}][\\p{L}\\p{M} ._’'-]*")
        private val PLACE = Regex("(?:package:[A-Za-z][A-Za-z0-9_.]{1,150}|chrome:https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?)")
        private val ROOM = Regex("screen:[a-z_]{1,40}:[0-9a-f]{8,64}")
    }
}
