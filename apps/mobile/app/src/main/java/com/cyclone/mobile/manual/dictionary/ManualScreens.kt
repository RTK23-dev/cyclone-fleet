package com.cyclone.mobile.manual.dictionary

import com.cyclone.mobile.manual.ScreenFindings

/**
 * Screen and door cards (plan 36 §3, alpha.60): what each place of the map is called in the app's own words, which
 * door led there, and which places are panels over another screen. Pure: the runtime passes what the reader found
 * and what the walker verified.
 */
object ManualScreens {
    /** One screen as read: its title, selected category and the app's words on its own buttons. */
    fun observed(dict: AppDictionary, roomKey: String, found: ScreenFindings, at: Long, pageKey: String? = null): AppDictionary {
        val old = dict.screens[roomKey] ?: ScreenCard(roomKey)
        val seenList = found.list?.let { l -> ListNote(l.shape, l.order?.wire, l.groups, l.searchable, l.searchLabel) }
        val next = DictionaryPrivacy.screen(old.copy(
            title = found.title?.text ?: old.title,
            category = found.selectedCategory ?: old.category,
            items = (found.controls + old.items).distinct().take(12),
            seen = old.seen + 1,
            lastSeenAt = at,
            // Newest first: a page key that changed with the app keeps the old ones only until they fall off.
            pageKeys = (listOfNotNull(pageKey) + old.pageKeys).distinct().take(DictionaryPrivacy.MAX_PAGE_KEYS),
            list = seenList?.let { l -> l.copy(order = l.order ?: old.list?.order, groups = (l.groups + old.list?.groups.orEmpty()).distinct().take(12)) } ?: old.list,
        )) ?: return dict
        return dict.copy(screens = cap(dict.screens + (roomKey to next)))
    }

    /**
     * A door the walker verified: [label] is the app's words on it (or null). The destination takes the door's words as
     * its name when it has no title, and a reveal door makes the destination a panel of the screen it opened over.
     */
    fun verified(dict: AppDictionary, from: String, to: String, edgeId: String, label: String?, kind: String, at: Long): AppDictionary {
        if (from == to) return dict
        val door = DictionaryPrivacy.door(DoorCard(edgeId, from, to, label, kind, at)) ?: return dict
        val target = dict.screens[to] ?: ScreenCard(to)
        val named = DictionaryPrivacy.screen(target.copy(
            via = target.via ?: door.label,
            panelOf = target.panelOf ?: from.takeIf { kind == "reveal" },
            lastSeenAt = maxOf(target.lastSeenAt, at),
        )) ?: return dict.copy(doors = capDoors(dict.doors + (edgeId to door)))
        return dict.copy(doors = capDoors(dict.doors + (edgeId to door)), screens = cap(dict.screens + (to to named)))
    }

    /** Active sets that live on [roomKey] (their anchors point at it): the dictionary tags of a place. */
    fun setsOn(dict: AppDictionary, roomKey: String): List<DictEntry> =
        dict.active().filter { e -> e.anchors.any { it.roomKey == roomKey } }

    private fun cap(screens: Map<String, ScreenCard>): Map<String, ScreenCard> =
        if (screens.size <= AppDictionary.MAX_SCREENS) screens
        else screens.values.sortedByDescending { it.lastSeenAt }.take(AppDictionary.MAX_SCREENS).associateBy { it.roomKey }

    private fun capDoors(doors: Map<String, DoorCard>): Map<String, DoorCard> =
        if (doors.size <= AppDictionary.MAX_DOORS) doors
        else doors.values.sortedByDescending { it.lastSeenAt }.take(AppDictionary.MAX_DOORS).associateBy { it.edgeId }
}
