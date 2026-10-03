package com.cyclone.mobile.manual.dictionary

import com.cyclone.mobile.manual.AppLexicon
import com.cyclone.mobile.manual.ChromeProof
import com.cyclone.mobile.manual.CoreKind
import com.cyclone.mobile.manual.SetProposal

/** The gates a proposal passes before it becomes part of the app's dictionary (plan 36 §7.5). */
enum class Gate(val wire: String, val plain: String) {
    NAMED_BY_APP("named_by_app", "named by the app"),
    ANCHORED("anchored", "has its own place on screen"),
    SEEN_TWICE("seen_twice", "seen twice"),
    DEPTH("depth", "at most 3 levels deep"),
    FAN_OUT("fan_out", "room under its parent"),
    KIND_KNOWN("kind_known", "kind is clear"),
    DISTINCT("distinct", "not close to an existing set"),
}

/** One question for the organizer's judge: what to do with a proposal the gates could not settle. */
data class OrganizerQuestion(
    val key: String,
    val entryId: String,
    val name: String,
    val kindGuess: CoreKind,
    val reason: String,
    val where: List<String>,
    val markers: List<String>,
    val parentHint: String?,
    /** Existing sets it may be the same as, or belong under. Only these ids are valid answers. */
    val nearby: List<Nearby>,
) {
    data class Nearby(val id: String, val path: String)
}

enum class Choice(val wire: String) {
    NEW("new"), SAME_AS("same_as"), SUBSET_OF("subset_of"), KEEP("keep"), REJECT("reject");

    companion object {
        fun fromWire(value: String?): Choice? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

data class OrganizerDecision(
    val key: String,
    val choice: Choice,
    val targetId: String? = null,
    val kind: CoreKind? = null,
    val parentId: String? = null,
)

/** Who answers the organizer's one narrow question. Production: the pass's chosen model. JEV only watches. */
interface OrganizerJudge {
    val label: String
    fun decide(questions: List<OrganizerQuestion>): List<OrganizerDecision>
}

data class PassInfo(
    val at: Long,
    val versionName: String?,
    /** Rooms the pass saw, so a confirmed set whose room was seen without it counts as missed. */
    val roomsSeen: Set<String> = emptySet(),
)

data class OrganizerResult(
    val dictionary: AppDictionary,
    val confirmed: List<String>,
    val questions: List<OrganizerQuestion>,
    val decisions: List<OrganizerDecision>,
)

data class Health(
    val orphans: List<String>,
    val nearDuplicates: List<Pair<String, String>>,
    val tooDeep: List<String>,
    val tooWide: List<String>,
    val staleCandidates: List<String>,
    val notSeenInVersion: List<String>,
)

/**
 * The gatekeeper (plan 36 §7.5). Describers and the structure reader propose; only the organizer admits.
 *
 * 1. [record] folds a pass's proposals into the dictionary. A proposal that is the same as an existing set (same
 *    string resource, same place on screen, or the same name) only adds a sighting: it never creates a second set.
 * 2. [run] passes each candidate through deterministic gates. Clear cases are confirmed by the gates alone; only what
 *    they cannot settle is asked, once per pass, as a typed choice with a fixed set of answers. Anything else in an
 *    answer is ignored (the candidate is kept).
 * 3. Every change is written to the audit. Merges keep the old id as a redirect, the owner can undo, lock or reject,
 *    and the organizer never changes a locked entry.
 *
 * Pure: time, the judge and the pass are passed in.
 */
object Organizer {
    const val MAX_DEPTH = 3
    const val MAX_CHILDREN = 12
    const val MAX_TOP_LEVEL = 40
    const val MAX_QUESTIONS = 8
    const val STALE_CANDIDATE_MS = 30L * 24 * 60 * 60 * 1000
    const val DAY_MS = 24L * 60 * 60 * 1000

    // ---- 1. record ----

    fun record(dictionary: AppDictionary, proposals: List<SetProposal>, pass: PassInfo): AppDictionary {
        var dict = dictionary
        val day = pass.at / DAY_MS
        for (proposal in proposals) {
            val name = DictionaryPrivacy.text(proposal.name.text) ?: continue
            val anchors = listOfNotNull(proposal.anchor, proposal.secondAnchor).map(DictionaryPrivacy::anchor)
            val existing = match(dict, proposal, name)
            if (existing != null) {
                val merged = existing.copy(
                    anchors = (existing.anchors + anchors).distinctBy { it.identity }.takeLast(6),
                    markers = DictionaryPrivacy.texts(existing.markers + proposal.markers.map { it.text }, 6),
                    aliases = if (AppLexicon.normalize(name) != AppLexicon.normalize(existing.name) && existing.status != EntryStatus.LOCKED)
                        DictionaryPrivacy.texts(existing.aliases + name, 8) else existing.aliases,
                    kind = if (existing.kind == CoreKind.OTHER && proposal.kindGuess != CoreKind.OTHER && existing.status == EntryStatus.CANDIDATE) proposal.kindGuess else existing.kind,
                    parentHint = existing.parentHint ?: proposal.parentName?.text,
                    observations = existing.observations + 1,
                    days = (existing.days + day).distinct().takeLast(10),
                    lastSeenAt = pass.at,
                    versions = (existing.versions + listOfNotNull(pass.versionName)).distinct().takeLast(8),
                    missedPasses = 0,
                    proven = existing.proven || proposal.proven,
                )
                dict = dict.copy(entries = dict.entries + (merged.id to merged))
                continue
            }
            if (dict.entries.size >= AppDictionary.MAX_ENTRIES) continue
            val id = dict.newId(name)
            val entry = DictEntry(
                id = id,
                kind = proposal.kindGuess,
                name = name,
                nameProof = proposal.name.proof,
                resKey = proposal.name.resKey,
                parentHint = proposal.parentName?.text,
                anchors = anchors,
                markers = DictionaryPrivacy.texts(proposal.markers.map { it.text }, 6),
                observations = 1,
                days = listOf(day),
                firstSeenAt = pass.at,
                lastSeenAt = pass.at,
                versions = listOfNotNull(pass.versionName),
                proven = proposal.proven,
            )
            dict = dict.copy(entries = dict.entries + (id to entry))
                .withAudit(AuditEvent(pass.at, "proposed", id, "“$name” (${proposal.anchor.kind.wire})", "reader"))
        }
        return dict
    }

    /** The existing entry this proposal is: same resource, same place, or same name of a compatible kind. */
    private fun match(dict: AppDictionary, proposal: SetProposal, name: String): DictEntry? {
        val live = dict.entries.values.filter { it.status != EntryStatus.MERGED }
        val identities = listOfNotNull(proposal.anchor, proposal.secondAnchor).map { DictionaryPrivacy.anchor(it).identity }.toSet()
        val normalized = AppLexicon.normalize(name)
        fun compatible(e: DictEntry) = e.kind == proposal.kindGuess || e.kind == CoreKind.OTHER || proposal.kindGuess == CoreKind.OTHER
        val found = live.firstOrNull { e -> proposal.name.resKey != null && e.resKey == proposal.name.resKey && compatible(e) }
            ?: live.firstOrNull { e -> e.anchors.any { it.identity in identities } && e.names().any { AppLexicon.normalize(it) == normalized } }
            ?: live.firstOrNull { e -> e.anchors.any { it.identity in identities } && proposal.anchor.kind == com.cyclone.mobile.manual.AnchorKind.VIEW }
            ?: live.firstOrNull { e -> compatible(e) && e.names().any { AppLexicon.normalize(it) == normalized } }
        return found?.let { dict.resolve(it.id) }
    }

    // ---- 2. gates and the one question ----

    data class GateCheck(val failed: Set<Gate>, val verdict: Verdict, val reason: String)
    enum class Verdict { CONFIRM, ASK, WAIT }

    fun check(dict: AppDictionary, entry: DictEntry): GateCheck {
        val failed = LinkedHashSet<Gate>()
        if (DictionaryPrivacy.text(entry.name) == null) failed += Gate.NAMED_BY_APP
        if (entry.anchors.isEmpty()) failed += Gate.ANCHORED
        // A probe that saw the category switch views proves it in one pass; otherwise two sightings (two days for a
        // downloaded name).
        val seen = entry.proven || if (entry.nameProof == ChromeProof.LEXICON) entry.observations >= 2 else entry.days.distinct().size >= 2
        if (!seen) failed += Gate.SEEN_TWICE
        val parent = entry.parentId?.let(dict::resolve)
        if (dict.depth(parent?.id) + 1 > MAX_DEPTH) failed += Gate.DEPTH
        val siblings = if (parent != null) dict.children(parent.id).count { it.status.active }
            else dict.active().count { it.parentId == null && it.kind == entry.kind }
        if (siblings >= (if (parent != null) MAX_CHILDREN else MAX_TOP_LEVEL)) failed += Gate.FAN_OUT
        if (entry.kind == CoreKind.OTHER) failed += Gate.KIND_KNOWN
        if (nearDuplicate(dict, entry) != null) failed += Gate.DISTINCT
        val parentPending = entry.parentId == null && entry.parentHint != null && dict.entries.values.any { p ->
            p.id != entry.id && p.status == EntryStatus.CANDIDATE && p.names().any { AppLexicon.normalize(it) == AppLexicon.normalize(entry.parentHint) }
        }
        return when {
            Gate.NAMED_BY_APP in failed || Gate.ANCHORED in failed -> GateCheck(failed, Verdict.WAIT, "no app name or no place on screen")
            parentPending -> GateCheck(failed, Verdict.WAIT, "waiting for “${entry.parentHint}” to be confirmed first")
            Gate.SEEN_TWICE in failed -> GateCheck(failed, Verdict.WAIT,
                if (entry.nameProof == ChromeProof.LEXICON) "seen once so far" else "a downloaded menu name needs two days")
            failed.isEmpty() && entry.nameProof == ChromeProof.LEXICON -> GateCheck(failed, Verdict.CONFIRM, "all gates passed")
            else -> GateCheck(failed, Verdict.ASK, buildList {
                if (entry.nameProof != ChromeProof.LEXICON) add("the name comes from a downloaded menu")
                failed.forEach { add("fails: ${it.plain}") }
            }.joinToString("; "))
        }
    }

    private fun nearDuplicate(dict: AppDictionary, entry: DictEntry): DictEntry? {
        val mine = words(entry.name)
        return dict.active().filter { it.id != entry.id && (it.kind == entry.kind || entry.kind == CoreKind.OTHER) }.firstOrNull { other ->
            other.names().any { name -> jaccard(mine, words(name)) >= 0.5 }
        }
    }

    private fun words(name: String): Set<String> = AppLexicon.tokens(AppLexicon.normalize(name)).filter { it.length > 1 }.toSet()
    private fun jaccard(a: Set<String>, b: Set<String>): Double = if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

    /** Links each candidate's on-screen parent name to an active set of the same kind. */
    private fun resolveParents(dict: AppDictionary): AppDictionary {
        var out = dict
        for (entry in dict.entries.values.filter { it.status == EntryStatus.CANDIDATE && it.parentId == null && it.parentHint != null }) {
            val hint = AppLexicon.normalize(entry.parentHint!!)
            val parent = dict.active().firstOrNull { p -> p.id != entry.id && (p.kind == entry.kind || entry.kind == CoreKind.OTHER) && p.names().any { AppLexicon.normalize(it) == hint } }
                ?: continue
            out = out.copy(entries = out.entries + (entry.id to entry.copy(parentId = parent.id, kind = if (entry.kind == CoreKind.OTHER) parent.kind else entry.kind)))
        }
        return out
    }

    fun questionFor(dict: AppDictionary, entry: DictEntry, key: String, reason: String): OrganizerQuestion {
        val mine = words(entry.name)
        val nearby = dict.active().filter { it.id != entry.id }
            .sortedWith(compareByDescending<DictEntry> { e -> e.names().maxOf { jaccard(mine, words(it)) } }
                .thenByDescending { it.kind == entry.kind }
                .thenBy { it.parentId != null })
            .take(6)
            .map { OrganizerQuestion.Nearby(it.id, dict.path(it)) }
        return OrganizerQuestion(
            key = key,
            entryId = entry.id,
            name = entry.name,
            kindGuess = entry.kind,
            reason = reason,
            where = entry.anchors.map { a ->
                when (a.kind) {
                    com.cyclone.mobile.manual.AnchorKind.VIEW -> "a category" + (a.screenTitle?.let { " on “$it”" } ?: "") +
                        if (a.siblings.isNotEmpty()) " next to ${a.siblings.joinToString(", ") { "“$it”" }}" else ""
                    com.cyclone.mobile.manual.AnchorKind.LIST -> "a list" + (a.screenTitle?.let { " on “$it”" } ?: "") +
                        (a.rowShape?.let { " (rows: $it)" } ?: "") + if (a.searchable) ", searchable" else ""
                }
            }.distinct().take(4),
            markers = entry.markers,
            parentHint = entry.parentHint,
            nearby = nearby,
        )
    }

    fun run(dictionary: AppDictionary, judge: OrganizerJudge?, pass: PassInfo): OrganizerResult {
        var dict = dictionary
        val confirmed = ArrayList<String>()
        val toAsk = LinkedHashMap<String, Pair<DictEntry, String>>()
        // Parents first: a set confirmed in one round can take its sub-categories in the next (at most MAX_DEPTH rounds).
        for (round in 0 until MAX_DEPTH) {
            dict = resolveParents(dict)
            var changed = false
            toAsk.clear()
            for (id in dict.entries.values.filter { it.status == EntryStatus.CANDIDATE }.sortedBy { it.firstSeenAt }.map { it.id }) {
                // A parent confirmed earlier in this round is linked before its sub-category is judged.
                dict = resolveParents(dict)
                val entry = dict.entries.getValue(id)
                val gates = check(dict, entry)
                when (gates.verdict) {
                    Verdict.CONFIRM -> {
                        dict = dict.copy(entries = dict.entries + (entry.id to entry.copy(status = EntryStatus.CONFIRMED, note = "gates: ${gates.reason}")))
                            .withAudit(AuditEvent(pass.at, "confirmed", entry.id, "“${entry.name}”: named by the app, own place on screen, seen twice", "gate"))
                        confirmed += entry.id
                        changed = true
                    }
                    Verdict.ASK -> toAsk[entry.id] = entry to gates.reason
                    Verdict.WAIT -> Unit
                }
            }
            if (!changed) break
        }
        val questions = toAsk.values.take(MAX_QUESTIONS).mapIndexed { i, (entry, reason) -> questionFor(dict, entry, "q${i + 1}", reason) }
        val decisions = if (judge == null || questions.isEmpty()) emptyList() else
            runCatching { judge.decide(questions) }.getOrElse { error ->
                dict = dict.withAudit(AuditEvent(pass.at, "question_failed", "-", (error.message ?: error.javaClass.simpleName).take(120), judge.label))
                emptyList()
            }
        for (question in questions) {
            val decision = decisions.firstOrNull { it.key == question.key } ?: continue
            val applied = apply(dict, question, decision, pass.at, judge?.label ?: "model")
            if (applied !== dict) {
                dict = applied
                if (dict.entries[question.entryId]?.status == EntryStatus.CONFIRMED) confirmed += question.entryId
            }
        }
        dict = sweep(dict, pass)
        return OrganizerResult(dict.copy(passes = dict.passes + 1, updatedAt = pass.at), confirmed, questions, decisions)
    }

    /** Applies one answer, if it is one of the allowed ones for its question. Anything else leaves the candidate. */
    fun apply(dict: AppDictionary, question: OrganizerQuestion, decision: OrganizerDecision, at: Long, by: String): AppDictionary {
        val entry = dict.entries[question.entryId]?.takeIf { it.status == EntryStatus.CANDIDATE } ?: return dict
        val allowed = question.nearby.map { it.id }.toSet()
        return when (decision.choice) {
            Choice.KEEP -> dict.withAudit(AuditEvent(at, "kept", entry.id, "“${entry.name}” stays a candidate", by))
            Choice.REJECT -> dict.copy(entries = dict.entries + (entry.id to entry.copy(status = EntryStatus.REJECTED, note = "rejected by $by")))
                .withAudit(AuditEvent(at, "rejected", entry.id, "“${entry.name}”", by))
            Choice.SAME_AS -> {
                val target = decision.targetId?.takeIf { it in allowed }?.let(dict::resolve) ?: return dict
                mergeInto(dict, entry, target, at, by)
            }
            Choice.SUBSET_OF -> {
                val parent = decision.targetId?.takeIf { it in allowed }?.let(dict::resolve)?.takeIf { it.status.active } ?: return dict
                if (dict.depth(parent.id) + 1 > MAX_DEPTH) return dict
                val kind = if (entry.kind == CoreKind.OTHER) parent.kind else entry.kind
                confirm(dict, entry.copy(parentId = parent.id, kind = kind), at, by, "under “${parent.shownName}”")
            }
            Choice.NEW -> {
                val kind = decision.kind?.takeIf { it != CoreKind.OTHER } ?: entry.kind.takeIf { it != CoreKind.OTHER } ?: return dict
                val parent = decision.parentId?.let { id -> if (id !in allowed) return dict else dict.resolve(id)?.takeIf { it.status.active } ?: return dict }
                if (parent != null && dict.depth(parent.id) + 1 > MAX_DEPTH) return dict
                val under = parent ?: entry.parentId?.let(dict::resolve)?.takeIf { it.status.active }
                // A sub-group always has its parent's kind, so the tree never mixes kinds.
                val finalKind = under?.kind ?: kind
                confirm(dict, entry.copy(kind = finalKind, parentId = under?.id), at, by,
                    "new ${finalKind.label}" + (under?.let { " under “${it.shownName}”" } ?: ""))
            }
        }
    }

    private fun confirm(dict: AppDictionary, entry: DictEntry, at: Long, by: String, detail: String): AppDictionary =
        dict.copy(entries = dict.entries + (entry.id to entry.copy(status = EntryStatus.CONFIRMED, note = "$by: $detail")))
            .withAudit(AuditEvent(at, "confirmed", entry.id, "“${entry.name}”: $detail", by))

    private fun mergeInto(dict: AppDictionary, from: DictEntry, into: DictEntry, at: Long, by: String): AppDictionary {
        if (from.id == into.id) return dict
        val keepName = into.status == EntryStatus.LOCKED
        val target = into.copy(
            aliases = if (keepName) into.aliases else DictionaryPrivacy.texts(into.aliases + from.names(), 8).filter { it != into.name },
            anchors = (into.anchors + from.anchors).distinctBy { it.identity }.takeLast(6),
            markers = DictionaryPrivacy.texts(into.markers + from.markers, 6),
            observations = into.observations + from.observations,
            days = (into.days + from.days).distinct().takeLast(10),
            versions = (into.versions + from.versions).distinct().takeLast(8),
            lastSeenAt = maxOf(into.lastSeenAt, from.lastSeenAt),
        )
        val repointed = dict.entries.mapValues { (_, e) -> if (e.parentId == from.id) e.copy(parentId = into.id) else e }
        return dict.copy(entries = repointed + (into.id to target) + (from.id to from.copy(status = EntryStatus.MERGED, redirectTo = into.id)))
            .withAudit(AuditEvent(at, "merged", from.id, "“${from.name}” is the same as “${into.shownName}”", by))
    }

    /** After a pass: confirmed sets whose screen was seen without them are counted as missed; two misses retire them. */
    private fun sweep(dictionary: AppDictionary, pass: PassInfo): AppDictionary {
        var dict = dictionary
        for (entry in dictionary.entries.values) {
            when {
                entry.status == EntryStatus.CANDIDATE && pass.at - entry.lastSeenAt > STALE_CANDIDATE_MS -> {
                    dict = dict.copy(entries = dict.entries - entry.id)
                        .withAudit(AuditEvent(pass.at, "expired", entry.id, "“${entry.name}” not seen for 30 days", "gate"))
                }
                entry.status == EntryStatus.CONFIRMED && entry.lastSeenAt < pass.at && entry.anchors.any { it.roomKey in pass.roomsSeen } -> {
                    val missed = entry.missedPasses + 1
                    dict = if (missed >= 2) dict.copy(entries = dict.entries + (entry.id to entry.copy(status = EntryStatus.RETIRED, missedPasses = missed)))
                        .withAudit(AuditEvent(pass.at, "retired", entry.id, "“${entry.name}” missing from its screen on two passes", "gate"))
                    else dict.copy(entries = dict.entries + (entry.id to entry.copy(missedPasses = missed)))
                }
            }
        }
        return dict
    }

    // ---- 3. the owner ----

    sealed class OwnerEdit {
        data class Confirm(val id: String) : OwnerEdit()
        data class Reject(val id: String) : OwnerEdit()
        data class Lock(val id: String) : OwnerEdit()
        data class Unlock(val id: String) : OwnerEdit()
        data class Rename(val id: String, val label: String) : OwnerEdit()
        data class Merge(val id: String, val into: String) : OwnerEdit()
        data class Unmerge(val id: String) : OwnerEdit()
        data class Move(val id: String, val parentId: String?) : OwnerEdit()
        data class SetKind(val id: String, val kind: CoreKind) : OwnerEdit()
    }

    class EditRefused(message: String) : IllegalArgumentException(message)

    fun edit(dict: AppDictionary, edit: OwnerEdit, at: Long): AppDictionary {
        fun entry(id: String) = dict.entries[id] ?: throw EditRefused("No set with id $id.")
        fun put(e: DictEntry, action: String, detail: String) = dict.copy(entries = dict.entries + (e.id to e), updatedAt = at)
            .withAudit(AuditEvent(at, action, e.id, detail, "owner"))
        return when (edit) {
            is OwnerEdit.Confirm -> entry(edit.id).let { put(it.copy(status = EntryStatus.CONFIRMED, missedPasses = 0), "confirmed", "“${it.name}”") }
            is OwnerEdit.Reject -> entry(edit.id).let { put(it.copy(status = EntryStatus.REJECTED), "rejected", "“${it.name}”") }
            is OwnerEdit.Lock -> entry(edit.id).let {
                if (it.status == EntryStatus.MERGED || it.status == EntryStatus.REJECTED) throw EditRefused("Only an active or candidate set can be locked.")
                put(it.copy(status = EntryStatus.LOCKED, missedPasses = 0), "locked", "“${it.name}”")
            }
            is OwnerEdit.Unlock -> entry(edit.id).let {
                if (it.status != EntryStatus.LOCKED) throw EditRefused("That set is not locked.")
                put(it.copy(status = EntryStatus.CONFIRMED), "unlocked", "“${it.name}”")
            }
            is OwnerEdit.Rename -> entry(edit.id).let {
                val label = DictionaryPrivacy.text(edit.label) ?: throw EditRefused("Use a short name (up to 6 words, no long numbers).")
                put(it.copy(ownerLabel = label, aliases = DictionaryPrivacy.texts(it.aliases + label, 8).filter { a -> a != it.name }), "renamed", "“${it.name}” shown as “$label”")
            }
            is OwnerEdit.Merge -> {
                val from = entry(edit.id)
                val into = dict.resolve(edit.into) ?: throw EditRefused("No set with id ${edit.into}.")
                if (from.id == into.id) throw EditRefused("A set can't be merged into itself.")
                if (from.status == EntryStatus.LOCKED) throw EditRefused("Unlock “${from.name}” first.")
                mergeInto(dict, from, into, at, "owner").copy(updatedAt = at)
            }
            is OwnerEdit.Unmerge -> entry(edit.id).let {
                if (it.status != EntryStatus.MERGED) throw EditRefused("That set was not merged.")
                put(it.copy(status = EntryStatus.CONFIRMED, redirectTo = null), "unmerged", "“${it.name}” is its own set again")
            }
            is OwnerEdit.Move -> {
                val e = entry(edit.id)
                val parent = edit.parentId?.let { dict.resolve(it) ?: throw EditRefused("No set with id $it.") }
                if (parent != null) {
                    var at2: DictEntry? = parent
                    val seen = HashSet<String>()
                    while (at2 != null && seen.add(at2.id)) {
                        if (at2.id == e.id) throw EditRefused("A set can't sit under itself.")
                        at2 = at2.parentId?.let(dict.entries::get)
                    }
                    if (dict.depth(parent.id) + 1 > MAX_DEPTH) throw EditRefused("That would be more than $MAX_DEPTH levels deep.")
                }
                put(e.copy(parentId = parent?.id, kind = parent?.kind ?: e.kind), "moved", "“${e.name}” under " + (parent?.let { "“${it.shownName}”" } ?: e.kind.label))
            }
            is OwnerEdit.SetKind -> entry(edit.id).let {
                if (it.parentId != null) throw EditRefused("A set under another set takes its parent's kind.")
                put(it.copy(kind = edit.kind), "kind", "“${it.name}” is a ${edit.kind.label}")
            }
        }
    }

    // ---- the owner's "app word or yours?" answers (plan 36 §5.1, alpha.60) ----

    /**
     * The owner said a downloaded name is the app's own word: it joins as a confirmed set (or folds into the set it
     * already is). Only the owner can admit a name that is not one of the app's shipped strings.
     */
    fun ownerAdmit(dictionary: AppDictionary, proposal: SetProposal, at: Long): AppDictionary {
        val name = DictionaryPrivacy.text(proposal.name.text) ?: throw EditRefused("That name can't be kept.")
        val pass = PassInfo(at, null)
        var dict = record(dictionary, listOf(proposal.copy(proven = true)), pass)
        val entry = dict.entries.values.firstOrNull { e -> e.status != EntryStatus.MERGED && e.names().any { AppLexicon.normalize(it) == AppLexicon.normalize(name) } }
            ?: throw EditRefused("That name can't be kept.")
        dict = resolveParents(dict)
        val current = dict.entries.getValue(entry.id)
        if (current.status.active) return dict.withAudit(AuditEvent(at, "app_word", current.id, "“$name” is the app's word", "owner"))
        val parent = current.parentId?.let(dict::resolve)?.takeIf { it.status.active }
        val kind = parent?.kind ?: current.kind
        return dict.copy(entries = dict.entries + (current.id to current.copy(status = EntryStatus.CONFIRMED, kind = kind, parentId = parent?.id, note = "owner: the app's word")), updatedAt = at)
            .withAudit(AuditEvent(at, "app_word", current.id, "“$name” is the app's word", "owner"))
    }

    /** The owner said a name is their own ("Mine"): only its hash is kept, so it is never asked about again. */
    fun decline(dictionary: AppDictionary, nameHash: String, at: Long): AppDictionary =
        dictionary.copy(declined = (dictionary.declined + nameHash).toList().takeLast(AppDictionary.MAX_DECLINED).toSet(), updatedAt = at)
            .withAudit(AuditEvent(at, "declined", "-", "a name you said is yours (not kept)", "owner"))

    // ---- health and glossary ----

    fun health(dict: AppDictionary, now: Long, currentVersion: String?): Health {
        val active = dict.active()
        val near = ArrayList<Pair<String, String>>()
        for ((i, a) in active.withIndex()) for (b in active.drop(i + 1)) {
            if (a.kind == b.kind && a.names().any { x -> b.names().any { y -> jaccard(words(x), words(y)) >= 0.5 } }) near += a.id to b.id
        }
        return Health(
            orphans = active.filter { e -> e.parentId != null && dict.resolve(e.parentId)?.status?.active != true }.map { it.id },
            nearDuplicates = near.take(20),
            tooDeep = active.filter { dict.depth(it.id) > MAX_DEPTH }.map { it.id },
            tooWide = active.filter { dict.children(it.id).count { c -> c.status.active } > MAX_CHILDREN }.map { it.id },
            staleCandidates = dict.entries.values.filter { it.status == EntryStatus.CANDIDATE && now - it.lastSeenAt > 14 * DAY_MS }.map { it.id },
            notSeenInVersion = if (currentVersion == null) emptyList() else active.filter { currentVersion !in it.versions }.map { it.id },
        )
    }

    /**
     * The glossary block an agent reads first (plan 36 §7.4): active sets under their core kinds, with where each one
     * lives and what shows membership. At most [maxLines] lines.
     */
    fun glossary(dict: AppDictionary, appLabel: String, maxLines: Int = 20): String {
        val active = dict.active()
        val places = placeLines(dict, maxLines / 2)
        if (active.isEmpty()) return if (places.isEmpty()) "" else (listOf("Places in $appLabel (the app's own words):") + places).joinToString("\n")
        val lines = ArrayList<String>()
        lines += "Dictionary of $appLabel (the app's own groups; ids are stable):"
        for (entry in active.sortedWith(compareBy({ it.kind.ordinal }, { dict.path(it) }))) {
            if (lines.size >= maxLines) { lines += "  …"; break }
            val where = entry.anchors.firstOrNull()?.let { a ->
                when (a.kind) {
                    com.cyclone.mobile.manual.AnchorKind.VIEW -> "category" + (a.screenTitle?.let { " on “$it”" } ?: "")
                    com.cyclone.mobile.manual.AnchorKind.LIST -> "list" + (a.screenTitle?.let { " “$it”" } ?: "")
                }
            }
            val search = entry.anchors.firstOrNull { it.searchable }?.let { a -> "find one: " + (a.searchLabel?.let { "“$it”" } ?: "search") }
            val marker = entry.markers.takeIf { it.isNotEmpty() }?.let { m -> "rows show " + m.joinToString(", ") { "“$it”" } }
            lines += "  ${entry.id} = ${dict.path(entry)}" + listOfNotNull(where, search, marker).joinToString("; ").let { if (it.isBlank()) "" else " ($it)" }
        }
        if (places.isNotEmpty() && lines.size < maxLines + 4) lines += listOf("Places (the app's own words):") + places
        return lines.joinToString("\n")
    }

    /** Panels first (what "+" or ⋯ opens and what it offers), then named screens; at most [max] lines. */
    private fun placeLines(dict: AppDictionary, max: Int): List<String> {
        val named = dict.screens.values.filter { it.name != null }.sortedWith(compareBy({ !it.isPanel }, { -it.seen }))
        return named.take(max.coerceAtLeast(0)).map { card ->
            val over = card.panelOf?.let { dict.screens[it]?.name }
            if (card.isPanel) "  “${card.name}” opens a panel" + (over?.let { " over “$it”" } ?: "") +
                (if (card.items.isNotEmpty()) " offering " + card.items.take(8).joinToString(", ") { "“$it”" } else "")
            else "  “${card.name}”" + (card.category?.let { " (category “$it” selected)" } ?: "") +
                (if (card.items.isNotEmpty()) " with buttons " + card.items.take(6).joinToString(", ") { "“$it”" } else "")
        }
    }
}
