package com.cyclone.mobile.mapping.crawl

import java.util.Locale

/**
 * Mapper safety v2: what a mapping pass must never tap, beyond the GATE's pay / send / delete / grant classes.
 *
 * A mapping pass promises the owner it never changes anything: no settings flipped, no social action taken, no
 * security area entered. A tap on a toggle (or on a settings row that holds one) changes a setting; "Follow" or
 * "Install" change state; security pages are off limits even to look at. For the owner's own account ("look only")
 * account and sign-in doors are off limits too. Pure: labels, role and checkable facts in, a danger or null out.
 */
object MapperDoorRisk {
    data class Facts(
        val labels: List<String>,
        val role: String = "",
        /** The control itself is checkable (switch, checkbox, radio, toggle). */
        val checkable: Boolean = false,
        /** The control's bounds contain a checkable control (a settings row with a switch). */
        val containsCheckable: Boolean = false,
    )

    private val CHECKABLE_ROLES = setOf("switch", "checkbox", "radiobutton", "radio", "togglebutton", "toggle", "seekbar", "slider", "ratingbar")

    private val STATE_CHANGE = Regex(
        """\b(follow|unfollow|like|unlike|subscribe|unsubscribe|join|leave|add friend|accept|decline|reject|block|unblock|report|mute|unmute|""" +
            """save|unsave|bookmark|vote|upvote|downvote|rate|install|uninstall|update|enable|disable|turn on|turn off|allow|deny|""" +
            """archive|pin|unpin|favourite|favorite|hide|restrict|remove|clear|reset|apply|confirm|agree|""" +
            """add to|buy|purchase|order now|checkout|donate|tip|send gift|go live|start call|call|video call|record)\b""",
    )
    private val SECURITY = Regex(
        """\b(password|passcode|two[- ]?factor|2fa|two[- ]?step|passkeys?|security|recovery|authenticator|login activity|""" +
            """where you'?re logged in|logged[- ]in devices|active sessions|blocked (?:accounts|users)|deactivate|delete account|""" +
            """close account|verification|backup codes?)\b""",
    )
    private val ACCOUNT = Regex(
        """\b(log ?in|sign ?in|sign ?up|register|create (?:an )?account|add account|switch account|accounts? center|account centre|""" +
            """account settings|personal (?:details|information)|profile photo|edit profile)\b""",
    )

    fun classify(facts: Facts, identity: MappingIdentity?): MappingDanger? {
        val role = facts.role.lowercase(Locale.US).replace(Regex("[^a-z]"), "")
        if (facts.checkable || role in CHECKABLE_ROLES || facts.containsCheckable) return MappingDanger.SETTING_CHANGE
        val text = facts.labels.joinToString(" ").lowercase(Locale.US).replace(Regex("[_\\-]+"), " ")
        if (text.isBlank()) return null
        if (SECURITY.containsMatchIn(text)) return MappingDanger.SECURITY
        if (STATE_CHANGE.containsMatchIn(text)) return MappingDanger.STATE_CHANGE
        if (identity == MappingIdentity.OWN && ACCOUNT.containsMatchIn(text)) return MappingDanger.ACCOUNT
        return null
    }
}
