package com.cyclone.mobile.mind.learn

import android.content.Context
import com.cyclone.mobile.applearner.AppKnowledgeStore
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.LearnedApp
import com.cyclone.mobile.applearner.LearnedScreen
import com.cyclone.mobile.applearner.LearnedTransition
import com.cyclone.mobile.applearner.graphv2.AtlasRuntime
import com.cyclone.mobile.mind.mission.MindMissions
import com.cyclone.mobile.mind.mission.Mission

/** The outcome of pressing Learn on a run. */
sealed class LearnOutcome {
    data class Learned(val report: LearnReport, val alreadyLearned: Boolean) : LearnOutcome()
    data class Refused(val code: String, val message: String) : LearnOutcome()
}

/** The phone's app knowledge store as the learner's sink. */
class AppKnowledgeSink(private val store: AppKnowledgeStore) : LearnSink {
    override fun app(packageName: String): LearnedApp? = store.getApp(packageName)
    override fun screenIdFor(packageName: String, pageKey: String): String? =
        store.listScreens(packageName).firstOrNull { it.recognition.semanticFingerprint == pageKey }?.id
    override fun putApp(app: LearnedApp) = store.upsertApp(app)
    override fun putScreen(screen: LearnedScreen) = store.upsertScreen(screen)
    override fun putAction(action: LearnedAction): String {
        store.upsertAction(action)
        return store.listActions(action.packageName)
            .firstOrNull { it.screenId == action.screenId && it.semanticName == action.semanticName && it.selectorJson == action.selectorJson }?.id
            ?: action.id
    }
    override fun putTransition(transition: LearnedTransition) = store.upsertTransition(transition)
}

/** Learn: one button per run that turns everything the run saw and did into lasting app knowledge. */
object MissionLearning {
    private val lock = Any()

    fun learn(context: Context, missionId: String): LearnOutcome = synchronized(lock) {
        val app = context.applicationContext
        val missions = MindMissions.store(app)
        val mission = missions.load(missionId) ?: return LearnOutcome.Refused("RUN_NOT_FOUND", "That run is no longer on the phone.")
        if (mission.status.live) return LearnOutcome.Refused("ASK_BUSY", "Wait until this run has finished.")
        mission.learned?.let { return LearnOutcome.Learned(it, alreadyLearned = true) }
        val trail = missions.loadTrail(missionId)
            ?: return LearnOutcome.Refused("NOTHING_TO_LEARN", "This run is from before Learn existed. Run it again and press Learn.")
        AppLearnerRuntime.initialize(app)
        val store = AppLearnerRuntime.store
        val report = MissionLearner(AppKnowledgeSink(store)).learn(trail) { pkg -> label(app, pkg) }
        // The same knowledge, drawn on Glass's map board.
        runCatching {
            AtlasRuntime.initialize(app)
            report.apps.forEach { learned -> store.graph(learned.packageName)?.let(AtlasRuntime.legacyImporter::import) }
        }
        report.apps.forEach { runCatching { store.mirror(it.packageName) } }
        missions.save(mission.copy(learned = report))
        MindMissions.refresh(app)
        LearnOutcome.Learned(report, alreadyLearned = false)
    }

    /** The Mind mission behind a run in Brain or Glass (runs are traces; missions carry their trace id). */
    fun missionForRun(context: Context, runId: String): Mission? =
        MindMissions.store(context.applicationContext).list().firstOrNull { it.traceId == runId || it.id == runId }

    /** Whether Learn can do anything for this mission (it finished and recorded a trail). */
    fun canLearn(context: Context, mission: Mission): Boolean =
        !mission.status.live && mission.learned == null && MindMissions.store(context.applicationContext).loadTrail(mission.id) != null

    private fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg.substringAfterLast('.'))
}
