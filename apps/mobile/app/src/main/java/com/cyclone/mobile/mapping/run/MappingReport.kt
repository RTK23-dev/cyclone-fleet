package com.cyclone.mobile.mapping.run

import com.cyclone.mobile.mapping.session.MappingJob
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Shareable mapping evidence. Structural only: job identity, state, counts, budget and the driver's
 * step codes (door kinds, room purpose words, reason codes). It never contains labels, typed values,
 * screen text, selectors or secrets, so it can be pasted into an issue.
 */
object MappingReport {
    const val SCHEMA = "cyclone-mapping-report-v1"

    fun build(
        job: MappingJob,
        atlas: AtlasStoreMappingPort,
        session: ControllerSessionPort,
        events: List<MappingDriverEvent>,
        /** What the pass taught runs (one map); null until it ends. Counts only. */
        learned: com.cyclone.mobile.mind.learn.LearnReport? = null,
    ): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("mappingJobId", job.mappingJobId)
        .put("placeId", job.placeId)
        .put("persona", job.persona)
        .put("identity", job.identity.wire)
        .put("plane", job.lease.plane.kind.name.lowercase())
        .put("state", job.state.wireValue)
        .put("failureCode", job.failureCode ?: JSONObject.NULL)
        .put("atlasStatus", job.atlasStatus?.wireValue ?: JSONObject.NULL)
        .put("startedAt", Instant.ofEpochMilli(job.startedAtEpochMs).toString())
        .put("updatedAt", Instant.ofEpochMilli(job.updatedAtEpochMs).toString())
        .put("budget", JSONObject()
            .put("maxNewScreens", job.budget.maxNewScreens)
            .put("maxElapsedMs", job.budget.maxElapsedMs)
            .put("maxConsecutiveNonProgress", job.budget.maxConsecutiveNonProgress)
            .put("maxAttemptsPerDoor", job.budget.maxAttemptsPerDoor))
        .put("progress", JSONObject()
            .put("newScreens", job.progress.newScreens)
            .put("verifiedMutations", job.progress.verifiedMutations)
            .put("attemptedDoors", job.progress.attemptedDoors)
            .put("consecutiveNonProgress", job.progress.consecutiveNonProgress))
        .put("atlas", JSONObject()
            .put("rooms", atlas.roomCount())
            .put("doors", atlas.doorCount())
            .put("darkDoors", atlas.darkDoorCount())
            .put("blockedDoorEncounters", session.blockedDoors)
            .put("noProgressDoorAttempts", session.noProgressDoors))
        .put("runsCanUse", learned?.let { JSONObject().put("screens", it.screens).put("moves", it.transitions) } ?: JSONObject.NULL)
        .put("steps", JSONArray().also { out ->
            events.forEach { event ->
                out.put(JSONObject()
                    .put("at", Instant.ofEpochMilli(event.atEpochMs).toString())
                    .put("kind", safeCode(event.kind))
                    .put("detail", safeCode(event.detail)))
            }
        })

    /** Codes are lower-case words joined by `_ : -`; anything else is dropped. */
    internal fun safeCode(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9_:\\-]+"), "_").take(80)
}
