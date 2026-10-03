package com.cyclone.mobile.agent.tools

import com.cyclone.mobile.agent.contract.NavigationIntent
import org.json.JSONObject

internal object TaskTypingAuthorization {
    fun allows(userGoal: String?, packageName: String, evidence: JSONObject, value: String): Boolean {
        if (userGoal.isNullOrBlank() || value.isBlank() || value.length > 4096) return false
        if (evidence.optBoolean("password") || !evidence.optBoolean("enabled", true)) return false
        val id = evidence.optString("resourceId").lowercase()
        if (listOf("url_bar", "urlbar", "location_bar", "address_bar", "search_box").none(id::contains)) return false
        if (!evidence.optBoolean("editable") && evidence.optString("role") !in setOf("textbox", "edittext", "text_field")) return false
        if (listOf("chrome", "browser", "firefox", "edge").none(packageName.lowercase()::contains)) return false
        val navigation = NavigationIntent.parse(userGoal)
        if (navigation != null) return (!navigation.chrome || packageName == "com.android.chrome") && navigation.accepts(value)
        val query = Regex("(?i)^search(?: for)? (.+?)(?: in (?:google )?chrome)?$").matchEntire(userGoal.trim())?.groupValues?.get(1)
        return query != null && value == query
    }
}

/**
 * Cyclone Mind missions (plan 21, Hands): the mission is the owner's own request, so the Mind may type into an ordinary
 * text field. Never a password or a field whose label, hint or id looks like a secret, code, card or payment; those go
 * through the Secrets Card. Typing is not sending: send, post, pay and delete stay with GATE. PhoneTypeEngine still
 * applies its own sensitive-field refusal after this.
 */
internal object OwnerMissionTyping {
    private val SENSITIVE = Regex(
        "(?i)(?:^|[^a-z0-9])(password|passcode|passwd|wachtwoord|secret|otp|one.?time|verification.?code|verificatiecode|" +
            "cvv|cvc|card.?number|kaartnummer|iban|ssn|pin|payment|credit.?card|cardholder|expiry|security.?code)(?:$|[^a-z0-9])",
    )

    fun allows(evidence: JSONObject, value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_CHARS) return false
        if (evidence.optBoolean("password") || !evidence.optBoolean("enabled", true)) return false
        if (!evidence.optBoolean("editable") && evidence.optString("role").lowercase() !in setOf("textbox", "edittext", "edit_text", "text_field")) return false
        val hints = listOf("label", "text", "hint", "resourceId", "contentDescription", "semanticName")
            .joinToString(" ") { evidence.optString(it) }
        return !SENSITIVE.containsMatchIn(hints)
    }

    const val MAX_CHARS = 20_000
}
