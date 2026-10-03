package com.cyclone.mobile.mind

/**
 * Plan 37 W3 (alpha.67): notices when the owner asks Cyclone to remember something, in the goal, in a message during
 * the mission or in an answer: "remember that…", "don't forget…", "from now on…", "next time…", "onthoud…",
 * "voortaan…". A question about the past ("do you remember…?") and a reminder ("remind me…") are not requests to keep
 * something. Returns the sentence that asks, so the Mind is told exactly what to keep.
 */
object RememberIntent {
    private val ASK = Regex(
        "(?i)(?<![\\p{L}])(remember( that| this| my| her| his| their| it)?|don'?t forget|do not forget|keep in mind|make a note( that| of)?|" +
            "note that|save (this|that|it) (to|in) (your )?memory|add (this|that|it) to (your )?memory|from now on|next time|going forward|" +
            "onthoud|vergeet niet|voortaan|de volgende keer|sla (dit|dat) op|unthoud)(?![\\p{L}])",
    )
    private val QUESTION = Regex("(?i)(do|did|can|could|what do|don'?t) you remember|remember when|remind me")

    fun detect(text: String?): String? {
        val clean = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (clean.isBlank()) return null
        val sentences = clean.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.isNotBlank() }
        return sentences.firstOrNull { ASK.containsMatchIn(it) && !QUESTION.containsMatchIn(it) }?.take(300)
    }
}
