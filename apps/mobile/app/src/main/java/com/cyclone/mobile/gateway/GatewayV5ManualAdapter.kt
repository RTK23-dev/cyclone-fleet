package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.ai.OpenRouterCatalogStore
import com.cyclone.mobile.manual.CoreKind
import com.cyclone.mobile.manual.ManualRuntime
import com.cyclone.mobile.manual.dictionary.AppDictionary
import com.cyclone.mobile.manual.dictionary.DictEntry
import com.cyclone.mobile.manual.dictionary.EntryStatus
import com.cyclone.mobile.manual.dictionary.Organizer
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app dictionary over the gateway (plan 36 §7): `dictionary.get` (read), `dictionary.edit` (the owner's rename,
 * merge, undo, lock, reject, confirm, move and kind, from Glass only) and `models.list` (the phone's models for the
 * PC's model picker). Structure only: the app's own words, anchors, ids and counts of sightings.
 */
internal object GatewayV5ManualAdapter {
    const val MAX_ENTRIES = 400
    private val PLACE = Regex("^package:[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val ID = Regex("^set:[\\p{L}\\p{N}_]{1,40}$")

    @Volatile private var context: Context? = null
    fun install(context: Context) { this.context = context.applicationContext }
    private fun app(): Context = checkNotNull(context) { "dictionary adapter not installed" }

    /** Seams for JVM tests; production reads the phone's dictionaries and models. */
    internal var load: (String) -> AppDictionary = { pkg -> ManualRuntime.dictionary(app(), pkg) }
    internal var editor: (String, Organizer.OwnerEdit) -> AppDictionary = { pkg, e -> ManualRuntime.edit(app(), pkg, e) }
    internal var label: (String) -> String = { pkg -> ManualRuntime.appLabel(app(), pkg) }
    internal var version: (String) -> String? = { pkg -> ManualRuntime.currentVersion(app(), pkg) }
    internal var models: () -> Pair<Triple<String, String, Boolean>?, List<Triple<String, String, Boolean>>> = {
        val c = app()
        val active = OpenRouterCatalogStore.activeId(c).takeIf { it.isNotBlank() }?.let { id ->
            val p = OpenRouterCatalogStore.preset(c, id); Triple(id, p.label, p.vision)
        }
        active to OpenRouterCatalogStore.picker(c).map { Triple(it.id, it.label, it.vision) }
    }
    internal var now: () -> Long = { System.currentTimeMillis() }
    /** Downloaded names waiting for the owner (in memory on the phone), and the owner's answer. */
    internal var review: (String) -> List<com.cyclone.mobile.manual.ReviewQueue.Item> = { pkg -> ManualRuntime.reviewItems(pkg) }
    internal var answer: (String, String, Boolean) -> AppDictionary = { pkg, id, appWord -> ManualRuntime.answerReview(app(), pkg, id, appWord) }
    private val REVIEW_ID = Regex("^rv:[0-9a-f]{16}$")

    fun dispatch(op: String, args: JSONObject): JSONObject = try {
        when (op) {
            "dictionary.get" -> {
                only(args, setOf("placeId"))
                val pkg = packageOf(args)
                render(pkg, load(pkg))
            }
            "dictionary.edit" -> {
                only(args, setOf("placeId", "action", "id", "into", "label", "parentId", "kind"))
                val pkg = packageOf(args)
                when (val action = args.optString("action")) {
                    "app_word", "mine" -> {
                        val id = args.optString("id")
                        if (!REVIEW_ID.matches(id)) throw GatewayProtocolException("INVALID_REQUEST", "id must be a review id.")
                        render(pkg, answer(pkg, id, action == "app_word"))
                    }
                    else -> render(pkg, editor(pkg, edit(args)))
                }
            }
            "manual.get" -> {
                only(args, setOf("placeId", "query"))
                val pkg = packageOf(args)
                val query = if (args.has("query") && !args.isNull("query")) args.optString("query").trim().take(200) else ""
                manual(pkg, load(pkg), query)
            }
            "models.list" -> {
                only(args, emptySet())
                val (active, list) = models()
                JSONObject()
                    .put("active", active?.let { model(it) } ?: JSONObject.NULL)
                    .put("models", JSONArray().also { out -> list.take(40).forEach { out.put(model(it)) } })
            }
            else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported dictionary operation: $op")
        }
    } catch (error: Organizer.EditRefused) {
        throw GatewayProtocolException("INVALID_REQUEST", error.message ?: "That change is not allowed.")
    }

    private fun model(m: Triple<String, String, Boolean>): JSONObject =
        JSONObject().put("id", m.first.take(200)).put("label", m.second.take(80)).put("vision", m.third)

    private fun only(args: JSONObject, allowed: Set<String>) {
        val extra = args.keys().asSequence().filter { it !in allowed }.toList()
        if (extra.isNotEmpty()) throw GatewayProtocolException("INVALID_REQUEST", "Unexpected field: ${extra.first()}")
    }

    private fun packageOf(args: JSONObject): String {
        val place = args.optString("placeId")
        if (!PLACE.matches(place)) throw GatewayProtocolException("INVALID_REQUEST", "placeId must be package:<app>.")
        return place.removePrefix("package:")
    }

    private fun id(args: JSONObject, key: String): String {
        val value = args.optString(key)
        if (!ID.matches(value)) throw GatewayProtocolException("INVALID_REQUEST", "$key must be a set id.")
        return value
    }

    private fun edit(args: JSONObject): Organizer.OwnerEdit {
        val id = id(args, "id")
        return when (args.optString("action")) {
            "confirm" -> Organizer.OwnerEdit.Confirm(id)
            "reject" -> Organizer.OwnerEdit.Reject(id)
            "lock" -> Organizer.OwnerEdit.Lock(id)
            "unlock" -> Organizer.OwnerEdit.Unlock(id)
            "rename" -> Organizer.OwnerEdit.Rename(id, args.optString("label").take(200))
            "merge" -> Organizer.OwnerEdit.Merge(id, id(args, "into"))
            "unmerge" -> Organizer.OwnerEdit.Unmerge(id)
            "move" -> Organizer.OwnerEdit.Move(id, if (args.isNull("parentId") || !args.has("parentId")) null else id(args, "parentId"))
            "kind" -> Organizer.OwnerEdit.SetKind(id, CoreKind.fromWire(args.optString("kind"))
                ?: throw GatewayProtocolException("INVALID_REQUEST", "kind must be one of the core kinds."))
            else -> throw GatewayProtocolException("INVALID_REQUEST", "action must be confirm, reject, lock, unlock, rename, merge, unmerge, move or kind.")
        }
    }

    fun render(pkg: String, dict: AppDictionary): JSONObject {
        val appLabel = label(pkg)
        val current = version(pkg)
        val health = Organizer.health(dict, now(), current)
        val entries = dict.entries.values.sortedWith(compareBy<DictEntry>({ it.kind.ordinal }, { dict.path(it) })).take(MAX_ENTRIES)
        return JSONObject()
            .put("placeId", "package:$pkg")
            .put("appLabel", appLabel.take(80))
            .put("currentVersion", current?.take(40) ?: JSONObject.NULL)
            .put("passes", dict.passes)
            .put("updatedAt", dict.updatedAt)
            .put("coreKinds", JSONArray().also { out -> CoreKind.entries.forEach { out.put(JSONObject().put("wire", it.wire).put("label", it.label)) } })
            .put("entries", JSONArray().also { out -> entries.forEach { out.put(entry(dict, it)) } })
            .put("truncated", dict.entries.size > MAX_ENTRIES)
            .put("audit", JSONArray().also { out ->
                dict.audit.takeLast(50).reversed().forEach { e ->
                    out.put(JSONObject().put("at", e.at).put("action", e.action.take(24)).put("entryId", e.entryId.take(48)).put("detail", e.detail.take(160)).put("by", e.by.take(60)))
                }
            })
            .put("health", JSONObject()
                .put("orphans", JSONArray(health.orphans))
                .put("nearDuplicates", JSONArray(health.nearDuplicates.map { JSONArray(listOf(it.first, it.second)) }))
                .put("tooDeep", JSONArray(health.tooDeep))
                .put("tooWide", JSONArray(health.tooWide))
                .put("staleCandidates", JSONArray(health.staleCandidates))
                .put("notSeenInVersion", JSONArray(health.notSeenInVersion)))
            .put("jev", JSONObject().put("summary", dict.jev.summary().take(160)).put("answered", dict.jev.answered).put("agreed", dict.jev.agreed)
                .put("sureAnswered", dict.jev.sureAnswered).put("sureAgreed", dict.jev.sureAgreed))
            .put("glossary", Organizer.glossary(dict, appLabel, maxLines = 40).take(6000))
            .put("screens", JSONArray().also { out ->
                dict.screens.values.sortedByDescending { it.seen }.take(com.cyclone.mobile.manual.dictionary.AppDictionary.MAX_SCREENS).forEach { c ->
                    out.put(JSONObject()
                        .put("roomKey", c.roomKey)
                        .put("name", c.name ?: JSONObject.NULL)
                        .put("title", c.title ?: JSONObject.NULL)
                        .put("category", c.category ?: JSONObject.NULL)
                        .put("via", c.via ?: JSONObject.NULL)
                        .put("panelOf", c.panelOf ?: JSONObject.NULL)
                        .put("items", JSONArray(c.items))
                        .put("sets", JSONArray(com.cyclone.mobile.manual.dictionary.ManualScreens.setsOn(dict, c.roomKey).map { it.id }.take(12)))
                        .put("seen", c.seen)
                        .put("purpose", c.purpose ?: JSONObject.NULL)
                        .put("list", c.list?.let { l ->
                            JSONObject().put("shape", l.shape).put("order", l.order ?: JSONObject.NULL).put("groups", JSONArray(l.groups))
                                .put("searchable", l.searchable).put("searchLabel", l.searchLabel ?: JSONObject.NULL)
                        } ?: JSONObject.NULL))
                }
            })
            .put("doors", JSONArray().also { out ->
                dict.doors.values.sortedByDescending { it.lastSeenAt }.take(com.cyclone.mobile.manual.dictionary.AppDictionary.MAX_DOORS).forEach { d ->
                    out.put(JSONObject().put("edgeId", d.edgeId).put("from", d.from).put("to", d.to).put("label", d.label ?: JSONObject.NULL).put("kind", d.kind))
                }
            })
            .put("review", JSONArray().also { out ->
                review(pkg).take(com.cyclone.mobile.manual.ReviewQueue.MAX).forEach { item ->
                    val a = item.proposal.anchor
                    out.put(JSONObject()
                        .put("id", item.id)
                        .put("name", item.proposal.name.text.take(60))
                        .put("screenTitle", a.screenTitle ?: JSONObject.NULL)
                        .put("siblings", JSONArray(a.siblings.take(11)))
                        .put("seenAt", item.seenAt))
                }
            })
    }

    const val MAX_ABILITIES = 400
    const val MAX_MARKDOWN = 60_000

    /**
     * `manual.get` (plan 36 §8, §10, §11): the app's abilities with their paths, the self-quiz, the manual as Markdown
     * and the Lab scores; with a query, the abilities that fit it. The app's own words and structure only.
     */
    fun manual(pkg: String, dict: AppDictionary, query: String): JSONObject {
        val view = com.cyclone.mobile.manual.ManualView.of(dict, label(pkg))
        val hits = if (query.isBlank()) emptyList() else view.index.search(query, 8)
        return JSONObject()
            .put("placeId", "package:$pkg")
            .put("appLabel", view.appLabel.take(80))
            .put("currentVersion", version(pkg)?.take(40) ?: JSONObject.NULL)
            .put("abilities", JSONArray().also { out ->
                view.abilities.sortedWith(compareByDescending<com.cyclone.mobile.manual.Ability> { it.confidence }.thenBy { it.name })
                    .take(MAX_ABILITIES).forEach { out.put(ability(it)) }
            })
            .put("truncated", view.abilities.size > MAX_ABILITIES)
            .put("query", query.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
            .put("hits", JSONArray().also { out -> hits.forEach { out.put(JSONObject().put("id", it.ability.id).put("score", round(it.score))) } })
            .put("clear", com.cyclone.mobile.manual.AbilityIndex.clear(hits))
            .put("quiz", dict.quiz?.let { q ->
                JSONObject().put("at", q.at).put("asked", q.goals.size).put("answered", q.answered)
                    .put("goals", JSONArray().also { out ->
                        q.goals.forEach { g -> out.put(JSONObject().put("goal", g.goal).put("abilityId", g.abilityId ?: JSONObject.NULL).put("score", round(g.score))) }
                    })
            } ?: JSONObject.NULL)
            .put("scores", scores(dict, view))
            .put("markdown", com.cyclone.mobile.manual.ManualRenderer.markdown(dict, view.appLabel, version(pkg), view.abilities).take(MAX_MARKDOWN))
    }

    private fun ability(a: com.cyclone.mobile.manual.Ability): JSONObject = JSONObject()
        .put("id", a.id)
        .put("kind", a.kind.wire)
        .put("name", a.name.take(120))
        .put("place", a.place)
        .put("placeName", a.placeName ?: JSONObject.NULL)
        .put("path", JSONArray(a.path.take(10)))
        .put("tap", a.tap ?: JSONObject.NULL)
        .put("pick", a.pick ?: JSONObject.NULL)
        .put("effect", a.effect)
        .put("setId", a.setId ?: JSONObject.NULL)
        .put("provenance", a.provenance)
        .put("confidence", round(a.confidence))
        .put("note", a.note?.take(200) ?: JSONObject.NULL)
        .put("say", JSONArray(a.say.take(8)))

    /**
     * The Lab's scores for one app (plan 36 §11), each 0–1 or null when there is nothing to score yet:
     * - map: named places, panels with what they offer, lists with a known order, categories proven;
     * - dictionary: groups without a health problem;
     * - quiz: goals the manual alone answers;
     * - walks: ability walks that arrived.
     */
    fun scores(dict: AppDictionary, view: com.cyclone.mobile.manual.ManualView): JSONObject {
        fun share(n: Int, of: Int): Double? = if (of <= 0) null else n.toDouble() / of
        val places = dict.screens.values.filter { !it.isPanel }
        val panels = dict.screens.values.filter { it.isPanel }
        val lists = dict.screens.values.mapNotNull { it.list }
        val views = dict.active().filter { e -> e.anchors.any { it.kind == com.cyclone.mobile.manual.AnchorKind.VIEW } }
        val parts = listOfNotNull(
            share(places.count { it.name != null }, places.size),
            share(panels.count { it.items.isNotEmpty() }, panels.size),
            share(lists.count { it.order != null }, lists.size),
            share(views.count { it.proven }, views.size),
        )
        val health = Organizer.health(dict, now(), version(dict.packageName))
        val troubled = (health.orphans + health.nearDuplicates.flatMap { listOf(it.first, it.second) } + health.tooDeep + health.tooWide).toSet()
        val active = dict.active()
        val walked = dict.abilityStats.values.sumOf { it.walked }
        val failed = dict.abilityStats.values.sumOf { it.failed }
        return JSONObject()
            .put("map", parts.takeIf { it.isNotEmpty() }?.let { round(it.average()) } ?: JSONObject.NULL)
            .put("dictionary", share(active.count { it.id !in troubled }, active.size)?.let(::round) ?: JSONObject.NULL)
            .put("quiz", dict.quiz?.let { q -> share(q.answered, q.goals.size)?.let(::round) } ?: JSONObject.NULL)
            .put("walks", share(walked, walked + failed)?.let(::round) ?: JSONObject.NULL)
            .put("places", places.size)
            .put("named", places.count { it.name != null })
            .put("panels", panels.size)
            .put("lists", lists.size)
            .put("ordered", lists.count { it.order != null })
            .put("abilities", view.abilities.size)
            .put("walkedAbilities", view.abilities.count { it.provenance == "walked" })
    }

    private fun round(value: Double): Double = Math.round(value * 100) / 100.0

    private fun entry(dict: AppDictionary, e: DictEntry): JSONObject {
        val gates = if (e.status == EntryStatus.CANDIDATE) Organizer.check(dict, e) else null
        return JSONObject()
            .put("id", e.id)
            .put("kind", e.kind.wire)
            .put("name", e.name)
            .put("shownName", e.shownName)
            .put("nameProof", e.nameProof.wire)
            .put("aliases", JSONArray(e.aliases))
            .put("parentId", e.parentId ?: JSONObject.NULL)
            .put("path", dict.path(e).take(200))
            .put("status", e.status.wire)
            .put("redirectTo", e.redirectTo ?: JSONObject.NULL)
            .put("anchors", JSONArray().also { out ->
                e.anchors.forEach { a ->
                    out.put(JSONObject().put("kind", a.kind.wire).put("roomKey", a.roomKey).put("screenTitle", a.screenTitle ?: JSONObject.NULL)
                        .put("position", a.position ?: JSONObject.NULL).put("siblings", JSONArray(a.siblings)).put("groups", JSONArray(a.groups))
                        .put("rowShape", a.rowShape ?: JSONObject.NULL).put("searchable", a.searchable).put("searchLabel", a.searchLabel ?: JSONObject.NULL))
                }
            })
            .put("markers", JSONArray(e.markers))
            .put("observations", e.observations)
            .put("days", e.days.distinct().size)
            .put("versions", JSONArray(e.versions))
            .put("missedPasses", e.missedPasses)
            .put("note", e.note ?: JSONObject.NULL)
            .put("failedGates", JSONArray(gates?.failed?.map { it.wire }.orEmpty()))
            .put("waiting", gates?.let { "${it.verdict.name.lowercase()}: ${it.reason}" }?.take(200) ?: JSONObject.NULL)
    }
}
