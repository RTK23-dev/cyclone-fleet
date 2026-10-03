package com.cyclone.mobile.manual

import java.security.MessageDigest

/** One accessibility node as the reader needs it. Text is read here and never leaves except as proven chrome. */
data class UiNode(
    val id: String,
    val parentId: String? = null,
    val text: String = "",
    val description: String = "",
    val resourceId: String = "",
    val className: String = "",
    val role: String = "",
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val clickable: Boolean = false,
    val selected: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val visible: Boolean = true,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerY: Int get() = top + height / 2
}

enum class AnchorKind(val wire: String) {
    /** The set is the list shown on a screen (or under one category of it). */
    LIST("list"),
    /** The set is one category of a category strip (tabs, segments, chips). */
    VIEW("view");

    companion object {
        fun fromWire(value: String?): AnchorKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Where a set lives, in structure only: the room (the mapper's structural screen key), the screen's title when it is
 * the app's own words, a hash of the container, and for a category its position and its siblings' names. A list anchor
 * adds the list's shape, its section headers and whether it can be searched: never a row's text.
 */
data class Anchor(
    val kind: AnchorKind,
    val roomKey: String,
    val containerKey: String,
    val screenTitle: String? = null,
    val position: Int? = null,
    val siblings: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    val rowShape: String? = null,
    val searchable: Boolean = false,
    val searchLabel: String? = null,
    /** How the list is ordered (a [ListOrder] wire value), concluded from row shapes that are then thrown away. */
    val order: String? = null,
) {
    /** Two anchors are the same place when their kind, container and position agree. */
    val identity: String get() = "${kind.wire}|$containerKey|${position ?: ""}"
}

/** What the reader suggests to the organizer: a named set, where it lives and what shows membership. Nothing more. */
data class SetProposal(
    val name: ChromeWord,
    val kindGuess: CoreKind,
    val anchor: Anchor,
    val parentName: ChromeWord? = null,
    val markers: List<ChromeWord> = emptyList(),
    val secondAnchor: Anchor? = null,
    /** Proven by a probe in this pass: its category row was seen switching views (plan 36 §5.1). */
    val proven: Boolean = false,
)

/** The main list of a screen, in structure only: its row shape, order, section headers and how to find one row. */
data class ListSeen(
    val shape: String,
    val order: ListOrder?,
    val groups: List<String>,
    val searchable: Boolean,
    val searchLabel: String?,
)

/** A category row as seen on one screen: its structural key and which of its (named) items was selected. */
data class StripSeen(val key: String, val selected: String?, val labels: List<String>)

data class ScreenFindings(
    val title: ChromeWord?,
    val strips: List<StripSeen>,
    val lists: Int,
    val proposals: List<SetProposal>,
    /** The selected category on this screen, in the app's words. */
    val selectedCategory: String? = null,
    /** The app's words on this screen's own buttons (outside list rows and category rows), at most 12. */
    val controls: List<String> = emptyList(),
    /** The screen's main list, when it has one. */
    val list: ListSeen? = null,
)

/**
 * Reads one screen's accessibility tree into structure (plan 36 §5.2): the screen title, category strips (tabs,
 * segments, chips with a selection) and lists (repeated rows in a scroll container), with their section headers,
 * search and row markers. Only text the [AppLexicon] proves to be the app's own words survives; every other text is
 * dropped here. No app-specific rules: the same code reads every app.
 */
class StructureReader(
    private val lexicon: AppLexicon,
    private val appLabel: String? = null,
    /**
     * Names proven only word by word ([ChromeProof.VOCABULARY]) could be a user-made label ("Family", a chat folder), so
     * they are not proposed until probes can tell a downloaded menu from a user's own name. Off in production.
     */
    private val allowVocabulary: Boolean = false,
    /** Today, for reading ages like "Mon" or "12 Sep" (plan 36 §6.3). */
    private val today: () -> java.time.LocalDate = { java.time.LocalDate.now() },
) {
    private fun chrome(raw: String): ChromeWord? = lexicon.chrome(raw)?.takeIf { allowVocabulary || it.proof == ChromeProof.LEXICON }


    fun read(roomKey: String, nodes: List<UiNode>): ScreenFindings {
        val visible = nodes.filter { it.visible && it.width > 0 && it.height > 0 }
        if (visible.isEmpty()) return ScreenFindings(null, emptyList(), 0, emptyList())
        val screenH = visible.maxOf { it.bottom }.coerceAtLeast(1)
        val byId = visible.associateBy { it.id }
        val children = visible.groupBy { it.parentId }

        val lists = findLists(visible, children, byId)
        val rowIds = lists.flatMap { list -> list.rows.flatMap { descendants(it, children) + it } }.map { it.id }.toSet()
        val strips = findStrips(children, byId, screenH, rowIds)
        val stripIds = strips.flatMap { it.nodeIds }.toSet()
        val title = findTitle(visible, screenH, rowIds + stripIds)
        val search = findSearch(visible)

        val proposals = ArrayList<SetProposal>()
        val mainList = lists.maxByOrNull { it.rows.size }
        val titleIsApp = title != null && appLabel != null && AppLexicon.normalize(title.text) == AppLexicon.normalize(appLabel)
        val category = strips.firstOrNull { it.region == Region.CATEGORY }
        val selected = category?.items?.firstOrNull { it.selected && it.word != null }

        fun listAnchor(list: FoundList, context: String): Anchor = Anchor(
            kind = AnchorKind.LIST,
            roomKey = roomKey,
            containerKey = digest("list|${list.key}|$context"),
            screenTitle = title?.text,
            groups = list.headers.map { it.text }.distinct().take(12),
            rowShape = list.shape,
            searchable = search != null,
            searchLabel = search?.label,
            order = list.order?.wire,
        )

        if (title != null && !titleIsApp && mainList != null) {
            val evidence = listOf(title.text) + mainList.rowEvidence
            proposals += SetProposal(
                name = title,
                kindGuess = CoreKind.guess(evidence),
                anchor = listAnchor(mainList, ""),
                markers = mainList.markers,
            )
        }
        if (category != null) {
            val named = category.items.mapNotNull { it.word }
            val parent = title?.takeIf { !titleIsApp && mainList != null }
            category.items.forEachIndexed { index, item ->
                val word = item.word ?: return@forEachIndexed
                if (parent != null && AppLexicon.normalize(word.text) == AppLexicon.normalize(parent.text)) return@forEachIndexed
                val view = Anchor(
                    kind = AnchorKind.VIEW,
                    roomKey = roomKey,
                    containerKey = category.key,
                    screenTitle = title?.text,
                    position = index,
                    // Only the app's exact strings: a downloaded neighbour's name must not ride along.
                    siblings = named.filter { it.proof == ChromeProof.LEXICON }.map { it.text }.filter { it != word.text }.take(11),
                )
                val isShown = item == selected && mainList != null
                val evidence = listOfNotNull(word.text, parent?.text) + (if (isShown) mainList!!.rowEvidence else emptyList())
                proposals += SetProposal(
                    name = word,
                    kindGuess = CoreKind.guess(evidence),
                    anchor = view,
                    parentName = parent,
                    markers = if (isShown) mainList!!.markers else emptyList(),
                    secondAnchor = if (isShown) listAnchor(mainList!!, word.text) else null,
                )
            }
        }
        val controls = visible.asSequence()
            .filter { it.clickable && it.id !in rowIds && it.id !in stripIds && !it.editable }
            .sortedWith(compareBy({ it.top }, { it.left }))
            .mapNotNull { node -> label(node, children)?.let(lexicon::chrome)?.takeIf { it.proof == ChromeProof.LEXICON }?.text }
            .distinct().take(12).toList()
        val seen = strips.filter { it.region == Region.CATEGORY }.map { strip ->
            StripSeen(strip.key, strip.items.firstOrNull { it.selected && it.word != null }?.word?.text, strip.items.mapNotNull { it.word?.text })
        }
        val listSeen = mainList?.let { ListSeen(it.shape, it.order, it.headers.map { h -> h.text }.distinct().take(12), search != null, search?.label) }
        return ScreenFindings(title, seen, lists.size, proposals, selected?.word?.takeIf { it.proof == ChromeProof.LEXICON }?.text, controls, listSeen)
    }

    // ---- lists ----

    private class FoundList(
        val key: String,
        val rows: List<UiNode>,
        val headers: List<ChromeWord>,
        val markers: List<ChromeWord>,
        val shape: String,
        val rowEvidence: List<String>,
        val order: ListOrder?,
    )

    private fun findLists(visible: List<UiNode>, children: Map<String?, List<UiNode>>, byId: Map<String, UiNode>): List<FoundList> =
        visible.filter { it.scrollable }.mapNotNull { container ->
            val kids = children[container.id].orEmpty()
            if (kids.size < 3) return@mapNotNull null
            val commonClass = kids.groupingBy { it.className }.eachCount().maxByOrNull { it.value }?.key
            val candidates = kids.filter { it.className == commonClass && it.width >= container.width * 0.6 }
            if (candidates.size < 3) return@mapNotNull null
            val heights = candidates.map { it.height }.sorted()
            val median = heights[heights.size / 2].coerceAtLeast(1)
            val rows = candidates.filter { it.height in (median / 2)..(median * 2) }
            if (rows.size < 3) return@mapNotNull null
            val headers = kids.filter { it !in rows }
                .mapNotNull { header -> label(header, children)?.let(lexicon::chrome) }
                .filter { it.proof == ChromeProof.LEXICON }
            val markerCounts = HashMap<String, Pair<ChromeWord, Int>>()
            rows.forEach { row ->
                descendants(row, children).filter { it.clickable && it.id != row.id }
                    .mapNotNull { node -> label(node, children)?.let(lexicon::chrome) }
                    .filter { it.proof == ChromeProof.LEXICON }
                    .distinctBy { it.text.lowercase() }
                    .forEach { word -> markerCounts.merge(word.text.lowercase(), word to 1) { a, _ -> a.first to a.second + 1 } }
            }
            val markers = markerCounts.values.filter { it.second >= maxOf(2, (rows.size + 1) / 2) }.map { it.first }.take(4)
            val texts = rows.map { row -> descendants(row, children).count { it.text.isNotBlank() } }.sorted()
            val image = rows.count { row -> descendants(row, children).any { it.className.contains("Image", true) } } * 2 >= rows.size
            val shape = buildList {
                add("${texts[texts.size / 2]} text${if (texts[texts.size / 2] == 1) "" else "s"}")
                if (image) add("image")
                if (markers.isNotEmpty()) add("button")
            }.joinToString(" · ")
            val rowEvidence = rows.take(6).flatMap { row -> (descendants(row, children) + row).map { idWord(it.resourceId) } }
                .filter { it.isNotBlank() }.distinct().take(20)
            // Row texts are read for their shapes (ages, first letters) and dropped here: only the order is kept.
            val rowTexts = rows.map { row ->
                (listOf(row) + descendants(row, children)).sortedWith(compareBy({ it.top }, { it.left }))
                    .map { it.text.trim() }.filter { it.isNotBlank() && it != REDACTED }
            }
            FoundList(
                key = "${idWord(container.resourceId)}|${container.className}|${byId[container.parentId ?: ""]?.className.orEmpty()}",
                rows = rows, headers = headers, markers = markers, shape = shape, rowEvidence = rowEvidence,
                order = ListOrder.of(rowTexts, today()),
            )
        }

    // ---- title and search ----

    private fun findTitle(visible: List<UiNode>, screenH: Int, excluded: Set<String>): ChromeWord? =
        visible.asSequence()
            .filter { it.id !in excluded && !it.editable && it.text.isNotBlank() && it.top < screenH * 0.16 }
            .sortedWith(compareBy({ it.top }, { it.left }))
            // A title is kept only when it is exactly one of the app's own strings: a chat or folder title never is.
            .mapNotNull { lexicon.chrome(it.text)?.takeIf { word -> word.proof == ChromeProof.LEXICON } }
            .firstOrNull()

    /** A search on this screen, with its hint when that is the app's own words ("Search chats"). */
    private class FoundSearch(val label: String?)

    private fun findSearch(visible: List<UiNode>): FoundSearch? = visible.firstNotNullOfOrNull { node ->
        val hint = "${idWord(node.resourceId)} ${node.description} ${if (node.editable) node.text else ""}".lowercase()
        if (!(node.editable || node.clickable) || node.password || !SEARCH.containsMatchIn(hint)) return@firstNotNullOfOrNull null
        FoundSearch(listOf(node.description, if (node.editable) node.text else "").firstNotNullOfOrNull { lexicon.chrome(it) }
            ?.takeIf { it.proof == ChromeProof.LEXICON }?.text)
    }

    // ---- category strips ----

    private enum class Region { CATEGORY, SECTIONS }
    private class StripItem(val word: ChromeWord?, val selected: Boolean)
    private class FoundStrip(val key: String, val region: Region, val items: List<StripItem>, val nodeIds: Set<String>)

    private fun findStrips(children: Map<String?, List<UiNode>>, byId: Map<String, UiNode>, screenH: Int, rowIds: Set<String>): List<FoundStrip> =
        children.entries.mapNotNull { (parentId, kids) ->
            val parent = parentId?.let(byId::get)
            val items = kids.filter { it.clickable && it.id !in rowIds }.sortedBy { it.left }
            if (items.size !in 2..8) return@mapNotNull null
            val top = items.first().top
            val heights = items.map { it.height }
            if (items.any { kotlin.math.abs(it.top - top) > 12 }) return@mapNotNull null
            if (heights.max() > heights.min() * 1.6) return@mapNotNull null
            if (items.zipWithNext().any { (a, b) -> b.left < a.right - 4 }) return@mapNotNull null
            val tabLike = items.any { it.selected } ||
                items.any { TABLIKE.containsMatchIn("${it.className} ${it.role}") } ||
                (parent != null && TABLIKE.containsMatchIn("${parent.className} ${parent.role}"))
            if (!tabLike) return@mapNotNull null
            val words = items.map { item -> StripItem(label(item, children)?.let(::chrome), item.selected) }
            if (words.count { it.word != null } < 2 || words.count { it.word != null } * 2 < words.size) return@mapNotNull null
            val region = if (items.first().centerY > screenH * 0.85) Region.SECTIONS else Region.CATEGORY
            val key = digest("strip|${idWord(parent?.resourceId.orEmpty())}|${parent?.className.orEmpty()}|${items.size}|${(top * 20) / screenH}")
            FoundStrip(key, region, words, items.flatMap { descendants(it, children) + it }.map { it.id }.toSet())
        }

    // ---- helpers ----

    private fun label(node: UiNode, children: Map<String?, List<UiNode>>): String? {
        node.text.takeIf { it.isNotBlank() && it != REDACTED }?.let { return it }
        node.description.takeIf { it.isNotBlank() && it != REDACTED }?.let { return it }
        return descendants(node, children, maxDepth = 3).firstNotNullOfOrNull { child ->
            child.text.takeIf { it.isNotBlank() && it != REDACTED } ?: child.description.takeIf { it.isNotBlank() && it != REDACTED }
        }
    }

    private fun descendants(node: UiNode, children: Map<String?, List<UiNode>>, maxDepth: Int = 6): List<UiNode> {
        val out = ArrayList<UiNode>()
        var frontier = children[node.id].orEmpty()
        var depth = 0
        while (frontier.isNotEmpty() && depth < maxDepth && out.size < 200) {
            out += frontier
            frontier = frontier.flatMap { children[it.id].orEmpty() }
            depth++
        }
        return out
    }

    companion object {
        private const val REDACTED = "<redacted>"
        private val TABLIKE = Regex("(?i)tab|segment|chip|radio|toggle ?group|pager")
        private val SEARCH = Regex("(?i)search|find|zoek|such|recherch|buscar|cerca")
        private val ID_WORD = Regex("^[a-z][a-z0-9_]{1,39}$")

        /** The developer's name for a node (`com.app:id/row_user` → `row_user`): part of the app, not the user. */
        fun idWord(resourceId: String): String = resourceId.substringAfterLast('/').lowercase().takeIf { ID_WORD.matches(it) }.orEmpty()

        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    }
}
