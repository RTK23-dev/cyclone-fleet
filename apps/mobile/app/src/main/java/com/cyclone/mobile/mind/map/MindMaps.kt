package com.cyclone.mobile.mind.map

import com.cyclone.mobile.applearner.AppKnowledgeStore
import com.cyclone.mobile.mind.learn.LearnedReader

/**
 * The maps one mission uses: built lazily per app from learned knowledge and dropped after feedback, so a route that
 * just failed is not offered again in the same mission.
 */
class MindMaps(private val reader: LearnedReader, private val feedbackFor: (String) -> MapFeedback? = { null }) {
    private val cache = HashMap<String, MindMap?>()
    private val shown = HashSet<String>()

    fun map(packageName: String): MindMap? = synchronized(cache) {
        if (packageName.isBlank()) return null
        cache.getOrPut(packageName) { runCatching { MindMap.from(reader, packageName) }.getOrNull() }
    }

    /** True the first time a map card is due for this app in this mission. */
    fun firstVisit(packageName: String): Boolean = synchronized(cache) { shown.add(packageName) }

    fun feedback(packageName: String): MapFeedback {
        val inner = feedbackFor(packageName)
        return object : MapFeedback {
            override fun walked(move: MapMove) { inner?.walked(move) }
            override fun diverged(move: MapMove) {
                inner?.diverged(move)
                synchronized(cache) { cache.remove(packageName) }
            }
        }
    }
}

/** Walking confirms a move (it counts as another success); a surprise counts against it and, twice, marks it stale. */
class AppKnowledgeMapFeedback(private val store: AppKnowledgeStore, private val packageName: String) : MapFeedback {
    override fun walked(move: MapMove) {
        val transition = store.listTransitions(packageName).firstOrNull { it.id == move.transitionId } ?: return
        store.upsertTransition(transition.copy(successfulCount = 1, confidence = 0.9, lastObservedAt = System.currentTimeMillis()))
        store.markActionSuccess(move.actionId)
    }

    override fun diverged(move: MapMove) {
        val transition = store.listTransitions(packageName).firstOrNull { it.id == move.transitionId } ?: return
        store.upsertTransition(transition.copy(successfulCount = 0, confidence = 0.2, lastObservedAt = System.currentTimeMillis()))
        store.markActionFailure(move.actionId)
    }
}
