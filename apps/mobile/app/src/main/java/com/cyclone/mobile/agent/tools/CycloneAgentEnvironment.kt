package com.cyclone.mobile.agent.tools

import android.content.Context
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.PhoneToolErrorCode
import com.cyclone.mobile.PhoneToolExecutor
import com.cyclone.mobile.PhoneToolRequest
import com.cyclone.mobile.PhoneToolResult
import com.cyclone.mobile.fastpath.FastPathTree
import com.cyclone.mobile.agent.contract.AgentActionEnvelope
import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentFailure
import com.cyclone.mobile.agent.contract.AgentFailureClass
import com.cyclone.mobile.agent.contract.AgentFailureLayer
import com.cyclone.mobile.agent.contract.AgentInspectResult
import com.cyclone.mobile.agent.contract.AgentKnowledgeResult
import com.cyclone.mobile.agent.contract.AgentLearningResult
import com.cyclone.mobile.agent.contract.AgentObservationResult
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.agent.contract.AgentScreenshotResult
import com.cyclone.mobile.agent.contract.AgentSearchResult
import com.cyclone.mobile.agent.contract.AgentSemanticVerification
import com.cyclone.mobile.agent.contract.AgentStateDelta
import com.cyclone.mobile.agent.contract.AgentVerificationStatus
import com.cyclone.mobile.agent.recovery.ActionOutcomePolicy
import com.cyclone.mobile.ai.CycloneAiAccessPolicy
import com.cyclone.mobile.ai.CycloneAiAccessProfileStore
import com.cyclone.mobile.gateway.GatewayAppGraphAdapter
import com.cyclone.mobile.gateway.GatewayBrainAdapter
import com.cyclone.mobile.gateway.GatewayCaptureAdapter
import com.cyclone.mobile.gateway.GatewayObservation
import com.cyclone.mobile.gateway.GatewayObservationAdapter
import com.cyclone.mobile.gateway.GatewayObservationStore
import com.cyclone.mobile.gateway.GatewayProtocolException
import com.cyclone.mobile.gateway.GatewayV33ActionAdapter
import com.cyclone.mobile.policy.GateClassifier
import com.cyclone.mobile.ui.overlay.ClickGateIntercept
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.OverlayChromeState
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID

/** Native in-process eyes/hands/verification contract for the standalone mobile agent. */
interface CycloneAgentEnvironmentApi {
    fun observe(goal: String = ""): AgentObservationResult
    fun observeWithImage(goal: String): AgentObservationResult = observe(goal)
    fun locate(goal: String): AgentSearchResult
    fun search(query: String, goal: String = query): AgentSearchResult
    fun inspect(elementId: String): AgentInspectResult
    fun screenshot(goal: String = ""): AgentScreenshotResult
    fun act(tool: String, params: JSONObject = JSONObject(), goal: String = ""): AgentActionEnvelope
    /** Oldest to newest. takeLast(n) always returns the most recent outcomes. */
    fun history(): List<AgentActionEnvelope>
    fun invalidateObservation() {}
    fun photoEffect(): PhotoEffectLedger.State = PhotoEffectLedger.State.NOT_ATTEMPTED
    fun brainRecall(goal: String): AgentKnowledgeResult
    fun knownRoutes(goal: String): AgentKnowledgeResult
    /** Every control of the current observation (the page card holds a shortlist). Empty when none is current. */
    fun allControls(): List<AgentElementCandidate> = emptyList()
    /** The value of an ordinary editable field in the current observation; never secrets or the address bar. */
    fun fieldValue(elementId: String): String? = null
}

class CycloneAgentEnvironment internal constructor(
    private val runtime: CycloneAgentRuntimePort,
    private val userTaskGoal: String? = null,
    private val revalidateTargets: Boolean = false,
    private val projectionMode: ObservationProjectionMode = ObservationProjectionMode.AUTHORITATIVE,
    /** A Cyclone Mind mission: the owner's own request may type into ordinary fields ([OwnerMissionTyping]). */
    private val ownerMission: Boolean = false,
) : CycloneAgentEnvironmentApi {
    constructor(
        context: Context,
        execution: com.cyclone.mobile.runtime.session.ExecutionContext = com.cyclone.mobile.runtime.session.ExecutionContext.DEFAULT,
        userTaskGoal: String? = null,
        ownerMission: Boolean = false,
    ) : this(AndroidCycloneAgentRuntimePort(context.applicationContext, execution), userTaskGoal, revalidateTargets = true,
        ownerMission = ownerMission)

    private val scope = AgentObservationScope()
    private val actionHistory = ArrayDeque<AgentActionEnvelope>()
    private val photoLedger = PhotoEffectLedger()
    override fun photoEffect(): PhotoEffectLedger.State {
        if (photoLedger.state == PhotoEffectLedger.State.AWAITING_PROOF) photoLedger.observe(runtime.cameraImages())
        return photoLedger.state
    }

    override fun observe(goal: String): AgentObservationResult = synchronized(this) {
        runCatching {
            val observation = runtime.capture()
            val generation = scope.publish(observation.id, observation.generation.takeIf { it > 0 })
            AgentObservationResult(page = pageCard(observation, goal, generation, actionable = true))
        }.getOrElse { AgentObservationResult(failure = failureFromThrowable(it, AgentFailureLayer.OBSERVATION)) }
    }

    override fun observeWithImage(goal: String): AgentObservationResult = synchronized(this) {
        runCatching {
            val observation = runtime.captureWithImage()
            val generation = scope.publish(observation.id, observation.generation.takeIf { it > 0 })
            AgentObservationResult(page = pageCard(observation, goal, generation, actionable = true),
                image = observation.payload.optJSONObject("screenshot")?.let { JSONObject(it.toString()) })
        }.getOrElse {
            invalidateObservation()
            AgentObservationResult(failure = failureFromThrowable(it, AgentFailureLayer.OBSERVATION))
        }
    }

    override fun locate(goal: String): AgentSearchResult = synchronized(this) {
        runCatching {
            val observation = runtime.capture()
            val generation = scope.publish(observation.id, observation.generation.takeIf { it > 0 })
            AgentSearchResult(
                page = pageCard(observation, goal, generation, actionable = true),
                observationId = observation.id,
                generation = generation,
                query = goal,
                goal = goal,
                candidates = candidates(observation, goal, DEFAULT_LOCATE_LIMIT),
            )
        }.getOrElse {
            AgentSearchResult(query = goal, goal = goal, failure = failureFromThrowable(it, AgentFailureLayer.OBSERVATION))
        }
    }

    override fun search(query: String, goal: String): AgentSearchResult = synchronized(this) {
        if (query.isBlank()) {
            return@synchronized AgentSearchResult(
                query = query,
                goal = goal,
                failure = AgentFailure(
                    AgentFailureClass.INVALID_REQUEST,
                    AgentFailureLayer.INPUT,
                    false,
                    "Search query is required.",
                ),
            )
        }
        runCatching {
            val observation = currentVisibleObservation() ?: runtime.capture().also { scope.publish(it.id, it.generation.takeIf { it > 0 }) }
            val generation = scope.generation
            AgentSearchResult(
                page = pageCard(observation, goal, generation, actionable = true),
                observationId = observation.id,
                generation = generation,
                query = query,
                goal = goal,
                candidates = candidates(observation, query, DEFAULT_SEARCH_LIMIT),
            )
        }.getOrElse {
            AgentSearchResult(query = query, goal = goal, failure = failureFromThrowable(it, AgentFailureLayer.OBSERVATION))
        }
    }

    override fun inspect(elementId: String): AgentInspectResult = synchronized(this) {
        val observation = currentVisibleObservation()
            ?: return@synchronized AgentInspectResult(
                elementId = elementId,
                failure = staleFailure("Observe or search the current page again before inspecting an element."),
            )
        if (!belongsToObservation(elementId, observation.id)) {
            return@synchronized AgentInspectResult(
                observationId = observation.id,
                generation = scope.generation,
                elementId = elementId,
                failure = staleFailure("Element ID belongs to an expired observation."),
            )
        }
        runCatching { runtime.element(observation, elementId) }
            .fold(
                onSuccess = { evidence -> AgentInspectResult(observation.id, scope.generation, elementId, evidence) },
                onFailure = { error ->
                    AgentInspectResult(
                        observation.id,
                        scope.generation,
                        elementId,
                        failure = failureFromThrowable(error, AgentFailureLayer.OBSERVATION),
                    )
                },
            )
    }

    override fun screenshot(goal: String): AgentScreenshotResult = synchronized(this) {
        val visibleId = currentVisibleObservation()?.id
        runCatching { runtime.screenshot(goal) }.fold(
            onSuccess = { frame ->
                AgentScreenshotResult(
                    goal = goal,
                    observationId = visibleId,
                    filePath = frame.optString("filePath").takeIf(String::isNotBlank),
                    width = frame.optInt("width").takeIf { frame.has("width") },
                    height = frame.optInt("height").takeIf { frame.has("height") },
                    timestampMs = frame.optLong("timestampMs").takeIf { frame.has("timestampMs") },
                )
            },
            onFailure = { error ->
                AgentScreenshotResult(
                    goal,
                    visibleId,
                    failure = failureFromThrowable(error, AgentFailureLayer.CAPABILITY),
                )
            },
        )
    }

    override fun act(tool: String, params: JSONObject, goal: String): AgentActionEnvelope = synchronized(this) {
        val effectiveGoal = goal.ifBlank { tool.removePrefix("phone.").replace('_', ' ') }
        if (tool !in LOCAL_MUTATING_TOOLS) {
            return@synchronized failureEnvelope(
                tool,
                effectiveGoal,
                AgentFailure(
                    AgentFailureClass.CAPABILITY_UNAVAILABLE,
                    AgentFailureLayer.CAPABILITY,
                    false,
                    "Tool is not exposed to the local agent contract.",
                ),
            )
        }

        var before = currentVisibleObservation()
            ?: return@synchronized failureEnvelope(
                tool,
                effectiveGoal,
                staleFailure("Fresh observe/locate/search is required before every mutation."),
            )
        var visibleGeneration = scope.generation
        var rawElementId = elementId(params, before)
        // Plan 21 (Hands): a Mind mission may type into the text box that has focus, without a ref.
        val focusedType = ownerMission && tool == "phone.type" && rawElementId == null && params.optBoolean("focused")
        if (tool in ELEMENT_ID_REQUIRED_TOOLS && rawElementId == null && !focusedType) {
            return@synchronized failureEnvelope(
                tool,
                effectiveGoal,
                AgentFailure(
                    AgentFailureClass.INVALID_REQUEST,
                    AgentFailureLayer.INPUT,
                    false,
                    "Use a current observation-scoped elementId from observe/locate/search for this action.",
                    "ELEMENT_ID_REQUIRED",
                ),
                before,
                visibleGeneration,
            )
        }
        if (tool in ELEMENT_SCOPED_TOOLS && rawElementId == null && hasFreeformSelector(params)) {
            return@synchronized failureEnvelope(
                tool,
                effectiveGoal,
                AgentFailure(
                    AgentFailureClass.INVALID_REQUEST,
                    AgentFailureLayer.INPUT,
                    false,
                    "Free-form selectors are not accepted by the local agent contract; re-locate the target.",
                    "SCOPED_ELEMENT_ID_REQUIRED",
                ),
                before,
                visibleGeneration,
            )
        }
        if (rawElementId != null && !belongsToObservation(rawElementId, before.id)) {
            return@synchronized failureEnvelope(
                tool,
                effectiveGoal,
                staleFailure("Element ID belongs to an expired observation."),
                before,
                visibleGeneration,
            )
        }

        // Re-observe once at the execution boundary, then bind a new ID. Never repair coordinates.
        if (revalidateTargets && rawElementId != null) {
            val fresh = runCatching { runtime.capture() }.getOrElse {
                scope.expire()
                return@synchronized failureEnvelope(tool, effectiveGoal,
                    failureFromThrowable(it, AgentFailureLayer.OBSERVATION), before, visibleGeneration)
            }
            val report = CurrentTargetRevalidation.resolve(before, fresh, rawElementId)
            scope.expire()
            if (report.elementId == null) return@synchronized failureEnvelope(tool, effectiveGoal,
                AgentFailure(AgentFailureClass.STALE_OBSERVATION, AgentFailureLayer.OBSERVATION, false,
                    "Target revalidation: ${report.status.name}. Inspect a fresh same-scope control.", report.status.name),
                before, visibleGeneration)
            before = fresh
            visibleGeneration = scope.publish(fresh.id, fresh.generation.takeIf { it > 0 })
            rawElementId = report.elementId
        }
        val normalizedParams = JSONObject(params.toString())
            .put("fastPath", true)
        if (com.cyclone.mobile.fastpath.MutationGrounding.requiredFor(tool)) {
            normalizedParams.put("observationId", before.id)
        }
        if (before.execution.sessionId != "default-foreground") {
            normalizedParams.put("executionGeneration", before.payload.optLong("executionGeneration"))
        }
        if (rawElementId != null) {
            if (revalidateTargets) normalizedParams.remove("selector")
            val evidence = runCatching { runtime.element(before, rawElementId) }.getOrElse { error ->
                return@synchronized failureEnvelope(
                    tool,
                    effectiveGoal,
                    failureFromThrowable(error, AgentFailureLayer.OBSERVATION),
                    before,
                    visibleGeneration,
                )
            }
            if (normalizedParams.optJSONObject("selector") == null ||
                normalizedParams.optJSONObject("selector")?.length() == 0
            ) {
                evidence.optJSONObject("selector")?.let {
                    normalizedParams.put("selector", JSONObject(it.toString()))
                }
            }
            normalizedParams.put("elementId", rawElementId)
            if (tool in setOf("phone.type", "phone.replace_text")) {
                // Discard self-authorization and text selectors from the planner. The engine retains
                // its independent ID, freshness, editable-field and sensitive-input checks.
                normalizedParams.remove("user_authorized")
                normalizedParams.remove("selector")
                normalizedParams.put("currentObservationId", before.id)
                val value = normalizedParams.optString("value", normalizedParams.optString("text"))
                if (TaskTypingAuthorization.allows(userTaskGoal, before.page.packageName, evidence, value) ||
                    ownerMission && OwnerMissionTyping.allows(evidence, value)) {
                    normalizedParams.put("user_authorized", true)
                }
            }
        }

        if (focusedType) {
            val value = normalizedParams.optString("value", normalizedParams.optString("text"))
            val focusedField = before.elements.values.firstOrNull {
                it.evidence.optBoolean("focused") && it.evidence.optBoolean("editable") && it.source != "raw_accessibility"
            } ?: before.elements.values.firstOrNull { it.evidence.optBoolean("focused") && it.evidence.optBoolean("editable") }
            normalizedParams.remove("user_authorized")
            normalizedParams.remove("selector")
            normalizedParams.remove("elementId")
            normalizedParams.put("focused", true).put("currentObservationId", before.id)
            if (focusedField == null) return@synchronized failureEnvelope(tool, effectiveGoal,
                AgentFailure(AgentFailureClass.TARGET_NOT_FOUND, AgentFailureLayer.OBSERVATION, false,
                    "No text box has focus. Tap the box first, then type with focused=true.", "NO_FOCUSED_FIELD"),
                before, visibleGeneration)
            if (OwnerMissionTyping.allows(focusedField.evidence, value)) normalizedParams.put("user_authorized", true)
        }

        runtime.readinessFailure()?.let { failure ->
            return@synchronized failureEnvelope(tool, effectiveGoal, failure, before, visibleGeneration)
        }
        runtime.policyFailure(tool, normalizedParams)?.let { failure ->
            return@synchronized failureEnvelope(tool, effectiveGoal, failure, before, visibleGeneration)
        }

        if (PhotoEffectLedger.isSinglePhotoGoal(userTaskGoal) && tool == "phone.click" && rawElementId != null) {
            val target = runtime.element(before, rawElementId)
            if (PhotoEffectLedger.isShutter(before.page.packageName,
                    "${target.optString("label")} ${target.optString("resourceId")} ${target.optString("contentDescription")}")) {
                if (!photoLedger.request(runtime.cameraImages()?.keys, System.currentTimeMillis())) {
                    return@synchronized failureEnvelope(tool, effectiveGoal,
                        AgentFailure(AgentFailureClass.GATE_REQUIRED, AgentFailureLayer.POLICY, false,
                            "A shutter was already requested. Review the saved photo; Cyclone will not take another.", "PHOTO_ALREADY_ATTEMPTED"), before, visibleGeneration)
                }
            }
        }

        // IDs expire the instant a mutation is handed to the canonical PhoneToolExecutor.
        scope.expire()
        val requestId = "local-agent-${UUID.randomUUID()}"
        val result = runCatching { runtime.execute(requestId, tool, normalizedParams) }.getOrElse { error ->
            val envelope = failureEnvelope(
                tool,
                effectiveGoal,
                failureFromThrowable(error, AgentFailureLayer.EXECUTION),
                before,
                visibleGeneration,
            )
            remember(envelope)
            return@synchronized envelope
        }

        val executorAssertionFailed = result.error?.code == PhoneToolErrorCode.ASSERTION_FAILED
        val androidExecutionOk = result.ok || executorAssertionFailed
        val after = if (ActionOutcomePolicy.shouldCaptureAfter(result.error?.code)) {
            runCatching { runtime.captureAfter(tool, normalizedParams, before) }.getOrNull()
        } else {
            null
        }
        val verification = runtime.verify(
            tool = tool,
            expectedPackage = normalizedParams.optString("package"),
            goalLabel = effectiveGoal,
            before = before,
            after = after,
            androidExecutionOk = androidExecutionOk,
            executorAssertionFailed = executorAssertionFailed,
            explicitExpectation = normalizedParams.optJSONObject("expect") != null &&
                (result.payload as? JSONObject)?.optBoolean("expectationVerified") == true,
        )

        val executionFailure = if (!androidExecutionOk) failureFromPhoneResult(result) else null
        val verificationFailure = when (verification.status) {
            AgentVerificationStatus.PASSED -> null
            AgentVerificationStatus.DEGRADED -> AgentFailure(
                AgentFailureClass.AFTER_OBSERVATION_FAILED,
                AgentFailureLayer.VERIFICATION,
                true,
                verification.detail ?: "Fresh after-observation was unavailable.",
                verification.basis,
            )
            AgentVerificationStatus.FAILED,
            AgentVerificationStatus.OBSERVED,
            -> AgentFailure(
                AgentFailureClass.VERIFICATION_FAILED,
                AgentFailureLayer.VERIFICATION,
                true,
                verification.detail ?: "Fresh after-state did not prove semantic success.",
                verification.basis,
            )
            AgentVerificationStatus.NOT_REQUIRED -> null
        }
        val failure = executionFailure ?: verificationFailure

        val learning = if (verification.passed && before.page.pageKey != after?.page?.pageKey) {
            runCatching {
                runtime.recordLearning(
                    effectiveGoal,
                    tool,
                    normalizedParams,
                    before,
                    after,
                    androidExecutionOk,
                    verification,
                )
            }.getOrElse {
                AgentLearningResult(
                    false,
                    "Verified route persistence failed safely: ${it.javaClass.simpleName}",
                )
            }
        } else {
            AgentLearningResult(
                false,
                "Only a semantically PASSED page transition may enter verified route learning.",
            )
        }

        val delta = stateDelta(before, after, verification)
        val envelope = AgentActionEnvelope(
            tool = tool,
            goal = effectiveGoal,
            androidExecutionOk = androidExecutionOk,
            executorReportedOk = result.ok,
            verification = verification,
            before = pageCard(before, effectiveGoal, visibleGeneration, actionable = false),
            after = after?.let { pageCard(it, effectiveGoal, visibleGeneration + 1, actionable = false) },
            pageChanged = delta.pageChanged,
            delta = delta,
            errorClass = failure?.errorClass ?: AgentFailureClass.NONE,
            failureLayer = failure?.failureLayer ?: AgentFailureLayer.NONE,
            retryable = failure?.retryable ?: false,
            semanticSuccessClaimed = verification.semanticSuccessClaimed,
            beforeObservationId = before.id,
            afterObservationId = after?.id,
            observationGeneration = visibleGeneration,
            learning = learning,
            // Plan 21 (Hands): typed text the executor could not read back is reported, never passed off as typed.
            safeMessage = failure?.message ?: (result.payload as? JSONObject)?.takeIf { tool in setOf("phone.type", "phone.replace_text") }
                ?.let { payload -> "TYPE_METHOD=${payload.optString("method", "set_text")}" +
                    if (payload.has("textVerified") && !payload.optBoolean("textVerified")) " TEXT_UNVERIFIED" else "" },
            executorInvoked = true,
        )
        remember(envelope)
        envelope
    }

    override fun allControls(): List<AgentElementCandidate> = synchronized(this) {
        val observation = currentVisibleObservation() ?: return@synchronized emptyList()
        val controls = runtime.allControls(observation)
        (0 until controls.length()).mapNotNull { index ->
            val evidence = controls.optJSONObject(index) ?: return@mapNotNull null
            val id = evidence.optString("elementId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AgentElementCandidate(id, observation.id, evidence.optString("label"), evidence.optString("semanticName"),
                evidence.optString("role"), evidence.optString("source"), 0.0, evidence,
                evidence.optInt("elementIndex", -1).takeIf { it > 0 })
        }
    }

    override fun fieldValue(elementId: String): String? = synchronized(this) {
        val observation = currentVisibleObservation() ?: return@synchronized null
        runtime.fieldValue(observation, elementId)
    }

    override fun invalidateObservation() = synchronized(this) {
        scope.expire()
        actionHistory.clear() // Previous after-states cannot satisfy post-handoff completion.
    }

    override fun history(): List<AgentActionEnvelope> = synchronized(this) { actionHistory.toList() }

    override fun brainRecall(goal: String): AgentKnowledgeResult = synchronized(this) {
        runCatching { runtime.brainRecall(goal) }.fold(
            onSuccess = { AgentKnowledgeResult(goal, it) },
            onFailure = {
                AgentKnowledgeResult(
                    goal,
                    failure = failureFromThrowable(it, AgentFailureLayer.LEARNING),
                )
            },
        )
    }

    override fun knownRoutes(goal: String): AgentKnowledgeResult = synchronized(this) {
        runCatching { runtime.knownRoutes(goal) }.fold(
            onSuccess = { AgentKnowledgeResult(goal, it) },
            onFailure = {
                AgentKnowledgeResult(
                    goal,
                    failure = failureFromThrowable(it, AgentFailureLayer.LEARNING),
                )
            },
        )
    }

    private fun currentVisibleObservation(): GatewayObservation? {
        val visibleId = scope.observationId ?: return null
        val current = runtime.current() ?: return null
        return current.takeIf { it.id == visibleId }
    }

    private fun candidates(
        observation: GatewayObservation,
        query: String,
        limit: Int,
    ): List<AgentElementCandidate> {
        val results = runtime.search(observation, query, limit)
        return buildList {
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val id = item.optString("elementId")
                val evidence = runCatching { runtime.element(observation, id) }.getOrNull() ?: JSONObject()
                add(candidateFrom(item, evidence))
            }
        }
    }

    private fun pageCard(observation: GatewayObservation, goal: String, generation: Long, actionable: Boolean): AgentPageCard {
        val shared = ObservationProjections.pageCard(observation, goal, generation, actionable)
        if (projectionMode == ObservationProjectionMode.SHADOW) {
            val legacy = legacyPageCard(observation, goal, generation, actionable)
            legacy.pageEvidence.put("projectionShadow", ObservationProjections.shadow(legacy, shared))
            return legacy
        }
        shared.pageEvidence.put("projectionMode", "authoritative")
        return shared
    }

    private fun legacyPageCard(
        observation: GatewayObservation,
        goal: String,
        generation: Long,
        actionable: Boolean,
    ): AgentPageCard {
        val ranked = if (goal.isBlank()) {
            emptyList()
        } else {
            candidates(observation, goal, PAGE_CARD_GOAL_CANDIDATES)
        }
        val byId = linkedMapOf<String, AgentElementCandidate>()
        ranked.forEach { byId[it.elementId] = it }

        val semantic = observation.payload.optJSONArray("semanticControls") ?: JSONArray()
        for (index in 0 until semantic.length()) {
            val evidence = semantic.optJSONObject(index) ?: continue
            if (byId.size >= PAGE_CARD_CONTROL_LIMIT &&
                !com.cyclone.mobile.ai.CookieInterruptionPolicy.isRejectLabel(evidence.optString("label"))) continue
            val id = evidence.optString("elementId")
            if (id.isBlank() || id in byId) continue
            byId[id] = AgentElementCandidate(
                elementId = id,
                observationId = observation.id,
                label = evidence.optString("label"),
                semanticName = evidence.optString("semanticName"),
                role = evidence.optString("role"),
                source = evidence.optString("source"),
                relevance = 0.0,
                evidence = JSONObject(evidence.toString()),
                elementIndex = evidence.optInt("elementIndex", evidence.optInt("element_index", -1)).takeIf { it > 0 },
            )
        }

        val legacy = AgentPageCard(
            observationId = observation.id,
            generation = generation,
            actionable = actionable,
            capturedAtMs = observation.capturedAt,
            packageName = observation.page.packageName,
            activity = observation.payload.optString("activity").takeIf {
                it.isNotBlank() && it != "null"
            },
            pageKey = observation.page.pageKey,
            structuralKey = observation.page.structuralKey,
            contentKey = observation.page.contentKey,
            accessibilityFingerprint = observation.payload.optString("accessibilityFingerprint"),
            pageSummary = copyObject(observation.payload.optJSONObject("pageSummary")),
            pageText = copyObject(observation.payload.optJSONObject("pageText")),
            pageEvidence = copyObject(observation.payload.optJSONObject("pageEvidence")),
            controls = byId.values.sortedByDescending { com.cyclone.mobile.ai.CookieInterruptionPolicy.isRejectLabel(it.label) }
                .take(PAGE_CARD_CONTROL_LIMIT),
            nextHopHints = copyArray(observation.payload.optJSONArray("nextHopHints")),
            perceptionMode = observation.payload.optString("perceptionMode", "a11y").ifBlank { "a11y" },
            treeUseful = observation.payload.optBoolean("treeUseful", true),
            sessionId = observation.execution.sessionId,
            displayId = observation.execution.displayId,
            legacyPage = observation.page,
            observation = com.cyclone.mobile.runtime.session.ObservationIdentity.fromPayload(observation.id, generation,
                observation.execution, observation.capturedAt, observation.payload).copy(freshness = if (actionable) "current" else "stale"),
        )
        return legacy
    }

    private fun candidateFrom(item: JSONObject, evidence: JSONObject) = AgentElementCandidate(
        elementId = item.optString("elementId"),
        observationId = item.optString("observationId"),
        label = item.optString("label"),
        semanticName = item.optString("semanticName"),
        role = item.optString("role"),
        source = item.optString("source"),
        relevance = item.optDouble("relevance", 0.0),
        evidence = JSONObject(evidence.toString()),
        elementIndex = item.optInt("elementIndex", evidence.optInt("elementIndex", evidence.optInt("element_index", -1))).takeIf { it > 0 },
    )

    private fun stateDelta(
        before: GatewayObservation,
        after: GatewayObservation?,
        verification: AgentSemanticVerification,
    ): AgentStateDelta {
        if (after == null) {
            return AgentStateDelta(
                false,
                false,
                false,
                emptyList(),
                false,
                "No authoritative after-observation available.",
            )
        }
        val pageChanged = before.page.pageKey != after.page.pageKey
        val packageChanged = before.page.packageName != after.page.packageName
        val accessibilityChanged =
            before.payload.optString("accessibilityFingerprint") !=
                after.payload.optString("accessibilityFingerprint")
        val semanticChanges = verification.basis
            ?.takeIf { it.endsWith("_STATE_CHANGED") || it == "EDITABLE_TEXT_CHANGED" }
            ?.let(::listOf)
            .orEmpty()
        val goalAppeared = verification.basis == "GOAL_LABEL_APPEARED"
        val pieces = buildList {
            if (packageChanged) add("package changed")
            if (pageChanged) add("page changed")
            if (semanticChanges.isNotEmpty()) add(semanticChanges.joinToString())
            if (goalAppeared) add("goal label appeared")
            if (accessibilityChanged && !pageChanged && semanticChanges.isEmpty()) {
                add("accessibility fingerprint changed")
            }
        }
        return AgentStateDelta(
            pageChanged,
            packageChanged,
            accessibilityChanged,
            semanticChanges,
            goalAppeared,
            pieces.joinToString("; ").ifBlank { "No semantic delta proven." },
        )
    }

    private fun failureEnvelope(
        tool: String,
        goal: String,
        failure: AgentFailure,
        before: GatewayObservation? = null,
        generation: Long? = null,
    ): AgentActionEnvelope = AgentActionEnvelope(
        tool = tool,
        goal = goal,
        androidExecutionOk = false,
        executorReportedOk = false,
        verification = AgentSemanticVerification(
            AgentVerificationStatus.NOT_REQUIRED,
            false,
            false,
            "EXECUTION_NOT_ACCEPTED",
        ),
        before = before?.let {
            pageCard(
                it,
                goal,
                generation ?: scope.generation,
                actionable = scope.observationId == it.id,
            )
        },
        after = null,
        pageChanged = false,
        delta = AgentStateDelta(
            false,
            false,
            false,
            emptyList(),
            false,
            "No mutation was verified.",
        ),
        errorClass = failure.errorClass,
        failureLayer = failure.failureLayer,
        retryable = failure.retryable,
        semanticSuccessClaimed = false,
        beforeObservationId = before?.id,
        afterObservationId = null,
        observationGeneration = generation,
        learning = AgentLearningResult(false, "No verified route outcome to learn."),
        safeMessage = failure.message,
    )

    private fun failureFromPhoneResult(result: PhoneToolResult): AgentFailure {
        val error = result.error
        val code = error?.code
        val message = com.cyclone.mobile.agent.contract.HarnessFailureCopy.describe(code?.name)
        return when (code) {
            PhoneToolErrorCode.FRESH_OBSERVATION_REQUIRED,
            PhoneToolErrorCode.STALE_ELEMENT,
            -> staleFailure(message)
            PhoneToolErrorCode.ELEMENT_NOT_FOUND -> AgentFailure(
                AgentFailureClass.TARGET_NOT_FOUND,
                AgentFailureLayer.OBSERVATION,
                true,
                message,
                code.name,
            )
            PhoneToolErrorCode.HUMAN_HAS_CONTROL -> AgentFailure(
                AgentFailureClass.HUMAN_HAS_CONTROL,
                AgentFailureLayer.DEVICE,
                true,
                message,
                code.name,
            )
            PhoneToolErrorCode.ACCESSIBILITY_NOT_CONNECTED -> AgentFailure(
                AgentFailureClass.ACCESSIBILITY_UNAVAILABLE,
                AgentFailureLayer.DEVICE,
                true,
                message,
                code.name,
            )
            PhoneToolErrorCode.CAPABILITY_UNAVAILABLE -> AgentFailure(
                AgentFailureClass.CAPABILITY_UNAVAILABLE,
                AgentFailureLayer.CAPABILITY,
                true,
                message,
                code.name,
            )
            PhoneToolErrorCode.APP_NOT_FOUND -> AgentFailure(
                AgentFailureClass.TARGET_NOT_FOUND,
                AgentFailureLayer.EXECUTION,
                true,
                message,
                code.name,
            )
            PhoneToolErrorCode.WORKSPACE_SCOPE_CONFLICT -> AgentFailure(
                AgentFailureClass.EXECUTION_FAILED,
                AgentFailureLayer.EXECUTION,
                true,
                message,
                code.name,
            )
            PhoneToolErrorCode.TARGET_SCOPE_MISMATCH -> AgentFailure(
                AgentFailureClass.STALE_OBSERVATION,
                AgentFailureLayer.OBSERVATION,
                true,
                message,
                code.name,
            )
            PhoneToolErrorCode.TIMEOUT -> AgentFailure(
                AgentFailureClass.TIMEOUT,
                AgentFailureLayer.EXECUTION,
                true,
                message,
                code.name,
            )
            PhoneToolErrorCode.POLICY_DENIED,
            PhoneToolErrorCode.SECURITY_RESTRICTION,
            -> {
                val gate = OverlayChromeRuntime.snapshot().state == OverlayChromeState.GATE
                AgentFailure(
                    if (gate) AgentFailureClass.GATE_REQUIRED else AgentFailureClass.POLICY_DENIED,
                    AgentFailureLayer.POLICY,
                    gate,
                    message,
                    code.name,
                )
            }
            PhoneToolErrorCode.INVALID_REQUEST -> AgentFailure(
                AgentFailureClass.INVALID_REQUEST,
                AgentFailureLayer.INPUT,
                false,
                message,
                code.name,
            )
            else -> AgentFailure(
                AgentFailureClass.EXECUTION_FAILED,
                AgentFailureLayer.EXECUTION,
                true,
                message,
                code?.name,
            )
        }
    }

    private fun failureFromThrowable(
        error: Throwable,
        defaultLayer: AgentFailureLayer,
    ): AgentFailure {
        // WorkspaceRuntime still exposes these fixed codes as IllegalStateException messages.
        // Preserve the code, never the arbitrary message suffix (which may contain screen data).
        val workspaceCode = when (error) {
            is GatewayProtocolException -> error.code
            is IllegalStateException -> error.message?.substringBefore(':')?.trim()
            else -> null
        }
        when (workspaceCode) {
            "OBSERVATION_CHANGED_DURING_CAPTURE" -> return AgentFailure(AgentFailureClass.AFTER_OBSERVATION_FAILED,
                AgentFailureLayer.OBSERVATION, true, "The screen or task scope changed during capture; retry a fresh same-scope observation.", workspaceCode)
            "BACKEND_DISCONNECTED" -> return AgentFailure(AgentFailureClass.DEVICE_DISCONNECTED,
                AgentFailureLayer.OBSERVATION, false, "The workspace backend is disconnected.", workspaceCode)
            "STALE_SESSION" -> return AgentFailure(AgentFailureClass.STALE_OBSERVATION,
                AgentFailureLayer.OBSERVATION, false, "The requested workspace session is no longer available.", workspaceCode)
            "FOREGROUND_REQUIRED" -> return AgentFailure(AgentFailureClass.TARGET_NOT_FOUND,
                AgentFailureLayer.OBSERVATION, false, "The target app is not observable on this task's display.", workspaceCode)
            "ACCESSIBILITY_NOT_CONNECTED" -> return AgentFailure(AgentFailureClass.ACCESSIBILITY_UNAVAILABLE,
                AgentFailureLayer.OBSERVATION, false, "The Accessibility service is disconnected.", workspaceCode)
        }
        if (error is com.cyclone.mobile.runtime.session.SessionIdentityException) return AgentFailure(
            AgentFailureClass.STALE_OBSERVATION, AgentFailureLayer.OBSERVATION, false,
            "The requested session/display identity is unavailable or changed.", error.errorClass)
        if (error is SecurityException) return AgentFailure(AgentFailureClass.ACCESSIBILITY_UNAVAILABLE,
            AgentFailureLayer.OBSERVATION, false, "Observation permission is unavailable.", "OBSERVATION_PERMISSION_REQUIRED")
        if (error is GatewayProtocolException) {
            return when (error.code) {
                "SESSION_REQUIRED", "SESSION_NOT_FOUND", "SESSION_UNKNOWN", "SESSION_DISPLAY_MISMATCH", "OBSERVATION_SESSION_MISMATCH" ->
                    AgentFailure(AgentFailureClass.STALE_OBSERVATION, AgentFailureLayer.OBSERVATION, false,
                        "The requested session/display identity is unavailable or changed.", error.code)
                "STALE_OBSERVATION", "STALE_ELEMENT" -> staleFailure(
                    com.cyclone.mobile.agent.contract.HarnessFailureCopy.describe(error.code),
                )
                "ELEMENT_NOT_FOUND" -> AgentFailure(
                    AgentFailureClass.TARGET_NOT_FOUND,
                    AgentFailureLayer.OBSERVATION,
                    true,
                    com.cyclone.mobile.agent.contract.HarnessFailureCopy.describe(error.code),
                    error.code,
                )
                "POLICY_DENIED" -> AgentFailure(
                    AgentFailureClass.POLICY_DENIED,
                    AgentFailureLayer.POLICY,
                    false,
                    com.cyclone.mobile.agent.contract.HarnessFailureCopy.describe(error.code),
                    error.code,
                )
                "ACCESSIBILITY_NOT_CONNECTED" -> AgentFailure(
                    AgentFailureClass.ACCESSIBILITY_UNAVAILABLE,
                    AgentFailureLayer.DEVICE,
                    true,
                    com.cyclone.mobile.agent.contract.HarnessFailureCopy.describe(error.code),
                    error.code,
                )
                "CAPABILITY_UNAVAILABLE" -> AgentFailure(
                    AgentFailureClass.CAPABILITY_UNAVAILABLE,
                    AgentFailureLayer.CAPABILITY,
                    true,
                    com.cyclone.mobile.agent.contract.HarnessFailureCopy.describe(error.code),
                    error.code,
                )
                "AUTH_REJECTED" -> AgentFailure(
                    AgentFailureClass.AUTH_REQUIRED,
                    AgentFailureLayer.POLICY,
                    true,
                    com.cyclone.mobile.agent.contract.HarnessFailureCopy.describe(error.code),
                    error.code,
                )
                "TIMEOUT" -> AgentFailure(
                    AgentFailureClass.TIMEOUT,
                    defaultLayer,
                    true,
                    com.cyclone.mobile.agent.contract.HarnessFailureCopy.describe(error.code),
                    error.code,
                )
                else -> AgentFailure(
                    AgentFailureClass.EXECUTION_FAILED,
                    defaultLayer,
                    true,
                    com.cyclone.mobile.agent.contract.HarnessFailureCopy.describe(error.code),
                    error.code,
                )
            }
        }
        return AgentFailure(
            AgentFailureClass.EXECUTION_FAILED,
            defaultLayer,
            true,
            com.cyclone.mobile.agent.contract.HarnessFailureCopy.describe(null),
        )
    }

    private fun remember(envelope: AgentActionEnvelope) {
        actionHistory.addLast(
            envelope.copy(
                before = envelope.before?.copy(controls = emptyList(), legacyPage = null),
                after = envelope.after?.copy(controls = emptyList(), legacyPage = null),
            ),
        )
        while (actionHistory.size > HISTORY_LIMIT) actionHistory.removeFirst()
    }

    private fun hasFreeformSelector(params: JSONObject): Boolean =
        params.optJSONObject("selector") != null ||
            SELECTOR_KEYS.any { params.has(it) }

    private fun elementId(params: JSONObject, observation: GatewayObservation? = null): String? {
        val direct = params.optString("elementId").takeIf(String::isNotBlank)
            ?: params.optJSONObject("selector")
                ?.optString("elementId")
                ?.takeIf(String::isNotBlank)
            ?: params.optJSONObject("selector")
                ?.optString("ref")
                ?.takeIf(String::isNotBlank)
        if (direct != null) return direct
        val index = when {
            params.has("elementIndex") -> params.optInt("elementIndex")
            params.has("element_index") -> params.optInt("element_index")
            else -> params.optJSONObject("selector")?.optInt("elementIndex", -1) ?: -1
        }
        if (index < 1 || observation == null) return null
        val byEvidence = observation.elements.mapValues { it.value.evidence }
        return FastPathTree.lookupElementId(byEvidence, index)
    }

    private fun belongsToObservation(elementId: String, observationId: String): Boolean =
        elementId.split(':').getOrNull(1)?.let { it == observationId } ?: false

    private fun staleFailure(message: String) = AgentFailure(
        AgentFailureClass.STALE_OBSERVATION,
        AgentFailureLayer.OBSERVATION,
        true,
        message,
        "STALE_OBSERVATION",
    )

    private fun copyObject(value: JSONObject?): JSONObject =
        value?.let { JSONObject(it.toString()) } ?: JSONObject()

    private fun copyArray(value: JSONArray?): JSONArray =
        value?.let { JSONArray(it.toString()) } ?: JSONArray()

    companion object {
        private const val DEFAULT_LOCATE_LIMIT = 8
        private const val DEFAULT_SEARCH_LIMIT = 20
        private const val PAGE_CARD_GOAL_CANDIDATES = 10
        private const val PAGE_CARD_CONTROL_LIMIT = 36
        private const val HISTORY_LIMIT = 40

        private val LOCAL_MUTATING_TOOLS = setOf(
            "phone.click",
            "phone.long_press",
            "phone.scroll",
            "phone.type",
            "phone.replace_text",
            "phone.back",
            "phone.home",
            "phone.open_app",
            "phone.launch_intent",
            "phone.wait_for",
            "phone.set_alarm",
            "phone.set_timer",
            "phone.open_settings",
            "phone.submit_text",
            "phone.tap_point",
            "phone.swipe",
            "phone.open_notification",
        )
        private val ELEMENT_ID_REQUIRED_TOOLS = setOf(
            "phone.click",
            "phone.long_press",
            "phone.type",
            "phone.replace_text",
            "phone.submit_text",
        )
        private val ELEMENT_SCOPED_TOOLS = ELEMENT_ID_REQUIRED_TOOLS + "phone.scroll"
        private val SELECTOR_KEYS = setOf(
            "resourceId",
            "text",
            "textContains",
            "contentDescription",
            "contentDescriptionContains",
            "class",
            "role",
            "ancestorText",
            "descendantText",
            "x",
            "y",
            "relativeToText",
            "relativeDirection",
            "fuzzyText",
            "path",
        )
    }
}

internal class AgentObservationScope {
    var observationId: String? = null
        private set

    var generation: Long = 0
        private set

    fun publish(id: String, sourceGeneration: Long? = null): Long {
        require(id.isNotBlank())
        generation = sourceGeneration ?: (generation + 1)
        observationId = id
        return generation
    }

    fun expire() {
        observationId = null
    }
}

internal interface CycloneAgentRuntimePort {
    fun cameraImages(): Map<Long, Long>? = null
    fun capture(): GatewayObservation
    fun captureWithImage(): GatewayObservation = capture()
    fun current(): GatewayObservation?
    fun search(observation: GatewayObservation, query: String, limit: Int): JSONArray
    fun element(observation: GatewayObservation, elementId: String): JSONObject
    fun screenshot(goal: String): JSONObject
    fun readinessFailure(): AgentFailure?
    fun policyFailure(tool: String, params: JSONObject): AgentFailure?
    fun execute(requestId: String, tool: String, params: JSONObject): PhoneToolResult
    fun captureAfter(
        tool: String,
        params: JSONObject,
        before: GatewayObservation,
    ): GatewayObservation?

    fun verify(
        tool: String,
        expectedPackage: String,
        goalLabel: String,
        before: GatewayObservation,
        after: GatewayObservation?,
        androidExecutionOk: Boolean,
        executorAssertionFailed: Boolean,
        explicitExpectation: Boolean,
    ): AgentSemanticVerification

    fun recordLearning(
        goal: String,
        tool: String,
        params: JSONObject,
        before: GatewayObservation,
        after: GatewayObservation?,
        androidExecutionOk: Boolean,
        verification: AgentSemanticVerification,
    ): AgentLearningResult

    fun brainRecall(goal: String): JSONObject
    fun knownRoutes(goal: String): JSONObject
    fun allControls(observation: GatewayObservation): JSONArray = JSONArray()
    fun fieldValue(observation: GatewayObservation, elementId: String): String? = null
}

private class AndroidCycloneAgentRuntimePort(
    private val context: Context,
    private val execution: com.cyclone.mobile.runtime.session.ExecutionContext,
) : CycloneAgentRuntimePort {
    override fun cameraImages(): Map<Long, Long>? = runCatching {
        // No permission request or image bytes: only an already-authorized camera media witness.
        if (context.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        context.contentResolver.query(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Images.Media._ID, android.provider.MediaStore.Images.Media.DATE_TAKEN), "${android.provider.MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${android.provider.MediaStore.Images.Media.IS_PENDING}=0",
            arrayOf("DCIM/Camera/%"), "${android.provider.MediaStore.Images.Media._ID} DESC")?.use { cursor ->
                buildMap { while (cursor.moveToNext() && size < 32) put(cursor.getLong(0), cursor.getLong(1)) }
            }
    }.getOrNull()

    private val background get() = execution.sessionId != "default-foreground"
    private fun scoped(params: JSONObject = JSONObject()) = com.cyclone.mobile.runtime.session.ExecutionRequestScope.merge(
        JSONObject().put("sessionId", execution.sessionId).put("displayId", execution.displayId), params)

    override fun capture(): GatewayObservation = GatewayObservationAdapter.capture(context, scoped())
    override fun allControls(observation: GatewayObservation): JSONArray = GatewayObservationAdapter.controls(observation)
    override fun fieldValue(observation: GatewayObservation, elementId: String): String? =
        GatewayObservationAdapter.fieldValue(observation, elementId)
    override fun captureWithImage(): GatewayObservation = GatewayObservationAdapter.capture(context,
        scoped(JSONObject().put("includeScreenshot", true).put("includeScreenshotBase64", true)))
    override fun current(): GatewayObservation? = GatewayObservationStore.current(execution.sessionId)

    override fun search(
        observation: GatewayObservation,
        query: String,
        limit: Int,
    ): JSONArray = GatewayObservationAdapter.search(observation, query, limit)

    override fun element(
        observation: GatewayObservation,
        elementId: String,
    ): JSONObject = GatewayObservationAdapter.element(observation, elementId)

    override fun screenshot(goal: String): JSONObject = if (background) {
        val result = PhoneToolExecutor.execute(context, PhoneToolRequest("workspace-capture-${UUID.randomUUID()}", "phone.screenshot", scoped()))
        if (!result.ok) throw IllegalStateException(result.error?.message)
        result.payload as JSONObject
    } else GatewayCaptureAdapter.capture(
        context,
        JSONObject().put("maxDimension", 960).put("includeBase64", false),
    ).apply {
        remove("base64")
    }

    override fun readinessFailure(): AgentFailure? = when {
        CycloneAccessibilityService.instance == null || !DeviceState.accessibilityConnected -> AgentFailure(
            AgentFailureClass.ACCESSIBILITY_UNAVAILABLE,
            AgentFailureLayer.DEVICE,
            true,
            "Cyclone Accessibility is not connected.",
            "ACCESSIBILITY_UNAVAILABLE",
        )
        (if (background) !backgroundInput() else DeviceState.controller != DeviceState.Controller.AGENT) -> AgentFailure(
            AgentFailureClass.HUMAN_HAS_CONTROL,
            AgentFailureLayer.DEVICE,
            true,
            "Human currently owns device input.",
            "HUMAN_HAS_CONTROL",
        )
        !background && DeviceState.requireFreshObservation -> AgentFailure(
            AgentFailureClass.STALE_OBSERVATION,
            AgentFailureLayer.OBSERVATION,
            true,
            "A fresh observation is required before mutation.",
            "STALE_OBSERVATION",
        )
        else -> null
    }

    /**
     * Plan 28: a Mind mission's background screen whose input was paused while no switch runs has lost it by accident
     * (nobody else holds it): take it back instead of waiting for an owner hand-back that will never come.
     */
    private fun backgroundInput(): Boolean {
        val runtime = com.cyclone.mobile.runtime.background.WorkspaceRuntime
        if (runtime.ownsInput(execution.sessionId)) return true
        val plane = com.cyclone.mobile.runtime.plane.MissionPlanes.ui.value
        if (plane?.backgroundSessionId != execution.sessionId || plane.switching || plane.waitingFor != null) return false
        return runtime.reclaim(execution.sessionId) && runtime.ownsInput(execution.sessionId)
    }

    override fun policyFailure(tool: String, params: JSONObject): AgentFailure? {
        val decision = CycloneAiAccessPolicy.evaluate(
            CycloneAiAccessProfileStore.read(context),
            tool,
            params,
        )
        if (decision.allowed) return null
        val localConfirmation = decision.reasonCode == "LOCAL_CONFIRMATION_REQUIRED"
        if (localConfirmation && tool in setOf("phone.click", "phone.long_press", "phone.submit_text")) {
            val selector = params.optJSONObject("selector") ?: params
            val labels = listOf(
                selector.optString("text"),
                selector.optString("textContains"),
                selector.optString("contentDescription"),
                selector.optString("contentDescriptionContains"),
                selector.optString("fuzzyText"),
                selector.optString("descendantText"),
                selector.optString("resourceId"),
            ).map(String::trim).filter(String::isNotBlank)
            val gateClass = GateClassifier.classify(tool, labels)?.let(ClickGateIntercept::overlayClass)
                ?: if (tool == "phone.submit_text") com.cyclone.mobile.ui.overlay.OverlayGateClass.SEND else null
            if (gateClass != null) {
                // Policy may let an explicitly confirmed exact action proceed, but does not consume
                // the grant. The final Accessibility click interceptor consumes the one-shot token.
                // Enter has no click interceptor, so its one-shot grant is consumed here.
                if (tool == "phone.submit_text" && OverlayChromeRuntime.consumeGateApproval(gateClass, tool, labels)) return null
                if (OverlayChromeRuntime.hasGateApproval(gateClass, tool, labels)) return null
                OverlayChromeRuntime.registerGateChallenge(gateClass, tool, labels)
                OverlayChromeRuntime.enterGate(gateClass)
                return AgentFailure(
                    AgentFailureClass.GATE_REQUIRED,
                    AgentFailureLayer.POLICY,
                    true,
                    decision.safeMessage,
                    decision.reasonCode,
                )
            }
        }
        if (localConfirmation && tool in setOf("phone.type", "phone.replace_text")) {
            return AgentFailure(
                AgentFailureClass.AUTH_REQUIRED,
                AgentFailureLayer.POLICY,
                true,
                decision.safeMessage,
                decision.reasonCode,
            )
        }
        return AgentFailure(
            errorClass = if (localConfirmation) AgentFailureClass.GATE_REQUIRED else AgentFailureClass.POLICY_DENIED,
            failureLayer = AgentFailureLayer.POLICY,
            retryable = localConfirmation,
            message = decision.safeMessage,
            reasonCode = decision.reasonCode,
        )
    }

    override fun execute(
        requestId: String,
        tool: String,
        params: JSONObject,
    ): PhoneToolResult = PhoneToolExecutor.execute(
        context,
        PhoneToolRequest(requestId, tool, scoped(params)),
    )

    override fun captureAfter(
        tool: String,
        params: JSONObject,
        before: GatewayObservation,
    ): GatewayObservation? =
        GatewayV33ActionAdapter.captureAfterAction(context, tool, scoped(params), before)

    override fun verify(
        tool: String,
        expectedPackage: String,
        goalLabel: String,
        before: GatewayObservation,
        after: GatewayObservation?,
        androidExecutionOk: Boolean,
        executorAssertionFailed: Boolean,
        explicitExpectation: Boolean,
    ): AgentSemanticVerification = GatewayV33ActionAdapter.verifyAfterState(
        tool = tool,
        expectedPackage = expectedPackage,
        goalLabel = goalLabel,
        beforeObservation = before,
        afterObservation = after,
        androidExecutionOk = androidExecutionOk,
        executorAssertionFailed = executorAssertionFailed,
        explicitExpectation = explicitExpectation,
    )

    override fun recordLearning(
        goal: String,
        tool: String,
        params: JSONObject,
        before: GatewayObservation,
        after: GatewayObservation?,
        androidExecutionOk: Boolean,
        verification: AgentSemanticVerification,
    ): AgentLearningResult {
        val verificationJson = JSONObject()
            .put("ok", verification.passed)
            .put("status", verification.status.name)
            .put("semanticSuccessClaimed", verification.semanticSuccessClaimed)
            .put("basis", verification.basis ?: JSONObject.NULL)
        val result = GatewayV33ActionAdapter.recordVerifiedRouteOutcome(
            context = context,
            goal = goal,
            tool = tool,
            params = params,
            before = before,
            after = after,
            transportOk = true,
            androidExecutionOk = androidExecutionOk,
            verification = verificationJson,
            brainSource = "LOCAL_AGENT_VERIFIED_ROUTE",
        )
        return AgentLearningResult(
            recorded = result.optBoolean("recorded", false),
            reason = result.optString("reason").ifBlank {
                "Verified route learning returned no reason."
            },
            evidence = JSONObject(result.toString()),
        )
    }

    override fun brainRecall(goal: String): JSONObject =
        GatewayBrainAdapter.recall(context, JSONObject().put("goal", goal))

    override fun knownRoutes(goal: String): JSONObject =
        GatewayAppGraphAdapter.query(context, JSONObject().put("goal", goal))
}
