package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.brain.graphv2.AtlasGraphIds
import com.cyclone.mobile.market.MarketListing
import com.cyclone.mobile.market.Marketplace
import com.cyclone.mobile.market.SkillAnchor
import com.cyclone.mobile.market.SkillHealth
import org.json.JSONArray
import org.json.JSONObject

/**
 * The owner's saved skills and where each lives on the map (`skills.list`, plan 23). Names, structural screen titles,
 * health and counts only: never a goal's inputs, typed text or anything read on a screen. Each waypoint carries the
 * Atlas screen id of the learned screen (the "Taught" map in Glass) when the phone knows it, so Glass can draw the
 * skill's way on the map.
 */
internal object GatewayV5SkillsAdapter {
    const val MAX_SKILLS = 200

    data class Row(val listing: MarketListing, val anchor: SkillAnchor?, val health: SkillHealth)

    /** Seams for JVM tests; production reads the phone's skills and its app knowledge store. */
    internal var skills: () -> List<Row> = {
        val app = checkNotNull(context) { "skills adapter not installed" }
        Marketplace.skillsWithHealth(app).map { (listing, anchor, health) -> Row(listing, anchor, health) }
    }
    internal var learnedScreenId: (String, String) -> String? = { pkg, pageKey ->
        runCatching {
            com.cyclone.mobile.applearner.AppLearnerRuntime.store.listScreens(pkg).firstOrNull { it.recognition.semanticFingerprint == pageKey }?.id
        }.getOrNull()
    }

    @Volatile private var context: Context? = null
    fun install(context: Context) {
        this.context = context.applicationContext
        runCatching { com.cyclone.mobile.applearner.AppLearnerRuntime.initialize(context.applicationContext) }
    }

    fun dispatch(op: String, args: JSONObject): JSONObject {
        if (op != "skills.list") throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported skills operation: $op")
        if (args.length() != 0) throw GatewayProtocolException("INVALID_REQUEST", "skills.list takes no arguments.")
        val rows = skills()
        return JSONObject()
            .put("skills", JSONArray().also { out -> rows.take(MAX_SKILLS).forEach { out.put(row(it)) } })
            .put("truncated", rows.size > MAX_SKILLS)
    }

    private fun row(row: Row): JSONObject {
        val anchor = row.anchor
        return JSONObject()
            .put("skillId", row.listing.id)
            .put("name", row.listing.name.take(80))
            .put("placeId", anchor?.let { "package:${it.packageName}" } ?: JSONObject.NULL)
            .put("ground", row.health.state.wire)
            .put("detail", row.health.detail.take(200))
            .put("routeMoves", row.health.routeMoves ?: JSONObject.NULL)
            .put("route", JSONArray().also { out ->
                anchor?.route?.forEach { point ->
                    val screen = learnedScreenId(anchor.packageName, point.pageKey)
                    out.put(JSONObject().put("title", point.title.take(60))
                        .put("screenId", screen?.let { AtlasGraphIds.encoded("page", it).value.take(160) } ?: JSONObject.NULL))
                }
            })
            .put("finishSteps", anchor?.finishSteps ?: 0)
            .put("savedAt", anchor?.savedAtMs?.takeIf { it > 0 } ?: JSONObject.NULL)
    }
}
