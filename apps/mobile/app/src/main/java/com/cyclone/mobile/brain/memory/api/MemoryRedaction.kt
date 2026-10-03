package com.cyclone.mobile.brain.memory.api

sealed class MemoryRedactionResult {
    data class Safe(
        val content: MemoryContent,
        val redactedFieldNames: Set<String>,
    ) : MemoryRedactionResult()

    data class Rejected(val reasonCode: String) : MemoryRedactionResult()
}

fun interface MemoryRedactor {
    fun redact(draft: MemoryDraft): MemoryRedactionResult
}

class DefaultMemoryRedactor : MemoryRedactor {
    private val sensitiveKeyTokens = setOf(
        "password", "passcode", "pin", "otp", "token", "secret", "apikey",
        "authorization", "cookie", "payment", "cvv", "credential", "credentials", "typedtext",
    )
    private val sensitiveCompoundKeys = setOf(
        "api_key", "card_number", "typed_value", "typed_text", "one_time_code",
        "verification_code", "auth_code",
    )
    private val credentialValuePatterns = listOf(
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]+=*"),
        Regex("\\bsk-[A-Za-z0-9_-]{8,}\\b"),
    )

    override fun redact(draft: MemoryDraft): MemoryRedactionResult {
        if (draft.sensitivity == MemorySensitivity.RESTRICTED) {
            return MemoryRedactionResult.Rejected("RESTRICTED_CONTENT")
        }
        val removed = linkedSetOf<String>()
        val safe = linkedMapOf<String, String>()
        draft.content.fields.toSortedMap().forEach { (key, value) ->
            val normalizedKey = key
                .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                .lowercase()
                .replace('-', '_')
                .replace(' ', '_')
            val sensitiveKey = normalizedKey in sensitiveCompoundKeys ||
                normalizedKey.split('_').any { it in sensitiveKeyTokens }
            val credentialValue = credentialValuePatterns.any { it.containsMatchIn(value) }
            if (sensitiveKey || credentialValue) removed += key else safe[key] = value
        }
        if (safe.isEmpty()) return MemoryRedactionResult.Rejected("NO_SAFE_CONTENT")
        return MemoryRedactionResult.Safe(MemoryContent(safe), removed)
    }
}
