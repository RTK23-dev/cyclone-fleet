package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.applearner.AppGraphSnapshot
import com.cyclone.mobile.applearner.AppLearnerRuntime
import com.cyclone.mobile.applearner.FollowMeLearnerRuntime
import com.cyclone.mobile.applearner.FollowMeProgress
import com.cyclone.mobile.brain.AdaptiveBrainRuntime
import com.cyclone.mobile.debug.PageDebugSandboxV293
import com.cyclone.mobile.guided.RoutineTeachingRuntime
import com.cyclone.mobile.guided.RoutineTeachingSession
import com.cyclone.mobile.guided.TeachingGestureEvidenceV292
import com.cyclone.mobile.runtime.session.ExecutionContext
import com.cyclone.mobile.runtime.session.ExecutionRequestScope
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionIdentityException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal object GatewayPageDebugAdapter {
    fun capture(context: Context, args: JSONObject): JSONObject {
        val reference = AtomicReference<Result<JSONObject>?>()
        val latch = CountDownLatch(1)
        PageDebugSandboxV293.captureAsync(context, "PC_CODEX_GATEWAY") {
            reference.set(it)
            latch.countDown()
        }
        if (!latch.await(20, TimeUnit.SECONDS)) {
            throw GatewayProtocolException("TIMEOUT", "PageDebug capture did not finish in 20 seconds")
        }
        val capture = reference.get()?.getOrThrow()
            ?: throw GatewayProtocolException("INTERNAL_ERROR", "PageDebug capture returned no result")
        return safeExport(capture, args.optString("expected").takeIf(String::isNotBlank))
    }

    fun safeExport(capture: JSONObject, expectedOverride: String? = null): JSONObject {
        val diagnosis = if (expectedOverride != null) PageDebugSandboxV293.reDiagnose(capture, expectedOverride)
            else capture.optJSONObject("diagnosis") ?: JSONObject()
        val metrics = capture.optJSONObject("metrics") ?: JSONObject()
        val screenshot = capture.optJSONObject("screenshot")
        return JSONObject()
            .put("schema", capture.optString("schema", PageDebugSandboxV293.SCHEMA))
            .put("captureId", capture.optString("captureId"))
            .put("capturedAt", capture.optLong("capturedAt"))
            .put("source", "PC_CODEX_GATEWAY")
            .put("goal", GatewayPrivacy.sanitizeDeep(capture.optString("goal")))
            .put("expectedNext", GatewayPrivacy.sanitizeDeep(expectedOverride ?: capture.optString("expectedNext")))
            .put("package", capture.optString("package"))
            .put("activity", capture.opt("class") ?: JSONObject.NULL)
            .put("pageKey", capture.optString("pageKey"))
            .put("pageTitle", capture.optString("pageTitle"))
            .put("metrics", GatewayPrivacy.sanitizeDeep(metrics))
            .put("funnel", JSONObject()
                .put("rawAccessibilityCollectionLimit", metrics.optInt("rawAccessibilityCollectionLimit", 2500))
                .put("semanticNodeScanLimit", metrics.optInt("semanticNodeScanLimit", 450))
                .put("semanticControlStoreLimit", metrics.optInt("semanticControlStoreLimit", 80))
                .put("agentControlLimit", metrics.optInt("agentControlLimit", 36))
                .put("rawNodeCount", metrics.optInt("rawNodes"))
                .put("visibleNodeCount", metrics.optInt("visibleNodes"))
                .put("interactiveCount", metrics.optInt("visibleInteractive"))
                .put("unlabeledInteractiveCount", metrics.optInt("unlabeledInteractive"))
                .put("semanticControlCount", metrics.optInt("semanticControls"))
                .put("agentPayloadControlCount", metrics.optInt("agentControls")))
            .put("diagnosis", GatewayPrivacy.sanitizeDeep(diagnosis))
            .put("rawAccessibility", GatewayPrivacy.sanitizeDeep(capture.optJSONObject("rawAccessibility") ?: JSONObject()))
            .put("semanticPageFull", GatewayPrivacy.sanitizeDeep(capture.optJSONObject("semanticPageFull") ?: JSONObject()))
            .put("productionAgentPayload", GatewayPrivacy.sanitizeDeep(capture.optJSONObject("agentInputCurrent") ?: JSONObject()))
            .put("fullControlsComparison", GatewayPrivacy.sanitizeDeep(capture.optJSONObject("agentInputFullControls") ?: JSONObject()))
            .put("pageTransitions", GatewayPrivacy.sanitizeDeep(capture.optJSONArray("pageTransitions") ?: JSONArray()))
            .put("appGraphRetrieval", GatewayPrivacy.sanitizeDeep(capture.opt("appGraphRetrieval")))
            .put("brainRecall", GatewayPrivacy.sanitizeDeep(capture.optJSONObject("brainRecall") ?: JSONObject()))
            .put("screenshotMetadata", if (screenshot == null) JSONObject.NULL else JSONObject()
                .put("width", screenshot.optInt("width"))
                .put("height", screenshot.optInt("height"))
                .put("bytes", screenshot.optLong("bytes"))
                .put("timestampMs", screenshot.optLong("timestampMs")))
            .put("reasoningDisclosure", "Deterministic pipeline evidence only; hidden chain-of-thought is not captured or exported.")
    }
}

internal object GatewayGraphQuery {
    fun matchedScreenId(graph: AppGraphSnapshot?, pageKey: String?): String? {
        if (graph == null || pageKey.isNullOrBlank()) return null
        return graph.screens.firstOrNull { it.recognition.semanticFingerprint == pageKey }?.id
    }
}

internal object GatewayAppGraphAdapter {
    fun query(context: Context, args: JSONObject): JSONObject {
        AppLearnerRuntime.initialize(context)
        val latest = GatewayObservationStore.current()
        val packageName = args.optString("package").ifBlank { latest?.page?.packageName.orEmpty() }
        if (packageName.isBlank()) throw GatewayProtocolException("INVALID_REQUEST", "package is required when no current observation exists")
        val goal = args.optString("goal").ifBlank { "Navigate the current app" }
        val requestedPageKey = args.optString("pageKey").takeIf(String::isNotBlank) ?: latest?.page?.pageKey
        val graph = AppLearnerRuntime.graph(packageName)
        val matchedScreenId = GatewayGraphQuery.matchedScreenId(graph, requestedPageKey)
        val matchedScreen = matchedScreenId?.let { id -> graph?.screens?.firstOrNull { it.id == id } }
        val retrieval = AppLearnerRuntime.retrieval(packageName, goal, matchedScreenId)
        return JSONObject()
            .put("query", JSONObject()
                .put("package", packageName)
                .put("goal", GatewayPrivacy.sanitizeDeep(goal))
                .put("pageKey", requestedPageKey ?: JSONObject.NULL))
            .put("relevance", JSONObject()
                .put("packageMatched", graph != null)
                .put("pageMatched", matchedScreen != null)
                .put("matchedScreenId", matchedScreen?.id ?: JSONObject.NULL)
                .put("matchedScreenTitle", matchedScreen?.title ?: JSONObject.NULL))
            .put("graphSummary", JSONObject()
                .put("screenCount", graph?.screens?.size ?: 0)
                .put("actionCount", graph?.actions?.size ?: 0)
                .put("transitionCount", graph?.transitions?.size ?: 0))
            .put("retrieval", GatewayPrivacy.sanitizeDeep(retrieval ?: JSONObject()))
    }
}

internal object GatewayBrainAdapter {
    fun recall(context: Context, args: JSONObject): JSONObject {
        AdaptiveBrainRuntime.initialize(context)
        val latest = GatewayObservationStore.current()
        val goal = args.optString("goal").ifBlank { "Navigate the current phone state" }
        val packageName = args.optString("package").ifBlank { latest?.page?.packageName.orEmpty() }
        val pageKey = args.optString("pageKey").takeIf(String::isNotBlank) ?: latest?.page?.pageKey
        val fingerprint = latest?.payload?.optString("accessibilityFingerprint").orEmpty()
        val environment = JSONObject()
            .put("currentPackage", packageName)
            .put("pageKey", pageKey ?: JSONObject.NULL)
            .put("fingerprint", fingerprint)
        val recall = AdaptiveBrainRuntime.recall(context, goal, environment)
        return JSONObject()
            .put("query", JSONObject()
                .put("goal", GatewayPrivacy.sanitizeDeep(goal))
                .put("package", packageName.takeIf(String::isNotBlank) ?: JSONObject.NULL)
                .put("pageKey", pageKey ?: JSONObject.NULL))
            .put("recall", GatewayPrivacy.sanitizeDeep(recall))
            .put("privacy", "Typed values, passwords, OTPs, provider keys and tokens are excluded or redacted.")
    }
}

internal object GatewayActionAdapter {
    val allowedTools = linkedSetOf(
        "phone.observe",
        "phone.find",
        "phone.click",
        "phone.long_press",
        "phone.swipe",
        "phone.scroll",
        "phone.type",
        "phone.back",
        "phone.home",
        "phone.open_app",
        "phone.wait_for",
    )
    fun execute(context: Context, requestId: String, args: JSONObject): JSONObject {
        val tool = args.optString("tool")
        if (tool !in allowedTools) throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Tool is not enabled for the PC gateway", requestId)
        val source = args.optString("source", "PC_CODEX")
        if (source != "PC_CODEX") throw GatewayProtocolException("PROTOCOL_MISMATCH", "Gateway action source must be PC_CODEX", requestId)
        val params = args.optJSONObject("params") ?: JSONObject()
        val plane = try {
            val merged = ExecutionRequestScope.merge(args, params)
            if (args.has("workspaceId") && !merged.has("workspaceId")) merged.put("workspaceId", args.get("workspaceId"))
            if (args.has("workspaceGeneration") && !merged.has("workspaceGeneration")) {
                merged.put("workspaceGeneration", args.get("workspaceGeneration"))
            }
            SessionContract.classify(merged)
        } catch (error: SessionIdentityException) {
            throw GatewayProtocolException(error.errorClass, error.message ?: "session/display mismatch", requestId)
        }
        val bound = ExecutionContext(plane.sessionId, plane.displayId)
        val goal = args.optString("goal").ifBlank { tool.removePrefix("phone.").replace('_', ' ') }
        val currentObservationId = args.optString("currentObservationId")
            .takeIf(String::isNotBlank)
            ?: try { GatewayObservationStore.current(bound)?.id } catch (_: SessionIdentityException) { null }
        val missionMetadata = args.optJSONObject("missionMetadata") ?: JSONObject()
        val authorityRequest = GatewayActionAuthorityRequest(
            requestId = requestId,
            capability = tool,
            parameters = JSONObject(params.toString()),
            currentObservationId = currentObservationId,
            source = source,
            goal = goal,
            missionMetadata = JSONObject(missionMetadata.toString()),
        )
        val authorityDecision = GatewayActionAuthorityRegistry.authorize(context, authorityRequest)
        authorityDecision.requireAuthorized(requestId)

        val executableParams = ExecutionRequestScope.attach(
            JSONObject(params.toString()).apply { remove("_gatewayRisk") },
            bound,
        )
        val result = PhoneToolExecutor.execute(context, PhoneToolRequest(requestId, tool, executableParams))
        // V3.3 owns post-action observation. Learning here would confuse executor/transport
        // success with a verified page result, so GatewayV33ActionAdapter records only a passed
        // semantic after-state.
        val envelope = JSONObject()
            .put("source", "PC_CODEX")
            .put("tool", tool)
            .put("authority", JSONObject()
                .put("binding", GatewayActionAuthorityRegistry.bindingName())
                .put("outcome", authorityDecision.outcome.name)
                .put("reasonCode", authorityDecision.reasonCode))
            .put("sanitizedParams", GatewayPrivacy.redactActionParams(tool, executableParams))
            .put("execution", GatewayPrivacy.sanitizeDeep(result.toJson()))
        return SessionContract.attach(envelope, plane)
    }

}

internal object GatewayTeachingMapper {
    fun toJson(
        progress: FollowMeProgress,
        sessionId: String?,
        session: RoutineTeachingSession?,
        gestureCount: Int,
        currentPageKey: String?,
        fallbackPackage: String?,
    ): JSONObject = JSONObject()
        .put("sessionId", sessionId ?: JSONObject.NULL)
        .put("active", progress.active)
        .put("paused", progress.paused)
        .put("currentPackage", progress.currentPackage.ifBlank { fallbackPackage.orEmpty() })
        .put("currentPageKey", currentPageKey ?: JSONObject.NULL)
        .put("pageCount", progress.screensSeen)
        .put("actionCount", progress.actionsSeen)
        .put("gestureCount", gestureCount)
        .put("appsSeen", progress.appsSeen)
        .put("pathsLearned", progress.pathsLearned)
        .put("message", progress.message)
        .put("canonicalSessionStatus", session?.status ?: JSONObject.NULL)
}

internal object GatewayTeachingAdapter {
    fun start(context: Context): JSONObject {
        RoutineTeachingRuntime.initialize(context)
        if (!FollowMeLearnerRuntime.progress().active) FollowMeLearnerRuntime.start(context)
        return status(context).put("startedBy", "PC_CODEX")
    }

    fun status(context: Context): JSONObject {
        RoutineTeachingRuntime.initialize(context)
        val progress = FollowMeLearnerRuntime.progress()
        val sessionId = progress.teachingSessionId ?: RoutineTeachingRuntime.activeSessionId()
        val session = sessionId?.let(RoutineTeachingRuntime::load)
        val gestureCount = sessionId?.let { TeachingGestureEvidenceV292.list(context, it).size } ?: 0
        val currentPageKey = session?.steps?.asReversed()?.firstOrNull { !it.pageKey.isNullOrBlank() }?.pageKey
            ?: GatewayObservationStore.current()?.page?.pageKey
        return GatewayTeachingMapper.toJson(progress, sessionId, session, gestureCount, currentPageKey, DeviceState.currentPackage)
    }

    fun stop(context: Context): JSONObject {
        RoutineTeachingRuntime.initialize(context)
        val before = FollowMeLearnerRuntime.progress()
        val sessionId = before.teachingSessionId ?: RoutineTeachingRuntime.activeSessionId()
        if (before.active) {
            // Finish through the canonical Follow Me -> RoutineTeaching path. No second history is created.
            FollowMeLearnerRuntime.finishFromOverlay(null, null)
        }
        val finished = sessionId?.let(RoutineTeachingRuntime::load)
        return status(context)
            .put("stoppedSessionId", sessionId ?: JSONObject.NULL)
            .put("reportSessionId", finished?.id ?: JSONObject.NULL)
            .put("summary", finished?.summary ?: "")
            .put("sessionStatus", finished?.status ?: "INACTIVE")
            .put("startedAt", finished?.startedAt ?: JSONObject.NULL)
            .put("endedAt", finished?.endedAt ?: JSONObject.NULL)
    }
}
