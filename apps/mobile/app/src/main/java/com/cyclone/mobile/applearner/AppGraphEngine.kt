package com.cyclone.mobile.applearner

import android.content.Context
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.automation.AutomationDefinition
import com.cyclone.mobile.automation.AutomationRuntime
import com.cyclone.mobile.automation.FailureAction
import com.cyclone.mobile.automation.RecoveryPolicy
import com.cyclone.mobile.automation.Selector
import com.cyclone.mobile.automation.SkillDefinition
import com.cyclone.mobile.automation.StepDefinition
import com.cyclone.mobile.automation.StepType
import com.cyclone.mobile.automation.TriggerDefinition
import com.cyclone.mobile.automation.TriggerType
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

object AppGraphRetriever {
    data class Path(
        val start: LearnedScreen,
        val end: LearnedScreen,
        val hops: List<Pair<LearnedAction, LearnedTransition>>,
        val score: Double,
    ) {
        fun titles(graph: AppGraphSnapshot): List<String> = buildList {
            add(start.title)
            hops.forEach { (_, t) -> graph.screens.firstOrNull { it.id == t.toScreenId }?.title?.let(::add) }
        }
    }

    fun retrieve(graph: AppGraphSnapshot, goal: String, currentScreenId: String? = null, maxItems: Int = 20): JSONObject {
        val terms = goalTerms(goal)
        val relevantScreens = graph.screens.map { screen -> screen to screenScore(screen, terms) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(maxItems)
        val relevantIds = relevantScreens.map { it.first.id }.toSet()
        val relevantTransitions = graph.transitions.filter { it.fromScreenId in relevantIds || it.toScreenId in relevantIds }
            .sortedByDescending { it.confidence }
            .take(maxItems * 2)
        return JSONObject()
            .put("protocol", "cyclone-app-graph-retrieval-v1")
            .put("app", graph.app.label)
            .put("package", graph.app.packageName)
            .put("goal", goal)
            .put("currentScreenId", currentScreenId ?: JSONObject.NULL)
            .put("screens", JSONArray().also { arr -> relevantScreens.forEach { (s, score) -> arr.put(s.toJson().put("relevance", score)) } })
            .put("transitions", JSONArray().also { arr -> relevantTransitions.forEach { arr.put(it.toJson()) } })
            .put("availableActions", JSONArray().also { arr ->
                graph.actions.filter { it.screenId == currentScreenId || it.screenId in relevantIds }
                    .sortedByDescending { actionScore(it, terms) + it.confidence }
                    .take(maxItems * 2)
                    .forEach { arr.put(it.toJson()) }
            })
    }

    fun findBestPath(graph: AppGraphSnapshot, goal: String, currentScreenId: String? = null, maxDepth: Int = 8): Path? {
        if (graph.screens.isEmpty()) return null
        val terms = goalTerms(goal)
        val starts = when {
            currentScreenId != null -> graph.screens.filter { it.id == currentScreenId }
            else -> graph.screens.sortedWith(compareByDescending<LearnedScreen> { rootLikelihood(it) }.thenBy { it.lastSeenAt }).take(4)
        }
        if (starts.isEmpty()) return null
        var best: Path? = null
        for (start in starts) {
            val queue = ArrayDeque<Pair<String, List<Pair<LearnedAction, LearnedTransition>>>>()
            queue.add(start.id to emptyList())
            val seen = mutableSetOf(start.id)
            while (queue.isNotEmpty()) {
                val (screenId, hops) = queue.removeFirst()
                val screen = graph.screens.firstOrNull { it.id == screenId } ?: continue
                val endpointScore = screenScore(screen, terms) + hops.sumOf { (action, transition) ->
                    actionScore(action, terms) * 0.12 + transition.confidence * 0.08
                } - hops.size * 0.03
                if (hops.isNotEmpty() && endpointScore > (best?.score ?: Double.NEGATIVE_INFINITY)) {
                    best = Path(start, screen, hops, endpointScore)
                }
                if (hops.size >= maxDepth) continue
                graph.outgoing(screenId)
                    .filter { (action, transition) ->
                        action.risk != ActionRisk.AUTHENTICATION && action.risk != ActionRisk.CROSS_APP &&
                            transition.knowledgeState != KnowledgeState.STALE
                    }
                    .sortedByDescending { (action, transition) -> actionScore(action, terms) + transition.confidence }
                    .forEach { pair ->
                        val to = pair.second.toScreenId
                        if (to !in seen) {
                            seen += to
                            queue.add(to to (hops + pair))
                        }
                    }
            }
        }
        return best?.takeIf { it.score > 0.02 }
    }

    fun answerLocally(graph: AppGraphSnapshot, question: String): String {
        val path = findBestPath(graph, question)
        if (path != null) {
            val route = path.titles(graph).joinToString(" → ")
            val confidence = (path.hops.map { it.second.confidence }.average().takeIf { !it.isNaN() } ?: path.end.confidence)
            return "Cyclone knows a route: $route. Confidence ${(confidence * 100).toInt()}%."
        }
        val terms = goalTerms(question)
        val actions = graph.actions.sortedByDescending { actionScore(it, terms) }.filter { actionScore(it, terms) > 0.0 }.take(5)
        if (actions.isNotEmpty()) {
            return "Cyclone knows these relevant actions: ${actions.joinToString { it.label }}. It may need more learning to connect them into a verified route."
        }
        return "Cyclone does not have enough learned knowledge to answer this yet. Continue learning this app with a focused task instruction."
    }

    private fun rootLikelihood(screen: LearnedScreen): Double {
        val text = (screen.title + " " + screen.identity).lowercase()
        return when {
            "home" in text || "main" in text -> 2.0
            "dashboard" in text -> 1.5
            else -> 0.2 + (1.0 - screen.lastSeenAt.toDouble() / Long.MAX_VALUE)
        }
    }

    private fun screenScore(screen: LearnedScreen, terms: Set<String>): Double {
        if (terms.isEmpty()) return screen.confidence * 0.1
        val hay = tokenize(screen.title + " " + screen.identity + " " + screen.purpose + " " + screen.recognition.stableAnchors.joinToString(" "))
        return terms.sumOf { term -> if (term in hay) 1.0 else if (hay.any { it.contains(term) || term.contains(it) }) 0.35 else 0.0 } + screen.confidence * 0.15
    }

    private fun actionScore(action: LearnedAction, terms: Set<String>): Double {
        if (terms.isEmpty()) return action.confidence * 0.1
        val hay = tokenize(action.label + " " + action.semanticName)
        val semantic = terms.sumOf { term -> if (term in hay) 1.0 else if (hay.any { it.contains(term) || term.contains(it) }) 0.35 else 0.0 }
        val safety = when (action.risk) {
            ActionRisk.SAFE -> 0.25
            ActionRisk.UNKNOWN -> 0.0
            ActionRisk.CONSEQUENTIAL -> -0.4
            ActionRisk.AUTHENTICATION, ActionRisk.CROSS_APP -> -2.0
        }
        return semantic + safety
    }

    internal fun goalTerms(value: String): Set<String> = tokenize(value).filterNot { it in STOP_WORDS }.toSet()
    private fun tokenize(value: String): Set<String> = value.lowercase().split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 }.toSet()
    private val STOP_WORDS = setOf("the", "and", "for", "with", "from", "this", "that", "how", "can", "you", "app", "open", "find", "learn", "where", "into", "about", "latest", "newest")
}

object GraphAutomationCompiler {
    fun compile(graph: AppGraphSnapshot, path: AppGraphRetriever.Path, name: String): AutomationDefinition {
        val steps = mutableListOf<StepDefinition>()
        steps += StepDefinition(
            name = "Open ${graph.app.label}",
            type = StepType.PHONE_TOOL,
            parameters = mapOf("tool" to "phone.open_app", "package" to graph.app.packageName),
            recovery = RecoveryPolicy(maxRetries = 1, onFailure = FailureAction.REQUEST_AI_HELP),
        )
        path.hops.forEachIndexed { index, (action, transition) ->
            val target = graph.screens.firstOrNull { it.id == transition.toScreenId }
            steps += StepDefinition(
                name = "${index + 1}. ${action.label}",
                type = StepType.PHONE_TOOL,
                parameters = mapOf("tool" to "phone.click"),
                selector = selectorFromJson(action.selectorJson),
                confirmationRequired = action.risk == ActionRisk.CONSEQUENTIAL,
                recovery = RecoveryPolicy(maxRetries = 1, onFailure = FailureAction.REQUEST_AI_HELP),
            )
            val title = target?.title?.takeIf { it.isNotBlank() && it != "Screen" }
            if (title != null) {
                steps += StepDefinition(
                    name = "Verify ${target.title}",
                    type = StepType.PHONE_TOOL,
                    parameters = mapOf("tool" to "phone.wait_for", "type" to "selector_exists", "timeoutMs" to "5000"),
                    selector = Selector(partialText = title),
                    recovery = RecoveryPolicy(maxRetries = 1, onFailure = FailureAction.REQUEST_AI_HELP),
                )
            }
        }
        return AutomationDefinition(
            name = name,
            description = "Generated from Cyclone App Learner's persisted graph for ${graph.app.label}. Path: ${path.titles(graph).joinToString(" → ")}",
            enabled = false,
            trigger = TriggerDefinition(TriggerType.MANUAL),
            steps = steps,
            failureBehavior = FailureAction.REQUEST_AI_HELP,
        )
    }

    private fun selectorFromJson(raw: String): Selector {
        val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        return Selector(
            resourceId = json.optString("resourceId").takeIf { it.isNotBlank() },
            text = json.optString("text").takeIf { it.isNotBlank() },
            partialText = json.optString("textContains").takeIf { it.isNotBlank() },
            contentDescription = json.optString("contentDescription").takeIf { it.isNotBlank() },
            contentDescriptionContains = json.optString("contentDescriptionContains").takeIf { it.isNotBlank() },
            role = json.optString("role").takeIf { it.isNotBlank() },
            className = json.optString("class").takeIf { it.isNotBlank() },
            x = json.optInt("x").takeIf { json.has("x") },
            y = json.optInt("y").takeIf { json.has("y") },
            requireClickable = json.optBoolean("clickable").takeIf { json.has("clickable") },
            requireEditable = json.optBoolean("editable").takeIf { json.has("editable") },
            requireScrollable = json.optBoolean("scrollable").takeIf { json.has("scrollable") },
        )
    }
}

object SkillCandidateGenerator {
    fun candidates(graph: AppGraphSnapshot): List<SkillCandidate> {
        val seen = mutableSetOf<String>()
        return graph.transitions.asSequence()
            .filter { it.confidence >= 0.68 && it.knowledgeState != KnowledgeState.STALE }
            .mapNotNull { transition ->
                val action = graph.actions.firstOrNull { it.id == transition.actionId } ?: return@mapNotNull null
                val target = graph.screens.firstOrNull { it.id == transition.toScreenId } ?: return@mapNotNull null
                if (action.risk != ActionRisk.SAFE || action.requiredInput != null) return@mapNotNull null
                val key = action.semanticName + "|" + target.identity
                if (!seen.add(key)) return@mapNotNull null
                SkillCandidate(
                    packageName = graph.app.packageName,
                    name = action.semanticName,
                    description = "Use ${action.label} to reach ${target.title} in ${graph.app.label}.",
                    transitionIds = listOf(transition.id),
                    confidence = minOf(action.confidence, transition.confidence, target.confidence),
                    state = if (transition.knowledgeState == KnowledgeState.VERIFIED && action.knowledgeState == KnowledgeState.VERIFIED) KnowledgeState.VERIFIED else KnowledgeState.UNDERSTOOD,
                )
            }
            .sortedByDescending { it.confidence }
            .take(20)
            .toList()
    }

    fun saveAsSkill(context: Context, graph: AppGraphSnapshot, candidate: SkillCandidate): SkillDefinition? {
        val transitions = candidate.transitionIds.mapNotNull { id -> graph.transitions.firstOrNull { it.id == id } }
        if (transitions.isEmpty()) return null
        val steps = transitions.mapNotNull { t ->
            val action = graph.actions.firstOrNull { it.id == t.actionId } ?: return@mapNotNull null
            StepDefinition(
                name = action.label,
                type = StepType.PHONE_TOOL,
                parameters = mapOf("tool" to "phone.click"),
                selector = runCatching { JSONObject(action.selectorJson) }.getOrNull()?.let { json ->
                    Selector(
                        resourceId = json.optString("resourceId").takeIf { it.isNotBlank() },
                        text = json.optString("text").takeIf { it.isNotBlank() },
                        contentDescription = json.optString("contentDescription").takeIf { it.isNotBlank() },
                        role = json.optString("role").takeIf { it.isNotBlank() },
                        x = json.optInt("x").takeIf { json.has("x") }, y = json.optInt("y").takeIf { json.has("y") },
                    )
                },
                recovery = RecoveryPolicy(maxRetries = 1, onFailure = FailureAction.REQUEST_AI_HELP),
            )
        }
        if (steps.isEmpty()) return null
        val skill = SkillDefinition(
            name = candidate.name,
            description = candidate.description,
            steps = steps,
            enabled = candidate.state == KnowledgeState.VERIFIED,
        )
        AutomationRuntime.initialize(context)
        AutomationRuntime.store.saveSkill(skill)
        return skill
    }
}

object AppGraphHealer {
    data class Recovery(val action: LearnedAction, val newSelectorJson: String, val reason: String)

    fun recover(context: Context, store: AppKnowledgeStore, action: LearnedAction): Recovery? {
        store.markActionFailure(action.id)
        val fuzzy = JSONObject()
            .put("selector", JSONObject().put("fuzzyText", action.label).put("minFuzzyScore", 0.58).put("clickable", true))
            .put("limit", 5)
        val result = PhoneToolExecutor.execute(context, PhoneToolRequest("app-heal-${UUID.randomUUID()}", "phone.find", fuzzy))
        val matches = result.payload as? JSONArray ?: return null
        val best = matches.optJSONObject(0)?.optJSONObject("node") ?: return null
        val selector = JSONObject().apply {
            best.optString("resourceId").takeIf { it.isNotBlank() }?.let { put("resourceId", it) }
            best.optString("text").takeIf { it.isNotBlank() }?.let { put("text", it) }
            best.optString("contentDescription").takeIf { it.isNotBlank() }?.let { put("contentDescription", it) }
            best.optString("role").takeIf { it.isNotBlank() }?.let { put("role", it) }
            put("clickable", true)
        }
        if (selector.length() <= 1) return null
        store.markActionSuccess(action.id, selector.toString())
        return Recovery(store.getAction(action.id) ?: action, selector.toString(), "Recovered from fresh Accessibility semantics")
    }
}

class AppGraphExecutor(private val context: Context, private val store: AppKnowledgeStore) {
    fun execute(packageName: String, goal: String): Result<String> = runCatching {
        val graph = store.graph(packageName) ?: error("No learned map for $packageName")
        val path = AppGraphRetriever.findBestPath(graph, goal) ?: error("No learned path for '$goal'")
        val open = PhoneToolExecutor.execute(context, PhoneToolRequest("graph-open-${UUID.randomUUID()}", "phone.open_app", JSONObject().put("package", packageName)))
        check(open.ok) { open.error?.message ?: "Could not open app" }
        for ((action, _) in path.hops) {
            val params = JSONObject().put("selector", JSONObject(action.selectorJson)).put("retries", 1)
            var click = PhoneToolExecutor.execute(context, PhoneToolRequest("graph-click-${UUID.randomUUID()}", "phone.click", params))
            if (!click.ok) {
                val healed = AppGraphHealer.recover(context, store, action)
                if (healed != null) {
                    click = PhoneToolExecutor.execute(context, PhoneToolRequest("graph-healed-${UUID.randomUUID()}", "phone.click", JSONObject().put("selector", JSONObject(healed.newSelectorJson))))
                }
            }
            check(click.ok) { click.error?.message ?: "Learned action failed: ${action.label}" }
            store.markActionSuccess(action.id)
        }
        store.mirror(packageName)
        "Opened ${path.end.title} using learned route ${path.titles(graph).joinToString(" → ")}"
    }
}
