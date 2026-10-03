package com.cyclone.mobile.voice

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the owner meant (plan 24 §5.4). One call to a fast model turns a transcript into a strict shape:
 * `{kind, goal, ack, missing, confidence}`. This object is the pure part: the prompt, the schema and the parser.
 * Anything malformed is [VoiceKind.UNCLEAR], never a guess.
 *
 * The prompt carries the transcript, the open question (so "yes" or "tell her…" is read as an answer), and a few
 * recent goals. It never carries screen content or messages; drafting is the Mind's job, on the phone.
 */
enum class VoiceKind(val wire: String) {
    /** A new job for Cyclone. */
    TASK("task"),
    /** A new job that is a reply to someone. */
    REPLY("reply"),
    /** An answer to the open question. */
    ANSWER("answer"),
    /** "Yes" to the open readback or approval. */
    CONFIRM("confirm"),
    /** "No" to the open readback or approval. */
    DECLINE("decline"),
    /** "Never mind". */
    CANCEL("cancel"),
    /** Not addressed to Cyclone, or nothing to do. */
    NONE("none"),
    /** Something essential is missing: [Understanding.missing] is the one question to ask. */
    UNCLEAR("unclear");

    companion object {
        fun fromWire(value: String?): VoiceKind? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

data class Understanding(
    val kind: VoiceKind,
    /** The clean sentence the Mind receives (task, reply), or the answer text (answer). */
    val goal: String = "",
    /** The spoken confirmation, 8 words or fewer. */
    val ack: String = "",
    /** The one follow-up question when something is missing. */
    val missing: String = "",
    val confidence: Double = 0.0,
) {
    companion object {
        fun unclear(question: String = "") = Understanding(VoiceKind.UNCLEAR, missing = question)
    }
}

/** What is open while the owner speaks; the model reads this to tell an answer from a new request. */
data class VoiceContext(
    /** The question Cyclone just asked about the owner's earlier words (after an unclear request). */
    val followUp: FollowUp? = null,
    /** The open question, details card or readback of the running task, as spoken. */
    val open: OpenAsk? = null,
    val recentGoals: List<String> = emptyList(),
    val taskLive: Boolean = false,
    /** The owner's language setting ("auto" or a name such as "Dutch"). */
    val language: String = "auto",
) {
    data class FollowUp(val earlier: String, val question: String)
    data class OpenAsk(val kind: String, val spoken: String, val choices: List<String> = emptyList())
}

object VoiceUnderstanding {

    /** The phone's direct tools: these run without taking the screen, so goals for them are phrased plainly. */
    val DIRECT_TOOLS = listOf("set_timer", "set_alarm", "calendar_add", "calendar_find", "contact_find")

    fun systemPrompt(): String = """
You turn one spoken request to Cyclone, a phone assistant used while driving, into JSON. You never answer the request
yourself and never invent details. Output only JSON matching the schema.

kind:
- "task": a new job for the phone (open, find, navigate, play, set, add, check, call...).
- "reply": a new job to reply to or message someone.
- "answer": the owner answers the open question or details request (see OPEN).
- "confirm": the owner says yes to the open readback or approval ("yes", "send it", "go ahead").
- "decline": the owner says no to the open readback or approval ("no", "don't send it").
- "cancel": the owner withdraws ("never mind", "cancel").
- "none": not addressed to Cyclone, small talk, or nothing to do.
- "unclear": a job is meant but something essential is missing (who, what, when). Put the one short question in
  "missing".

goal: one clean imperative sentence for the phone agent, in the owner's language, keeping names, times and wording
exactly (for "answer": the answer itself; when the owner changes a draft, "Change the message to: ..."). Timers,
alarms, calendar events and contact lookups are done directly (${DIRECT_TOOLS.joinToString(", ")}): phrase them plainly,
e.g. "Set a timer for 10 minutes." Never add steps the owner did not ask for.
ack: at most 8 words, spoken back at once, e.g. "Setting a 10 minute timer." or "Replying to Louella."
missing: only for "unclear", at most 15 words.
confidence: 0 to 1.
""".trim()

    fun userPrompt(transcript: String, context: VoiceContext = VoiceContext()): String = buildString {
        context.followUp?.let {
            appendLine("EARLIER the owner said: \"${it.earlier}\"")
            appendLine("Cyclone asked: \"${it.question}\"")
            appendLine("Combine the earlier words and this answer into one request.")
        }
        context.open?.let {
            appendLine("OPEN (${it.kind}): \"${it.spoken}\"")
            if (it.choices.isNotEmpty()) appendLine("Options: ${it.choices.joinToString(" | ")}")
        }
        if (context.taskLive && context.open == null) appendLine("A task is running now.")
        if (context.recentGoals.isNotEmpty()) appendLine("Recent requests: ${context.recentGoals.takeLast(3).joinToString(" | ")}")
        if (context.language != "auto") appendLine("The owner speaks ${context.language}.")
        append("SPOKEN: \"").append(transcript.take(VoiceRules.MAX_TRANSCRIPT_CHARS)).append('"')
    }

    /** OpenRouter `response_format` with a strict JSON schema. */
    fun responseFormat(): JSONObject = JSONObject()
        .put("type", "json_schema")
        .put("json_schema", JSONObject()
            .put("name", "voice_request")
            .put("strict", true)
            .put("schema", JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put("required", JSONArray(listOf("kind", "goal", "ack", "missing", "confidence")))
                .put("properties", JSONObject()
                    .put("kind", JSONObject().put("type", "string").put("enum", JSONArray(VoiceKind.entries.map { it.wire })))
                    .put("goal", JSONObject().put("type", "string"))
                    .put("ack", JSONObject().put("type", "string"))
                    .put("missing", JSONObject().put("type", "string"))
                    .put("confidence", JSONObject().put("type", "number")))))

    /** The model's reply, strictly. Malformed, unknown or empty where it matters means unclear. */
    fun parse(content: String?): Understanding {
        val json = extractJson(content) ?: return Understanding.unclear()
        val kind = VoiceKind.fromWire(json.optString("kind")) ?: return Understanding.unclear()
        val goal = json.optString("goal").replace(Regex("\\s+"), " ").trim().take(MAX_GOAL_CHARS)
        val ack = VoiceCopy.clip(json.optString("ack").trim(), VoiceCopy.ACK_WORDS)
        val missing = VoiceCopy.clip(json.optString("missing").trim(), VoiceCopy.QUESTION_WORDS)
        val confidence = json.optDouble("confidence", 0.0).takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0) ?: 0.0
        return when (kind) {
            VoiceKind.TASK, VoiceKind.REPLY, VoiceKind.ANSWER ->
                if (goal.isBlank() || confidence < MIN_CONFIDENCE) Understanding.unclear(missing) else Understanding(kind, goal, ack, "", confidence)
            VoiceKind.UNCLEAR -> Understanding.unclear(missing)
            else -> Understanding(kind, goal, ack, "", confidence)
        }
    }

    private fun extractJson(content: String?): JSONObject? {
        val text = content?.trim()?.removePrefix("```json")?.removePrefix("```")?.removeSuffix("```")?.trim() ?: return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
    }

    const val MAX_GOAL_CHARS = 600
    const val MIN_CONFIDENCE = 0.35
}
