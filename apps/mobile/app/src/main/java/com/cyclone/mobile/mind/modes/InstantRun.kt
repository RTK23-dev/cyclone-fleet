package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.mind.pilot.Pilot

/** The screen as Instant sees it: the app, the labels, and whether code says it is sensitive. */
data class InstantScreen(val app: String, val labels: List<String>, val sensitive: Boolean = false, val image: String? = null)
data class InstantMove(val ok: Boolean, val changed: Boolean, val note: String)
data class InstantContact(val name: String, val numbers: List<String>)

/** Why Instant stopped and where the run goes next. */
data class Promotion(val to: Mode, val reason: String, val candidates: List<String> = emptyList())

data class InstantOutcome(
    val done: Boolean,
    val lines: List<String>,
    val moves: Int,
    val promotion: Promotion? = null,
    /** Cancelled by the owner (the call window). Not a failure, not a promotion. */
    val cancelled: Boolean = false,
) {
    fun baton(goal: String): RunBaton? = promotion?.let { RunBaton(goal, listOf(Mode.INSTANT), lines, it.reason, it.candidates) }
}

/** The phone, as Instant moves it. Every move goes through Cyclone's executor on the phone. */
interface InstantHands {
    fun look(withImage: Boolean = false): InstantScreen?
    fun gesture(intent: InstantIntent, direction: String?): InstantMove
    fun tapLabel(label: String): InstantMove
    fun openApp(packageName: String): InstantMove
    fun camera(front: Boolean): InstantMove
    fun contacts(name: String): List<InstantContact>
    fun dial(number: String): InstantMove
    /** Shows "Calling Mam in 2 s — Cancel" (and says it in Live); true when the owner let it go ahead. */
    fun confirmWindow(text: String, ms: Long): Boolean
    fun timer(seconds: Int): InstantMove
    fun alarm(hour: Int, minute: Int): InstantMove
    fun flashlight(on: Boolean): InstantMove
    fun volume(up: Boolean): InstantMove
    fun media(action: String): InstantMove
    fun stopped(): Boolean
}

/**
 * Plan 42 (M4): Instant mode. One command, done with Cyclone's tools, at most [MAX_MOVES] moves. The follow-up moves
 * (the camera's shutter, the dialer's call button) are found on the screen by name, or picked by one decision box.
 *
 * It never types, never composes, never sends, pays, deletes or posts (such a label promotes to the Mind, which asks
 * the owner), and never acts on a sensitive screen. Anything it can't finish promotes with what it did, so the next
 * mode continues. Pure.
 */
object InstantRun {
    const val MAX_MOVES = 3
    const val CALL_WINDOW_MS = 2_000L

    private val SHUTTER = Regex("(?i)\\b(shutter|take (a )?(photo|picture)|capture|photo button|camera button|sluiter|foto maken|neem (een )?foto|opname)\\b")
    private val CALL_BUTTON = Regex("(?i)^(call|dial|voice call|bellen|bel|audio call)$|\\bcall button\\b|\\bbelknop\\b")

    fun run(command: InstantCommand, hands: InstantHands, box: DecisionBox? = null, bar: Double = 0.9): InstantOutcome {
        val lines = mutableListOf<String>()
        var moves = 0
        fun done() = InstantOutcome(true, lines.toList(), moves)
        fun promote(to: Mode, reason: String, candidates: List<String> = emptyList()) =
            InstantOutcome(false, lines.toList(), moves, Promotion(to, reason, candidates))
        fun step(move: InstantMove, line: String): Boolean {
            moves++
            if (move.ok) lines += line
            return move.ok
        }
        if (hands.stopped()) return promote(Mode.MIND, "the owner stopped it")

        return when (command.intent) {
            InstantIntent.SWIPE, InstantIntent.SCROLL, InstantIntent.BACK, InstantIntent.HOME, InstantIntent.RECENTS -> {
                val move = hands.gesture(command.intent, command.direction)
                if (step(move, describe(command))) done() else promote(Mode.FLASH, "the ${command.intent.name.lowercase()} didn't happen: ${move.note}")
            }
            InstantIntent.TIMER -> if (step(hands.timer(command.seconds ?: 0), "set a timer")) done() else promote(Mode.MIND, "the timer couldn't be set")
            InstantIntent.ALARM -> if (step(hands.alarm(command.hour ?: -1, command.minute ?: -1), "set an alarm")) done() else promote(Mode.MIND, "the alarm couldn't be set")
            InstantIntent.FLASHLIGHT -> if (step(hands.flashlight(command.direction == "on"), "turned the flashlight ${command.direction}")) done()
                else promote(Mode.MIND, "the flashlight couldn't be switched")
            InstantIntent.VOLUME -> if (step(hands.volume(command.direction == "up"), "turned the volume ${command.direction}")) done()
                else promote(Mode.MIND, "the volume couldn't be changed")
            InstantIntent.MEDIA -> if (step(hands.media(command.direction ?: "play_pause"), "media: ${command.direction}")) done()
                else promote(Mode.MIND, "the media key didn't work")
            InstantIntent.OPEN_APP -> {
                val move = hands.openApp(command.target.orEmpty())
                if (step(move, "opened ${command.targetLabel ?: command.target}")) done() else promote(Mode.FLASH, "the app didn't open: ${move.note}")
            }
            InstantIntent.CAMERA -> if (step(hands.camera(front = false), "opened the camera")) done() else promote(Mode.FLASH, "the camera didn't open")
            InstantIntent.TAP -> tap(command.target.orEmpty(), hands, lines, { moves++ }, ::promote) ?: done()
            InstantIntent.PHOTO, InstantIntent.SELFIE -> {
                val selfie = command.intent == InstantIntent.SELFIE
                if (!step(hands.camera(front = selfie), if (selfie) "opened the front camera" else "opened the camera")) return promote(Mode.FLASH, "the camera didn't open")
                val shutter = find(hands, box, bar, SHUTTER, "Which control takes the photo (the shutter)?") ?: return promote(Mode.FLASH, "couldn't find the camera's shutter")
                if (moves >= MAX_MOVES) return promote(Mode.FLASH, "too many moves")
                val move = hands.tapLabel(shutter)
                if (step(move, "took the photo")) done() else promote(Mode.FLASH, "the shutter didn't work: ${move.note}")
            }
            InstantIntent.CALL -> call(command, hands, box, bar, lines, { moves++ }, ::promote) ?: done()
        }
    }

    private fun tap(label: String, hands: InstantHands, lines: MutableList<String>, count: () -> Unit,
                    promote: (Mode, String, List<String>) -> InstantOutcome): InstantOutcome? {
        val screen = hands.look() ?: return promote(Mode.FLASH, "the screen couldn't be read", emptyList())
        if (screen.sensitive) return promote(Mode.MIND, "this screen shows a password, code or card field, or the app is kept out of quick actions", emptyList())
        // Code decides what can't be undone; the Mind asks the owner for those.
        if (Pilot.irreversible(label)) return promote(Mode.MIND, "\"$label\" can't be undone, so it needs your approval", emptyList())
        if (screen.labels.none { it == label }) return promote(Mode.FLASH, "\"$label\" is no longer on the screen", emptyList())
        count()
        val move = hands.tapLabel(label)
        if (!move.ok) return promote(Mode.FLASH, "the tap on \"$label\" didn't happen: ${move.note}", emptyList())
        lines += "tapped \"$label\""
        return null
    }

    private fun call(command: InstantCommand, hands: InstantHands, box: DecisionBox?, bar: Double, lines: MutableList<String>,
                     count: () -> Unit, promote: (Mode, String, List<String>) -> InstantOutcome): InstantOutcome? {
        val name = command.target.orEmpty()
        val found = hands.contacts(name).filter { it.numbers.isNotEmpty() }
        when {
            found.isEmpty() -> return promote(Mode.FLASH, "no contact with a number matches \"$name\"", emptyList())
            found.size > 1 -> return promote(Mode.FLASH, "more than one contact matches \"$name\"; ask the owner which one", found.map { it.name }.take(6))
        }
        val who = found.single()
        if (!hands.confirmWindow("Calling ${who.name}", CALL_WINDOW_MS)) {
            lines += "the owner cancelled the call to ${who.name}"
            return InstantOutcome(true, lines.toList(), 0, cancelled = true)
        }
        count()
        if (!hands.dial(who.numbers.first()).ok) return promote(Mode.FLASH, "the dialer didn't open", emptyList())
        lines += "opened the dialer for ${who.name}"
        val button = find(hands, box, bar, CALL_BUTTON, "Which control starts the call?") ?: return promote(Mode.FLASH, "couldn't find the call button", emptyList())
        count()
        val move = hands.tapLabel(button)
        if (!move.ok) return promote(Mode.FLASH, "the call button didn't work: ${move.note}", emptyList())
        lines += "called ${who.name}"
        return null
    }

    /**
     * A follow-up control: by name on the screen first (unique), otherwise one decision box with the screenshot. Null
     * when neither is sure.
     */
    private fun find(hands: InstantHands, box: DecisionBox?, bar: Double, pattern: Regex, question: String): String? {
        repeat(2) { attempt ->
            // A screenshot only for a box that can read one (JEV can't): taking it costs time for nothing.
            val screen = hands.look(withImage = box?.sees == true) ?: return null
            if (screen.sensitive) return null
            screen.labels.filter { pattern.containsMatchIn(it) }.distinct().singleOrNull()?.let { return it }
            if (box != null && screen.labels.isNotEmpty()) {
                val choices = screen.labels.distinct().take(40) + "none"
                val reply = box.ask(BoxRequest("Pick the one control a phone assistant should press next.",
                    "Screen: ${screen.app}\nControls: ${screen.labels.distinct().take(40).joinToString(", ")}",
                    listOf(BoxQuestion("next", question, choices)), screen.image))
                reply?.sure("next", bar)?.takeIf { it != "none" && !Pilot.irreversible(it) }?.let { return it }
            }
        }
        return null
    }

    private fun describe(command: InstantCommand): String = when (command.intent) {
        InstantIntent.SWIPE -> "swiped ${command.direction}"
        InstantIntent.SCROLL -> "scrolled ${command.direction}"
        InstantIntent.BACK -> "pressed Back"
        InstantIntent.HOME -> "went Home"
        InstantIntent.RECENTS -> "opened recent apps"
        else -> command.intent.name.lowercase()
    }
}
