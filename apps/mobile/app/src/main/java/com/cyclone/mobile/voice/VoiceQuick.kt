package com.cyclone.mobile.voice

/**
 * Plan 42 (Live): what the owner says in the seconds after a quick action. The first part is already done, so
 * "… and take a selfie" is the next command, and "no, open Spotify" replaces what was meant. Pure.
 */
object VoiceQuick {
    private val LEAD = Regex("(?i)^\\s*(?:and then|and also|and|then|after that|also|en dan|en daarna|en ook|en|daarna|dan|ook)\\b[\\s,]*")
    private val CORRECTION = Regex("(?i)^\\s*(?:no|nope|nee|not that|niet dat|i mean|ik bedoel)\\b[\\s,.!]+(?=\\S)")

    /** The follow-up as its own request: the joining word or the "no," dropped. Unchanged when there is none. */
    fun continuation(text: String): String {
        val trimmed = text.trim()
        val rest = CORRECTION.replaceFirst(trimmed, "").let { LEAD.replaceFirst(it, "") }.trim()
        return rest.ifBlank { trimmed }
    }
}
