package com.cyclone.mobile.voice

import java.util.Locale

/**
 * Rules before any model (plan 24 §5.2): what a transcript is worth, decided locally and for free. Only a real request
 * reaches the understanding model; an empty clip, a filler sound or "never mind" never costs a call.
 */
object VoiceRules {

    sealed interface Screen {
        /** Nothing was said: close quietly. */
        data object Empty : Screen
        /** Only "uh", "hmm", or a transcriber's stock phrase for silence: close quietly. */
        data object Filler : Screen
        /** "Never mind", "cancel": close with "Okay." [stop] is "stop" itself, which also stops a running task. */
        data class Cancel(val stop: Boolean) : Screen
        /** A real request, cleaned of surrounding filler. */
        data class Pass(val text: String) : Screen
    }

    /**
     * [answering] is true while Cyclone waits for an answer (a question, a readback): then "yes", "no" and "okay" are
     * answers and go on; otherwise they are not a request and close quietly.
     */
    fun screen(transcript: String?, answering: Boolean = false): Screen {
        val raw = transcript?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val words = normalize(raw)
        if (words.isEmpty()) return Screen.Empty
        val meaningful = words.filterNot { it in FILLER_WORDS }
        if (meaningful.isEmpty()) return Screen.Filler
        val phrase = meaningful.joinToString(" ")
        if (phrase in SILENCE_PHRASES) return Screen.Filler
        if (phrase in STOP_PHRASES) return Screen.Cancel(stop = true)
        if (phrase in CANCEL_PHRASES) return Screen.Cancel(stop = false)
        if (!answering && phrase in BARE_ANSWERS) return Screen.Filler
        return Screen.Pass(stripFiller(raw).take(MAX_TRANSCRIPT_CHARS))
    }

    /** Lowercase words without punctuation, for matching only; the request itself keeps its wording. */
    fun normalize(text: String): List<String> = text.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}' ]"), " ")
        .split(' ').map { it.trim('\'') }.filter { it.isNotBlank() }

    /** "uh, reply to Louella" → "reply to Louella". Only leading and trailing filler goes; the middle is the owner's. */
    private fun stripFiller(raw: String): String {
        val tokens = raw.split(' ').toMutableList()
        fun filler(token: String) = normalize(token).let { it.isEmpty() || it.all { w -> w in FILLER_WORDS } }
        while (tokens.isNotEmpty() && filler(tokens.first())) tokens.removeAt(0)
        while (tokens.isNotEmpty() && filler(tokens.last())) tokens.removeAt(tokens.lastIndex)
        return tokens.joinToString(" ").trim().trimStart(',', '.', ' ')
    }

    /**
     * An explicit yes or no to a readback, decided locally (no model, no call): true, false, or null when it is
     * anything else ("change the end to …" goes to the model). Only these exact phrases count as a yes.
     */
    fun yesNo(transcript: String?): Boolean? {
        val phrase = normalize(transcript.orEmpty()).filterNot { it in FILLER_WORDS }.joinToString(" ")
        return when (phrase) {
            in YES -> true
            in NO -> false
            else -> null
        }
    }

    private val YES = setOf("yes", "yes send it", "yes please", "yes send", "send it", "yeah send it", "yep send it", "yeah", "yep",
        "go ahead", "sure send it", "ok send it", "okay send it", "send", "ja", "ja stuur maar", "stuur maar", "stuur het", "ja graag")
    private val NO = setOf("no", "nope", "don't send it", "do not send it", "don't send", "no don't send it", "no thanks", "not now",
        "nee", "niet sturen", "nee niet sturen", "stuur niet")

    const val MAX_TRANSCRIPT_CHARS = 600

    private val FILLER_WORDS = setOf(
        "uh", "uhh", "um", "umm", "hmm", "hm", "mm", "mmm", "er", "erm", "ah", "ahh", "oh", "huh", "eh", "ehm", "euh",
        "uhm",
    )

    /** What speech-to-text models write for a clip with no words in it. */
    private val SILENCE_PHRASES = setOf(
        "you", "thank you", "thanks", "thanks for watching", "thank you for watching", "bye", "bye bye",
        "subtitles by the amara org community", "dank je", "dankjewel", "doei",
    )

    /** Answers with nothing to answer: not a request on their own. */
    private val BARE_ANSWERS = setOf("okay", "ok", "yeah", "yes", "yep", "no", "nope", "ja", "nee", "sure", "right", "alright")

    private val STOP_PHRASES = setOf(
        "stop", "stop it", "stop that", "stop the task", "stop cyclone", "cyclone stop", "stop now", "stoppen", "stop maar",
    )

    private val CANCEL_PHRASES = setOf(
        "cancel", "cancel that", "cancel it", "never mind", "nevermind", "forget it", "forget about it", "nothing",
        "nothing thanks", "laat maar", "annuleer", "annuleren", "niks", "laat maar zitten",
        "vergeet het",
    )
}
