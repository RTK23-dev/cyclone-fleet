package com.cyclone.mobile.mind.modes

import com.cyclone.mobile.mind.MindMemory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan 42 (M1): the decision box, the one primitive of Cyclone's modes. One fast call answers **several typed
 * questions at once**. Each answer is chosen from a list Cyclone prepared on the phone (on-screen labels, installed
 * apps, contacts), with a confidence. It never writes free text, so it can't invent an app, a contact or a button.
 *
 * Pure: requests, the two wire formats (a fast model with a strict schema; a decision endpoint) and tolerant readers.
 * The call itself is `mind/decide/Decisions` on the phone (JEV now; OpenAI Decisions when it is live).
 */
data class BoxQuestion(val id: String, val instructions: String, val choices: List<String>) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_]{0,31}"))) { "question id" }
        require(choices.isNotEmpty() && choices.size <= 64) { "choices" }
    }
}

data class BoxRequest(
    /** What the box is for, in one line. */
    val task: String,
    /** The situation: the request, a compact screen (app words only), what was done. Never secret values. */
    val context: String,
    val questions: List<BoxQuestion>,
    /** A marked screenshot as a data URL, only when labels are weak and the privacy rules allow it. */
    val image: String? = null,
)

data class BoxAnswer(val choice: String, val confidence: Double)

data class BoxReply(val answers: Map<String, BoxAnswer>, val ms: Long = 0) {
    fun choice(id: String): String? = answers[id]?.choice
    fun confidence(id: String): Double = answers[id]?.confidence ?: 0.0
    /** The answer to [id] when it is at least [bar] sure; otherwise null. */
    fun sure(id: String, bar: Double): String? = answers[id]?.takeIf { it.confidence >= bar }?.choice
}

/** One decision-box call. Null means no usable answer (the caller then routes one mode up). */
fun interface DecisionBox {
    fun ask(request: BoxRequest): BoxReply?

    /** Can the box read a screenshot? False for JEV (alpha.78): callers then don't take one. */
    val sees: Boolean get() = false
}

object BoxWire {
    /** Only app words reach a model: secret-looking lines are dropped from the context. */
    fun clean(context: String): String = context.lines().filterNot { MindMemory.looksSecret(it) }.joinToString("\n").take(6_000)

    fun chatBody(model: String, request: BoxRequest): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        request.questions.forEach { q ->
            properties.put(q.id, JSONObject().put("type", "string").put("enum", JSONArray(q.choices)).put("description", q.instructions))
            properties.put("${q.id}_confidence", JSONObject().put("type", "number").put("description", "0 to 1: how sure you are of ${q.id}"))
            required.put(q.id).put("${q.id}_confidence")
        }
        val schema = JSONObject().put("type", "object").put("additionalProperties", false).put("properties", properties).put("required", required)
        val text = buildString {
            appendLine(clean(request.context))
            appendLine()
            request.questions.forEach { q -> appendLine("${q.id}: ${q.instructions} Answers: ${q.choices.joinToString(", ")}") }
        }.trimEnd()
        val user = if (request.image == null) JSONObject().put("role", "user").put("content", text)
        else JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "$text\nThe screenshot shows the screen, with controls labelled."))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", request.image))))
        return JSONObject().put("model", model).put("temperature", 0).put("max_tokens", 60 + 30 * request.questions.size)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", "${request.task} Answer every question with exactly one of its " +
                    "answers and how sure you are (0 to 1). Never guess: when unsure, say so with a low confidence or the answer that means none."))
                .put(user))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "decision_box").put("strict", true).put("schema", schema)))
            .put("provider", JSONObject().put("sort", "latency").put("require_parameters", false))
    }

    fun parseChat(body: String?, request: BoxRequest): BoxReply? {
        val content = runCatching {
            JSONObject(body ?: return null).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        }.getOrNull() ?: return null
        val json = json(content) ?: return null
        val answers = request.questions.mapNotNull { q ->
            val choice = json.optString(q.id).trim().takeIf { it in q.choices } ?: return@mapNotNull null
            q.id to BoxAnswer(choice, json.optDouble("${q.id}_confidence", 0.0).coerceIn(0.0, 1.0))
        }.toMap()
        return answers.takeIf { it.isNotEmpty() }?.let { BoxReply(it) }
    }

    fun decisionsBody(model: String, request: BoxRequest): JSONObject = JSONObject()
        .put("model", model)
        .put("state", JSONObject().put("task", request.task).put("situation", clean(request.context)))
        .put("questions", JSONObject().also { questions ->
            request.questions.forEach { q ->
                questions.put(q.id, JSONObject().put("type", "choice").put("instructions", q.instructions).put("choices", JSONArray(q.choices)))
            }
        })

    /**
     * A decision endpoint's answer, read tolerantly: `answers|decisions|results|output` or the top level, holding each
     * question as a string or `{choice|value|answer|label, confidence|probability|p}`. Answers outside the choices are
     * dropped.
     */
    fun parseDecisions(body: String?, request: BoxRequest): BoxReply? {
        val json = runCatching { JSONObject(body ?: return null) }.getOrNull() ?: return null
        val holder = listOf("answers", "decisions", "results", "output").firstNotNullOfOrNull { json.optJSONObject(it) } ?: json
        val answers = request.questions.mapNotNull { q ->
            val raw = holder.opt(q.id) ?: return@mapNotNull null
            val node = raw as? JSONObject
            val value = node?.let { n -> listOf("choice", "value", "answer", "label").firstNotNullOfOrNull { k -> n.optString(k).takeIf { it.isNotBlank() } } }
                ?: (raw as? String) ?: return@mapNotNull null
            val confidence = node?.let { n -> listOf("confidence", "probability", "p").firstNotNullOfOrNull { k -> n.optDouble(k, Double.NaN).takeIf { !it.isNaN() } } } ?: 0.0
            value.trim().takeIf { it in q.choices }?.let { q.id to BoxAnswer(it, confidence.coerceIn(0.0, 1.0)) }
        }.toMap()
        return answers.takeIf { it.isNotEmpty() }?.let { BoxReply(it) }
    }

    private fun json(text: String): JSONObject? {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { return JSONObject(trimmed) }
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) runCatching { JSONObject(trimmed.substring(start, end + 1)) }.getOrNull() else null
    }
}
