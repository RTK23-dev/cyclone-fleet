package com.cyclone.mobile.voice

/**
 * Owner Moments as voice turns (plan 24 §5.5). The Owner Moment stays the one source of truth; this is only how it
 * sounds and which answers voice may give. Every answer goes back through Task Kit.
 *
 * | Moment | Cyclone says | The answer becomes |
 * |---|---|---|
 * | QUESTION | the question (≤ 15 words), options as "A, or B?" | `Reply(text)` |
 * | VALUES | one field at a time ("What's the date?") | `Fill(values, remember = false)` |
 * | SEND | the verbatim readback: "I'll send Louella: "…". Send it?" | yes `Approve`, no `Decline`, "change it to …" `Reply` |
 * | any other approval, SECRET, HANDOVER | "That needs you on screen when you're stopped." | nothing by voice |
 */
object VoiceMoments {
    /** The kinds the owner can answer by voice. */
    val VOICE_KINDS: Set<VoiceMoment.Kind> = setOf(VoiceMoment.Kind.QUESTION, VoiceMoment.Kind.VALUES, VoiceMoment.Kind.SEND)

    /** A draft longer than this is checked on screen, not read out while driving. */
    const val READBACK_MAX_WORDS = 40

    const val EDITING = "Changing it."
    const val SENDING = "Sending."
    const val NOT_SENT = "Okay, I won't send it."

    /** What Cyclone says when [m] opens. */
    fun prompt(m: VoiceMoment): String = when (m.kind) {
        VoiceMoment.Kind.QUESTION -> question(m.text, m.choices)
        VoiceMoment.Kind.VALUES -> m.fields.firstOrNull()?.let { fieldQuestion(it) } ?: VoiceCopy.NEEDS_SCREEN
        VoiceMoment.Kind.SEND -> readback(m)
        else -> VoiceCopy.NEEDS_SCREEN
    }

    /**
     * The verbatim readback (plan 32): the exact message the approval will send, quoted whole. [m.message] comes from
     * the approval itself and is only a SEND when it can be spoken unchanged ([readable]).
     */
    fun readback(m: VoiceMoment): String =
        if (m.recipient.isBlank()) "I'll send: \"${m.message}\". Send it?" else "I'll send ${m.recipient}: \"${m.message}\". Send it?"

    /** A message that can be read back word for word: nothing masked by redaction, and short enough for the road. */
    fun readable(message: String): Boolean {
        val plain = message.replace(Regex("\\s+"), " ").trim()
        return plain.isNotEmpty() && VoiceCopy.words(plain).size <= READBACK_MAX_WORDS && VoiceRedaction.spoken(plain) == plain &&
            !plain.contains('"')
    }

    /** One field of a details card as a short question, with its options. */
    fun fieldQuestion(field: VoiceMoment.Field): String {
        val label = VoiceCopy.clip(field.label.trim().trimEnd('?', ':'), 8)
        val options = VoiceCopy.options(field.choices)
        val ask = "What's the ${label.replaceFirstChar { it.lowercase() }}?"
        return if (options.isBlank()) ask else "$ask $options"
    }

    /**
     * A question as spoken: quoted messages longer than 25 words become "a long message", and a question longer than
     * 15 words (quotes aside) is cut to its last sentence, which is where the question is.
     */
    fun question(text: String, choices: List<String> = emptyList()): String {
        val safe = VoiceRedaction.spoken(text)
        val quoted = QUOTE.replace(safe) { match ->
            val inner = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
            val said = VoiceCopy.message(inner)
            if (said == inner) match.value else said
        }
        val outsideQuotes = VoiceCopy.words(QUOTE.replace(quoted, "")).size
        val line = if (outsideQuotes <= VoiceCopy.QUESTION_WORDS) quoted
            else VoiceCopy.clip(lastSentence(quoted), VoiceCopy.QUESTION_WORDS)
        val options = VoiceCopy.options(choices)
        return if (options.isBlank()) line else "$line $options"
    }

    /** "Change it to …" as the instruction the Mind receives while its readback is open. */
    fun edit(text: String): String {
        val t = text.trim()
        return if (t.startsWith("change", ignoreCase = true)) t else "Change the message: $t"
    }

    private fun lastSentence(text: String): String {
        val parts = text.trim().split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        return parts.lastOrNull { it.trimEnd().endsWith("?") } ?: parts.lastOrNull().orEmpty()
    }

    private val QUOTE = Regex("\"([^\"]+)\"|“([^”]+)”|'([^']{3,})'(?=[\\s.,!?]|$)|‘([^’]+)’")
}
