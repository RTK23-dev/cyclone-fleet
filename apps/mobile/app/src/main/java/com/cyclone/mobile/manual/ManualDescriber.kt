package com.cyclone.mobile.manual

import com.cyclone.mobile.manual.dictionary.AppDictionary
import com.cyclone.mobile.manual.dictionary.DictionaryPrivacy
import com.cyclone.mobile.manual.dictionary.QuizGoal
import com.cyclone.mobile.manual.dictionary.QuizResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * The describer (plan 36 §5.1, §5.5): one model call after a pass, with the pass's model. It is shown the manual's
 * skeleton in the app's own words only (screen names, panels and what they offer, categories, list shapes, abilities)
 * and writes back three things, as strict JSON:
 * - a one-line purpose for each screen;
 * - other ways a person says each ability;
 * - about 20 plain-language goals a person might have in the app (the self-quiz).
 *
 * The quiz is then answered from the manual alone, by the [AbilityIndex]: a goal the index cannot answer is a gap
 * for "Map deeper". The model only describes; it never admits sets, walks or chooses anything.
 */
object ManualDescriber {
    const val MAX_SCREENS = 40
    const val MAX_ABILITIES = 60
    const val MAX_GOALS = 20

    /** The question as sent: the prompt text and the handles it used ("s3" → room key, "a7" → ability id). */
    data class Question(val system: String, val user: String, val screens: Map<String, String>, val abilities: Map<String, String>)

    data class Answer(val purposes: Map<String, String>, val phrasings: Map<String, List<String>>, val goals: List<String>)

    val SYSTEM = """
        You describe a phone app for other AI agents, from its map. You see only the app's own words: screen names,
        panels and what they offer, categories, list shapes and the things a person can do. Answer with one JSON
        object and nothing else:
        {"screens":[{"s":"s1","purpose":"…"}],"abilities":[{"a":"a1","say":["…","…"]}],"quiz":["…"]}
        - purpose: one plain sentence (at most 15 words) on what the screen is for.
        - say: 3 to 6 other ways a person would ask for that ability (at most 8 words each), in plain English.
        - quiz: $MAX_GOALS different goals a person might have in this app (at most 10 words each), including some the
          map may not cover yet.
        Never invent people, chats, numbers, emails or links. Use only the handles given (s1, a1…).
    """.trimIndent()

    fun question(dict: AppDictionary, appLabel: String, abilities: List<Ability>): Question? {
        val screens = dict.screens.values.filter { it.name != null && !it.isPanel }.sortedByDescending { it.seen }.take(MAX_SCREENS)
        if (screens.isEmpty() && abilities.isEmpty()) return null
        val screenIds = LinkedHashMap<String, String>()
        val abilityIds = LinkedHashMap<String, String>()
        val user = buildString {
            append("App: $appLabel\n\nScreens:\n")
            screens.forEachIndexed { i, card ->
                val handle = "s${i + 1}"
                screenIds[handle] = card.roomKey
                append("$handle ${card.name}")
                val panels = dict.screens.values.filter { it.panelOf == card.roomKey && it.name != null }
                val categories = dict.active().filter { e -> e.anchors.any { it.kind == AnchorKind.VIEW && it.roomKey == card.roomKey } }.map { it.shownName }
                val parts = buildList {
                    if (categories.isNotEmpty()) add("categories " + categories.joinToString(", "))
                    card.list?.let { add("list of rows (${it.shape})" + (ListOrder.fromWire(it.order)?.let { o -> ", ${o.words}" } ?: "") + if (it.searchable) ", searchable" else "") }
                    if (card.items.isNotEmpty()) add("buttons " + card.items.take(8).joinToString(", "))
                    panels.forEach { p -> add("panel “${p.name}” offering " + p.items.take(8).joinToString(", ")) }
                }
                if (parts.isNotEmpty()) append(": ").append(parts.joinToString("; "))
                append("\n")
            }
            append("\nAbilities:\n")
            abilities.sortedByDescending { it.confidence }.take(MAX_ABILITIES).forEachIndexed { i, a ->
                val handle = "a${i + 1}"
                abilityIds[handle] = a.id
                append("$handle ${a.name} (${a.pathText})\n")
            }
        }
        return Question(SYSTEM, user, screenIds, abilityIds)
    }

    /** Strict parse: unknown handles, long or unsafe text are dropped. Null when the reply is not the JSON asked for. */
    fun parse(reply: String, question: Question): Answer? {
        val json = runCatching { JSONObject(reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()) }.getOrNull()
            ?: Regex("\\{[\\s\\S]*\\}").find(reply)?.value?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return null
        val purposes = LinkedHashMap<String, String>()
        json.optJSONArray("screens")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val room = question.screens[o.optString("s")] ?: continue
                DictionaryPrivacy.sentence(o.optString("purpose"), 18, 120)?.let { purposes[room] = it }
            }
        }
        val phrasings = LinkedHashMap<String, List<String>>()
        json.optJSONArray("abilities")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = question.abilities[o.optString("a")] ?: continue
                val says = strings(o.optJSONArray("say")).mapNotNull(DictionaryPrivacy::phrasing).distinctBy { it.lowercase() }.take(AppDictionary.MAX_PHRASINGS)
                if (says.isNotEmpty()) phrasings[id] = says
            }
        }
        val goals = strings(json.optJSONArray("quiz")).mapNotNull(DictionaryPrivacy::goal).distinctBy { it.lowercase() }.take(MAX_GOALS)
        if (purposes.isEmpty() && phrasings.isEmpty() && goals.isEmpty()) return null
        return Answer(purposes, phrasings, goals)
    }

    /** Applies an answer: purposes on screens, phrasings on abilities, then the self-quiz answered from the manual alone. */
    fun apply(dict: AppDictionary, answer: Answer, at: Long): AppDictionary {
        val screens = dict.screens.mapValues { (room, card) ->
            answer.purposes[room]?.let { card.copy(purpose = it) } ?: card
        }
        val next = dict.copy(screens = screens, phrasings = (dict.phrasings + answer.phrasings).entries.take(AppDictionary.MAX_ABILITY_STATS).associate { it.key to it.value })
        return if (answer.goals.isEmpty()) next else next.copy(quiz = SelfQuiz.answer(next, answer.goals, at))
    }

    private fun strings(array: JSONArray?): List<String> = array?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
}

/** The self-quiz (plan 36 §5.5): can the manual alone answer each goal? */
object SelfQuiz {
    fun answer(dict: AppDictionary, goals: List<String>, at: Long): QuizResult {
        val index = AbilityIndex(Abilities.derive(dict))
        return QuizResult(at, goals.take(AppDictionary.MAX_QUIZ).map { goal ->
            val top = index.search(goal, 1).firstOrNull()
            if (top != null && top.score >= AbilityIndex.ANSWER_SCORE) QuizGoal(goal, top.ability.id, top.score) else QuizGoal(goal, null, top?.score ?: 0.0)
        })
    }

    /**
     * Words for "Map deeper" (plan 36 §5.5): the words of the goals the manual could not answer, so the next pass tries
     * doors with those words first. Generic verbs are left out.
     */
    fun focusWords(quiz: QuizResult?): List<String> =
        quiz?.gaps.orEmpty().flatMap { AbilityIndex.terms(listOf(it)) }.filter { it.length > 2 && it !in VERBS }.distinct().take(24)

    private val VERBS = setOf("open", "find", "add", "new", "setting", "delete", "newest", "turn", "change", "use", "see", "send")
}
