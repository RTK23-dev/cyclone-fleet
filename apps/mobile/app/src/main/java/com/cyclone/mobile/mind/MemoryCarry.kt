package com.cyclone.mobile.mind

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 40 P2: people memory shared across the owner's profiles, labelled by the profile each memory came from.
 *
 * A memory is known everywhere by where it was made and its id there (`profile`, `origin`); in each file it also has
 * a local id the Mind uses. When two profiles meet:
 * - a memory neither side forgot is kept, and the newer wording ([MindFact.changed]) wins;
 * - a forgotten memory stays forgotten everywhere, unless its words changed after it was forgotten;
 * - each memory keeps its profile, so "Louella" from Work and "Louella" from Personal stay two labelled cards;
 * - nothing that looks like a secret is carried, either way.
 * Pure: no Android, no files; [MindMemory.export] and [MindMemory.absorb] do the reading and sealing.
 */
object MemoryCarry {
    const val FORGOTTEN_DAYS = 90
    const val FORGOTTEN_MAX = 1_000

    data class Parcel(val facts: List<MindFact>, val forgotten: List<ForgottenFact>) {
        fun toJson(): JSONObject = JSONObject()
            .put("facts", JSONArray().also { array -> facts.forEach { array.put(it.toJson()) } })
            .put("forgotten", JSONArray().also { array -> forgotten.forEach { array.put(it.toJson()) } })

        companion object {
            fun fromJson(json: JSONObject): Parcel {
                val facts = json.optJSONArray("facts") ?: JSONArray()
                val gone = json.optJSONArray("forgotten") ?: JSONArray()
                return Parcel(
                    (0 until facts.length()).mapNotNull { facts.optJSONObject(it)?.let { f -> runCatching { MindFact.fromJson(f) }.getOrNull() } },
                    (0 until gone.length()).mapNotNull { gone.optJSONObject(it)?.let { g -> runCatching { ForgottenFact.fromJson(g) }.getOrNull() } },
                )
            }
        }
    }

    data class Result(val facts: List<MindFact>, val forgotten: List<ForgottenFact>, val added: Int, val updated: Int, val removed: Int)

    /** The record kept when [fact] is forgotten here. */
    fun forgotten(fact: MindFact, nowMs: Long) = ForgottenFact(fact.profile, fact.origin ?: fact.id, nowMs)

    /** Every memory labelled with its profile ([me] for this profile's own), without mission links or secrets. */
    fun export(facts: List<MindFact>, forgotten: List<ForgottenFact>, me: String, myLabel: String, nowMs: Long): Parcel = Parcel(
        facts.filterNot(::secret).map { fact ->
            if (fact.profile == null) fact.copy(profile = me, profileLabel = myLabel, origin = fact.id, missionId = null)
            else fact.copy(origin = fact.origin ?: fact.id, missionId = null)
        },
        tidy(forgotten, nowMs).map { it.copy(profile = it.profile ?: me) },
    )

    /** Merges [parcel] (from another profile) into this profile's [local] memories; [me] is this profile. */
    fun absorb(local: List<MindFact>, localForgotten: List<ForgottenFact>, parcel: Parcel, me: String, nowMs: Long): Result {
        fun mine(profile: String?) = profile?.takeUnless { it == me }
        val forgotten = tidy(localForgotten + parcel.forgotten.map { it.copy(profile = mine(it.profile)) }, nowMs)
        val goneAt = forgotten.associate { key(it.profile, it.id) to it.atMs }
        var removed = 0
        val facts = local.filter { fact ->
            val at = goneAt[keyOf(fact)]
            (at == null || fact.changed > at).also { if (!it) removed++ }
        }.toMutableList()
        val index = facts.withIndex().associate { keyOf(it.value) to it.index }.toMutableMap()
        var added = 0
        var updated = 0
        for (incoming in parcel.facts) {
            // A carried memory always names its profile; one that doesn't, or looks secret, is not taken.
            if (incoming.profile.isNullOrBlank() || secret(incoming)) continue
            val profile = mine(incoming.profile)
            val originId = incoming.origin ?: incoming.id
            val key = key(profile, originId)
            val forgottenAt = goneAt[key]
            if (forgottenAt != null && incoming.changed <= forgottenAt) continue
            val label = if (profile == null) null else incoming.profileLabel
            val at = index[key]
            if (at != null) {
                val current = facts[at]
                val newer = incoming.changed > current.changed
                val merged = if (newer) {
                    current.copy(text = incoming.text, kind = incoming.kind, subject = incoming.subject, relation = incoming.relation,
                        handles = incoming.handles, note = incoming.note, source = stronger(current.source, incoming.source),
                        history = incoming.history, changedAtMs = incoming.changed, lastUsedAtMs = maxOf(current.lastUsedAtMs, incoming.lastUsedAtMs),
                        profileLabel = label ?: current.profileLabel)
                } else {
                    current.copy(lastUsedAtMs = maxOf(current.lastUsedAtMs, incoming.lastUsedAtMs), profileLabel = label ?: current.profileLabel)
                }
                if (newer) updated++
                facts[at] = merged
            } else if (profile == null) {
                // One of this profile's own memories that is no longer here (it made room for newer ones): it comes
                // back under its own id, unless that id now belongs to another memory.
                if (facts.none { it.id == originId }) {
                    facts += incoming.copy(id = originId, profile = null, profileLabel = null, origin = null, missionId = null)
                    index[key] = facts.lastIndex
                    added++
                }
            } else {
                facts += incoming.copy(id = nextId(facts), profile = profile, profileLabel = label, origin = originId, missionId = null)
                index[key] = facts.lastIndex
                added++
            }
        }
        return Result(facts, forgotten, added, updated, removed)
    }

    /** One record per memory (the latest), none older than [FORGOTTEN_DAYS], at most [FORGOTTEN_MAX]. */
    fun tidy(forgotten: List<ForgottenFact>, nowMs: Long): List<ForgottenFact> {
        val since = nowMs - FORGOTTEN_DAYS * 24L * 60 * 60_000
        return forgotten.filter { it.atMs >= since }
            .groupBy { key(it.profile, it.id) }.values.map { same -> same.maxBy { it.atMs } }
            .sortedByDescending { it.atMs }.take(FORGOTTEN_MAX)
    }

    /** The people, grouped by the profile they came from: this profile first, then the others by name. */
    fun byProfile(facts: List<MindFact>): List<Pair<String?, List<MindFact>>> =
        facts.groupBy { it.profile }.entries
            .sortedWith(compareBy<Map.Entry<String?, List<MindFact>>> { it.key != null }.thenBy { it.value.first().profileLabel.orEmpty().lowercase() })
            .map { it.key to it.value }

    private fun key(profile: String?, id: String) = (profile ?: "") + "\u0000" + id
    private fun keyOf(fact: MindFact) = key(fact.profile, if (fact.profile == null) fact.id else fact.origin ?: fact.id)
    private fun nextId(facts: List<MindFact>) = "f" + ((facts.mapNotNull { it.id.removePrefix("f").toIntOrNull() }.maxOrNull() ?: 0) + 1)
    private fun stronger(a: String, b: String) = if (a == MindMemory.OWNER || b == MindMemory.OWNER) MindMemory.OWNER else MindMemory.LEARNED
    private fun secret(fact: MindFact) = MindMemory.looksSecret(
        listOfNotNull(fact.text, fact.subject, fact.relation, fact.note).joinToString(" ") + " " + fact.handles.values.joinToString(" "),
    )
}
