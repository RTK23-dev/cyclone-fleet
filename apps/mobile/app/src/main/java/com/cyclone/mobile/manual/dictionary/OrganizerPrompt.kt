package com.cyclone.mobile.manual.dictionary

import com.cyclone.mobile.manual.CoreKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * The organizer's one narrow question, as text for a model and as a typed choice for JEV (plan 36 §7.5). Only the app's
 * own words, core kinds, anchors and existing set ids go in: never a row, a name of a person or anything typed. The
 * answer is read strictly; anything that is not one of the allowed choices is dropped, which keeps the candidate.
 */
object OrganizerPrompt {
    const val SYSTEM = """You keep an app's dictionary of groups tidy. A group is a named set of one kind of thing that the app itself shows (for example "Followers" is a set of people). You are shown new groups the automatic checks could not settle, each with where the app shows it and existing groups it might match.

For each question answer exactly one choice:
- "new": a real, separate group. Give its kind (one of the kinds listed) and, if it belongs under an existing group, that group's id as parent.
- "same_as": the same group as an existing one (another name for it). Give that id as target.
- "subset_of": a part of an existing group (a sub-category). Give that id as target.
- "keep": not sure yet; wait for more evidence.
- "reject": not a group of things (a button, an action, a screen name, a person's name or content).

Be conservative: prefer "same_as" over creating near-duplicates, and "keep" when unsure. Only use ids that are offered in that question.
Reply with JSON only: {"decisions":[{"key":"q1","choice":"new","kind":"person","parent":null,"target":null}, ...]}"""

    fun user(appLabel: String, questions: List<OrganizerQuestion>): String = JSONObject()
        .put("app", appLabel.take(60))
        .put("kinds", JSONArray(CoreKind.entries.filter { it != CoreKind.OTHER }.map { it.wire }))
        .put("questions", JSONArray().also { out -> questions.forEach { out.put(question(it)) } })
        .toString()

    private fun question(q: OrganizerQuestion): JSONObject = JSONObject()
        .put("key", q.key)
        .put("name", q.name)
        .put("kindGuess", if (q.kindGuess == CoreKind.OTHER) JSONObject.NULL else q.kindGuess.wire)
        .put("why_asked", q.reason)
        .put("where", JSONArray(q.where))
        .put("rows_show", JSONArray(q.markers))
        .put("shown_under", q.parentHint ?: JSONObject.NULL)
        .put("existing", JSONArray().also { out -> q.nearby.forEach { out.put(JSONObject().put("id", it.id).put("path", it.path)) } })

    /** Strict reader: unknown keys, choices, kinds or ids are dropped. */
    fun parse(text: String, questions: List<OrganizerQuestion>): List<OrganizerDecision> {
        val json = runCatching { JSONObject(extractJson(text)) }.getOrNull() ?: return emptyList()
        val array = json.optJSONArray("decisions") ?: return emptyList()
        val byKey = questions.associateBy { it.key }
        val out = ArrayList<OrganizerDecision>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val question = byKey[item.optString("key")] ?: continue
            if (out.any { it.key == question.key }) continue
            val choice = Choice.fromWire(item.optString("choice")) ?: continue
            val allowed = question.nearby.map { it.id }.toSet()
            fun id(key: String): String? = item.optString(key).takeIf { !item.isNull(key) && it in allowed }
            val decision = when (choice) {
                Choice.KEEP, Choice.REJECT -> OrganizerDecision(question.key, choice)
                Choice.SAME_AS, Choice.SUBSET_OF -> OrganizerDecision(question.key, choice, targetId = id("target") ?: continue)
                Choice.NEW -> OrganizerDecision(question.key, choice,
                    kind = CoreKind.fromWire(item.optString("kind"))?.takeIf { it != CoreKind.OTHER } ?: question.kindGuess.takeIf { it != CoreKind.OTHER } ?: continue,
                    parentId = if (item.has("parent") && !item.isNull("parent")) id("parent") ?: continue else null)
            }
            out += decision
        }
        return out
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text
    }

    // ---- JEV (watch-only) ----

    const val JEV_MODEL = "~typesafe/jev-latest"

    /** One JEV choice per question, as a string like `new`, `same_as:set:x` or `subset_of:set:x`. */
    fun jevChoices(q: OrganizerQuestion): List<String> =
        listOf("new", "keep", "reject") + q.nearby.flatMap { listOf("same_as:${it.id}", "subset_of:${it.id}") }

    fun jevRequest(appLabel: String, q: OrganizerQuestion): JSONObject = JSONObject()
        .put("model", JEV_MODEL)
        .put("state", JSONObject()
            .put("task", "Keep an app's dictionary of groups tidy: decide what a newly seen group is.")
            .put("app", appLabel.take(60))
            .put("group", q.name)
            .put("where", JSONArray(q.where))
            .put("rows_show", JSONArray(q.markers))
            .put("existing", JSONArray(q.nearby.map { "${it.id} = ${it.path}" })))
        .put("questions", JSONObject().put("decision", JSONObject()
            .put("type", "choice")
            .put("instructions", "new: a separate group. same_as:<id>: another name for that group. subset_of:<id>: a part of that group. " +
                "keep: not sure yet. reject: not a group of things (a button, an action, a person's name or content).")
            .put("choices", JSONArray(jevChoices(q)))))

    /** JEV's pick and confidence, read as tolerantly as Drive reads it; null when there is no usable answer. */
    fun jevParse(body: String?, q: OrganizerQuestion): Pair<String, Double>? {
        val json = runCatching { JSONObject(body ?: return null) }.getOrNull() ?: return null
        val holder = listOf("answers", "decisions", "results", "output").firstNotNullOfOrNull { json.optJSONObject(it) } ?: json
        val answer: Any = holder.opt("decision") ?: return null
        val node = answer as? JSONObject
        val value = node?.let { n -> listOf("choice", "value", "answer", "label").firstNotNullOfOrNull { k -> n.optString(k).takeIf { it.isNotBlank() } } }
            ?: (answer as? String) ?: return null
        if (value !in jevChoices(q)) return null
        val confidence = node?.let { n -> listOf("confidence", "probability", "p").firstNotNullOfOrNull { k -> n.optDouble(k, Double.NaN).takeIf { !it.isNaN() } } } ?: 0.0
        return value to confidence
    }

    /** The same pick in JEV's vocabulary, so the two can be compared. */
    fun asJevChoice(decision: OrganizerDecision?): String = when (decision?.choice) {
        null, Choice.KEEP -> "keep"
        Choice.REJECT -> "reject"
        Choice.NEW -> "new"
        Choice.SAME_AS -> "same_as:${decision.targetId}"
        Choice.SUBSET_OF -> "subset_of:${decision.targetId}"
    }
}
