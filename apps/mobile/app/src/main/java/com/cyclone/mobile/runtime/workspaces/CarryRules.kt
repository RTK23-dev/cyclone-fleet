package com.cyclone.mobile.runtime.workspaces

import com.cyclone.mobile.mind.MindMemory
import org.json.JSONObject

/** What one carry brought into this profile. Counts and names only, never content. */
data class CarryReport(
    val atMs: Long,
    val fromLabel: String,
    val memories: Int,
    val forgotten: Int,
    val skills: Int,
    val settings: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("at", atMs).put("from", fromLabel).put("memories", memories)
        .put("forgotten", forgotten).put("skills", skills).put("settings", settings)

    companion object {
        fun fromJson(json: JSONObject) = CarryReport(json.getLong("at"), json.getString("from"), json.optInt("memories"),
            json.optInt("forgotten"), json.optInt("skills"), json.optInt("settings"))
    }
}

/**
 * Plan 40 P2, Cyclone Carry: what crosses between the owner's profiles on every switch, and how it merges. Pure.
 *
 * - **Carried, both ways:** people memory (labelled by profile, see `MemoryCarry`), the Brain's verified skills and
 *   paths, notes, how well Cyclone opens each app, and the Drive and look settings.
 * - **Never:** keys, tokens, pairing, sessions, the vault, chat history, missions and run logs. A setting whose name
 *   sounds like one of those, or whose value looks like a secret, is not carried.
 * - **Merging:** the row used most recently wins; notes are only added; nothing is deleted by a carry except
 *   memories the owner forgot.
 */
object CarryRules {
    const val VERSION = 1
    /** How the main profile names itself in a carry (other profiles use their `Cyclone_…` id). */
    const val MAIN = "main"
    const val MAIN_LABEL = "Profile A"
    const val ROWS_PER_TABLE = 2_000

    /** The Brain's tables that travel, with the columns carried, the key and the column that says which is newer. */
    data class Table(val name: String, val key: String, val time: String, val columns: List<String>, val onlyAdd: Boolean = false)

    val tables = listOf(
        Table("micro_skills", "signature", "last_used_at", listOf("signature", "name", "tool", "params_json", "goal_hints", "from_package",
            "from_fingerprint", "to_package", "to_fingerprint", "success_count", "failure_count", "confidence", "source", "last_used_at")),
        Table("learned_paths", "signature", "last_used_at",
            listOf("signature", "goal_key", "skills_json", "success_count", "failure_count", "confidence", "last_used_at")),
        Table("user_notes", "id", "created_at", listOf("id", "text", "source", "created_at"), onlyAdd = true),
    )

    /** Words the owner reads: the text columns checked for secrets before a row leaves or lands. */
    private val readable = setOf("name", "params_json", "goal_hints", "goal_key", "text")

    /** Whether the incoming row replaces (or adds to) this profile's. */
    fun takes(table: Table, localTime: Long?, incomingTime: Long): Boolean =
        localTime == null || (!table.onlyAdd && incomingTime > localTime)

    /** A row travels only whole, with a key, and with nothing in it that looks like a secret. */
    fun safeRow(table: Table, row: JSONObject): Boolean =
        table.columns.all(row::has) && row.optString(table.key).isNotBlank() &&
            !MindMemory.looksSecret(table.columns.filter { it in readable }.joinToString(" ") { row.optString(it) })

    /** The settings that travel: the file, then the keys (null means every key in it). */
    val settings: Map<String, Set<String>?> = mapOf(
        "cyclone_drive" to null,
        "cyclone_ui" to setOf("visual_quality"),
    )
    private val privateName = Regex("(?i)(^|_)(key|keys|token|secret|password|pin|otp|pairing|paired|session|auth|cookie|account)(_|$)")

    fun carriesSetting(file: String, key: String, value: Any?): Boolean {
        val keys = if (file in settings) settings[file] else return false
        if (keys != null && key !in keys) return false
        if (privateName.containsMatchIn(key)) return false
        return when (value) {
            is Boolean, is Int, is Long, is Float -> true
            is String -> value.length <= 200 && !MindMemory.looksSecret(value)
            else -> false
        }
    }

    /** The context bound into a bundle's encryption: which profile it is for, and for which switch. */
    fun cipherContext(target: Int, nonce: String) = "cyclone-carry-v$VERSION:$target:$nonce"

    /** "Brought 12 memories and 40 skills from Work" for Profiles, or null when nothing came. */
    fun line(report: CarryReport): String? {
        val parts = listOfNotNull(
            report.memories.takeIf { it > 0 }?.let { "$it ${if (it == 1) "memory" else "memories"}" },
            report.skills.takeIf { it > 0 }?.let { "$it ${if (it == 1) "skill" else "skills"}" },
            report.settings.takeIf { it > 0 }?.let { "your settings" },
        )
        if (parts.isEmpty()) return if (report.forgotten > 0) "Forgot ${report.forgotten} as you did in ${report.fromLabel}" else null
        val list = if (parts.size == 1) parts[0] else parts.dropLast(1).joinToString(", ") + " and " + parts.last()
        return "Brought $list from ${report.fromLabel}"
    }
}
