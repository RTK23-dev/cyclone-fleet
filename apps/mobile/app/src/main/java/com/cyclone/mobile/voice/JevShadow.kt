package com.cyclone.mobile.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * JEV, watching (alpha.52). TypeSafe's JEV is a decision model: it answers typed questions with a calibrated
 * confidence, very fast and nearly free, but it cannot write. In this release it only watches: for each request the
 * understanding model reads, JEV is asked the same "what kind of request is this?" and the two are compared. Its answer
 * never changes what Cyclone does; Settings → Voice shows how often it agreed and how fast it was, so the owner's own
 * car test decides whether it may take over simple decisions later.
 *
 * Pure: the request, a tolerant reader for the answer (the Decisions API is alpha), and the running tally.
 */
object JevShadow {
    const val MODEL = "~typesafe/jev-latest"

    /** The kinds JEV chooses between: the same as the understanding model's. */
    val CHOICES: List<String> = VoiceKind.entries.map { it.wire }

    data class Decision(val kind: VoiceKind, val confidence: Double)

    /** One comparison: what the understanding model said, what JEV said (null: no usable answer), and JEV's time. */
    data class Sample(val model: VoiceKind, val jev: VoiceKind?, val jevMs: Long, val confidence: Double = 0.0) {
        val agreed: Boolean get() = jev == model
    }

    data class Tally(val samples: List<Sample> = emptyList(), val lastError: String? = null) {
        val answered: List<Sample> get() = samples.filter { it.jev != null }
        val agreement: Double? get() = answered.takeIf { it.isNotEmpty() }?.let { a -> a.count { it.agreed }.toDouble() / a.size }
        val medianMs: Long? get() = answered.map { it.jevMs }.sorted().let { if (it.isEmpty()) null else it[(it.size - 1) / 2] }
        /** Agreement among the answers JEV was sure of (confidence ≥ 0.8): the ones it could take over. */
        val confidentAgreement: Double? get() = answered.filter { it.confidence >= 0.8 }.takeIf { it.isNotEmpty() }
            ?.let { c -> c.count { it.agreed }.toDouble() / c.size }

        fun add(sample: Sample): Tally = copy(samples = (samples + sample).takeLast(KEEP), lastError = null)
        fun failed(reason: String): Tally = copy(lastError = reason.take(160))

        /** "Agreed 18 of 20 · 0.2 s" for Settings. */
        fun summary(): String {
            val a = answered
            if (a.isEmpty()) return lastError?.let { "No answer yet: $it" } ?: "No requests yet."
            return "Agreed ${a.count { it.agreed }} of ${a.size} · ${VoiceTimings.seconds(medianMs)}" +
                (confidentAgreement?.let { " · ${(it * 100).toInt()}% when sure" } ?: "")
        }
    }

    const val KEEP = 50

    /** The decision request: the state (what was said, what is open) and one typed choice question. */
    fun request(transcript: String, context: VoiceContext): JSONObject = JSONObject()
        .put("model", MODEL)
        .put("state", JSONObject()
            .put("task", "Classify one spoken request to a phone assistant used while driving.")
            .put("spoken", transcript.take(VoiceRules.MAX_TRANSCRIPT_CHARS))
            .put("open_question", context.open?.spoken ?: JSONObject.NULL)
            .put("open_kind", context.open?.kind ?: JSONObject.NULL)
            .put("task_running", context.taskLive))
        .put("questions", JSONObject().put("kind", JSONObject()
            .put("type", "choice")
            .put("instructions", "task: a new job for the phone. reply: a new job to reply to or message someone. answer: an answer to " +
                "the open question. confirm: yes to the open readback. decline: no to it. cancel: never mind. none: not for the assistant. " +
                "unclear: a job is meant but something essential is missing.")
            .put("choices", JSONArray(CHOICES))))

    /**
     * JEV's answer to the "kind" question, read tolerantly: the alpha API has shipped the answer as
     * `answers.kind`, `decisions.kind` or `kind`, holding `choice`, `value` or `answer`, with `confidence` or
     * `probability`. Anything else is no answer.
     */
    fun parse(body: String?): Decision? {
        val json = runCatching { JSONObject(body ?: return null) }.getOrNull() ?: return null
        val holder = listOf("answers", "decisions", "results", "output").firstNotNullOfOrNull { json.optJSONObject(it) } ?: json
        val answer: Any = holder.opt("kind") ?: return null
        val node = answer as? JSONObject
        val value = node?.let { n -> listOf("choice", "value", "answer", "label").firstNotNullOfOrNull { k -> n.optString(k).takeIf { it.isNotBlank() } } }
            ?: (answer as? String)
            ?: return null
        val kind = VoiceKind.fromWire(value) ?: return null
        val confidence = node?.let { n -> listOf("confidence", "probability", "p").firstNotNullOfOrNull { k -> n.optDouble(k, Double.NaN).takeIf { !it.isNaN() } } } ?: 0.0
        return Decision(kind, confidence.coerceIn(0.0, 1.0))
    }
}
