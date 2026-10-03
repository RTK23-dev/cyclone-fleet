package com.cyclone.mobile.mind.decide

import org.json.JSONArray
import org.json.JSONObject

/** Settings → Speed → Phone model (alpha 89). */
enum class PhoneModelUse(val wire: String, val label: String) {
    /** It acts on the actions it has earned; everything else goes to JEV. The default. */
    EARNED("earned", "Use what it has earned"),
    /** It only watches and learns; JEV decides everything the grammar doesn't. */
    LEARN("learn", "Learn only"),
    /** Not used and not trained. */
    OFF("off", "Off");

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: EARNED }
}

/** Who settled a request's route (alpha 89). */
enum class Decider { GRAMMAR, ANSWER, RULES, PHONE, DECISIONS, SETTING, FALLBACK }

/** What became of a routed request: the proof a decision was right or wrong. */
enum class Outcome {
    /** Instant did it and the phone confirmed it. */
    VERIFIED,
    /** Instant tried and it didn't work, or it was stopped by the owner. */
    FAILED,
    /** Instant couldn't finish and handed the run up a mode. */
    PROMOTED,
    /** The owner cancelled it (the call window). Not a verdict on the decision. */
    CANCELLED,
    /** Went to Flash or the Mind as decided. */
    HANDED,
    /** Nothing to check (an answer, not meant for Cyclone). */
    NONE,
}

/** A decision in the typed shape every decider answers: mode, action, target, confidence. */
data class Guess(val mode: String, val intent: String, val target: String?, val confidence: Double) {
    fun toJson(): JSONObject = JSONObject().put("mode", mode).put("intent", intent).put("target", target ?: JSONObject.NULL).put("confidence", confidence)

    /** The same decision as [other] (mode, action and target), whatever the confidence. */
    fun agrees(other: Guess): Boolean = mode == other.mode && intent == other.intent && (target ?: "") == (other.target ?: "")

    companion object {
        fun fromJson(value: JSONObject?): Guess? {
            value ?: return null
            val mode = value.optString("mode").takeIf { it.isNotBlank() } ?: return null
            return Guess(mode, value.optString("intent", "none").ifBlank { "none" },
                value.optString("target").takeIf { it.isNotBlank() && it != "null" }, value.optDouble("confidence", 0.0).coerceIn(0.0, 1.0))
        }
    }
}

/**
 * One routed request, kept on this phone only (alpha 89): the words, the choices the phone offered, who decided and how
 * fast, what the phone model guessed alongside (its shadow), and whether the result was verified. These are the
 * lessons the phone model learns from and the numbers the Lab reads. Never a secret: a request that looks like one is
 * not kept at all.
 */
data class Lesson(
    val atMs: Long,
    val request: String,
    /** On-screen labels and installed app names the decision could choose from. */
    val candidates: List<String>,
    val decider: Decider,
    val decision: Guess,
    /** How long the deciding stage took (the JEV call, the grammar, the phone model). */
    val decideMs: Long,
    /** The phone model's guess for the same request, made silently when it didn't decide. */
    val shadow: Guess?,
    val outcome: Outcome,
    /** Which provider answered when [decider] is DECISIONS (JEV now, OpenAI Decisions later). */
    val provider: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("atMs", atMs).put("request", request).put("candidates", JSONArray(candidates)).put("decider", decider.name)
        .put("decision", decision.toJson()).put("decideMs", decideMs).put("shadow", shadow?.toJson() ?: JSONObject.NULL)
        .put("outcome", outcome.name).put("provider", provider ?: JSONObject.NULL)

    /**
     * Can the phone model learn from this lesson? Only from a teacher (the grammar, JEV, the rules), never from its own
     * decisions, and only when the result backs the decision up: an Instant action that was verified, or a Flash or Mind
     * route JEV was sure of. A failure teaches nothing about the right answer.
     */
    fun teaches(bar: Double): Boolean = when (decider) {
        Decider.GRAMMAR -> outcome == Outcome.VERIFIED
        Decider.DECISIONS -> decision.confidence >= bar && (outcome == Outcome.VERIFIED || (decision.mode != "instant" && outcome == Outcome.HANDED))
        Decider.RULES -> outcome == Outcome.HANDED
        else -> false
    }

    companion object {
        const val MAX_REQUEST = 200
        const val MAX_CANDIDATES = 40

        fun fromJson(value: JSONObject): Lesson? = runCatching {
            Lesson(
                atMs = value.getLong("atMs"),
                request = value.getString("request").take(MAX_REQUEST),
                candidates = value.optJSONArray("candidates")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
                decider = Decider.valueOf(value.getString("decider")),
                decision = Guess.fromJson(value.optJSONObject("decision")) ?: return null,
                decideMs = value.optLong("decideMs", 0),
                shadow = Guess.fromJson(value.optJSONObject("shadow")),
                outcome = Outcome.valueOf(value.getString("outcome")),
                provider = value.optString("provider").takeIf { it.isNotBlank() && it != "null" },
            )
        }.getOrNull()
    }
}

/** The lessons as one bounded list, newest last. Pure: the phone's store reads and writes this text. */
object Lessons {
    const val MAX_KEPT = 2_000

    fun encode(lessons: List<Lesson>): String = lessons.takeLast(MAX_KEPT).joinToString("\n") { it.toJson().toString() }

    fun decode(text: String?): List<Lesson> =
        text.orEmpty().lineSequence().filter { it.isNotBlank() }.mapNotNull { runCatching { Lesson.fromJson(JSONObject(it)) }.getOrNull() }.toList().takeLast(MAX_KEPT)

    /** A lesson safe to keep: bounded, and dropped entirely when [looksSecret] says the request carries a secret. */
    fun keepable(lesson: Lesson, looksSecret: (String) -> Boolean): Lesson? {
        if (lesson.request.isBlank() || looksSecret(lesson.request)) return null
        return lesson.copy(request = lesson.request.take(Lesson.MAX_REQUEST),
            candidates = lesson.candidates.filterNot(looksSecret).map { it.take(80) }.distinct().take(Lesson.MAX_CANDIDATES))
    }
}
