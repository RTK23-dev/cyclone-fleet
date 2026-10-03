package com.cyclone.mobile.mind

import com.cyclone.mobile.mind.mission.MindRedaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * One durable memory the Mind kept for future missions (plan 37 W3, alpha.67). Typed so memory stays tidy:
 * - `person`: someone the owner told Cyclone about ([subject] is their name; [relation], [handles] per app, [note]);
 * - `app`: how the owner uses an app ([subject] is the app);
 * - `preference`: how the owner likes things done;
 * - `fact`: anything else durable.
 * [source] is `owner` when the owner asked for it to be remembered, `learned` when the Mind chose to keep it.
 * [history] keeps the last few earlier versions, so an update never silently loses what was there.
 *
 * People memory is shared across the owner's profiles (plan 40 P2) and stays labelled by where it came from:
 * [profile] is null for a memory made in this profile, or the id of the profile that made it ([profileLabel] is that
 * profile's name when it was last carried, [origin] its id there). [changedAtMs] is when its words last changed, so
 * the newer wording wins when two profiles meet.
 */
data class MindFact(
    val id: String,
    val text: String,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val missionId: String? = null,
    val kind: String = MindMemory.FACT,
    val subject: String? = null,
    val relation: String? = null,
    val handles: Map<String, String> = emptyMap(),
    val note: String? = null,
    val source: String = MindMemory.LEARNED,
    val history: List<String> = emptyList(),
    val profile: String? = null,
    val profileLabel: String? = null,
    val origin: String? = null,
    val changedAtMs: Long = 0,
) {
    /** When the words last changed (older files have no such time: then when it was made). */
    val changed: Long get() = maxOf(changedAtMs, createdAtMs)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("text", text).put("created", createdAtMs)
        .put("used", lastUsedAtMs).put("mission", missionId ?: JSONObject.NULL)
        .put("kind", kind).put("subject", subject ?: JSONObject.NULL).put("relation", relation ?: JSONObject.NULL)
        .put("handles", JSONObject(handles as Map<*, *>)).put("note", note ?: JSONObject.NULL).put("source", source)
        .put("history", JSONArray(history)).put("profile", profile ?: JSONObject.NULL)
        .put("profile_label", profileLabel ?: JSONObject.NULL).put("origin", origin ?: JSONObject.NULL).put("changed", changedAtMs)

    companion object {
        fun fromJson(json: JSONObject): MindFact {
            fun opt(key: String) = json.optString(key).takeUnless { json.isNull(key) || it.isBlank() }
            val handles = json.optJSONObject("handles")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
            return MindFact(json.getString("id"), json.optString("text"), json.optLong("created"), json.optLong("used"), opt("mission"),
                opt("kind") ?: MindMemory.FACT, opt("subject"), opt("relation"), handles, opt("note"), opt("source") ?: MindMemory.LEARNED,
                json.optJSONArray("history")?.let { a -> (0 until a.length()).map(a::optString) }.orEmpty(),
                opt("profile"), opt("profile_label"), opt("origin"), json.optLong("changed"))
        }
    }
}

/**
 * A memory the owner forgot, remembered only by where it came from and when, so a copy in another profile is
 * forgotten too instead of coming back on the next switch. [profile] is null for this profile's own memories.
 */
data class ForgottenFact(val profile: String?, val id: String, val atMs: Long) {
    fun toJson(): JSONObject = JSONObject().put("profile", profile ?: JSONObject.NULL).put("id", id).put("at", atMs)

    companion object {
        fun fromJson(json: JSONObject) =
            ForgottenFact(json.optString("profile").takeUnless { json.isNull("profile") || it.isBlank() }, json.getString("id"), json.getLong("at"))
    }
}

/** Encrypts the memory file at rest. Production: an Android Keystore AES-GCM key; tests run without one. */
interface MemorySealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/**
 * What the Mind remembers between missions: the people the owner told it about, how the owner uses apps, preferences
 * and durable facts. The owner can read and delete every memory.
 *
 * It stays tidy the way mem0 does it (extract, then ADD / UPDATE / DELETE / NOOP against what is already there),
 * with the running Mind as the judge instead of a second model call: a new memory about a person merges into that
 * person's card, a near-duplicate updates the old one (keeping its earlier text in history), an exact duplicate
 * changes nothing, and similar ones are shown back so the Mind can say `replaces`. Anything that looks like a secret
 * is refused, and things that are only true for now ("today", "tonight") are refused unless the owner asked.
 */
class MindMemory(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limit: Int = 300,
    private val sealer: MemorySealer? = null,
) {
    sealed class Saved {
        /** A new memory. [similar] are older ones that look related: the Mind may replace one of them. */
        data class Stored(val fact: MindFact, val similar: List<MindFact> = emptyList()) : Saved()
        /** An existing memory changed ([previous] is its earlier text) or, with [previous] null, was already there. */
        data class Updated(val fact: MindFact, val previous: String? = null) : Saved()
        data class Refused(val reason: String) : Saved()
    }

    /** What the Mind (or the owner, through the check-in card) wants kept. */
    data class Candidate(
        val text: String,
        val kind: String = FACT,
        val person: String? = null,
        val relation: String? = null,
        val app: String? = null,
        val handle: String? = null,
        val replaces: String? = null,
        val source: String = LEARNED,
    )

    @Synchronized fun all(): List<MindFact> = read().sortedByDescending { maxOf(it.lastUsedAtMs, it.createdAtMs) }

    @Synchronized fun remember(text: String, missionId: String? = null): Saved = remember(Candidate(text), missionId)

    @Synchronized fun remember(candidate: Candidate, missionId: String? = null): Saved {
        val clean = squash(candidate.text)
        val person = candidate.person?.let(::squash)?.takeIf { it.isNotBlank() }?.take(60)
        val app = candidate.app?.let(::squash)?.takeIf { it.isNotBlank() }?.take(40)
        val handle = candidate.handle?.let(::squash)?.takeIf { it.isNotBlank() }?.take(80)
        val relation = candidate.relation?.let(::squash)?.takeIf { it.isNotBlank() }?.take(40)
        val kind = when {
            person != null -> PERSON
            app != null && candidate.kind == FACT -> APP
            candidate.kind in KINDS -> candidate.kind
            else -> FACT
        }
        val everything = listOfNotNull(clean, person, app, handle, relation).joinToString(" ")
        if (looksSecret(everything)) {
            return Saved.Refused("it looks like it contains a secret (password, code, key or card/account number); secrets are never remembered")
        }
        if (clean.length > MAX_CHARS) return Saved.Refused("keep a memory under $MAX_CHARS characters")
        if (kind != PERSON && clean.length < 4) return Saved.Refused("the memory is empty")
        if (kind == PERSON && clean.length < 2 && relation == null && handle == null) return Saved.Refused("say who they are to the owner, or their name in an app")
        if (candidate.source != OWNER && TRANSIENT.containsMatchIn(clean)) {
            return Saved.Refused("it sounds true only for now (today, tonight, this time); keep it with note for this mission instead")
        }
        val facts = read().toMutableList()
        val now = clock()

        // UPDATE by id: the Mind (or the owner) said which memory this one replaces.
        candidate.replaces?.trim()?.takeIf { it.isNotBlank() }?.let { id ->
            val old = facts.firstOrNull { it.id == id } ?: return Saved.Refused("there is no memory $id")
            val updated = if (old.kind == PERSON) mergePerson(old, clean, relation, app, handle, candidate.source, now)
                else old.copy(text = clean, lastUsedAtMs = now, source = stronger(old.source, candidate.source), history = pushed(old.history, old.text),
                    changedAtMs = now)
            return save(facts, old, updated, old.text)
        }

        // A person: one card per person; new details merge into it.
        if (kind == PERSON && person != null) {
            // This profile's own card: a card carried from another profile stays that profile's.
            val card = facts.firstOrNull { it.profile == null && it.kind == PERSON && it.subject.equals(person, ignoreCase = true) }
            if (card != null) {
                val merged = mergePerson(card, clean, relation, app, handle, candidate.source, now)
                return if (merged.text == card.text) save(facts, card, merged.copy(history = card.history), null) else save(facts, card, merged, card.text)
            }
            val handles = if (app != null && handle != null) mapOf(app to handle) else emptyMap()
            val note = clean.takeIf { it.isNotBlank() && !it.equals(person, true) }
            val fact = MindFact(nextId(facts), render(person, relation, handles, note), now, now, missionId, PERSON, person, relation, handles, note,
                candidate.source)
            return add(facts, fact)
        }

        val same = facts.filter { it.profile == null && it.kind == kind && (kind != APP || it.subject.equals(app, ignoreCase = true)) }
        val text = if (kind == APP && app != null && !clean.startsWith(app, ignoreCase = true)) "$app: $clean" else clean
        // NOOP: the same memory again.
        same.firstOrNull { squash(it.text).equals(text, ignoreCase = true) }?.let { existing ->
            return save(facts, existing, existing.copy(lastUsedAtMs = now, source = stronger(existing.source, candidate.source)), null)
        }
        // UPDATE: nearly the same memory, newer wording wins; the old one stays in history.
        val scored = same.map { it to overlap(it.text, text) }.sortedByDescending { it.second }
        scored.firstOrNull { it.second >= UPDATE_AT }?.first?.let { old ->
            return save(facts, old, old.copy(text = text, lastUsedAtMs = now, source = stronger(old.source, candidate.source),
                history = pushed(old.history, old.text), changedAtMs = now), old.text)
        }
        // ADD, with the related ones shown so the Mind can replace one if it is now wrong.
        val fact = MindFact(nextId(facts), text, now, now, missionId, kind, app, source = candidate.source)
        val similar = scored.filter { it.second >= SIMILAR_AT }.take(3).map { it.first }
        return when (val stored = add(facts, fact)) {
            is Saved.Stored -> stored.copy(similar = similar)
            else -> stored
        }
    }

    private fun mergePerson(card: MindFact, clean: String, relation: String?, app: String?, handle: String?, source: String, now: Long): MindFact {
        val handles = card.handles.toMutableMap()
        if (app != null && handle != null) handles.keys.firstOrNull { it.equals(app, true) }?.let(handles::remove)
        if (app != null && handle != null) handles[app] = handle
        val note = clean.takeIf { it.isNotBlank() && !it.equals(card.subject, true) && !(card.note ?: "").contains(it, true) }
            ?.let { new -> listOfNotNull(card.note?.takeIf { overlap(it, new) < UPDATE_AT }, new).joinToString(" ").take(MAX_CHARS) } ?: card.note
        val subject = card.subject ?: ""
        val text = render(subject, relation ?: card.relation, handles, note)
        return card.copy(text = text, relation = relation ?: card.relation, handles = handles, note = note, lastUsedAtMs = now,
            source = stronger(card.source, source), history = if (text != card.text) pushed(card.history, card.text) else card.history,
            changedAtMs = if (text != card.text) now else card.changedAtMs)
    }

    private fun save(facts: MutableList<MindFact>, old: MindFact, updated: MindFact, previous: String?): Saved {
        facts[facts.indexOf(old)] = updated
        write(facts)
        return Saved.Updated(updated, previous)
    }

    private fun add(facts: MutableList<MindFact>, fact: MindFact): Saved {
        facts += fact
        // Over the limit: learned memories go first, oldest use first; what the owner asked for goes last.
        while (facts.size > limit) {
            val victim = facts.filter { it.source != OWNER }.minByOrNull { maxOf(it.lastUsedAtMs, it.createdAtMs) }
                ?: facts.minByOrNull { maxOf(it.lastUsedAtMs, it.createdAtMs) }!!
            facts.remove(victim)
        }
        write(facts)
        return if (fact in facts) Saved.Stored(fact) else Saved.Refused("memory is full of things the owner asked to keep")
    }

    @Synchronized fun forget(id: String): Boolean {
        val facts = read()
        val gone = facts.filter { it.id == id.trim() }
        if (gone.isEmpty()) return false
        write(facts - gone.toSet(), forgotten + gone.map { MemoryCarry.forgotten(it, clock()) })
        return true
    }

    @Synchronized fun forgetAll() {
        val facts = read()
        write(emptyList(), forgotten + facts.map { MemoryCarry.forgotten(it, clock()) })
    }

    /**
     * What this profile hands to another one (plan 40 P2): every memory labelled with the profile it came from
     * ([me], named [myLabel], for its own), and what was forgotten. Nothing that looks like a secret ever leaves.
     */
    @Synchronized fun export(me: String, myLabel: String): MemoryCarry.Parcel = MemoryCarry.export(read(), forgotten, me, myLabel, clock())

    /** Takes in another profile's memories: newer words win, forgotten stays forgotten, each keeps its profile. */
    @Synchronized fun absorb(parcel: MemoryCarry.Parcel, me: String): MemoryCarry.Result {
        val result = MemoryCarry.absorb(read(), forgotten, parcel, me, clock())
        val facts = result.facts.toMutableList()
        while (facts.size > limit) {
            val victim = facts.filter { it.source != OWNER }.minByOrNull { maxOf(it.lastUsedAtMs, it.createdAtMs) }
                ?: facts.minByOrNull { maxOf(it.lastUsedAtMs, it.createdAtMs) }!!
            facts.remove(victim)
        }
        write(facts, result.forgotten)
        return result
    }

    /** Memories sharing words with [query] (names, relations and handles count), best first; marks them as used. */
    @Synchronized fun search(query: String, max: Int = 12): List<MindFact> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        val facts = read()
        val hits = facts.map { it to words(searchable(it)).intersect(words).size }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<MindFact, Int>> { it.second }.thenByDescending { it.first.lastUsedAtMs })
            .take(max).map { it.first }
        touch(facts, hits)
        return hits
    }

    /** The people [goal] talks about: by name, by relation ("my girlfriend") or by a handle. */
    @Synchronized fun peopleIn(goal: String): List<MindFact> {
        val lower = " ${goal.lowercase()} "
        return read().filter { it.kind == PERSON }.filter { card ->
            listOfNotNull(card.subject, card.relation).any { term -> Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term.lowercase())}(?![\\p{L}\\p{N}])").containsMatchIn(lower) } ||
                card.handles.values.any { lower.contains(it.lowercase()) }
        }
    }

    /**
     * The block placed in a new mission's opening message: the people the goal mentions first, then what the owner asked
     * to keep, then what fits the goal, then the rest by recent use, grouped so it reads like a small profile.
     */
    @Synchronized fun digest(goal: String = "", maxChars: Int = 3_000): String {
        val facts = read()
        if (facts.isEmpty()) return ""
        val words = words(goal)
        val people = if (goal.isBlank()) emptyList() else peopleIn(goal).map { it.id }.toSet()
        val ranked = facts.sortedWith(compareByDescending<MindFact> { it.id in people }
            .thenByDescending { words.isNotEmpty() && words(searchable(it)).intersect(words).isNotEmpty() }
            .thenByDescending { it.source == OWNER }
            .thenByDescending { maxOf(it.lastUsedAtMs, it.createdAtMs) })
        val chosen = mutableListOf<MindFact>()
        var used = 0
        for (fact in ranked) {
            val line = "- [${fact.id}] ${fact.text}${fromLine(fact)}"
            if (used + line.length > maxChars) continue
            chosen += fact
            used += line.length + 1
        }
        touch(facts, chosen.filter { it.id in people })
        return SECTIONS.mapNotNull { (kind, title) ->
            chosen.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { list ->
                "$title\n" + list.joinToString("\n") { "- [${it.id}] ${it.text}" + (if (it.source == OWNER) " (the owner asked)" else "") + fromLine(it) }
            }
        }.joinToString("\n")
    }

    private fun touch(facts: List<MindFact>, used: List<MindFact>) {
        if (used.isEmpty()) return
        val now = clock()
        val ids = used.map { it.id }.toSet()
        write(facts.map { if (it.id in ids) it.copy(lastUsedAtMs = now) else it })
    }

    private fun nextId(facts: List<MindFact>): String =
        "f" + ((facts.mapNotNull { it.id.removePrefix("f").toIntOrNull() }.maxOrNull() ?: 0) + 1)

    /** What was forgotten, as of the last [read]; every public call reads before it writes. */
    private var forgotten: List<ForgottenFact> = emptyList()

    private fun read(): List<MindFact> = runCatching {
        forgotten = emptyList()
        if (!file.isFile) return emptyList()
        val raw = file.readText()
        val text = when {
            raw.startsWith(SEALED_PREFIX) -> sealer?.let { String(it.open(Base64.getDecoder().decode(raw.removePrefix(SEALED_PREFIX))), Charsets.UTF_8) }
                ?: return emptyList()
            else -> raw // a memory file from before alpha.67; the next write seals it
        }
        val doc = JSONObject(text)
        val gone = doc.optJSONArray("forgotten") ?: JSONArray()
        forgotten = (0 until gone.length()).mapNotNull { gone.optJSONObject(it)?.let { json -> runCatching { ForgottenFact.fromJson(json) }.getOrNull() } }
        val array = doc.optJSONArray("facts") ?: JSONArray()
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { json -> runCatching { MindFact.fromJson(json) }.getOrNull() } }
    }.getOrDefault(emptyList())

    private fun write(facts: List<MindFact>, gone: List<ForgottenFact> = forgotten) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        val kept = MemoryCarry.tidy(gone, clock())
        forgotten = kept
        val json = JSONObject().put("schema", SCHEMA).put("facts", JSONArray().also { array -> facts.forEach { array.put(it.toJson()) } })
            .put("forgotten", JSONArray().also { array -> kept.forEach { array.put(it.toJson()) } }).toString()
        temp.writeText(sealer?.let { SEALED_PREFIX + Base64.getEncoder().encodeToString(it.seal(json.toByteArray(Charsets.UTF_8))) } ?: json)
        if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
    }

    companion object {
        const val SCHEMA = "cyclone-mind-memory-v2"
        const val MAX_CHARS = 300
        const val PERSON = "person"
        const val APP = "app"
        const val PREFERENCE = "preference"
        const val FACT = "fact"
        val KINDS = setOf(PERSON, APP, PREFERENCE, FACT)
        const val OWNER = "owner"
        const val LEARNED = "learned"
        const val UPDATE_AT = 0.75
        const val SIMILAR_AT = 0.3
        private const val SEALED_PREFIX = "CYM1:"
        private const val HISTORY = 3
        private val SECTIONS = listOf(PERSON to "People the owner told you about:", PREFERENCE to "The owner's preferences:",
            APP to "How the owner uses apps:", FACT to "Other things you kept:")
        private val SECRET_WORDS = Regex("(?i)\\b(password|passcode|wachtwoord|pin code|otp|one[- ]time code|verification code|cvv|cvc|api key|secret key|private key|seed phrase|recovery phrase)\\b")
        private val TRANSIENT = Regex("(?i)\\b(today|tonight|right now|at the moment|this time|this morning|this afternoon|this evening|vandaag|vanavond|nu even|deze keer)\\b")

        /** True when [text] looks like it holds a secret: refused by memory and by the mission workspace alike. */
        fun looksSecret(text: String): Boolean =
            com.cyclone.mobile.mind.mission.MindRedaction.scrub(text) != text || SECRET_WORDS.containsMatchIn(text)

        /** How a person card reads: "Louella — girlfriend; Instagram: lo.06. Note: …". */
        fun render(name: String, relation: String?, handles: Map<String, String>, note: String?): String = buildString {
            append(name)
            relation?.let { append(" — ").append(it) }
            if (handles.isNotEmpty()) append("; ").append(handles.entries.joinToString("; ") { "${it.key}: ${it.value}" })
            note?.takeIf { it.isNotBlank() }?.let { append(". ").append(it) }
        }.take(MAX_CHARS)

        private val STOP = setOf("the", "a", "an", "of", "to", "in", "on", "for", "and", "or", "is", "my", "me", "de", "het", "een", "van", "op", "en", "mijn")
        private fun words(text: String): Set<String> =
            // Numbers count, even one digit: "5 minutes" and "10 minutes" are different memories.
            text.lowercase().split(Regex("[^\\p{L}\\p{N}@.]+")).map { it.trim('.') }
                .filter { (it.length >= 2 || it.any(Char::isDigit)) && it !in STOP }.toSet()
        private fun searchable(fact: MindFact) = listOfNotNull(fact.text, fact.subject, fact.relation).joinToString(" ") + " " + fact.handles.values.joinToString(" ")
        private fun overlap(a: String, b: String): Double {
            val x = words(a)
            val y = words(b)
            if (x.isEmpty() || y.isEmpty()) return 0.0
            return x.intersect(y).size.toDouble() / x.union(y).size
        }
        private fun squash(text: String) = text.replace(Regex("\\s+"), " ").trim()
        private fun fromLine(fact: MindFact) = fact.profile?.let { " (from ${fact.profileLabel ?: "another profile"})" } ?: ""
        private fun pushed(history: List<String>, old: String) = (history + old).takeLast(HISTORY)
        private fun stronger(a: String, b: String) = if (a == OWNER || b == OWNER) OWNER else LEARNED
    }
}
