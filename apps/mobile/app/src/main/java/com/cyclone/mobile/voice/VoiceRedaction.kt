package com.cyclone.mobile.voice

import com.cyclone.mobile.mind.mission.MindRedaction

/**
 * Nothing secret is ever spoken (plan 32). Every line goes through the same redaction that guards what Cyclone
 * writes to disk, with code-context masking for text from other apps, and the masks are said as a plain word.
 */
object VoiceRedaction {
    fun spoken(text: String): String = MindRedaction.scrubText(text)
        .replace("[key hidden]", "a hidden key")
        .replace("[number hidden]", "a hidden number")
        .replace("[account hidden]", "a hidden account number")
        .replace("[hidden]", "hidden")
        .replace(Regex("\\s+"), " ")
        .trim()
}
