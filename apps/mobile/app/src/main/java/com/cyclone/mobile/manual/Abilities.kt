package com.cyclone.mobile.manual

import com.cyclone.mobile.manual.dictionary.AppDictionary
import com.cyclone.mobile.manual.dictionary.DictionaryPrivacy
import com.cyclone.mobile.manual.dictionary.DoorCard
import com.cyclone.mobile.manual.dictionary.ScreenCard
import com.cyclone.mobile.mapping.crawl.MapperDoorRisk
import com.cyclone.mobile.mapping.crawl.MappingIdentity
import java.security.MessageDigest

/** What kind of thing an ability does (plan 36 §3). */
enum class AbilityKind(val wire: String) {
    /** Go to a named screen. */
    OPEN("open"),
    /** Open a panel ("+", ⋯) over its screen. */
    PANEL("panel"),
    /** Show one category of a screen (a tab, segment or chip). */
    SWITCH("switch"),
    /** Something a panel offers: the walk opens the panel and leaves the pick to the Mind. */
    OFFER("offer"),
    /** A button of a screen that does not lead to a mapped place: the walk goes there and leaves the tap to the Mind. */
    CONTROL("control"),
    /** Find one item of a list: the walk goes to the list and says how to find one (search, order, groups). */
    FIND("find"),
}

/**
 * A thing a person can do in an app (plan 36 §3), with the path that does it. Abilities are derived from the
 * dictionary's places, doors and sets: the app's own words only. [id] is stable across passes (a hash of what it
 * does), so its walk record ([com.cyclone.mobile.manual.dictionary.AbilityStat]) and phrasings stay attached.
 *
 * - The walked part is navigate, reveal and switch only. [pick] (an offer, a button) is never tapped by the walk: the
 *   Mind or the owner chooses, with the usual rules and approvals.
 * - [effect] is "navigate", "reveal", "switch", "choose" or "asks" (a button the approval rules would ask about).
 */
data class Ability(
    val id: String,
    val kind: AbilityKind,
    val name: String,
    /** The room the walk goes to (a panel's room for a panel or an offer). */
    val place: String,
    val placeName: String?,
    /** For a switch: the category label the walk taps after arriving. */
    val tap: String? = null,
    /** What is left for the Mind or the owner to choose once there (an offer, a button). Never tapped by the walk. */
    val pick: String? = null,
    val effect: String,
    /** The steps from the app's first screen, in the app's words ("Chat › Add photos and files › Connectors"). */
    val path: List<String>,
    val setId: String? = null,
    val provenance: String,
    val confidence: Double,
    val say: List<String> = emptyList(),
    /** How to find one item, for a list ("Search chats at the top · newest first"). */
    val note: String? = null,
) {
    /** The whole ability is navigation: a Tier 0 walk may do all of it without a model decision. */
    val navigationOnly: Boolean get() = effect in WALKED

    val pathText: String get() = path.joinToString(" › ")

    companion object {
        val WALKED = setOf("navigate", "reveal", "switch")
    }
}

/** Derives an app's abilities from its dictionary (plan 36 §3). Pure; the same code for every app. */
object Abilities {
    const val MAX = 400
    private const val MAX_CONTROLS_PER_SCREEN = 8

    fun derive(dict: AppDictionary): List<Ability> {
        val routes = Routes(dict)
        val out = LinkedHashMap<String, Ability>()
        fun add(a: Ability) {
            if (a.id !in out && out.size < MAX) out[a.id] = withRecord(dict, a)
        }
        val cards = dict.screens.values.sortedWith(compareBy({ it.isPanel }, { -it.seen }, { it.roomKey }))
        val doorsFrom = dict.doors.values.filter { it.label != null }.groupBy { it.from }

        for (card in cards) {
            val name = card.name ?: continue
            if (card.isPanel) {
                val over = card.panelOf?.let { dict.screens[it]?.name }
                add(Ability(idOf("panel", card.roomKey), AbilityKind.PANEL, name + (over?.let { " (on $it)" } ?: ""), card.roomKey, name,
                    effect = "reveal", path = routes.names(card.roomKey), provenance = "mapped", confidence = 0.8,
                    say = listOf("open $name", "$name menu", "show $name")))
                val walkedOut = doorsFrom[card.roomKey].orEmpty().mapNotNull { it.label?.let(AppLexicon::normalize) }.toSet()
                for (offer in card.items) {
                    if (AppLexicon.normalize(offer) in walkedOut || AppLexicon.normalize(offer) == AppLexicon.normalize(name)) continue
                    add(Ability(idOf("offer", card.roomKey, offer), AbilityKind.OFFER, "$offer (in $name)", card.roomKey, name, pick = offer,
                        effect = effectOf(offer), path = routes.names(card.roomKey) + offer, provenance = "mapped", confidence = 0.75,
                        say = listOf(offer, "$offer from $name") + (over?.let { listOf("$offer in $it") } ?: emptyList())))
                }
                continue
            }
            add(Ability(idOf("open", card.roomKey), AbilityKind.OPEN, "Open $name", card.roomKey, name, effect = "navigate",
                path = routes.names(card.roomKey), provenance = "mapped", confidence = if (routes.reached(card.roomKey)) 0.8 else 0.6,
                say = listOf(name, "go to $name", "show $name", "see $name") + (card.purpose?.let { listOf(it) } ?: emptyList()),
                note = card.list?.let { howToFind(it.searchable, it.searchLabel, it.order, it.groups) }))
            val walkedOut = doorsFrom[card.roomKey].orEmpty().mapNotNull { it.label?.let(AppLexicon::normalize) }.toSet()
            val categories = dict.active().filter { e -> e.anchors.any { it.kind == AnchorKind.VIEW && it.roomKey == card.roomKey } }
                .map { AppLexicon.normalize(it.name) }.toSet()
            card.items.filter { item ->
                val n = AppLexicon.normalize(item)
                n !in walkedOut && n !in categories && n != AppLexicon.normalize(name)
            }.take(MAX_CONTROLS_PER_SCREEN).forEach { item ->
                add(Ability(idOf("control", card.roomKey, item), AbilityKind.CONTROL, "$item (on $name)", card.roomKey, name, pick = item,
                    effect = effectOf(item), path = routes.names(card.roomKey) + item, provenance = "mapped", confidence = 0.7,
                    say = listOf(item, "$item on $name")))
            }
            if (card.list?.searchable == true && dict.active().none { e -> e.anchors.any { it.kind == AnchorKind.LIST && it.roomKey == card.roomKey } }) {
                add(Ability(idOf("find", card.roomKey), AbilityKind.FIND, "Find one in $name", card.roomKey, name, effect = "navigate",
                    path = routes.names(card.roomKey), provenance = "mapped", confidence = 0.7,
                    say = listOf("find in $name", "search $name", "look up in $name"),
                    note = howToFind(true, card.list.searchLabel, card.list.order, card.list.groups)))
            }
        }

        for (entry in dict.active().sortedBy { it.id }) {
            val noun = noun(entry.kind)
            for (anchor in entry.anchors) {
                val card = dict.screens[anchor.roomKey]
                val placeName = card?.name ?: anchor.screenTitle
                when (anchor.kind) {
                    AnchorKind.VIEW -> add(Ability(idOf("switch", anchor.roomKey, anchor.containerKey, entry.name), AbilityKind.SWITCH,
                        entry.shownName + (placeName?.let { " on $it" } ?: ""), anchor.roomKey, placeName, tap = entry.name, effect = "switch",
                        path = routes.names(anchor.roomKey) + entry.shownName, setId = entry.id, provenance = "mapped",
                        confidence = if (entry.proven) 0.85 else 0.75,
                        say = listOf(entry.shownName, "see ${entry.shownName}", "show ${entry.shownName}", "open ${entry.shownName}",
                            "${entry.shownName} $noun") + entry.aliases))
                    AnchorKind.LIST -> if (anchor.searchable || anchor.order != null || anchor.groups.isNotEmpty()) {
                        add(Ability(idOf("find", anchor.roomKey, entry.id), AbilityKind.FIND, "Find $noun in ${entry.shownName}", anchor.roomKey, placeName,
                            effect = "navigate", path = routes.names(anchor.roomKey), setId = entry.id, provenance = "mapped", confidence = 0.75,
                            say = listOf("find $noun in ${entry.shownName}", "search ${entry.shownName}", "open $noun from ${entry.shownName}") +
                                (if (anchor.order == ListOrder.NEWEST_FIRST.wire) listOf("newest $noun in ${entry.shownName}", "latest $noun", "most recent $noun") else emptyList()),
                            note = howToFind(anchor.searchable, anchor.searchLabel, anchor.order, anchor.groups)))
                    }
                }
            }
        }
        return out.values.toList()
    }

    /** "a person", "a conversation": what one item of a set is, from its core kind. */
    fun noun(kind: CoreKind): String = when (kind) {
        CoreKind.OTHER -> "one"
        CoreKind.EVENT, CoreKind.ORDER, CoreKind.ACCOUNT, CoreKind.ASSISTANT -> "an " + kind.label.lowercase()
        else -> "a " + kind.label.lowercase()
    }

    /** "Search “Search chats” at the top · newest first (item 1 is the newest) · groups “Today”, “Yesterday”". */
    fun howToFind(searchable: Boolean, searchLabel: String?, order: String?, groups: List<String>): String? {
        val parts = ArrayList<String>()
        if (searchable) parts += "search" + (searchLabel?.let { " (“$it”)" } ?: "") + " on the screen"
        when (ListOrder.fromWire(order)) {
            ListOrder.NEWEST_FIRST -> parts += "newest first (item 1 is the newest)"
            ListOrder.OLDEST_FIRST -> parts += "oldest first"
            ListOrder.A_Z -> parts += "A–Z"
            null -> {}
        }
        if (groups.isNotEmpty()) parts += "groups " + groups.take(6).joinToString(", ") { "“$it”" }
        if (!searchable) parts += "scroll to find one"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** A button the approval rules would ask about ("Delete", "Buy") is described but marked so. */
    private fun effectOf(label: String): String =
        if (MapperDoorRisk.classify(MapperDoorRisk.Facts(listOf(label), "button"), MappingIdentity.OWN) != null) "asks" else "choose"

    /** The ability's walk record raises or lowers its confidence: runs teach the manual (plan 36 §5.6). */
    private fun withRecord(dict: AppDictionary, a: Ability): Ability {
        val stat = dict.abilityStats[a.id]
        val phrasings = dict.phrasings[a.id].orEmpty()
        val says = (a.say + phrasings).mapNotNull(DictionaryPrivacy::phrasing).distinctBy { it.lowercase() }.take(16)
        if (stat == null) return a.copy(say = says)
        val confidence = if (stat.walked > 0) (0.85 + 0.03 * stat.walked - 0.15 * stat.failed) else (a.confidence - 0.15 * stat.failed)
        return a.copy(say = says, provenance = if (stat.walked > 0) "walked" else a.provenance, confidence = confidence.coerceIn(0.1, 0.97))
    }

    fun idOf(vararg parts: String): String = "ab:" + MessageDigest.getInstance("SHA-256")
        .digest(parts.joinToString("|").toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }

    /** Routes over the doors whose words are known, from the app's first screen. */
    internal class Routes(private val dict: AppDictionary) {
        val root: String? = rootOf(dict)
        private val parents: Map<String, DoorCard> = if (root == null) emptyMap() else bfs(root)

        fun reached(room: String): Boolean = room == root || room in parents

        fun names(room: String): List<String> {
            val here = dict.screens[room]?.name
            if (root == null || !reached(room)) return listOfNotNull(here)
            val steps = ArrayList<String>()
            var at = room
            var guard = 0
            while (at != root && guard++ < 12) {
                val door = parents[at] ?: break
                steps.add(0, door.label ?: (dict.screens[at]?.name ?: "…"))
                at = door.from
            }
            return listOfNotNull(dict.screens[root]?.name) + steps
        }

        private fun bfs(start: String): Map<String, DoorCard> {
            val out = HashMap<String, DoorCard>()
            val byFrom = dict.doors.values.filter { it.label != null }.groupBy { it.from }
            val queue = ArrayDeque(listOf(start))
            val seen = hashSetOf(start)
            while (queue.isNotEmpty()) {
                val at = queue.removeFirst()
                for (door in byFrom[at].orEmpty().sortedByDescending { it.lastSeenAt }) {
                    if (seen.add(door.to)) { out[door.to] = door; queue.addLast(door.to) }
                }
            }
            return out
        }

        companion object {
            /** The app's first screen: a room the mapper called home, else the named screen with the most doors out. */
            fun rootOf(dict: AppDictionary): String? {
                val screens = dict.screens.values.filter { !it.isPanel }
                screens.firstOrNull { it.roomKey.startsWith("screen:home:") }?.let { return it.roomKey }
                val out = dict.doors.values.groupingBy { it.from }.eachCount()
                val incoming = dict.doors.values.map { it.to }.toSet()
                return screens.sortedWith(compareBy<ScreenCard>({ it.roomKey in incoming }, { -(out[it.roomKey] ?: 0) }, { -it.seen }, { it.roomKey }))
                    .firstOrNull()?.roomKey
            }
        }
    }
}
