package com.cyclone.mobile.mapping.run

import com.cyclone.mobile.mapping.crawl.MappingIdentity
import com.cyclone.mobile.mind.learn.LearnReport
import com.cyclone.mobile.mind.learn.LearnSink
import com.cyclone.mobile.mind.learn.MissionLearner
import com.cyclone.mobile.mind.learn.MissionTrail

/**
 * Learns a finished mapping pass into app knowledge, once. What the owner's own account walked is trusted like a
 * Learn press; what a test account walked is learned as less trusted until a run on the owner's account confirms it.
 */
class MappingLearning(private val sink: LearnSink, private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile var report: LearnReport? = null
        private set

    @Synchronized
    fun learnOnce(trail: MissionTrail, identity: MappingIdentity?, appLabel: (String) -> String): LearnReport? {
        report?.let { return it }
        if (trail.screens.isEmpty()) return null
        val learned = MissionLearner(sink, confirmed = identity != MappingIdentity.TEST, clock = clock).learn(trail, appLabel)
        report = learned
        return learned
    }
}
