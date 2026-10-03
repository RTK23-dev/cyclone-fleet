package com.cyclone.mobile.gateway

import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.AppGraphSnapshot
import com.cyclone.mobile.applearner.GatewayRouteDescriptor
import com.cyclone.mobile.applearner.KnowledgeState
import com.cyclone.mobile.applearner.PageContext
import com.cyclone.mobile.applearner.VerifiedRouteDescriptors
import com.cyclone.mobile.brain.BrainMicroSkill
import org.json.JSONArray
import org.json.JSONObject

/**
 * Presentation-only route evidence for a semantic observation.
 *
 * A descriptor is deliberately not a second action API. Its invocation contract points back to
 * `action.execute`, which still requires a current observation, the gateway authority decision,
 * and a fresh after-state observation. Coordinates and arbitrary intents are excluded here.
 */
internal object GatewayRouteEvidence {
    private const val MAX_HINTS = 5

    fun nextHops(
        page: PageContext,
        accessibilityFingerprint: String,
        graph: AppGraphSnapshot?,
        brainSkills: List<BrainMicroSkill>,
        nowMs: Long = System.currentTimeMillis(),
    ): JSONArray {
        val graphHints = VerifiedRouteDescriptors.fromAppGraph(page, graph, nowMs)
        val graphKeys = graphHints.map { it.dedupeKey }.toSet()
        val brainHints = VerifiedRouteDescriptors.fromBrain(
            page = page,
            accessibilityFingerprint = accessibilityFingerprint,
            skills = brainSkills,
            nowMs = nowMs,
        ).filterNot { it.dedupeKey in graphKeys }

        return (graphHints + brainHints)
            .sortedWith(
                compareByDescending<GatewayRouteDescriptor> { it.confidence }
                    .thenByDescending { it.lastVerifiedAtMs }
                    .thenBy { it.label },
            )
            .take(MAX_HINTS)
            .fold(JSONArray()) { out, hint -> out.put(hint.toJson()) }
    }

    /** A single contract object keeps page identity/content bounded for every canonical observe. */
    fun pageEvidence(
        page: PageContext,
        packageName: String?,
        activity: String?,
        pageText: JSONObject,
        pageSummary: JSONObject,
        semanticControls: Int,
        supplementalControls: Int,
        rawNodes: Int,
        windows: Int,
        nextHopHints: JSONArray,
    ): JSONObject = JSONObject()
        .put("schema", "cyclone-agent-page-evidence-v1")
        .put("package", packageName ?: page.packageName)
        .put("activity", activity ?: page.className ?: JSONObject.NULL)
        .put("pageKey", page.pageKey)
        .put("pageTitle", page.title)
        .put("pageText", pageText)
        .put("pageSummary", pageSummary)
        .put("counts", JSONObject()
            .put("semanticControls", semanticControls)
            .put("supplementalControls", supplementalControls)
            .put("totalControls", semanticControls + supplementalControls)
            .put("rawNodes", rawNodes)
            .put("textLines", pageText.optInt("lineCount"))
            .put("windows", windows))
        .put("nextHopHints", nextHopHints)
        .put("hintsAdvisory", true)
        .put("hintContract", "Hints are evidence, not authority. Invoke only through action.execute with a fresh observation, policy authorization, and after-state verification.")
}
