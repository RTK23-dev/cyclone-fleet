package com.cyclone.mobile.mind.mission

/** Last line of defence for anything written to disk: numbers and tokens that look like secrets are masked. */
object MindRedaction {
    private val rules = listOf(
        Regex("(?i)\\b(password|passcode|wachtwoord|pin|otp|token|secret|api[_ -]?key)(\\s*[:=]\\s*)\\S+") to "$1$2[hidden]",
        Regex("(?i)\\b(code|otp|verification|verificatie)([^0-9\\n]{0,24})\\b\\d{4,8}\\b") to "$1$2[hidden]",
        Regex("\\bsk-[A-Za-z0-9_-]{8,}") to "[key hidden]",
        Regex("\\bAIza[0-9A-Za-z_-]{20,}") to "[key hidden]",
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{12,}") to "Bearer [hidden]",
        Regex("\\b(?:\\d[ -]?){13,19}\\b") to "[number hidden]",
        Regex("\\b[A-Z]{2}\\d{2}[A-Z0-9]{4}\\d{7}[A-Z0-9]{0,16}\\b") to "[account hidden]",
    )

    fun scrub(text: String): String = rules.fold(text) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

    private val codeContext = Regex("(?i)\\b(code|otp|verification|verificatie|verify|passcode|pin|2fa|one[- ]time|login|sign[- ]in|inlog)")
    private val shortNumber = Regex("\\b\\d{4,8}\\b")

    /**
     * For free text from other apps (notifications): when the text is about a code or a sign-in, every 4-8 digit
     * number is masked too, whatever the word order ("482913 is your code").
     */
    fun scrubText(text: String): String {
        val scrubbed = scrub(text)
        return if (codeContext.containsMatchIn(scrubbed)) shortNumber.replace(scrubbed, "[hidden]") else scrubbed
    }
}
