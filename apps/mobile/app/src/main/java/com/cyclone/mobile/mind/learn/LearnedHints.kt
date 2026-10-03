package com.cyclone.mobile.mind.learn

import com.cyclone.mobile.applearner.AppKnowledgeStore
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.LearnedScreen
import com.cyclone.mobile.applearner.LearnedTransition

/** Read side of learned app knowledge. Production: the phone's app knowledge store. */
interface LearnedReader {
    fun screens(packageName: String): List<LearnedScreen>
    fun actions(packageName: String): List<LearnedAction>
    fun transitions(packageName: String): List<LearnedTransition>
}

class AppKnowledgeReader(private val store: AppKnowledgeStore) : LearnedReader {
    override fun screens(packageName: String) = store.listScreens(packageName)
    override fun actions(packageName: String) = store.listActions(packageName)
    override fun transitions(packageName: String) = store.listTransitions(packageName)
}

/**
 * What Learn taught Cyclone about the screen the Mind is looking at: the moves that worked from here before and the
 * other screens it knows in this app. It is advice, never a replacement for the screen: the Mind still acts on the
 * refs it sees and re-reads the screen after every move. Structure only (control and screen names), no content.
 */
class LearnedHints(private val reader: LearnedReader) {
    private val cache = HashMap<String, Triple<List<LearnedScreen>, Map<String, LearnedAction>, List<LearnedTransition>>>()

    fun forScreen(packageName: String, pageKey: String): String? {
        if (packageName.isBlank() || pageKey.isBlank()) return null
        val (screens, actions, transitions) = cache.getOrPut(packageName) {
            Triple(reader.screens(packageName), reader.actions(packageName).associateBy { it.id }, reader.transitions(packageName))
        }
        if (screens.isEmpty()) return null
        val here = screens.firstOrNull { it.recognition.semanticFingerprint == pageKey } ?: return null
        val byId = screens.associateBy { it.id }
        val known = actions.values.count { it.screenId == here.id }
        val moves = transitions.filter { it.fromScreenId == here.id && it.successfulCount > 0 && it.toScreenId != here.id }
            .sortedByDescending { it.successfulCount }
            .mapNotNull { t ->
                val label = actions[t.actionId]?.label?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val to = byId[t.toScreenId]?.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                "“$label” → $to" + if (t.successfulCount > 1) " (worked ${t.successfulCount}×)" else ""
            }.distinct().take(MAX_MOVES)
        val elsewhere = screens.filter { it.id != here.id }.map { it.title }.filter { it.isNotBlank() }.distinct().take(MAX_SCREENS)
        return buildString {
            append("Learned before: Cyclone knows this screen (${known} control${if (known == 1) "" else "s"}).")
            if (moves.isNotEmpty()) append("\nMoves that worked from here: ").append(moves.joinToString("; "))
            if (elsewhere.isNotEmpty()) append("\nOther learned screens in this app: ").append(elsewhere.joinToString(", "))
            append("\nThis is advice; the refs above are what is really on screen.")
        }
    }

    companion object {
        private const val MAX_MOVES = 8
        private const val MAX_SCREENS = 10
    }
}
