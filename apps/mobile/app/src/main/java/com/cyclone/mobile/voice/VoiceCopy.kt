package com.cyclone.mobile.voice

/**
 * Everything Cyclone says or shows in Drive, in one tested place (plan 24 §5.2 length limits):
 * - a confirmation is 8 words or fewer;
 * - a done or failed line is 20 words or fewer;
 * - a question is 15 words or fewer;
 * - a message over 25 words is never read out in full.
 * Every spoken line has already passed [VoiceRedaction].
 */
object VoiceCopy {
    const val ACK_WORDS = 8
    const val DONE_WORDS = 20
    const val QUESTION_WORDS = 15
    const val MESSAGE_WORDS = 25

    const val OKAY = "Okay."
    const val STOPPING = "Stopping."
    const val STILL_WORKING = "Still working on it."
    const val UNCLEAR_AGAIN = "Okay, tap me when you're ready."
    const val DEFAULT_ACK = "On it."
    const val DEFAULT_QUESTION = "What should I do?"
    const val DONE = "Done."
    const val STOPPED = "Stopped."
    const val BUSY = "I'm still on the last one. Say stop to end it."
    const val NOTHING_OPEN = "I'm not waiting for an answer right now."
    /** Every approval but send, passwords and handovers (plan 32): never by voice. */
    const val NEEDS_SCREEN = "That needs you on screen when you're stopped."
    const val NO_KEY = "Add your OpenRouter key in Cyclone first."
    const val OFFLINE = "I can't reach the internet right now."
    const val NOT_HEARD = "I didn't catch that. Tap me to try again."
    const val MIC_BUSY = "The microphone is busy. Try again in a moment."
    const val NO_MIC = "Cyclone needs the microphone. Allow it in Cyclone's setup."
    /** Both Drive's microphone and Android's recognizer heard only silence. */
    const val MIC_SILENCED = "I can't hear the microphone right now. Open Cyclone once, then tap me again."

    /** Captions on AI mode, one per state. */
    const val CAPTION_LISTENING = "Listening…"
    const val CAPTION_THINKING = "One moment…"
    const val CAPTION_WORKING = "Working on it"

    /** The model's confirmation, kept to one short line, or [DEFAULT_ACK]. */
    fun ack(line: String?): String = clip(line, ACK_WORDS).ifBlank { DEFAULT_ACK }

    /** The follow-up question for an unclear request. */
    fun question(line: String?): String = clip(line, QUESTION_WORDS).ifBlank { DEFAULT_QUESTION }

    /** The line when a task ends well: "Done." or the first sentence of what the task reported. */
    fun done(summary: String?): String {
        val first = firstSentence(VoiceRedaction.spoken(summary.orEmpty()))
        if (first.isBlank()) return DONE
        val line = clip(first, DONE_WORDS)
        return if (line.startsWith("Done", ignoreCase = true)) line else "Done: ${lowerFirst(line)}".let { clip(it, DONE_WORDS) }
    }

    /** The line when a task fails: what went wrong, briefly. */
    fun failed(reason: String?): String {
        val first = firstSentence(VoiceRedaction.spoken(reason.orEmpty()))
        return if (first.isBlank()) "That didn't work." else clip("That didn't work: ${lowerFirst(first)}", DONE_WORDS)
    }

    /**
     * A message someone else wrote, as it may be spoken while driving: in full when short, otherwise only that it
     * is long (plan 32: never read a full message; summarise anything over 25 words).
     */
    fun message(text: String): String {
        val safe = VoiceRedaction.spoken(text).trim()
        return if (words(safe).size <= MESSAGE_WORDS) safe else "a long message"
    }

    /** "A, B, or C?" for a question's options (at most three). */
    fun options(choices: List<String>): String {
        val list = choices.map { clip(it, 5) }.filter { it.isNotBlank() }.take(3)
        return when (list.size) {
            0 -> ""
            1 -> "${list[0]}?"
            2 -> "${list[0]}, or ${list[1]}?"
            else -> "${list[0]}, ${list[1]}, or ${list[2]}?"
        }
    }

    fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    /** At most [max] words; a cut line ends cleanly with a full stop, never mid-thought with a comma. */
    fun clip(text: String?, max: Int): String {
        val w = words(text.orEmpty())
        if (w.size <= max) return w.joinToString(" ")
        return w.take(max).joinToString(" ").trimEnd(',', ';', ':', '-', '—') + "."
    }

    fun firstSentence(text: String): String {
        val trimmed = text.trim()
        val end = Regex("(?<=[.!?])\\s").find(trimmed)?.range?.first ?: return trimmed
        return trimmed.substring(0, end).trim()
    }

    private fun lowerFirst(text: String): String =
        if (text.length > 1 && text[0].isUpperCase() && !text[1].isUpperCase()) text[0].lowercase() + text.substring(1) else text
}
