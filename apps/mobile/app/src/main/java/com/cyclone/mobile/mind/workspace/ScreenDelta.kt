package com.cyclone.mobile.mind.workspace

import org.json.JSONArray
import org.json.JSONObject

/**
 * The facts of one screen the workspace compares (plan 37 §4): which app, its title, the visible lines, text field
 * values (never password fields) and control states. Built by the toolbox from an observation; kept in memory only.
 */
data class ScreenFacts(
    val packageName: String,
    val app: String,
    val title: String?,
    val texts: List<String>,
    val fields: Map<String, String> = emptyMap(),
    val states: Map<String, String> = emptyMap(),
) {
    /** Equal for screens that look the same to the model: same app, title, lines, field values and states. */
    val signature: String by lazy {
        (listOf(packageName, title.orEmpty()) + texts + fields.map { "${it.key}=${it.value}" }.sorted() +
            states.map { "${it.key}=${it.value}" }.sorted()).joinToString("\u0001").hashCode().toString(16)
    }

    /** Every word the model could have meant, for expectation and done checks. */
    val haystack: String by lazy {
        (listOf(app, title.orEmpty()) + texts + fields.keys + fields.values + states.keys).joinToString("\n").lowercase()
    }

    /** A short sketch for "where I left it": the title and the first few lines and fields. */
    fun sketch(max: Int = 240): String = buildString {
        append(app)
        title?.takeIf { it.isNotBlank() && it != app }?.let { append(" — ").append(it) }
        val lines = texts.take(4).joinToString(" · ")
        if (lines.isNotBlank()) append(": ").append(lines)
        fields.entries.firstOrNull { it.value.isNotBlank() }?.let { append(" · ${it.key} = \"${it.value.take(40)}\"") }
    }.take(max)

    fun toJson(): JSONObject = JSONObject().put("pkg", packageName).put("app", app).put("title", title ?: JSONObject.NULL)
        .put("texts", JSONArray(texts.take(12)))

    companion object {
        fun fromJson(json: JSONObject?): ScreenFacts? = json?.let {
            ScreenFacts(it.optString("pkg"), it.optString("app"), it.optString("title").takeUnless { _ -> it.isNull("title") },
                it.optJSONArray("texts")?.let { a -> (0 until a.length()).map(a::optString) }.orEmpty())
        }
    }
}

/** "What changed since the last screen", at most a few lines (plan 37 §4). */
object ScreenDelta {
    fun lines(before: ScreenFacts?, after: ScreenFacts, max: Int = 6): List<String> {
        if (before == null || before.packageName != after.packageName) return emptyList()
        if (before.signature == after.signature) return listOf("nothing on the screen changed")
        val out = mutableListOf<String>()
        if (before.title != after.title && !after.title.isNullOrBlank()) out += "title now \"${after.title.take(60)}\""
        after.states.forEach { (label, state) ->
            val was = before.states[label]
            if (was != null && was != state) out += "\"${label.take(40)}\" $was → $state"
        }
        after.fields.forEach { (label, value) ->
            val was = before.fields[label]
            if (was != null && was != value) out += "field \"${label.take(40)}\" now " + if (value.isBlank()) "empty" else "\"${value.take(40)}\""
        }
        val old = before.texts.toSet()
        val new = after.texts.toSet()
        val appeared = after.texts.filter { it !in old }.take(3)
        val gone = before.texts.filter { it !in new }.take(2)
        if (appeared.isNotEmpty()) out += "new: " + appeared.joinToString(" · ") { "\"${it.take(50)}\"" }
        if (gone.isNotEmpty()) out += "gone: " + gone.joinToString(" · ") { "\"${it.take(50)}\"" }
        return out.take(max)
    }
}
