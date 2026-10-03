package com.cyclone.mobile.manual

import com.cyclone.mobile.manual.dictionary.AppDictionary
import com.cyclone.mobile.manual.dictionary.DoorCard
import com.cyclone.mobile.manual.dictionary.ScreenCard

/** What the walker can see of the phone now: the page key and the words on screen (read, compared, not kept). */
data class ManualHere(val packageName: String?, val pageKey: String?, val words: Set<String>)

/** How the walker touches the phone. Production: the Mind toolbox, through PhoneToolExecutor and its checks. */
interface ManualWalkPort {
    fun here(): ManualHere?
    /** Presses the control with these words on the current screen. */
    fun press(label: String): ManualPress
}

enum class ManualPress { DONE, NOT_ON_SCREEN, REFUSED }

sealed class ManualWalk {
    abstract val moves: Int
    /** Arrived where the ability leads; [pick] is still to be chosen by the Mind (never tapped by the walk). */
    data class Arrived(val at: String?, override val moves: Int, val pick: String?) : ManualWalk()
    data class NotInApp(override val moves: Int = 0) : ManualWalk()
    data class Lost(override val moves: Int = 0) : ManualWalk()
    data class NoRoute(val from: String?, val to: String?) : ManualWalk() { override val moves = 0 }
    data class Stopped(val reason: String, override val moves: Int) : ManualWalk()
}

/**
 * Walks an ability's path (plan 36 §8) using only doors the mapper walked, whose words are the app's own. After every
 * press it checks the screen in plain code: the page key the place was seen with, or the place's title and button
 * words on screen. The first surprise stops the walk and hands back to the Mind. Nothing here chooses, types or
 * confirms: the last step of an offer or a button is left to the Mind.
 */
class ManualNavigator(private val dict: AppDictionary, private val port: ManualWalkPort) {
    private val labelled: Map<String, List<DoorCard>> = dict.doors.values.filter { it.label != null && it.from != it.to }.groupBy { it.from }

    fun walk(ability: Ability): ManualWalk {
        var here = port.here() ?: return ManualWalk.Lost()
        if (here.packageName != null && here.packageName != dict.packageName) return ManualWalk.NotInApp()
        var at = locate(dict, here) ?: return ManualWalk.Lost()
        var moves = 0
        var replans = 0
        var route = route(at, ability.place) ?: return ManualWalk.NoRoute(name(at), name(ability.place))
        while (route.isNotEmpty()) {
            if (moves >= MAX_MOVES) return ManualWalk.Stopped("the walk is longer than $MAX_MOVES moves", moves)
            val door = route.first()
            when (port.press(door.label!!)) {
                ManualPress.NOT_ON_SCREEN -> return ManualWalk.Stopped("“${door.label}” is not on ${name(at) ?: "this screen"} now", moves)
                ManualPress.REFUSED -> return ManualWalk.Stopped("pressing “${door.label}” was refused", moves)
                ManualPress.DONE -> moves++
            }
            here = port.here() ?: return ManualWalk.Stopped("the screen could not be read after “${door.label}”", moves)
            if (matches(dict.screens[door.to], here)) {
                at = door.to
                route = route.drop(1)
                continue
            }
            val landed = locate(dict, here)
                ?: return ManualWalk.Stopped("“${door.label}” led to a screen the manual does not know", moves)
            if (landed == ability.place) { at = landed; route = emptyList(); break }
            if (replans >= MAX_REPLANS) return ManualWalk.Stopped("“${door.label}” led to ${name(landed) ?: "another screen"} instead", moves)
            replans++
            at = landed
            route = route(at, ability.place) ?: return ManualWalk.Stopped("no known way on from ${name(landed) ?: "here"}", moves)
        }
        // A switch taps its category last; that is navigation (the frame stays, the list changes).
        ability.tap?.let { category ->
            when (port.press(category)) {
                ManualPress.DONE -> moves++
                ManualPress.NOT_ON_SCREEN -> return ManualWalk.Stopped("the category “$category” is not on ${name(at) ?: "this screen"} now", moves)
                ManualPress.REFUSED -> return ManualWalk.Stopped("pressing “$category” was refused", moves)
            }
        }
        return ManualWalk.Arrived(name(at), moves, ability.pick)
    }

    /** Fewest moves over labelled doors, preferring doors seen recently. */
    fun route(from: String, to: String): List<DoorCard>? {
        if (from == to) return emptyList()
        val via = HashMap<String, DoorCard>()
        val queue = ArrayDeque(listOf(from))
        val seen = hashSetOf(from)
        while (queue.isNotEmpty()) {
            val room = queue.removeFirst()
            if (room == to) break
            for (door in labelled[room].orEmpty().sortedByDescending { it.lastSeenAt }) {
                if (!seen.add(door.to)) continue
                via[door.to] = door
                queue.addLast(door.to)
            }
        }
        if (to !in via) return null
        val path = ArrayDeque<DoorCard>()
        var room = to
        while (room != from) {
            val door = via[room] ?: return null
            path.addFirst(door)
            room = door.from
            if (path.size > MAX_MOVES) return null
        }
        return path.toList()
    }

    private fun name(room: String): String? = dict.screens[room]?.name

    companion object {
        const val MAX_MOVES = 8
        const val MAX_REPLANS = 2

        /**
         * The plain-code screen check: is the phone on this place? Its page key, or its title on screen, or most of its
         * button words (at least two).
         */
        fun matches(card: ScreenCard?, here: ManualHere): Boolean {
            card ?: return false
            if (here.pageKey != null && here.pageKey in card.pageKeys) return true
            val words = here.words.map(AppLexicon::normalize).toSet()
            if (card.title != null && AppLexicon.normalize(card.title) in words) return true
            val items = card.items.map(AppLexicon::normalize)
            val present = items.count { it in words }
            return items.size >= 2 && present >= 2 && present * 3 >= items.size * 2
        }

        /** Where the phone is, as a room of the manual: by page key, else the one place whose words fit best. */
        fun locate(dict: AppDictionary, here: ManualHere): String? {
            here.pageKey?.let { key -> dict.screens.values.firstOrNull { key in it.pageKeys }?.let { return it.roomKey } }
            val words = here.words.map(AppLexicon::normalize).toSet()
            val scored = dict.screens.values.mapNotNull { card ->
                val title = card.title?.let { AppLexicon.normalize(it) in words } == true
                val items = card.items.map(AppLexicon::normalize)
                val present = items.count { it in words }
                val share = if (items.isEmpty()) 0.0 else present.toDouble() / items.size
                val score = (if (title) 1.0 else 0.0) + share
                card.roomKey.takeIf { (title || (present >= 2 && share >= 0.66)) } ?.let { it to score }
            }.sortedByDescending { it.second }
            val best = scored.firstOrNull() ?: return null
            // Two places that fit equally well: not sure where we are.
            if (scored.getOrNull(1)?.second == best.second) return null
            return best.first
        }
    }
}
