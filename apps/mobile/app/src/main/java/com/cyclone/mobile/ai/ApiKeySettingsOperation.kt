package com.cyclone.mobile.ai

/** Consumer-safe boundary: never return provider exceptions or secret-bearing messages. */
object ApiKeySettingsOperation {
    fun save(draft: String, write: (String) -> Unit, read: () -> String): String? {
        val key = draft.trim()
        if (!key.startsWith("sk-or-") || key.length <= 6 || key.any { it.isWhitespace() }) {
            return "Enter a complete OpenRouter API key beginning with sk-or-."
        }
        return try {
            write(key)
            if (read() == key) null else "The key could not be verified in secure storage. Please try again."
        } catch (_: Exception) {
            "Could not save the key securely. Unlock this profile and try again."
        }
    }

    fun remove(clear: () -> Unit): String? = try {
        clear()
        null
    } catch (_: Exception) {
        "Could not remove the saved key. Please try again."
    }
}
