package com.cyclone.mobile.agent.integration

import android.content.Context
import com.cyclone.mobile.agent.CycloneObservation
import com.cyclone.mobile.agent.contract.AgentActionEnvelope
import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentFailureClass
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.agent.contract.GoalContractCompiler
import com.cyclone.mobile.agent.recovery.AgenticRecoveryRuntimePort
import com.cyclone.mobile.agent.recovery.DefaultAgenticRecoveryRuntimePort
import com.cyclone.mobile.agent.recovery.EvidenceSource
import com.cyclone.mobile.agent.recovery.ObservationEvidence
import com.cyclone.mobile.agent.recovery.ProgressClassification
import com.cyclone.mobile.agent.recovery.ProgressResult
import com.cyclone.mobile.agent.recovery.RecoverableCause
import com.cyclone.mobile.agent.recovery.RecoveryDecision
import com.cyclone.mobile.agent.recovery.RecoveryLevel
import com.cyclone.mobile.agent.recovery.RecoveryMemory
import com.cyclone.mobile.agent.recovery.RecoveryRequest
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironment
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironmentApi
import com.cyclone.mobile.ai.AgentTraceRuntime
import com.cyclone.mobile.fastpath.FastPathLanding
import com.cyclone.mobile.fastpath.FastPathSurface
import com.cyclone.mobile.fastpath.FastPathTimings
import com.cyclone.mobile.skills.SkillRuntime
import com.cyclone.mobile.ai.PageAgentAction
import com.cyclone.mobile.ai.PageAgentProtocol
import com.cyclone.mobile.applearner.LearnedAction
import com.cyclone.mobile.applearner.PageContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Production bridge between the persistent local task runtime and the same Android contract used
 * by Cyclone PC. This class owns no Android execution: CycloneAgentEnvironment remains the only
 * action/observation boundary and PhoneToolExecutor remains canonical underneath it.
 */
class CyclonePcParityBridge internal constructor(
    private val environment: CycloneAgentEnvironmentApi,
    private val recovery: AgenticRecoveryRuntimePort = DefaultAgenticRecoveryRuntimePort(),
    private val execution: com.cyclone.mobile.runtime.session.ExecutionContext = com.cyclone.mobile.runtime.session.ExecutionContext.DEFAULT,
) {
    constructor(context: Context, execution: com.cyclone.mobile.runtime.session.ExecutionContext = com.cyclone.mobile.runtime.session.ExecutionContext.DEFAULT, userTaskGoal: String? = null) :
        this(CycloneAgentEnvironment(context.applicationContext, execution, userTaskGoal), execution = execution)

    var onOperation: ((String, AgentActionEnvelope?) -> Unit)? = null
    private var page: AgentPageCard? = null
    var observationHealth = com.cyclone.mobile.agent.ObservationHealth(com.cyclone.mobile.agent.ObservationState.UNAVAILABLE, execution.sessionId, execution.displayId)
        private set
    private var memory: RecoveryMemory = RecoveryMemory()
    private var lastRecovery: RecoveryDecision? = null
    private var searchEvidence: List<AgentElementCandidate> = emptyList()
    private var inspectionEvidence: List<JSONObject> = emptyList()
    private var brainEvidence: JSONObject? = null
    private var routeEvidence: JSONObject? = null
    private var forceVision = false
    private var visualCaptureAttempts = 0
    private var visualCaptureInFlight = false
    var incident: com.cyclone.mobile.agent.recovery.RecoveryIncident? = null

    @Synchronized fun observe(goal: String): AgentPageCard? = observeInternal(goal, false)?.page

    @Synchronized fun observeWithImage(goal: String): com.cyclone.mobile.agent.contract.AgentObservationResult? = observeInternal(goal, true)

    private fun observeInternal(goal: String, withImage: Boolean): com.cyclone.mobile.agent.contract.AgentObservationResult? {
        val now = System.nanoTime() / 1_000_000
        if (observationHealth.attempts > 0 && (observationHealth.terminal || now < observationHealth.cooldownUntilMs)) return null
        val previousKey = page?.pageKey
        val result = if (withImage) environment.observeWithImage(goal) else environment.locate(goal).let {
            com.cyclone.mobile.agent.contract.AgentObservationResult(page = it.page, failure = it.failure)
        }
        val fresh = result.page ?: run {
            page = null
            environment.invalidateObservation()
            observationHealth = com.cyclone.mobile.agent.ObservationHealth.failure(result.failure, execution.sessionId,
                execution.displayId, observationHealth.attempts + 1, observationHealth.lastSuccessMs, now)
            return null
        }
        if (fresh.sessionId != execution.sessionId || fresh.displayId != execution.displayId) {
            page = null
            environment.invalidateObservation()
            observationHealth = com.cyclone.mobile.agent.ObservationHealth(com.cyclone.mobile.agent.ObservationState.SCOPE_MISMATCH,
                execution.sessionId, execution.displayId, 1)
            return null
        }
        observationHealth = com.cyclone.mobile.agent.ObservationHealth(
            if (fresh.controls.isEmpty()) com.cyclone.mobile.agent.ObservationState.EMPTY_VALID else com.cyclone.mobile.agent.ObservationState.HEALTHY,
            execution.sessionId, execution.displayId, lastSuccessMs = fresh.capturedAtMs)
        page = fresh
        if (previousKey == null) {
            memory = RecoveryMemory(
                attemptedLevels = setOf(RecoveryLevel.CURRENT_SEMANTIC_PAGE),
                attemptedEvidence = setOf(EvidenceSource.CURRENT_SEMANTIC_PAGE),
            )
            searchEvidence = emptyList()
            inspectionEvidence = emptyList()
            forceVision = false
            lastRecovery = null
        } else {
            memory = memory.copy(
                attemptedLevels = memory.attemptedLevels + RecoveryLevel.CURRENT_SEMANTIC_PAGE,
                attemptedEvidence = memory.attemptedEvidence + EvidenceSource.CURRENT_SEMANTIC_PAGE,
            )
        }
        return result
    }

    fun invalidateAfterHandoff() {
        observationHealth = com.cyclone.mobile.agent.ObservationHealth(com.cyclone.mobile.agent.ObservationState.UNAVAILABLE, execution.sessionId, execution.displayId)
        environment.invalidateObservation()
        page = null
        searchEvidence = emptyList()
        inspectionEvidence = emptyList()
        memory = RecoveryMemory()
        visualCaptureAttempts = 0
        visualCaptureInFlight = false
        lastRecovery = null
        forceVision = false
    }

    fun currentPage(): AgentPageCard? = page

    fun invalidateCapture() {
        environment.invalidateObservation()
        page = null
        searchEvidence = emptyList()
        inspectionEvidence = emptyList()
    }

    fun observation(): CycloneObservation? = page?.let { card ->
        // Observation IDs intentionally do NOT participate in convergence. They rotate after every
        // capture for selector safety, while this witness changes only when semantic evidence does.
        val semanticWitness = listOf(card.pageKey, card.contentKey, card.accessibilityFingerprint)
            .joinToString("|")
        CycloneObservation(
            sessionId = card.sessionId, displayId = card.displayId, generation = card.generation, packageName = card.packageName,
            identity = semanticWitness,
            pageIdentity = card.pageKey,
            evidenceIdentity = semanticWitness,
        )
    }

    fun promptContext(goal: String): JSONObject {
        val card = page
        val history = environment.history()
        val contract = GoalContractCompiler.compile(goal)
        val completion = GoalContractCompiler.evaluate(contract, card, history)
        val out = JSONObject()
            .put("contract", "cyclone-pc-parity-local-v2")
            .put("goal", goal)
            .put("recoveryIncident", incident?.toJson() ?: JSONObject.NULL)
            .put("observationHealth", observationHealth.toJson())
            .put("goalContract", contract.toJson())
            .put("completionState", completion.toJson())
            .put("staleIdRule", "elementId and elementIndex are valid only for the current observation; re-locate after every mutation")
            .put("verificationRule", "executor acceptance is never semantic success; ordinary taps use local fingerprint settle (300ms then +500/+1000). Unchanged is not a second click.")
            .put("completionRule", "done means every goalContract requirement is independently satisfied; do not keep acting after the contract is satisfied")
            .put(
                "fastPath",
                JSONObject()
                    .put("settleMs", FastPathTimings.SETTLE_MS)
                    .put("ladderMs", JSONArray(FastPathTimings.LADDER_MS.toList()))
                    .put("navIsolation", "one screen-changing mutation per turn; form field batching allowed before navigation")
                    .put("tapVerification", "local fingerprint after settle is authority for ordinary taps; do not extra-verify with the model")
                    .put("vision", "escalate only when perceptionMode=vision_escalate or the a11y tree is useless")
                    .put("surface", FastPathSurface.toJson()),
            )
        if (card != null) {
            out.put("pageCard", pageCardJson(card))
            out.put(
                "scene",
                JSONObject()
                    .put("authoritativePackage", card.packageName)
                    .put("activity", card.activity ?: JSONObject.NULL)
                    .put("pageKey", card.pageKey)
                    .put("taskSurfaceLooksCycloneOwned", card.packageName == "com.cyclone.mobile")
                    .put("pageSummary", JSONObject(card.pageSummary.toString()))
                    .put("visibleTextExcerpt", compactText(card))
                    .put("interruptions", interruptionCandidates(card))
                    .put("perceptionMode", card.perceptionMode)
                    .put("treeUseful", card.treeUseful),
            )
        }
        FastPathLanding.resolve(goal)?.let { landing ->
            out.put("fastPathLanding", landing.toJson())
            out.put(
                "landingRule",
                "Prefer this open_app/intent landing before hunting launcher icons. Named apps and installed-app jobs both land locally.",
            )
        }
        card?.let { current ->
            SkillRuntime.match(
                packageName = current.packageName,
                goal = goal,
                startPageKey = current.pageKey,
                sessionId = environmentSessionId(),
                displayId = environmentDisplayId(),
            )?.let { route ->
                out.put("compiledSkill", route.toJson())
                out.put(
                    "compiledSkillRule",
                    "A compiled PhoneToolExecutor skill matches this package/goal/page/session. Replay it before a Fast Path LLM turn. Vision only on miss.",
                )
            }
        }
        out.put("recentOutcomes", recentOutcomeJson(history))
        val recoveryJson = JSONObject()
            .put("attemptedLevels", JSONArray(memory.attemptedLevels.sortedBy { it.stage }.map { it.name }))
            .put("attemptedEvidence", JSONArray(memory.attemptedEvidence.map { it.name }))
            .put("visualCaptureAttempts", visualCaptureAttempts)
            .put("usableVisualCaptures", memory.capturesForSemanticState)
            .put("semanticSearchExhausted", memory.semanticSearchExhausted)
            .put("materiallyDifferentActionsWithoutProgress", memory.materiallyDifferentActionsWithoutProgress)
        lastRecovery?.let {
            recoveryJson
                .put("suggestedLevel", it.level?.name ?: JSONObject.NULL)
                .put("reason", it.reason)
                .put("visionTriggers", JSONArray(it.visionTriggers.map { trigger -> trigger.name }))
        }
        if (searchEvidence.isNotEmpty()) {
            recoveryJson.put("searchCandidates", JSONArray().also { array ->
                searchEvidence.take(12).forEach { array.put(candidateJson(it)) }
            })
        }
        if (inspectionEvidence.isNotEmpty()) {
            recoveryJson.put("inspectedElements", JSONArray().also { array ->
                inspectionEvidence.take(6).forEach { array.put(JSONObject(it.toString())) }
            })
        }
        brainEvidence?.let { recoveryJson.put("brainRecall", JSONObject(it.toString())) }
        routeEvidence?.let { recoveryJson.put("knownRoutes", JSONObject(it.toString())) }
        out.put("recovery", recoveryJson)
        traceModelContext(out, card, history)
        return out
    }

    fun act(action: PageAgentAction, legacyPage: PageContext, goal: String): AgentActionEnvelope {
        // Normalize strict non-element tool arguments (for example Chrome -> package)
        // before entering the PC-parity action boundary. Element targeting is still re-resolved
        // against the authoritative current observation below.
        val params = PageAgentProtocol.resolveParams(action, legacyPage)
            .getOrElse { JSONObject(action.params.toString()) }
        params.remove("selector")
        val needsElement = action.tool in ELEMENT_TOOLS
        if (needsElement) {
            val elementId = resolveElementId(action.controlId, legacyPage, goal)
            if (elementId != null) params.put("elementId", elementId)
        }
        onOperation?.invoke(action.tool, null)
        return environment.act(action.tool, params, goal).also { envelope ->
            onOperation?.invoke(action.tool, envelope)
            page = envelope.after ?: page
        }
    }

    fun actGraph(action: LearnedAction, goal: String): AgentActionEnvelope {
        val query = action.label.ifBlank { action.id }
        val candidate = search(query, goal).filter { normalize(it.label) == normalize(query) || normalize(it.semanticName) == normalize(query) }.singleOrNull()
        val params = JSONObject()
        if (candidate != null) params.put("elementId", candidate.elementId)
        onOperation?.invoke("phone.click", null)
        return environment.act("phone.click", params, goal).also { envelope ->
            onOperation?.invoke("phone.click", envelope)
            page = envelope.after ?: page
        }
    }

    fun classifyProgress(envelope: AgentActionEnvelope): ProgressResult {
        val before = envelope.before?.let(::observationEvidence)
        val after = envelope.after?.let(::observationEvidence)
        return if (after == null) {
            ProgressResult(ProgressClassification.NO_PROGRESS, setOf("after_observation_missing"))
        } else {
            recovery.classifyProgress(before, after)
        }
    }

    /**
     * Selects and performs only read-only recovery escalation. Mutation recovery (scroll/back/backtrack)
     * remains an explicit next model/tool decision so GATE/policy and user intent stay authoritative.
     */
    fun recover(cause: RecoverableCause, goal: String, failuresWithoutProgress: Int = 0): RecoveryDecision? {
        val card = page ?: observe(goal) ?: return null
        val request = RecoveryRequest(
            observation = observationEvidence(card),
            memory = memory,
            cause = cause,
            targetAbsentFromStructuredControls = cause == RecoverableCause.TARGET_MISSING_FROM_COMPACT_CONTROLS,
            actionAfterStateAmbiguous = cause in setOf(
                RecoverableCause.VERIFICATION_FAILED,
                RecoverableCause.AFTER_STATE_MISSING,
                RecoverableCause.AMBIGUOUS_SEMANTICS,
            ),
            repeatedStaleOrVanishingTargets = cause == RecoverableCause.STALE_SELECTOR,
            knownVerifiedRouteAvailable = knownKnowledgeAvailable(goal),
            semanticSearchAvailable = true,
            supplementalInspectionAvailable = card.controls.isNotEmpty(),
            boundedExplorationAvailable = true,
            backtrackOrAlternateBranchAvailable = true,
        )
        // A second grounding failure after semantic search needs pixels, even on a populated tree.
        val groundingFailure = cause in setOf(RecoverableCause.STALE_SELECTOR,
            RecoverableCause.TARGET_MISSING_FROM_COMPACT_CONTROLS, RecoverableCause.VERIFICATION_FAILED,
            RecoverableCause.AFTER_STATE_MISSING, RecoverableCause.AMBIGUOUS_SEMANTICS,
            RecoverableCause.SAME_PAGE_NO_EFFECT)
        val decision = if (failuresWithoutProgress >= 2 && groundingFailure &&
            RecoveryLevel.GOAL_RANKED_SEARCH in memory.attemptedLevels && memory.capturesForSemanticState == 0) {
            RecoveryDecision(RecoveryLevel.SILENT_SCREENSHOT_VISION, "grounding_failed_after_semantic_search")
        } else recovery.selectRecovery(request.copy(knownVerifiedRouteAvailable = request.knownVerifiedRouteAvailable && failuresWithoutProgress == 0))
        lastRecovery = decision
        when (decision.level) {
            RecoveryLevel.KNOWN_VERIFIED_ROUTE -> loadKnowledge(goal)
            RecoveryLevel.GOAL_RANKED_SEARCH -> {
                val results = environment.search(goal, goal)
                results.page?.let { page = it }
                searchEvidence = results.candidates
                memory = memory.copy(
                    attemptedLevels = memory.attemptedLevels + RecoveryLevel.GOAL_RANKED_SEARCH,
                    attemptedEvidence = memory.attemptedEvidence + EvidenceSource.SEMANTIC_SEARCH,
                    semanticSearchExhausted = results.candidates.isEmpty(),
                )
            }
            RecoveryLevel.ADDITIONAL_ELEMENT_INSPECTION -> {
                val candidates = if (searchEvidence.isNotEmpty()) searchEvidence else card.controls
                inspectionEvidence = candidates.take(4).mapNotNull { candidate ->
                    environment.inspect(candidate.elementId).evidence
                }
                memory = memory.copy(
                    attemptedLevels = memory.attemptedLevels + RecoveryLevel.ADDITIONAL_ELEMENT_INSPECTION,
                    attemptedEvidence = memory.attemptedEvidence + EvidenceSource.ELEMENT_INSPECTION,
                )
            }
            RecoveryLevel.BOUNDED_PAGE_EXPLORATION -> {
                memory = memory.copy(
                    attemptedLevels = memory.attemptedLevels + RecoveryLevel.BOUNDED_PAGE_EXPLORATION,
                    attemptedEvidence = memory.attemptedEvidence + EvidenceSource.PAGE_EXPLORATION,
                )
            }
            RecoveryLevel.SILENT_SCREENSHOT_VISION -> {
                forceVision = true
            }
            RecoveryLevel.BACKTRACK_OR_REPLAN -> {
                memory = memory.copy(
                    attemptedLevels = memory.attemptedLevels + RecoveryLevel.BACKTRACK_OR_REPLAN,
                    attemptedEvidence = memory.attemptedEvidence + EvidenceSource.BACKTRACK,
                )
            }
            RecoveryLevel.CURRENT_SEMANTIC_PAGE -> {
                memory = memory.copy(
                    attemptedLevels = memory.attemptedLevels + RecoveryLevel.CURRENT_SEMANTIC_PAGE,
                    attemptedEvidence = memory.attemptedEvidence + EvidenceSource.CURRENT_SEMANTIC_PAGE,
                )
            }
            RecoveryLevel.HUMAN_GATE, null -> Unit
        }
        if (cause in setOf(
                RecoverableCause.VERIFICATION_FAILED,
                RecoverableCause.WRONG_TARGET,
                RecoverableCause.SAME_PAGE_NO_EFFECT,
            )
        ) {
            memory = memory.copy(
                materiallyDifferentActionsWithoutProgress = memory.materiallyDifferentActionsWithoutProgress + 1,
            )
        }
        return decision
    }

    fun consumeForcedVision(): Boolean {
        val value = forceVision
        forceVision = false
        return value
    }

    /** Attempts are bounded separately from usable visual evidence. */
    @Synchronized fun claimVisionCapture(): Boolean {
        if (visualCaptureInFlight || visualCaptureAttempts >= 2 || memory.capturesForSemanticState > 0) return false
        visualCaptureAttempts++
        visualCaptureInFlight = true
        forceVision = false
        return true
    }

    @Synchronized fun finishVisionCapture(usable: Boolean) {
        if (!visualCaptureInFlight) return
        visualCaptureInFlight = false
        if (usable) memory = memory.copy(capturesForSemanticState = 1,
            attemptedLevels = memory.attemptedLevels + RecoveryLevel.SILENT_SCREENSHOT_VISION,
            attemptedEvidence = memory.attemptedEvidence + EvidenceSource.SCREENSHOT_VISION)
    }

    /** One bounded retry for unavailable pixels, with no provider call until usable evidence exists. */
    fun captureVisualEvidence(goal: String, pause: (Long) -> Unit = { Thread.sleep(it) }): com.cyclone.mobile.agent.contract.AgentObservationResult? {
        var result: com.cyclone.mobile.agent.contract.AgentObservationResult? = null
        repeat(2) { attempt ->
            if (!claimVisionCapture()) return result
            var usable = false
            try {
                result = observeWithImage(goal)
                usable = result?.page != null && result?.image?.optBoolean("available", false) == true &&
                    !result?.image?.optString("pngBase64").isNullOrBlank()
            } finally { finishVisionCapture(usable) }
            if (usable || observationHealth.terminal || visualCaptureAttempts >= 2 || attempt == 1) return result
            pause(500)
        }
        return result
    }

    fun markVerifiedProgress() {
        visualCaptureAttempts = 0
        visualCaptureInFlight = false
        memory = RecoveryMemory(
            attemptedLevels = setOf(RecoveryLevel.CURRENT_SEMANTIC_PAGE),
            attemptedEvidence = setOf(EvidenceSource.CURRENT_SEMANTIC_PAGE),
        )
        lastRecovery = null
        searchEvidence = emptyList()
        inspectionEvidence = emptyList()
        forceVision = false
    }

    fun photoEffect() = environment.photoEffect()

    fun completionEvidence(goal: String): Boolean {
        if (com.cyclone.mobile.agent.tools.PhotoEffectLedger.isSinglePhotoGoal(goal))
            return photoEffect() == com.cyclone.mobile.agent.tools.PhotoEffectLedger.State.VERIFIED
        val contract = GoalContractCompiler.compile(goal)
        return GoalContractCompiler.evaluate(contract, page, environment.history()).satisfied
    }

    fun verifiedSimpleNavigation(goal: String): Boolean =
        GoalContractCompiler.isSimpleWebNavigation(goal) && completionEvidence(goal)

    fun verifiedNamedAppOpen(goal: String): Boolean =
        com.cyclone.mobile.agent.plan.TaskDifficulty.isNamedAppOpenOnly(goal) && completionEvidence(goal)

    fun completionEvaluation(goal: String): JSONObject {
        val contract = GoalContractCompiler.compile(goal)
        return GoalContractCompiler.evaluate(contract, page, environment.history()).toJson()
    }

    fun causeFor(envelope: AgentActionEnvelope): RecoverableCause = when (envelope.errorClass) {
        AgentFailureClass.STALE_OBSERVATION -> RecoverableCause.STALE_SELECTOR
        AgentFailureClass.TARGET_NOT_FOUND -> RecoverableCause.TARGET_MISSING_FROM_COMPACT_CONTROLS
        AgentFailureClass.VERIFICATION_FAILED -> RecoverableCause.VERIFICATION_FAILED
        AgentFailureClass.AFTER_OBSERVATION_FAILED -> RecoverableCause.AFTER_STATE_MISSING
        AgentFailureClass.TIMEOUT -> RecoverableCause.PAGE_LOAD_SLOW
        else -> if (envelope.androidExecutionOk) RecoverableCause.SAME_PAGE_NO_EFFECT
        else RecoverableCause.RETRYABLE_TOOL_OR_TRANSPORT_ERROR
    }

    private fun resolveElementId(controlId: String?, legacyPage: PageContext, goal: String): String? {
        val card = page ?: observe(goal) ?: return null
        if (!controlId.isNullOrBlank()) {
            card.controls.firstOrNull { it.elementId == controlId }?.let { return it.elementId }
        }
        val legacy = controlId?.let { id -> legacyPage.controls.firstOrNull { it.key == id } }
        val query = legacy?.semanticName?.takeIf { it.isNotBlank() }
            ?: legacy?.label?.takeIf { it.isNotBlank() }
            ?: controlId?.takeIf { it.isNotBlank() }
            ?: goal
        val normalized = normalize(query)
        card.controls.singleOrNull {
            normalize(it.semanticName) == normalized || normalize(it.label) == normalized
        }?.let { return it.elementId }
        return search(query, goal).filter { normalize(it.label) == normalized || normalize(it.semanticName) == normalized }.singleOrNull()?.elementId
    }

    private fun search(query: String, goal: String): List<AgentElementCandidate> {
        val result = environment.search(query, goal)
        result.page?.let { page = it }
        searchEvidence = result.candidates
        return result.candidates
    }

    private fun environmentSessionId(): String = execution.sessionId

    private fun environmentDisplayId(): Int = execution.displayId

    private fun knownKnowledgeAvailable(goal: String): Boolean {
        val route = environment.knownRoutes(goal)
        val brain = environment.brainRecall(goal)
        routeEvidence = route.evidence
        brainEvidence = brain.evidence
        return route.evidence != null || brain.evidence != null
    }

    private fun loadKnowledge(goal: String) {
        knownKnowledgeAvailable(goal)
        memory = memory.copy(
            attemptedLevels = memory.attemptedLevels + RecoveryLevel.KNOWN_VERIFIED_ROUTE,
            attemptedEvidence = memory.attemptedEvidence + EvidenceSource.KNOWN_ROUTE,
        )
    }

    private fun observationEvidence(card: AgentPageCard): ObservationEvidence {
        val interactions = linkedMapOf<String, String>()
        card.controls.forEach { control ->
            val e = control.evidence
            interactions[control.semanticName.ifBlank { control.elementId }] = listOf(
                e.optBoolean("selected"),
                e.optBoolean("checked"),
                e.optBoolean("focused"),
                e.optString("textStateDigest"),
            ).joinToString("|")
        }
        val goalControls = card.controls
            .map { normalize(it.semanticName.ifBlank { it.label }) }
            .filter(String::isNotBlank)
            .toSet()
        val rawCount = card.pageEvidence.optInt("rawNodeCount", card.controls.size)
        val summary = card.pageSummary.toString().lowercase()
        return ObservationEvidence(
            semanticStateKey = card.pageKey,
            accessibilityFingerprint = card.accessibilityFingerprint,
            contentKey = card.contentKey,
            goalRelevantControls = goalControls,
            interactionState = interactions,
            packageName = card.packageName,
            activityName = card.activity,
            collectedEvidence = memory.attemptedEvidence,
            structuredControlCount = card.controls.size,
            rawNodeCount = rawCount,
            pageLooksWebOrCanvas = listOf("webview", "canvas", "composeview").any(summary::contains),
        )
    }

    private fun pageCardJson(card: AgentPageCard): JSONObject =
        com.cyclone.mobile.agent.tools.ObservationProjections.prompt(card)

    private fun compactText(card: AgentPageCard): String {
        val combined = buildString {
            append(card.pageText.toString()).append(' ')
            card.controls.take(40).forEach { control ->
                append(control.label).append(' ')
                append(control.semanticName).append(' ')
            }
        }.replace(Regex("\\s+"), " ").trim()
        return combined.take(2_400)
    }

    private fun interruptionCandidates(card: AgentPageCard): JSONArray {
        val markers = listOf(
            "cookie", "consent", "privacy", "tracking", "translate", "notification", "permission",
            "allow", "deny", "newsletter", "sign in", "login", "captcha", "open in app",
        )
        val array = JSONArray()
        card.controls.asSequence()
            .filter { control ->
                val text = "${control.label} ${control.semanticName}".lowercase()
                markers.any(text::contains)
            }
            .take(12)
            .forEach { array.put(candidateJson(it)) }
        return array
    }

    private fun recentOutcomeJson(history: List<AgentActionEnvelope>): JSONArray = JSONArray().also { array ->
        history.takeLast(8).forEach { envelope ->
            array.put(
                JSONObject()
                    .put("tool", envelope.tool)
                    .put("androidExecutionOk", envelope.androidExecutionOk)
                    .put("verificationStatus", envelope.verification.status.name)
                    .put("verificationPassed", envelope.verification.passed)
                    .put("verificationBasis", envelope.verification.basis ?: JSONObject.NULL)
                    .put("semanticSuccessClaimed", envelope.semanticSuccessClaimed)
                    .put("delta", envelope.delta.summary.take(240))
                    .put("errorClass", envelope.errorClass.name)
                    .put("failureLayer", envelope.failureLayer.name)
                    .put("executorInvoked", envelope.executorInvoked)
                    .put("safeMessage", envelope.safeMessage?.let { com.cyclone.mobile.ai.TracePrivacy.clean(it).take(300) } ?: JSONObject.NULL)
                    .put("learningRecorded", envelope.learning.recorded),
            )
        }
    }

    /**
     * The downloadable trace should show what the model was actually grounded on without storing a
     * raw accessibility tree or hidden provider reasoning. AgentTraceRuntime is already initialized
     * before production promptContext calls; tests/offline callers fail this optional trace safely.
     */
    private fun traceModelContext(
        contextJson: JSONObject,
        card: AgentPageCard?,
        history: List<AgentActionEnvelope>,
    ) {
        runCatching {
            val active = AgentTraceRuntime.store.listSessions(8)
                .firstOrNull { it.status == "RUNNING" || it.status == "SUSPENDED" }
                ?: return@runCatching
            val goalContract = contextJson.optJSONObject("goalContract")
            val completion = contextJson.optJSONObject("completionState")
            val controls = card?.controls.orEmpty().take(14).joinToString(" | ") { control ->
                val label = control.semanticName.ifBlank { control.label }.replace(Regex("\\s+"), " ").take(70)
                "${control.role}:$label"
            }
            val recent = history.takeLast(4).joinToString(" | ") { outcome ->
                "${outcome.tool}:${outcome.verification.status.name}:${outcome.verification.basis.orEmpty()}"
            }
            val detail = buildString {
                append("package=").append(card?.packageName.orEmpty())
                append(" activity=").append(card?.activity.orEmpty())
                append(" page=").append(card?.pageKey?.takeLast(16).orEmpty())
                append(" completion=").append(completion?.optBoolean("satisfied", false))
                append(" goalContract=").append(goalContract?.optJSONArray("requirements")?.toString().orEmpty().take(420))
                append(" summary=").append(card?.pageSummary?.toString().orEmpty().replace(Regex("\\s+"), " ").take(360))
                append(" controls=").append(controls.take(900))
                if (recent.isNotBlank()) append(" recent=").append(recent.take(500))
            }
            AgentTraceRuntime.store.append(
                active.id,
                "MODEL_CONTEXT",
                "Sanitized model context prepared",
                code = "model.context.v2",
                ok = true,
                detail = detail,
            )
        }
    }

    private fun candidateJson(candidate: AgentElementCandidate): JSONObject = JSONObject()
        .put("controlId", candidate.elementId)
        .put("elementId", candidate.elementId)
        .put("elementIndex", candidate.elementIndex ?: JSONObject.NULL)
        .put("label", candidate.label)
        .put("semanticName", candidate.semanticName)
        .put("role", candidate.role)
        .put("source", candidate.source)
        .put("relevance", candidate.relevance)
        .put("androidActions", candidate.evidence.optJSONArray("androidActions") ?: JSONArray())
        .put("risk", candidate.evidence.optString("risk"))
        .put("expectedEffect", candidate.evidence.opt("expectedEffect") ?: JSONObject.NULL)
        .put("clickable", candidate.evidence.optBoolean("clickable"))
        .put("enabled", candidate.evidence.optBoolean("enabled", true))
        .put("visibleToUser", candidate.evidence.optBoolean("visibleToUser", true))
        .put("bounds", candidate.evidence.optJSONObject("bounds") ?: JSONObject.NULL)
        .put("editable", candidate.evidence.optBoolean("editable"))
        .put("scrollable", candidate.evidence.optBoolean("scrollable"))
        .put("selected", candidate.evidence.optBoolean("selected"))
        .put("checked", candidate.evidence.optBoolean("checked"))
        .put("focused", candidate.evidence.optBoolean("focused"))

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    companion object {
        private val ELEMENT_TOOLS = setOf(
            "phone.click",
            "phone.long_press",
            "phone.type",
            "phone.replace_text",
        )
    }
}
