package com.cyclone.mobile.voice

import java.util.Locale

/**
 * A message that arrived while driving, read from its notification by [DriveAnnouncements]. It lives in memory only,
 * long enough to be announced, and is never logged or stored.
 */
data class IncomingMessage(
    /** The notification key plus the text's hash: a repost of the same message has the same id. */
    val id: String,
    /** The app's package, matched against the owner's allowed apps. */
    val app: String,
    val appLabel: String,
    /** Who wrote it; for a group, the group's name. */
    val sender: String,
    val text: String,
    val group: Boolean,
    /** The notification has a reply field, so Cyclone can answer without opening the app (plan 26 tier 0). */
    val replyable: Boolean,
)

/** What Drive says about an announced message, and the reply a "yes" starts. Held by [VoiceTurn] for a minute. */
data class VoiceOffer(
    val id: String,
    /** The spoken line, already redacted and length-limited. */
    val line: String,
    /** The sender's name, cleaned for speech and for the goal. */
    val sender: String,
    val appLabel: String,
    /** The goal a "yes" submits; null when there is nothing to answer (a group, or no reply field). */
    val replyGoal: String?,
)

/**
 * Message announcements (plan 32 D3, plan 24 §5.5), the pure part. Opt-in per app and per contact, Driver mode only:
 * - "Louella wrote: "I will be home late." Reply?";
 * - more than 25 words: "Louella sent a long message. Reply?" (never read in full);
 * - a group: its name only, and no reply offer (a reply there reaches many people);
 * - anything that looks like a code or had to be redacted: not announced at all.
 *
 * Announcing never opens the microphone (plan 32: only a tap does). The orb glows, and a tap within a minute answers:
 * "yes" asks the Mind to reply (it asks what to say), "tell her …" gives the answer at once, "no" lets it go.
 * Either way the message is drafted, read back word for word and sent only on a spoken yes (D2).
 */
object VoiceAnnounce {
    /** How long a tap still answers the announcement. */
    const val OFFER_MS = 60_000L

    /** The chat apps offered in Settings (only those installed are shown). */
    val CHAT_APPS: List<Pair<String, String>> = listOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.thoughtcrime.securesms" to "Signal",
        "org.telegram.messenger" to "Telegram",
        "com.google.android.apps.messaging" to "Messages",
        "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram",
    )

    /** Whether the owner asked to hear [m]: Driver mode and announcements on, its app allowed, and its sender if a list is set. */
    fun allowed(m: IncomingMessage, settings: DriverSettings): Boolean {
        if (!settings.enabled || !settings.announce || m.app !in settings.announceApps) return false
        if (settings.announceContacts.isEmpty()) return true
        val who = name(m.sender)
        return settings.announceContacts.any { name(it) == who }
    }

    /** What Cyclone says for [m], or null when it stays silent (no sender, a code, something redacted). */
    fun offer(m: IncomingMessage): VoiceOffer? {
        val sender = cleanName(m.sender)
        if (sender.isBlank()) return null
        val text = m.text.replace(Regex("\\s+"), " ").trim()
        if (looksLikeCode(text) || VoiceRedaction.spoken(text) != text) return null
        val app = cleanName(m.appLabel).ifBlank { "your phone" }
        if (m.group) return VoiceOffer(m.id, "New message in $sender on $app.", sender, app, replyGoal = null)
        val said = VoiceCopy.message(text).trimEnd()
        val body = when {
            said.isBlank() -> "$sender sent a message."
            said == "a long message" -> "$sender sent a long message."
            else -> "$sender wrote: \"${said.trimEnd('.', '!', '?').replace("\"", "")}.\""
        }
        val goal = if (m.replyable) "Reply to the latest $app message from \"$sender\"." else null
        return VoiceOffer(m.id, if (goal != null) "$body Reply?" else body, sender, app, goal)
    }

    /** The goal when the owner says what to answer right away ("tell her I'm on my way"). */
    fun replyWith(offer: VoiceOffer, answer: String): String =
        "Reply to the latest ${offer.appLabel} message from \"${offer.sender}\". What the owner wants to say: ${answer.trim()}"

    /** What the understanding model sees while an offer is open: who and which app, never the message itself. */
    fun openAsk(offer: VoiceOffer): VoiceContext.OpenAsk =
        VoiceContext.OpenAsk("reply offer", "Reply to the message from ${offer.sender} on ${offer.appLabel}?")

    /** "Reply", said alone after an announcement, means yes. */
    fun replyWord(transcript: String): Boolean =
        VoiceRules.normalize(transcript).joinToString(" ") in REPLY_WORDS

    /** A verification code, a PIN or a password: never read aloud, so never announced. */
    fun looksLikeCode(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val digits = Regex("(?<!\\d)\\d{4,8}(?!\\d)").containsMatchIn(text) || Regex("(?<!\\d)\\d{3}[ -]\\d{3}(?!\\d)").containsMatchIn(text)
        return (digits && CODE_WORDS.containsMatchIn(lower)) || SECRET_WORDS.containsMatchIn(lower)
    }

    /**
     * A name as it may be spoken and put in a goal: letters, digits and simple punctuation only, at most six words.
     * The name comes from another app, so nothing in it can read as an instruction or break the goal's quotes.
     */
    fun cleanName(raw: String): String =
        VoiceCopy.words(raw.replace(Regex("[^\\p{L}\\p{N} .'&-]"), " ")).take(6).joinToString(" ").trim('.', ' ', '-', '\'')

    private fun name(raw: String): String = VoiceRules.normalize(raw).joinToString(" ")

    private val REPLY_WORDS = setOf("reply", "reply to it", "reply to her", "reply to him", "reply to them", "answer", "answer it",
        "antwoord", "antwoorden", "beantwoorden", "ja antwoord")
    private val CODE_WORDS = Regex("\\b(code|codes|otp|verif\\w*|pin|passcode|login|log in|sign in|inlog\\w*|bevestig\\w*)\\b")
    private val SECRET_WORDS = Regex("\\b(password|wachtwoord|one-time|eenmalig\\w*)\\b")
}
