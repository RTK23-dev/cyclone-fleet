package com.cyclone.mobile.manual.dictionary

import com.cyclone.mobile.brain.graphv2.AtlasPrivacy
import com.cyclone.mobile.manual.Anchor
import com.cyclone.mobile.manual.AnchorKind
import com.cyclone.mobile.manual.AppLexicon
import com.cyclone.mobile.manual.ChromeProof
import com.cyclone.mobile.manual.CoreKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * Where an entry stands. Only the organizer (gates or its one question) and the owner move an entry out of
 * [CANDIDATE]; a model can only propose (plan 36 §7.5).
 */
enum class EntryStatus(val wire: String) {
    CANDIDATE("candidate"),
    CONFIRMED("confirmed"),
    /** Confirmed and locked by the owner: the organizer never changes it. */
    LOCKED("locked"),
    REJECTED("rejected"),
    /** Folded into another entry; its id still resolves there. */
    MERGED("merged"),
    /** Not seen for two passes over its screens; kept so old references still resolve. */
    RETIRED("retired");

    val active: Boolean get() = this == CONFIRMED || this == LOCKED

    companion object {
        fun fromWire(value: String?): EntryStatus? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One set in an app's dictionary: a named group of one [CoreKind] ("Close friends" ⊂ Person), defined by where the
 * app shows it. It holds the app's own words, anchors, markers and counts of sightings: never a member, a count of
 * members or any name of a person (plan 36 §7.3). [id] is stable and never reused.
 */
data class DictEntry(
    val id: String,
    val kind: CoreKind,
    val name: String,
    val nameProof: ChromeProof,
    val resKey: String? = null,
    val aliases: List<String> = emptyList(),
    val ownerLabel: String? = null,
    val parentId: String? = null,
    /** A parent named on screen (the title above a category strip) that is resolved to an id when the organizer runs. */
    val parentHint: String? = null,
    val status: EntryStatus = EntryStatus.CANDIDATE,
    val anchors: List<Anchor> = emptyList(),
    val markers: List<String> = emptyList(),
    val observations: Int = 0,
    val days: List<Long> = emptyList(),
    val firstSeenAt: Long = 0,
    val lastSeenAt: Long = 0,
    val versions: List<String> = emptyList(),
    val missedPasses: Int = 0,
    val redirectTo: String? = null,
    val note: String? = null,
    /** Proven by a probe in a pass (plan 36 §5.1): the same category row was seen switching views. */
    val proven: Boolean = false,
) {
    val shownName: String get() = ownerLabel ?: name
    fun names(): List<String> = (listOf(name) + aliases + listOfNotNull(ownerLabel)).distinct()
}

/**
 * One screen or panel of the app, named in the app's own words (plan 36 §3, alpha.60). Keyed by the mapper's room key,
 * which is also the Atlas screen id, so Glass can put the name on the map. Structure only: exact app strings, never a
 * row, a name or typed text.
 */
data class ScreenCard(
    val roomKey: String,
    /** The screen's title, when it is exactly one of the app's strings. */
    val title: String? = null,
    /** The category selected when the screen was read ("Primary"). */
    val category: String? = null,
    /** The app's words on the door that first led here ("Settings", "Add photos and files"). */
    val via: String? = null,
    /** For a panel (opened by a reveal door): the room it opens over. */
    val panelOf: String? = null,
    /** The app's words on this screen's own buttons (a panel's offers), at most 12. */
    val items: List<String> = emptyList(),
    val seen: Int = 0,
    val lastSeenAt: Long = 0,
    /** The phone's structural page keys for this place (`package:Class:hash`), so a walk can tell where it is. */
    val pageKeys: List<String> = emptyList(),
    /** One line on what the place is for, written by the describer from the app's words only (alpha.64). */
    val purpose: String? = null,
    /** The place's main list, in structure only. */
    val list: ListNote? = null,
) {
    val isPanel: Boolean get() = panelOf != null
    /** The name shown on the map: the title, else the door that led here, else the selected category. */
    val name: String? get() = title ?: via ?: category
}

/** A list as the manual keeps it: row shape, order, the app's section headers, and how to find one row. */
data class ListNote(
    val shape: String,
    val order: String? = null,
    val groups: List<String> = emptyList(),
    val searchable: Boolean = false,
    val searchLabel: String? = null,
)

/** How often walking an ability worked (plan 36 §5.6): runs teach the manual. Counts and a time only. */
data class AbilityStat(val walked: Int = 0, val failed: Int = 0, val lastAt: Long = 0)

/** One self-quiz goal (plan 36 §5.5): a plain-language goal and the ability the manual answers it with, if any. */
data class QuizGoal(val goal: String, val abilityId: String? = null, val score: Double = 0.0)

/** The last self-quiz: how many goals the manual alone can answer. */
data class QuizResult(val at: Long, val goals: List<QuizGoal>) {
    val answered: Int get() = goals.count { it.abilityId != null }
    val gaps: List<String> get() = goals.filter { it.abilityId == null }.map { it.goal }
}

/** A walked door between two rooms, with the app's words on it. [edgeId] is the Atlas edge id. */
data class DoorCard(val edgeId: String, val from: String, val to: String, val label: String?, val kind: String, val lastSeenAt: Long = 0)

data class AuditEvent(val at: Long, val action: String, val entryId: String, val detail: String, val by: String)

/** JEV watching the organizer's question: how often its pick matched the model's. Kinds and timings only. */
data class JevOrganizerTally(
    val answered: Int = 0,
    val agreed: Int = 0,
    val sureAnswered: Int = 0,
    val sureAgreed: Int = 0,
    val timesMs: List<Long> = emptyList(),
    val lastError: String? = null,
) {
    fun add(agree: Boolean, confidence: Double, ms: Long): JevOrganizerTally = copy(
        answered = answered + 1,
        agreed = agreed + if (agree) 1 else 0,
        sureAnswered = sureAnswered + if (confidence >= 0.8) 1 else 0,
        sureAgreed = sureAgreed + if (confidence >= 0.8 && agree) 1 else 0,
        timesMs = (timesMs + ms).takeLast(50),
        lastError = null,
    )

    fun summary(): String {
        if (answered == 0) return lastError?.let { "No answer yet: $it" } ?: "No decisions yet."
        val median = timesMs.sorted().let { if (it.isEmpty()) null else it[(it.size - 1) / 2] }
        return "Agreed $agreed of $answered" + (median?.let { " · ${"%.1f".format(it / 1000.0)} s" } ?: "") +
            (if (sureAnswered > 0) " · ${sureAgreed * 100 / sureAnswered}% when sure" else "")
    }
}

data class AppDictionary(
    val packageName: String,
    val entries: Map<String, DictEntry> = emptyMap(),
    val audit: List<AuditEvent> = emptyList(),
    val jev: JevOrganizerTally = JevOrganizerTally(),
    val passes: Int = 0,
    val updatedAt: Long = 0,
    val screens: Map<String, ScreenCard> = emptyMap(),
    val doors: Map<String, DoorCard> = emptyMap(),
    /** Hashes of names the owner said are their own ("Mine"): never asked again, never stored as text. */
    val declined: Set<String> = emptySet(),
    /** Per ability id: how often walking it worked or stopped (alpha.64). */
    val abilityStats: Map<String, AbilityStat> = emptyMap(),
    /** Per ability id: other ways a person says it, written by the describer from the app's words (alpha.64). */
    val phrasings: Map<String, List<String>> = emptyMap(),
    /** The last self-quiz (alpha.64), or null before the first describer run. */
    val quiz: QuizResult? = null,
) {
    /** Follows merges to the entry that stands for [id] now. */
    fun resolve(id: String?): DictEntry? {
        var current = id?.let(entries::get) ?: return null
        repeat(MAX_REDIRECTS) {
            val next = current.redirectTo?.let(entries::get) ?: return current
            current = next
        }
        return current
    }

    fun active(): List<DictEntry> = entries.values.filter { it.status.active }

    fun children(id: String): List<DictEntry> = entries.values.filter { it.parentId == id && it.status != EntryStatus.MERGED && it.status != EntryStatus.REJECTED }

    fun depth(id: String?): Int {
        var depth = 0
        var at = id?.let(entries::get)
        val seen = HashSet<String>()
        while (at != null && seen.add(at.id)) {
            depth++
            at = at.parentId?.let(entries::get)
        }
        return depth
    }

    /** The chain from the core kind down to [entry]: "Person › Followers". */
    fun path(entry: DictEntry): String {
        val names = ArrayList<String>()
        var at: DictEntry? = entry
        val seen = HashSet<String>()
        while (at != null && seen.add(at.id)) {
            names.add(0, at.shownName)
            at = at.parentId?.let(entries::get)
        }
        return (listOf(entry.kind.label) + names).joinToString(" › ")
    }

    fun withAudit(event: AuditEvent): AppDictionary = copy(audit = (audit + event).takeLast(MAX_AUDIT))

    fun newId(name: String): String {
        val slug = AppLexicon.normalize(name).replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(32).ifBlank { "set" }
        var id = "set:$slug"
        var n = 2
        while (id in entries) id = "set:${slug}_${n++}"
        return id
    }

    companion object {
        const val MAX_AUDIT = 200
        const val MAX_REDIRECTS = 8
        const val MAX_ENTRIES = 400
        const val MAX_SCREENS = 300
        const val MAX_DOORS = 600
        const val MAX_DECLINED = 500
        const val MAX_ABILITY_STATS = 600
        const val MAX_PHRASINGS = 8
        const val MAX_QUIZ = 24
    }
}

/**
 * The dictionary holds structure only (plan 36 §7.3). Every text field is checked here on the way in and on the way
 * out: short, free of captured values (emails, tokens, codes), and never more than a few words.
 */
object DictionaryPrivacy {
    const val MAX_TEXT = 60
    const val MAX_WORDS = 6

    fun text(raw: String?): String? {
        val value = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (value.isEmpty() || value.length > MAX_TEXT) return null
        if (value.split(' ').size > MAX_WORDS) return null
        if (Regex("\\d{3,}").containsMatchIn(value)) return null
        if (AtlasPrivacy.structuralLabel(value, "").isBlank()) return null
        return value
    }

    fun texts(values: List<String>, max: Int): List<String> = values.mapNotNull(::text).distinct().take(max)

    fun anchor(anchor: Anchor): Anchor = anchor.copy(
        screenTitle = text(anchor.screenTitle),
        siblings = texts(anchor.siblings, 11),
        groups = texts(anchor.groups, 12),
        searchLabel = text(anchor.searchLabel),
        order = anchor.order?.takeIf { com.cyclone.mobile.manual.ListOrder.fromWire(it) != null },
        rowShape = anchor.rowShape?.takeIf { SHAPE.matches(it) },
        roomKey = anchor.roomKey.takeIf { ROOM.matches(it) } ?: "screen:unknown",
        containerKey = anchor.containerKey.takeIf { HEX.matches(it) } ?: "0",
    )

    /** An entry as it may be stored or shown; null when its name itself fails. */
    fun entry(entry: DictEntry): DictEntry? {
        val name = text(entry.name) ?: return null
        return entry.copy(
            name = name,
            aliases = texts(entry.aliases, 8).filter { it != name },
            ownerLabel = text(entry.ownerLabel),
            parentHint = text(entry.parentHint),
            markers = texts(entry.markers, 6),
            anchors = entry.anchors.map(::anchor).distinctBy { it.identity }.take(6),
            resKey = entry.resKey?.takeIf { RES.matches(it) },
            note = entry.note?.take(120)?.let(::noteText),
        )
    }

    fun screen(card: ScreenCard): ScreenCard? {
        if (!ROOM.matches(card.roomKey) || card.roomKey == "screen:unknown") return null
        return card.copy(
            title = text(card.title),
            category = text(card.category),
            via = text(card.via),
            panelOf = card.panelOf?.takeIf { ROOM.matches(it) && it != card.roomKey },
            items = texts(card.items, 12),
            pageKeys = card.pageKeys.filter { PAGE_KEY.matches(it) }.distinct().take(MAX_PAGE_KEYS),
            purpose = sentence(card.purpose, 18, 120),
            list = card.list?.let(::list),
        )
    }

    fun list(note: ListNote): ListNote? {
        val shape = note.shape.takeIf { SHAPE.matches(it) } ?: return null
        return note.copy(
            shape = shape,
            order = note.order?.takeIf { com.cyclone.mobile.manual.ListOrder.fromWire(it) != null },
            groups = texts(note.groups, 12),
            searchLabel = text(note.searchLabel),
        )
    }

    /**
     * A short sentence written by a model from the app's words (a purpose, a phrasing, a quiz goal): one line, no
     * captured values (emails, links, codes or long numbers).
     */
    fun sentence(raw: String?, maxWords: Int, maxLength: Int): String? {
        val value = raw?.trim()?.replace(Regex("\\s+"), " ")?.trim('"', '“', '”', ' ') ?: return null
        if (value.isEmpty() || value.length > maxLength || value.split(' ').size > maxWords) return null
        if (Regex("\\d{3,}|@|https?:|www\\.|[<>{}\\[\\]]").containsMatchIn(value)) return null
        if (AtlasPrivacy.structuralLabel(value, "").isBlank()) return null
        return value
    }

    fun phrasing(raw: String?): String? = sentence(raw, 10, 70)
    fun goal(raw: String?): String? = sentence(raw, 12, 90)

    const val MAX_PAGE_KEYS = 4
    private val PAGE_KEY = Regex("^[A-Za-z][A-Za-z0-9_.]{1,120}:[A-Za-z0-9_$]{1,80}:[0-9a-f]{28}$")

    fun door(card: DoorCard): DoorCard? {
        if (!EDGE.matches(card.edgeId) || !ROOM.matches(card.from) || !ROOM.matches(card.to)) return null
        return card.copy(label = text(card.label), kind = card.kind.lowercase().takeIf { KIND.matches(it) } ?: "navigate")
    }

    private val EDGE = Regex("^edge:[0-9a-f]{16,64}$")
    private val KIND = Regex("^[a-z_]{2,24}$")

    private fun noteText(value: String): String? = value.takeIf { AtlasPrivacy.structuralLabel(it.take(60), "").isNotBlank() }

    private val SHAPE = Regex("^[0-9]{1,2} texts?( · image)?( · button)?$")
    private val ROOM = Regex("^screen:[a-z_]{1,20}:[0-9a-f]{16}$|^screen:unknown$")
    private val HEX = Regex("^[0-9a-f]{1,32}$")
    private val RES = Regex("^[A-Za-z0-9_.]{2,80}$")
}

/** JSON with a fixed set of keys: what is written is exactly what [DictionaryPrivacy] allowed. */
object DictionaryJson {
    val ENTRY_KEYS = setOf("id", "kind", "name", "nameProof", "resKey", "aliases", "ownerLabel", "parentId", "parentHint",
        "status", "anchors", "markers", "observations", "days", "firstSeenAt", "lastSeenAt", "versions", "missedPasses",
        "redirectTo", "note", "proven")
    val SCREEN_KEYS = setOf("roomKey", "title", "category", "via", "panelOf", "items", "seen", "lastSeenAt", "pageKeys", "purpose", "list")
    val LIST_KEYS = setOf("shape", "order", "groups", "searchable", "searchLabel")
    val DOOR_KEYS = setOf("edgeId", "from", "to", "label", "kind", "lastSeenAt")
    val ANCHOR_KEYS = setOf("kind", "roomKey", "containerKey", "screenTitle", "position", "siblings", "groups", "rowShape",
        "searchable", "searchLabel", "order")

    fun write(dictionary: AppDictionary): JSONObject = JSONObject()
        .put("schema", 3)
        .put("screens", JSONArray().also { out ->
            dictionary.screens.values.mapNotNull(DictionaryPrivacy::screen).forEach { c ->
                out.put(JSONObject().put("roomKey", c.roomKey).put("title", c.title ?: JSONObject.NULL).put("category", c.category ?: JSONObject.NULL)
                    .put("via", c.via ?: JSONObject.NULL).put("panelOf", c.panelOf ?: JSONObject.NULL).put("items", JSONArray(c.items))
                    .put("seen", c.seen).put("lastSeenAt", c.lastSeenAt).put("pageKeys", JSONArray(c.pageKeys))
                    .put("purpose", c.purpose ?: JSONObject.NULL).put("list", c.list?.let(::list) ?: JSONObject.NULL))
            }
        })
        .put("doors", JSONArray().also { out ->
            dictionary.doors.values.mapNotNull(DictionaryPrivacy::door).forEach { d ->
                out.put(JSONObject().put("edgeId", d.edgeId).put("from", d.from).put("to", d.to).put("label", d.label ?: JSONObject.NULL)
                    .put("kind", d.kind).put("lastSeenAt", d.lastSeenAt))
            }
        })
        .put("declined", JSONArray(dictionary.declined.filter { HASH.matches(it) }.take(AppDictionary.MAX_DECLINED)))
        .put("abilityStats", JSONArray().also { out ->
            dictionary.abilityStats.entries.filter { ABILITY.matches(it.key) }.sortedByDescending { it.value.lastAt }.take(AppDictionary.MAX_ABILITY_STATS)
                .forEach { (id, s) -> out.put(JSONObject().put("id", id).put("walked", s.walked).put("failed", s.failed).put("lastAt", s.lastAt)) }
        })
        .put("phrasings", JSONArray().also { out ->
            dictionary.phrasings.entries.filter { ABILITY.matches(it.key) }.take(AppDictionary.MAX_ABILITY_STATS).forEach { (id, says) ->
                val clean = says.mapNotNull(DictionaryPrivacy::phrasing).distinct().take(AppDictionary.MAX_PHRASINGS)
                if (clean.isNotEmpty()) out.put(JSONObject().put("id", id).put("say", JSONArray(clean)))
            }
        })
        .put("quiz", dictionary.quiz?.let { q ->
            JSONObject().put("at", q.at).put("goals", JSONArray().also { out ->
                q.goals.mapNotNull { g -> DictionaryPrivacy.goal(g.goal)?.let { g.copy(goal = it) } }.take(AppDictionary.MAX_QUIZ).forEach { g ->
                    out.put(JSONObject().put("goal", g.goal).put("abilityId", g.abilityId?.takeIf { ABILITY.matches(it) } ?: JSONObject.NULL)
                        .put("score", Math.round(g.score * 100) / 100.0))
                }
            })
        } ?: JSONObject.NULL)
        .put("packageName", dictionary.packageName)
        .put("passes", dictionary.passes)
        .put("updatedAt", dictionary.updatedAt)
        .put("entries", JSONArray().also { out -> dictionary.entries.values.mapNotNull(DictionaryPrivacy::entry).forEach { out.put(entry(it)) } })
        .put("audit", JSONArray().also { out ->
            dictionary.audit.forEach { e ->
                out.put(JSONObject().put("at", e.at).put("action", e.action).put("entryId", e.entryId).put("detail", e.detail.take(160)).put("by", e.by))
            }
        })
        .put("jev", JSONObject().put("answered", dictionary.jev.answered).put("agreed", dictionary.jev.agreed)
            .put("sureAnswered", dictionary.jev.sureAnswered).put("sureAgreed", dictionary.jev.sureAgreed)
            .put("timesMs", JSONArray(dictionary.jev.timesMs)).put("lastError", dictionary.jev.lastError ?: JSONObject.NULL))

    fun entry(e: DictEntry): JSONObject = JSONObject()
        .put("id", e.id)
        .put("kind", e.kind.wire)
        .put("name", e.name)
        .put("nameProof", e.nameProof.wire)
        .put("resKey", e.resKey ?: JSONObject.NULL)
        .put("aliases", JSONArray(e.aliases))
        .put("ownerLabel", e.ownerLabel ?: JSONObject.NULL)
        .put("parentId", e.parentId ?: JSONObject.NULL)
        .put("parentHint", e.parentHint ?: JSONObject.NULL)
        .put("status", e.status.wire)
        .put("anchors", JSONArray().also { out -> e.anchors.forEach { out.put(anchor(it)) } })
        .put("markers", JSONArray(e.markers))
        .put("observations", e.observations)
        .put("days", JSONArray(e.days))
        .put("firstSeenAt", e.firstSeenAt)
        .put("lastSeenAt", e.lastSeenAt)
        .put("versions", JSONArray(e.versions))
        .put("missedPasses", e.missedPasses)
        .put("redirectTo", e.redirectTo ?: JSONObject.NULL)
        .put("note", e.note ?: JSONObject.NULL)
        .put("proven", e.proven)

    fun anchor(a: Anchor): JSONObject = JSONObject()
        .put("kind", a.kind.wire)
        .put("roomKey", a.roomKey)
        .put("containerKey", a.containerKey)
        .put("screenTitle", a.screenTitle ?: JSONObject.NULL)
        .put("position", a.position ?: JSONObject.NULL)
        .put("siblings", JSONArray(a.siblings))
        .put("groups", JSONArray(a.groups))
        .put("rowShape", a.rowShape ?: JSONObject.NULL)
        .put("searchable", a.searchable)
        .put("searchLabel", a.searchLabel ?: JSONObject.NULL)
        .put("order", a.order ?: JSONObject.NULL)

    fun list(l: ListNote): JSONObject = JSONObject()
        .put("shape", l.shape)
        .put("order", l.order ?: JSONObject.NULL)
        .put("groups", JSONArray(l.groups))
        .put("searchable", l.searchable)
        .put("searchLabel", l.searchLabel ?: JSONObject.NULL)

    fun read(json: JSONObject): AppDictionary {
        val entries = LinkedHashMap<String, DictEntry>()
        json.optJSONArray("entries")?.let { array ->
            for (i in 0 until array.length()) {
                val raw = array.optJSONObject(i) ?: continue
                val parsed = parseEntry(raw)?.let(DictionaryPrivacy::entry) ?: continue
                entries[parsed.id] = parsed
            }
        }
        val audit = ArrayList<AuditEvent>()
        json.optJSONArray("audit")?.let { array ->
            for (i in 0 until array.length()) {
                val e = array.optJSONObject(i) ?: continue
                audit += AuditEvent(e.optLong("at"), e.optString("action"), e.optString("entryId"), e.optString("detail").take(160), e.optString("by"))
            }
        }
        val jev = json.optJSONObject("jev")?.let { j ->
            JevOrganizerTally(j.optInt("answered"), j.optInt("agreed"), j.optInt("sureAnswered"), j.optInt("sureAgreed"),
                j.optJSONArray("timesMs")?.let { t -> (0 until t.length()).map { t.optLong(it) } }.orEmpty(),
                j.optString("lastError").takeIf { it.isNotBlank() && it != "null" })
        } ?: JevOrganizerTally()
        val screens = LinkedHashMap<String, ScreenCard>()
        json.optJSONArray("screens")?.let { array ->
            for (i in 0 until array.length()) {
                val c = array.optJSONObject(i) ?: continue
                val card = DictionaryPrivacy.screen(ScreenCard(c.optString("roomKey"), nullable(c, "title"), nullable(c, "category"), nullable(c, "via"),
                    nullable(c, "panelOf"), strings(c.optJSONArray("items")), c.optInt("seen"), c.optLong("lastSeenAt"),
                    strings(c.optJSONArray("pageKeys")), nullable(c, "purpose"), c.optJSONObject("list")?.let(::parseList))) ?: continue
                screens[card.roomKey] = card
            }
        }
        val doors = LinkedHashMap<String, DoorCard>()
        json.optJSONArray("doors")?.let { array ->
            for (i in 0 until array.length()) {
                val d = array.optJSONObject(i) ?: continue
                val card = DictionaryPrivacy.door(DoorCard(d.optString("edgeId"), d.optString("from"), d.optString("to"), nullable(d, "label"),
                    d.optString("kind"), d.optLong("lastSeenAt"))) ?: continue
                doors[card.edgeId] = card
            }
        }
        val declined = strings(json.optJSONArray("declined")).filter { HASH.matches(it) }.toSet()
        val stats = LinkedHashMap<String, AbilityStat>()
        json.optJSONArray("abilityStats")?.let { array ->
            for (i in 0 until array.length()) {
                val s = array.optJSONObject(i) ?: continue
                val id = s.optString("id").takeIf { ABILITY.matches(it) } ?: continue
                stats[id] = AbilityStat(s.optInt("walked").coerceAtLeast(0), s.optInt("failed").coerceAtLeast(0), s.optLong("lastAt"))
            }
        }
        val phrasings = LinkedHashMap<String, List<String>>()
        json.optJSONArray("phrasings")?.let { array ->
            for (i in 0 until array.length()) {
                val p = array.optJSONObject(i) ?: continue
                val id = p.optString("id").takeIf { ABILITY.matches(it) } ?: continue
                val says = strings(p.optJSONArray("say")).mapNotNull(DictionaryPrivacy::phrasing).distinct().take(AppDictionary.MAX_PHRASINGS)
                if (says.isNotEmpty()) phrasings[id] = says
            }
        }
        val quiz = json.optJSONObject("quiz")?.let { q ->
            QuizResult(q.optLong("at"), q.optJSONArray("goals")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val g = a.optJSONObject(i) ?: return@mapNotNull null
                    val goal = DictionaryPrivacy.goal(g.optString("goal")) ?: return@mapNotNull null
                    QuizGoal(goal, nullable(g, "abilityId")?.takeIf { ABILITY.matches(it) }, g.optDouble("score", 0.0).coerceIn(0.0, 1.0))
                }
            }.orEmpty().take(AppDictionary.MAX_QUIZ))
        }
        return AppDictionary(json.optString("packageName"), entries, audit.takeLast(AppDictionary.MAX_AUDIT), jev,
            json.optInt("passes"), json.optLong("updatedAt"), screens, doors, declined, stats, phrasings, quiz)
    }

    private fun parseList(json: JSONObject): ListNote? = DictionaryPrivacy.list(ListNote(json.optString("shape"), nullable(json, "order"),
        strings(json.optJSONArray("groups")), json.optBoolean("searchable"), nullable(json, "searchLabel")))

    private fun strings(array: JSONArray?): List<String> = array?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
    private fun nullable(json: JSONObject, key: String): String? = json.optString(key).takeIf { json.has(key) && !json.isNull(key) && it.isNotBlank() }

    private fun parseEntry(json: JSONObject): DictEntry? {
        val id = json.optString("id").takeIf { ID.matches(it) } ?: return null
        return DictEntry(
            id = id,
            kind = CoreKind.fromWire(json.optString("kind")) ?: CoreKind.OTHER,
            name = json.optString("name"),
            nameProof = ChromeProof.fromWire(json.optString("nameProof")) ?: return null,
            resKey = nullable(json, "resKey"),
            aliases = strings(json.optJSONArray("aliases")),
            ownerLabel = nullable(json, "ownerLabel"),
            parentId = nullable(json, "parentId")?.takeIf { ID.matches(it) },
            parentHint = nullable(json, "parentHint"),
            status = EntryStatus.fromWire(json.optString("status")) ?: EntryStatus.CANDIDATE,
            anchors = json.optJSONArray("anchors")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parseAnchor) } }.orEmpty(),
            markers = strings(json.optJSONArray("markers")),
            observations = json.optInt("observations"),
            days = json.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.optLong(it) } }.orEmpty(),
            firstSeenAt = json.optLong("firstSeenAt"),
            lastSeenAt = json.optLong("lastSeenAt"),
            versions = strings(json.optJSONArray("versions")),
            missedPasses = json.optInt("missedPasses"),
            redirectTo = nullable(json, "redirectTo")?.takeIf { ID.matches(it) },
            note = nullable(json, "note"),
            proven = json.optBoolean("proven"),
        )
    }

    private fun parseAnchor(json: JSONObject): Anchor? {
        val kind = AnchorKind.fromWire(json.optString("kind")) ?: return null
        return Anchor(
        kind = kind,
        roomKey = json.optString("roomKey"),
        containerKey = json.optString("containerKey"),
        screenTitle = nullable(json, "screenTitle"),
        position = if (json.has("position") && !json.isNull("position")) json.optInt("position") else null,
        siblings = strings(json.optJSONArray("siblings")),
        groups = strings(json.optJSONArray("groups")),
        rowShape = nullable(json, "rowShape"),
        searchable = json.optBoolean("searchable"),
        searchLabel = nullable(json, "searchLabel"),
        order = nullable(json, "order"),
    )
    }

    val ID = Regex("^set:[\\p{L}\\p{N}_]{1,40}$")
    val HASH = Regex("^[0-9a-f]{24}$")
    /** An ability id: `ab:` and 12 hex characters of a hash of what it does (stable across passes). */
    val ABILITY = Regex("^ab:[0-9a-f]{12}$")
}
