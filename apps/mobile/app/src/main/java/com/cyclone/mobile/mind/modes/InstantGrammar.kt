package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.voice.VoiceIntents
import java.text.Normalizer

/** What Instant mode can do by itself (plan 42 §4). */
enum class InstantIntent {
    SWIPE, SCROLL, BACK, HOME, RECENTS, TAP, OPEN_APP, CAMERA, PHOTO, SELFIE, CALL, TIMER, ALARM, FLASHLIGHT, VOLUME, MEDIA
}

/**
 * One Instant command, with every argument taken from the request or from what is on the phone (a screen label, an
 * installed app, a contact name to look up), never invented.
 */
data class InstantCommand(
    val intent: InstantIntent,
    val text: String,
    /** up, down, left, right (swipes and scrolls); on or off (flashlight); up or down (volume); play_pause, next, previous (media). */
    val direction: String? = null,
    /** A screen label (tap), an app package (open app), or a name to look up in contacts (call). */
    val target: String? = null,
    /** How the target reads to the owner: the label or the app's name. */
    val targetLabel: String? = null,
    val seconds: Int? = null,
    val hour: Int? = null,
    val minute: Int? = null,
)

/** What the phone offers the grammar right now: the labels on screen and the installed apps (label to package). */
data class GrammarWorld(val labels: List<String> = emptyList(), val apps: List<Pair<String, String>> = emptyList())

sealed class GrammarResult {
    data class Match(val command: InstantCommand) : GrammarResult()
    /** A command whose target matches more than one thing: a decision box (or the owner) must pick. */
    data class Ambiguous(val intent: InstantIntent, val candidates: List<String>, val text: String) : GrammarResult()
    object None : GrammarResult()
}

/**
 * Plan 42 (M2): Instant's grammar. Plain commands in English and Dutch are recognised on the phone in about a
 * millisecond, with no model: gestures, Back and Home, opening an app, tapping a thing by name, the camera, a photo or
 * a selfie, calling someone, timers and alarms (Drive's parser), the flashlight, volume and media.
 *
 * It is strict on purpose. A sentence with a second clause, words to write, or a target that matches nothing is not a
 * command; the router sends it up a mode. Pure.
 */
object InstantGrammar {
    const val MAX_WORDS = 10
    /** A spoken target must be at least this similar to a label or app name. */
    const val MATCH = 0.75
    /** The best match must lead the next by this much to count as unique. */
    const val LEAD = 0.1

    private val FILLER = setOf("please", "cyclone", "hey", "ok", "okay", "now", "alsjeblieft", "graag", "even", "nu", "can", "you",
        "could", "would", "kun", "kan", "je", "jij", "wil", "u")
    private val JOINERS = setOf("and", "then", "en", "daarna", "dan", "after", "also", "ook")

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}:' ]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * The command's words, without filler. A word said twice in a row ("open open Telegram", a stutter speech-to-text
     * keeps) counts once (alpha.78).
     */
    private fun words(text: String): List<String> = normalize(text).split(' ').filter { it.isNotBlank() && it !in FILLER }
        .fold(mutableListOf<String>()) { out, w -> if (out.lastOrNull() != w) out += w; out }

    fun parse(text: String, world: GrammarWorld = GrammarWorld()): GrammarResult {
        val tokens = words(text)
        if (tokens.isEmpty() || tokens.size > MAX_WORDS) return GrammarResult.None
        // One command only: "open the camera and take a picture" is two, so it isn't Instant's to take whole.
        if (tokens.drop(1).any { it in JOINERS }) return GrammarResult.None
        val t = tokens.joinToString(" ")
        gesture(t)?.let { return GrammarResult.Match(it.copy(text = text)) }
        VoiceIntents.timerSeconds(text)?.let { return GrammarResult.Match(InstantCommand(InstantIntent.TIMER, text, seconds = it)) }
        VoiceIntents.alarmTime(text)?.let { (h, m) -> return GrammarResult.Match(InstantCommand(InstantIntent.ALARM, text, hour = h, minute = m)) }
        device(t)?.let { return GrammarResult.Match(it.copy(text = text)) }
        camera(t)?.let { return GrammarResult.Match(it.copy(text = text)) }
        CALL.matchEntire(t)?.let { m ->
            val name = m.groupValues[2].split(' ').filterNot { it in setOf("my", "mijn", "the", "de") }.joinToString(" ").trim()
            if (name.isNotBlank() && name !in setOf("me", "mij")) return GrammarResult.Match(InstantCommand(InstantIntent.CALL, text, target = name, targetLabel = name))
            return GrammarResult.None
        }
        OPEN.matchEntire(t)?.let { m ->
            val wanted = m.groupValues[3].removeSuffix(" app").trim()
            return pick(wanted, world.apps.map { it.first }, InstantIntent.OPEN_APP, text) { label ->
                val pkg = world.apps.first { it.first == label }.second
                InstantCommand(InstantIntent.OPEN_APP, text, target = pkg, targetLabel = label)
            }
        }
        TAP.matchEntire(t)?.let { m ->
            val wanted = m.groupValues[3].removeSuffix(" button").removeSuffix(" knop").trim()
            return pick(wanted, world.labels, InstantIntent.TAP, text) { label -> InstantCommand(InstantIntent.TAP, text, target = label, targetLabel = label) }
        }
        return GrammarResult.None
    }

    /** True when [text] is already a complete command Instant can do: Live commits it without waiting for silence. */
    fun complete(text: String, world: GrammarWorld = GrammarWorld()): Boolean = parse(text, world) is GrammarResult.Match

    /**
     * Live voice (plan 42 §5): true when [text] reads as one Instant command even before the phone's world is known
     * (an app or a label is checked by the router on the phone). Such a sentence skips the understanding call and the
     * spoken "On it."; the router still sends it up a mode when it isn't one.
     */
    fun quick(text: String): Boolean {
        val tokens = words(text)
        if (tokens.isEmpty() || tokens.size > MAX_WORDS || tokens.drop(1).any { it in JOINERS }) return false
        val t = tokens.joinToString(" ")
        return complete(text) || OPEN.matches(t) || TAP.matches(t)
    }

    /**
     * True when the command names something on the screen ("tap Pokémon GO", "click Settings"): only then does Instant
     * read the screen before routing. Gestures, apps, the camera and calls read it once, right before the move.
     */
    fun needsScreen(text: String): Boolean = TAP.matches(words(text).joinToString(" "))

    /** While the owner is still talking: what the command is becoming, so the phone can get ready (plan 42 §5.2). */
    fun prefix(text: String): InstantIntent? {
        val t = words(text).joinToString(" ")
        return when {
            t.startsWith("call ") || t.startsWith("bel ") || t == "call" || t == "bel" -> InstantIntent.CALL
            t.startsWith("take a p") || t.startsWith("take a s") || t.startsWith("maak een f") || t.startsWith("maak een s") -> InstantIntent.PHOTO
            Regex("^(open|start|launch) (my |the |de |mijn )?cam").containsMatchIn(t) -> InstantIntent.CAMERA
            t.startsWith("open ") || t.startsWith("launch ") || t.startsWith("start ") -> InstantIntent.OPEN_APP
            else -> null
        }
    }

    // ---- gestures ---------------------------------------------------------------------------------------------------

    private val DIRECTIONS = mapOf("up" to "up", "down" to "down", "left" to "left", "right" to "right", "omhoog" to "up",
        "omlaag" to "down", "naar boven" to "up", "naar beneden" to "down", "links" to "left", "rechts" to "right",
        "naar links" to "left", "naar rechts" to "right")
    private val SWIPE = Regex("^(swipe|veeg|swipen) (up|down|left|right|omhoog|omlaag|naar boven|naar beneden|links|rechts|naar links|naar rechts)$")
    private val SCROLL = Regex("^(scroll|scrol|scrollen)(?: (up|down|omhoog|omlaag|naar boven|naar beneden))?(?: a bit| een beetje| more| verder)?$")

    private fun gesture(t: String): InstantCommand? {
        SWIPE.matchEntire(t)?.let { return InstantCommand(InstantIntent.SWIPE, t, direction = DIRECTIONS[it.groupValues[2]]) }
        SCROLL.matchEntire(t)?.let { return InstantCommand(InstantIntent.SCROLL, t, direction = DIRECTIONS[it.groupValues[2]] ?: "down") }
        return when (t) {
            "back", "go back", "terug", "ga terug", "press back", "back button" -> InstantCommand(InstantIntent.BACK, t)
            "home", "go home", "home screen", "go to the home screen", "go to home screen", "naar huis", "startscherm",
            "ga naar het startscherm", "naar het startscherm", "beginscherm" -> InstantCommand(InstantIntent.HOME, t)
            "recent apps", "recents", "show recent apps", "recente apps", "open recent apps" -> InstantCommand(InstantIntent.RECENTS, t)
            else -> null
        }
    }

    // ---- device ------------------------------------------------------------------------------------------------------

    private val TORCH = Regex("^(?:turn |switch |zet )?(?:the |de )?(?:on |off |aan |uit )?(?:the |de )?(flashlight|torch|zaklamp)(?: (on|off|aan|uit))?$")
    private val TORCH_FIRST = Regex("^(?:turn|switch|zet) (on|off|aan|uit) (?:the |de )?(flashlight|torch|zaklamp)$")

    private fun device(t: String): InstantCommand? {
        TORCH_FIRST.matchEntire(t)?.let { return InstantCommand(InstantIntent.FLASHLIGHT, t, direction = onOff(it.groupValues[1])) }
        TORCH.matchEntire(t)?.let { m ->
            val state = m.groupValues[2].ifBlank { Regex("\\b(on|off|aan|uit)\\b").find(t)?.value.orEmpty() }
            if (state.isBlank()) return null
            return InstantCommand(InstantIntent.FLASHLIGHT, t, direction = onOff(state))
        }
        return when (t) {
            "volume up", "turn the volume up", "turn volume up", "louder", "harder", "volume omhoog", "zet het volume hoger", "turn it up" ->
                InstantCommand(InstantIntent.VOLUME, t, direction = "up")
            "volume down", "turn the volume down", "turn volume down", "softer", "quieter", "zachter", "volume omlaag",
            "zet het volume lager", "turn it down" -> InstantCommand(InstantIntent.VOLUME, t, direction = "down")
            "play", "pause", "resume", "play music", "pause music", "pause the music", "stop the music", "afspelen", "pauze",
            "pauzeer", "muziek pauzeren" -> InstantCommand(InstantIntent.MEDIA, t, direction = "play_pause")
            "next", "next song", "next track", "skip", "skip song", "volgende", "volgende nummer" -> InstantCommand(InstantIntent.MEDIA, t, direction = "next")
            "previous", "previous song", "previous track", "vorige", "vorige nummer" -> InstantCommand(InstantIntent.MEDIA, t, direction = "previous")
            else -> null
        }
    }

    private fun onOff(word: String) = if (word == "on" || word == "aan") "on" else "off"

    // ---- camera ------------------------------------------------------------------------------------------------------

    private val OPEN_CAMERA = Regex("^(?:open|start|launch|openen)(?: (?:my|the|de|mijn))? camera(?: app)?$|^camera(?: openen)?$")
    private val PHOTO = Regex("^(?:take|make|snap|shoot|maak|neem)(?: (?:a|an|een))? (photo|picture|pic|foto|selfie)(?: (of me|of myself|van mij|van mezelf))?$")

    private fun camera(t: String): InstantCommand? {
        if (OPEN_CAMERA.matches(t)) return InstantCommand(InstantIntent.CAMERA, t)
        PHOTO.matchEntire(t)?.let { m ->
            val selfie = m.groupValues[1] == "selfie" || m.groupValues[2].isNotBlank()
            return InstantCommand(if (selfie) InstantIntent.SELFIE else InstantIntent.PHOTO, t)
        }
        return null
    }

    // ---- calls, apps, taps -------------------------------------------------------------------------------------------

    private val CALL = Regex("^(call|phone|ring|bel|bellen)(?: to| naar)? (.+)$")
    private val OPEN = Regex("^(open|launch|start|go to|ga naar|openen)( the| my| de| mijn)? (.+)$")
    private val TAP = Regex("^(tap|click|press|klik|druk|tik|select)(?: on| op)?( the| de| het)? (.+)$")

    private inline fun pick(wanted: String, options: List<String>, intent: InstantIntent, text: String,
                            build: (String) -> InstantCommand): GrammarResult {
        if (wanted.isBlank()) return GrammarResult.None
        val scored = options.distinct().map { it to similarity(wanted, it) }.filter { it.second >= MATCH }.sortedByDescending { it.second }
        if (scored.isEmpty()) return GrammarResult.None
        val top = scored.first()
        val rivals = scored.drop(1).filter { top.second - it.second < LEAD }
        return if (rivals.isEmpty()) GrammarResult.Match(build(top.first))
        else GrammarResult.Ambiguous(intent, (listOf(top) + rivals).map { it.first }.take(6), text)
    }

    /** How alike a spoken name and a label are, 0 to 1: exact, without spaces, prefix, all words, or a close spelling. */
    fun similarity(spoken: String, label: String): Double {
        val a = normalize(spoken)
        val b = normalize(label)
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return 1.0
        val a0 = a.replace(" ", "")
        val b0 = b.replace(" ", "")
        if (a0 == b0) return 0.97
        if (a.length >= 3 && b.startsWith(a) && a.length * 10 >= b.length * 7) return 0.9
        val aw = a.split(' ').toSet()
        val bw = b.split(' ').toSet()
        if (aw.isNotEmpty() && bw.containsAll(aw) && aw.size * 2 >= bw.size) return 0.86
        val close = 1.0 - distance(a0, b0).toDouble() / maxOf(a0.length, b0.length)
        return if (a0.length >= 4 && close >= 0.8) close * 0.92 else close * 0.6
    }

    /** Edit distance where swapping two neighbouring letters counts once (speech-to-text often does that). */
    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { i -> IntArray(b.length + 1) { j -> if (i == 0) j else if (j == 0) i else 0 } }
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
