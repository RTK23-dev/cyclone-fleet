package com.cyclone.mobile.mind.decide

import com.cyclone.mobile.mind.modes.InstantGrammar
import kotlin.math.exp
import kotlin.math.ln

/**
 * The phone model (alpha 89): a small intent and mode classifier that runs on the phone in a few milliseconds, with no
 * network. It is taught by JEV (and later by OpenAI Decisions): it learns from the requests JEV and the grammar decided
 * and the phone then verified, plus built-in examples in English and Dutch.
 *
 * It answers the same typed questions as the decision box — mode, action, target, confidence — and it can only pick:
 * a target is always one of the phone's own candidates (an on-screen label or an installed app), never text it made
 * up. It acts on its own only for actions it has earned ([Earning]); everything else goes to JEV. Pure.
 *
 * Inside: multinomial naive Bayes over word, word-pair and letter-triple features. The letter triples make it robust to
 * speech-to-text slips ("opne camera") and to Dutch word forms. Swappable later for a small neural encoder behind the
 * same [guess], trained on the same lessons.
 */
class PhoneModel private constructor(private val modes: Bayes, private val intents: Bayes, val examples: Int) {

    /** The model's decision for [text], choosing a target from [candidates] when the action needs one. */
    fun guess(text: String, candidates: List<String>): Guess {
        // The app or label the request names is read from the phone's candidates first and replaced by "thing", exactly
        // as in training: the model classifies the shape of the request, not the name.
        val named = target(text, candidates)
        val features = features(neutral(text, named?.first))
        if (features.isEmpty()) return Guess("mind", "none", null, 0.0)
        val (mode, modeP) = modes.best(features)
        if (mode != "instant") return Guess(mode, "none", null, modeP)
        val (intent, intentP) = intents.best(features)
        if (intent == "none") return Guess("instant", "none", null, 0.0)
        if (intent !in NEEDS_TARGET) return Guess("instant", intent, null, minOf(modeP, intentP))
        val (target, targetScore) = named ?: return Guess("instant", intent, null, 0.0)
        return Guess("instant", intent, target, minOf(modeP, intentP, targetScore))
    }

    companion object {
        /** Actions whose target the model picks from the phone's candidates. */
        val NEEDS_TARGET = setOf("tap", "open_app")
        /** The model's actions: the Instant catalogue the router's decision box uses, without the ones that need arguments only the grammar reads (a contact, a time). */
        val INTENTS = listOf("swipe_up", "swipe_down", "swipe_left", "swipe_right", "scroll_up", "scroll_down", "back", "home", "recents",
            "tap", "open_app", "camera", "photo", "selfie", "flashlight_on", "flashlight_off", "volume_up", "volume_down",
            "media_play_pause", "media_next")
        val MODES = listOf("instant", "flash", "mind", "ignore")

        /** Trains on the built-in examples plus every lesson that teaches (see [Lesson.teaches]). */
        fun train(lessons: List<Lesson>, bar: Double): PhoneModel {
            val taught = lessons.filter { it.teaches(bar) }.map { l ->
                val intent = if (l.decision.mode == "instant" && l.decision.intent in INTENTS) l.decision.intent else "none"
                Example(neutral(l.request, l.decision.target), l.decision.mode, intent)
            }.filter { it.mode in MODES }
            val all = PhoneModelSeeds.EXAMPLES + taught
            val modes = Bayes(MODES).also { b -> all.forEach { b.add(it.mode, features(it.text)) } }
            val intents = Bayes(INTENTS + "none").also { b -> all.filter { it.mode == "instant" }.forEach { b.add(it.intent, features(it.text)) } }
            return PhoneModel(modes, intents, all.size)
        }

        /** A request as a training example: its target replaced by a placeholder, so "open Spotify" teaches "open <app>". */
        fun neutral(text: String, target: String?): String {
            if (target.isNullOrBlank()) return text
            val t = InstantGrammar.normalize(text)
            val g = InstantGrammar.normalize(target)
            if (g.isNotBlank() && t.contains(g)) return t.replace(g, "thing")
            // A spoken name that only resembles the label ("pokemon go" for "Pokémon GO", "whats app"): replace the
            // words that matched it best.
            val words = t.split(' ')
            val span = (1..minOf(4, words.size)).flatMap { n -> words.indices.take(words.size - n + 1).map { it until it + n } }
                .maxByOrNull { r -> InstantGrammar.similarity(words.slice(r).joinToString(" "), target) } ?: return t
            return (words.take(span.first) + "thing" + words.drop(span.last + 1)).joinToString(" ")
        }

        /** Words, word pairs and letter triples of the normalised request. */
        fun features(text: String): List<String> {
            val words = InstantGrammar.normalize(text).split(' ').filter { it.isNotBlank() }
            if (words.isEmpty()) return emptyList()
            val out = ArrayList<String>(words.size * 6)
            words.forEach { w -> out += "w:$w"; "^$w$".windowed(3).forEach { out += "c:$it" } }
            words.zipWithNext().forEach { (a, b) -> out += "b:$a $b" }
            return out
        }

        /**
         * The candidate the request names: the best match of any run of words against the phone's labels and apps. Only
         * a clear, unique match counts (the grammar's own bar and lead), else no target.
         */
        fun target(text: String, candidates: List<String>): Pair<String, Double>? {
            val words = InstantGrammar.normalize(text).split(' ').filter { it.isNotBlank() && it !in VERBS }
            if (words.isEmpty() || candidates.isEmpty()) return null
            val spans = (1..minOf(4, words.size)).flatMap { n -> words.windowed(n).map { it.joinToString(" ") } }
            val scored = candidates.distinct().map { c -> c to spans.maxOf { InstantGrammar.similarity(it, c) } }
                .filter { it.second >= InstantGrammar.MATCH }.sortedByDescending { it.second }
            val top = scored.firstOrNull() ?: return null
            if (scored.drop(1).any { top.second - it.second < InstantGrammar.LEAD }) return null
            return top
        }

        private val VERBS = setOf("open", "launch", "start", "tap", "click", "press", "hit", "select", "go", "to", "the", "my", "on", "app",
            "button", "up", "pull", "bring", "show", "me", "please", "can", "you", "de", "het", "mijn", "open", "klik", "druk", "tik", "op",
            "naar", "ga", "start", "knop", "laat", "zien", "even")
    }

    data class Example(val text: String, val mode: String, val intent: String)

    /** Multinomial naive Bayes with add-one smoothing; posteriors by softmax of the log scores. */
    class Bayes(private val classes: List<String>) {
        private val counts = HashMap<String, HashMap<String, Int>>()
        private val totals = HashMap<String, Int>()
        private val docs = HashMap<String, Int>()
        private val vocab = HashSet<String>()

        fun add(label: String, features: List<String>) {
            if (label !in classes || features.isEmpty()) return
            val c = counts.getOrPut(label) { HashMap() }
            features.forEach { f -> c[f] = (c[f] ?: 0) + 1; vocab += f }
            totals[label] = (totals[label] ?: 0) + features.size
            docs[label] = (docs[label] ?: 0) + 1
        }

        /** The most likely class and its posterior probability, among classes that have examples. */
        fun best(features: List<String>): Pair<String, Double> {
            val seen = classes.filter { (docs[it] ?: 0) > 0 }
            if (seen.isEmpty()) return (classes.lastOrNull() ?: "none") to 0.0
            val allDocs = seen.sumOf { docs[it] ?: 0 }.toDouble()
            val v = vocab.size.toDouble()
            val scores = seen.map { cls ->
                val c = counts[cls].orEmpty()
                val total = (totals[cls] ?: 0).toDouble()
                cls to (ln((docs[cls] ?: 0) / allDocs) + features.sumOf { f -> if (f in vocab) ln(((c[f] ?: 0) + 1.0) / (total + v)) else 0.0 })
            }
            val max = scores.maxOf { it.second }
            // Tempered softmax: naive Bayes is overconfident, so its log scores are scaled down before normalising.
            val weights = scores.map { it.first to exp((it.second - max) / TEMPERATURE) }
            val sum = weights.sumOf { it.second }
            return weights.maxBy { it.second }.let { it.first to it.second / sum }
        }

        companion object { const val TEMPERATURE = 1.6 }
    }
}

/**
 * When the phone model may act without JEV (alpha 89). Each action is earned separately: the model must have agreed
 * with its teacher on enough real requests where it was sure, and it loses the right again after failures of its own.
 * Pure.
 */
object Earning {
    const val MIN_SAMPLES = 50
    const val MIN_AGREEMENT = 0.98
    /** How sure the model must be for a shadow guess to count, and to act. */
    const val SURE = 0.9
    /** Phone decisions that failed: this many among its last [RECENT] for an action takes the right away again. */
    const val MAX_FAILURES = 1
    const val RECENT = 50
    /** One in this many earned decisions is still asked of JEV, so agreement stays measured. */
    const val AUDIT_EVERY = 5

    data class Record(val intent: String, val samples: Int, val agreed: Int, val phoneDecisions: Int, val phoneFailures: Int) {
        val agreement: Double get() = if (samples == 0) 0.0 else agreed.toDouble() / samples
        val earned: Boolean get() = samples >= MIN_SAMPLES && agreement >= MIN_AGREEMENT && phoneFailures <= MAX_FAILURES
    }

    fun records(lessons: List<Lesson>, bar: Double): List<Record> {
        val intents = (lessons.mapNotNull { it.shadow?.intent } + lessons.filter { it.decider == Decider.PHONE }.map { it.decision.intent })
            .filter { it in PhoneModel.INTENTS }.distinct()
        return intents.map { intent ->
            val shadows = lessons.filter { l -> l.shadow?.let { it.mode == "instant" && it.intent == intent && it.confidence >= SURE } == true && l.teaches(bar) }
            val own = lessons.filter { it.decider == Decider.PHONE && it.decision.intent == intent }.takeLast(RECENT)
            Record(intent, shadows.size, shadows.count { it.shadow!!.agrees(it.decision) }, own.size,
                own.count { it.outcome == Outcome.FAILED || it.outcome == Outcome.PROMOTED })
        }.sortedBy { it.intent }
    }

    fun earned(lessons: List<Lesson>, bar: Double): Set<String> = records(lessons, bar).filter { it.earned }.map { it.intent }.toSet()
}
