package com.cyclone.mobile.automation.skill

/**
 * Params on a skill capsule are slots only. Typed secrets never persist.
 */
object SkillSecrets {
    private val secretKeyTokens = setOf(
        "password", "passcode", "passwd", "pin", "otp", "token", "secret", "apikey",
        "authorization", "cookie", "cvv", "credential", "credentials", "typedtext",
    )
    private val secretCompoundKeys = setOf(
        "api_key", "card_number", "typed_value", "typed_text", "one_time_code",
        "verification_code", "auth_code",
    )
    private val secretValue = listOf(
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]+=*"),
        Regex("\\bsk-[A-Za-z0-9_-]{8,}\\b"),
        Regex("(?i)(password|passcode|otp|token|api[_-]?key|secret)\\s*[:=]\\s*\\S+"),
    )

    fun isSecretKey(key: String): Boolean {
        val normalized = normalizeKey(key)
        return normalized in secretCompoundKeys || normalized.split('_').any { it in secretKeyTokens }
    }

    fun isSecretValue(value: String): Boolean = secretValue.any { it.containsMatchIn(value) }

    fun strip(params: Map<String, String>): Map<String, String> =
        params.filterNot { (key, value) -> isSecretKey(key) || isSecretValue(value) }

    fun slotName(key: String): String = normalizeKey(key).ifBlank { "slot" }

    private fun normalizeKey(key: String): String = key
        .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
        .lowercase()
        .replace('-', '_')
        .replace(' ', '_')
}
