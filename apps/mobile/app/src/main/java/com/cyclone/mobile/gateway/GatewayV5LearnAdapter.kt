package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.mind.learn.LearnOutcome
import com.cyclone.mobile.mind.learn.MissionLearning
import org.json.JSONArray
import org.json.JSONObject

/**
 * Learn over the gateway (`learn.run`): the same one press as the phone's Learn button, for a run Glass shows. The
 * reply is counts and app labels only; a refusal (run gone, still running, from before Learn) comes back as data.
 */
internal object GatewayV5LearnAdapter {
    private val RUN_ID = Regex("^[A-Za-z0-9_-]{4,120}$")

    /** Seam for JVM tests; production learns on the phone. */
    internal var learn: (String) -> LearnOutcome = { runId ->
        val app = checkNotNull(context) { "learn adapter not installed" }
        val mission = MissionLearning.missionForRun(app, runId)
        if (mission == null) LearnOutcome.Refused("RUN_NOT_FOUND", "Only Cyclone Mind runs on this phone can be learned from.")
        else MissionLearning.learn(app, mission.id)
    }

    @Volatile private var context: Context? = null
    fun install(context: Context) { this.context = context.applicationContext }

    fun dispatch(op: String, args: JSONObject): JSONObject {
        if (op != "learn.run") throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported learn operation: $op")
        if (args.keys().asSequence().toSet() != setOf("runId")) throw GatewayProtocolException("INVALID_REQUEST", "learn.run takes runId only.")
        val runId = args.optString("runId")
        if (!RUN_ID.matches(runId)) throw GatewayProtocolException("INVALID_REQUEST", "runId is malformed.")
        val base = JSONObject().put("runId", runId)
        return when (val outcome = learn(runId)) {
            is LearnOutcome.Learned -> base.put("learned", true).put("alreadyLearned", outcome.alreadyLearned)
                .put("sentence", outcome.report.sentence())
                .put("apps", JSONArray().also { out ->
                    outcome.report.apps.take(20).forEach {
                        out.put(JSONObject().put("package", it.packageName).put("label", it.label.take(60))
                            .put("screens", it.screens).put("newScreens", it.newScreens)
                            .put("controls", it.controls).put("transitions", it.transitions))
                    }
                })
                .put("refusal", JSONObject.NULL)
            is LearnOutcome.Refused -> base.put("learned", false).put("alreadyLearned", false).put("sentence", "")
                .put("apps", JSONArray()).put("refusal", JSONObject().put("code", outcome.code).put("message", outcome.message))
        }
    }
}
