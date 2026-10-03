package com.cyclone.mobile.agent

import java.util.UUID
import com.cyclone.mobile.agent.recovery.RecoveryIncident
import com.cyclone.mobile.agent.recovery.IncidentEffect

enum class CycloneAgentStage { START, OBSERVE, PLAN_OR_RECALL, ACT, VERIFY, CLASSIFY_RESULT, SUSPENDED, TERMINAL }
enum class CycloneTaskClassification { COMPLETE, RECOVERABLE, PROVIDER_RETRY_LATER, HUMAN_OR_GATE, HARD_BLOCKER, CANCELLED, NON_CONVERGENCE }
enum class CycloneModelDirective { ACT, DONE, NEED_HUMAN, BLOCKED, NEED_VISION }
enum class CycloneRecoveryKind { OBSERVATION_FAILURE, MALFORMED_MODEL, MODEL_BLOCKED_UNCONFIRMED, EMPTY_OR_INVALID_PLAN, TOOL_FAILURE, VERIFICATION_FAILURE, VISION_UNCHANGED, STALE_TARGET, BACKTRACK, POLICY_DENIED }
enum class CycloneTraceEventType { TASK_STARTED, OBSERVE, PLAN, TOOL_REQUESTED, TOOL_RESULT, VERIFY, RECOVERY_CLASSIFIED, REPLAN, VISION_ESCALATION, GATE_SUSPEND, GATE_RESUME, PROVIDER_WAIT, COMPLETE, HARD_BLOCKER, NON_CONVERGENCE, CANCELLED, PHASE }

data class CycloneConvergencePolicy(
    val taskTimeoutMs: Long = 180_000,
    val maxRepeatedIdenticalActionWithoutProgress: Int = 2,
    val maxConsecutiveRecoveryCyclesWithoutNewEvidence: Int = 8,
    val maxMalformedModelResponses: Int = 3,
    val maxVisionAttemptsOnUnchangedState: Int = 1,
    val maxBacktrackAttempts: Int = 3,
    val maxStaleTargetRetries: Int = 2,
    val maxMutationsWithoutVerifiedProgress: Int = 10,
    val maxRepeatedUnverifiedDone: Int = 2,
) {
    init {
        require(taskTimeoutMs > 0)
        require(maxRepeatedIdenticalActionWithoutProgress > 0)
        require(maxConsecutiveRecoveryCyclesWithoutNewEvidence > 0)
        require(maxMalformedModelResponses > 0)
        require(maxVisionAttemptsOnUnchangedState > 0)
        require(maxBacktrackAttempts > 0)
        require(maxStaleTargetRetries > 0)
        require(maxMutationsWithoutVerifiedProgress > 0)
        require(maxRepeatedUnverifiedDone > 0)
    }
}

data class CycloneObservation(val identity: String, val pageIdentity: String, val evidenceIdentity: String = identity, val sessionId: String = "default-foreground", val displayId: Int = 0, val generation: Long = 0, val packageName: String = "")
data class CycloneModelTurn(val directive: CycloneModelDirective, val actionSignature: String? = null, val reason: String? = null, val payload: Any? = null, val intendedEffect: IncidentEffect = IncidentEffect.USER_GOAL_VERIFIED)
sealed interface CyclonePlanResult { data class Valid(val turn: CycloneModelTurn) : CyclonePlanResult; data class Malformed(val reason: String? = null) : CyclonePlanResult }
data class CycloneToolResult(
    val ok: Boolean,
    val actionSignature: String? = null,
    val evidenceIdentity: String? = null,
    val policyAllowed: Boolean = true,
    val gateRequired: Boolean = false,
    val hardBlocker: Boolean = false,
    val staleTarget: Boolean = false,
    val backtracked: Boolean = false,
    val message: String? = null,
    val payload: Any? = null,
)
data class CycloneVerificationResult(val verified: Boolean, val progress: Boolean, val complete: Boolean = false, val evidenceIdentity: String? = null, val message: String? = null)
data class CycloneTraceEvent(
    val type: CycloneTraceEventType,
    val timestampMs: Long,
    val taskId: String,
    val stage: CycloneAgentStage,
    val code: String? = null,
    val observationIdentity: String? = null,
    val pageIdentity: String? = null,
    val actionSignature: String? = null,
    val safeMessage: String? = null,
    val span: ExecutionSpan? = null,
)

fun interface CycloneAgentTraceSink { fun emit(event: CycloneTraceEvent); object NoOp : CycloneAgentTraceSink { override fun emit(event: CycloneTraceEvent) = Unit } }
interface CycloneTaskCheckpointStore { fun save(state: CycloneTaskState); object NoOp : CycloneTaskCheckpointStore { override fun save(state: CycloneTaskState) = Unit } }
interface CycloneAgentModel { fun plan(state: CycloneTaskState, observation: CycloneObservation): CyclonePlanResult }
interface CycloneAgentTools {
    fun observationHealth(): ObservationHealth? = null
    fun onRecovery(state: CycloneTaskState, kind: CycloneRecoveryKind, code: String) {}
    fun observe(state: CycloneTaskState): CycloneObservation?
    fun execute(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn): CycloneToolResult
    fun verify(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn, toolResult: CycloneToolResult): CycloneVerificationResult
    fun classifyModelBoundary(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn): CycloneTaskClassification = CycloneTaskClassification.RECOVERABLE
    fun verifyCompletion(state: CycloneTaskState, observation: CycloneObservation, turn: CycloneModelTurn): CycloneVerificationResult = CycloneVerificationResult(false, false)
}

data class CycloneTaskState(
    val taskId: String,
    val goal: String,
    val currentStage: CycloneAgentStage,
    val latestObservationIdentity: String?,
    val latestPageIdentity: String?,
    val recentSuccessfulVerifiedActions: List<String>,
    val recentFailedActions: List<String>,
    val recoveryAttempts: Map<CycloneRecoveryKind, Int>,
    val visionUseState: Map<String, Int>,
    val taskStartTimeMs: Long,
    val lastVerifiedProgressTimeMs: Long,
    val gateSuspended: Boolean,
    val requireFreshObservation: Boolean,
    val finalClassification: CycloneTaskClassification?,
    val modelTurns: Int,
    val consecutiveRecoveryCyclesWithoutNewEvidence: Int,
    val repeatedIdenticalActionWithoutProgress: Int,
    val backtrackAttempts: Int,
    val staleTargetRetries: Int,
    val lastActionSignature: String?,
    val incident: RecoveryIncident? = null,
    val executionSessionId: String = "default-foreground",
    val executionDisplayId: Int = 0,
    val observationGeneration: Long = 0,
    val executionPackageName: String = "",
)

sealed interface CycloneAgentRunResult {
    val state: CycloneTaskState
    data class Completed(override val state: CycloneTaskState, val message: String? = null) : CycloneAgentRunResult
    data class Suspended(override val state: CycloneTaskState, val message: String? = null) : CycloneAgentRunResult
    data class Stopped(override val state: CycloneTaskState, val message: String? = null) : CycloneAgentRunResult
    data class Cancelled(override val state: CycloneTaskState, val message: String? = null) : CycloneAgentRunResult
}

/** Cyclone-owned task graph. Provider/model turn count is telemetry, never a termination budget. */
class CycloneLocalAgent(
    goal: String,
    private val model: CycloneAgentModel,
    private val tools: CycloneAgentTools,
    private val convergence: CycloneConvergencePolicy = CycloneConvergencePolicy(),
    private val trace: CycloneAgentTraceSink = CycloneAgentTraceSink.NoOp,
    private val checkpoints: CycloneTaskCheckpointStore = CycloneTaskCheckpointStore.NoOp,
    private val now: () -> Long = System::currentTimeMillis,
    private val externallyCancelled: () -> Boolean = { false },
    taskId: String = "local-${UUID.randomUUID()}",
    restoredState: CycloneTaskState? = null,
    private val externallyPaused: () -> Boolean = { false },
    private val monotonicNow: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile private var cancelled = false
    private var suspendedAt: Long? = null
    private var mutationsWithoutVerifiedProgress = 0
    private var repeatedUnverifiedDone = 0
    private var intendedEffect = IncidentEffect.USER_GOAL_VERIFIED
    private var state = restoredState
        ?.also { require(it.goal == goal) { "Restored task goal does not match requested goal" } }
        ?.let { restored ->
            restored.copy(
                currentStage = if (restored.currentStage == CycloneAgentStage.TERMINAL) CycloneAgentStage.TERMINAL else CycloneAgentStage.OBSERVE,
                requireFreshObservation = restored.currentStage != CycloneAgentStage.TERMINAL,
            )
        }
        ?: CycloneTaskState(taskId, goal, CycloneAgentStage.START, null, null, emptyList(), emptyList(), emptyMap(), emptyMap(), now(), now(), false, true, null, 0, 0, 0, 0, 0, null)

    init {
        require(goal.isNotBlank())
        if (restoredState == null) emit(CycloneTraceEventType.TASK_STARTED)
        checkpoint()
    }

    private val timing = ExecutionTiming(clock = monotonicNow, sink = { span ->
        trace.emit(CycloneTraceEvent(CycloneTraceEventType.PHASE, now(), state.taskId,
            state.currentStage, code = "phase.${span.phase.name.lowercase()}", span = span))
    })
    private fun <T> timed(phase: ExecutionPhase, block: () -> T): T =
        timing.bounded(state.modelTurns + 1, phase, { cancelled || externallyCancelled() }, block)

    fun openIncident(effect: IncidentEffect, category: String) {
        val incident = state.incident?.takeIf { it.resolution == "OPEN" } ?: RecoveryIncident(
            taskId = state.taskId, sessionId = state.executionSessionId, displayId = state.executionDisplayId,
            openingGeneration = state.observationGeneration, category = category, intendedEffect = effect, packageName = state.executionPackageName)
        state = state.copy(incident = incident.attempt(category))
        checkpoint()
    }
    fun verifyIncident(effect: IncidentEffect, sessionId: String, displayId: Int) {
        val old = state.incident ?: return
        state = state.copy(incident = old.verified(effect, sessionId, displayId))
        checkpoint()
    }
    fun snapshot(): CycloneTaskState = state
    fun cancel() { cancelled = true }
    fun resume(): Boolean {
        if (!state.gateSuspended || state.finalClassification != CycloneTaskClassification.HUMAN_OR_GATE) return false
        val pausedDuration = suspendedAt?.let { (now() - it).coerceAtLeast(0) } ?: 0
        suspendedAt = null
        state = state.copy(taskStartTimeMs = state.taskStartTimeMs + pausedDuration, currentStage = CycloneAgentStage.OBSERVE, latestObservationIdentity = null, latestPageIdentity = null, lastActionSignature = null, gateSuspended = false, requireFreshObservation = true, finalClassification = null, consecutiveRecoveryCyclesWithoutNewEvidence = 0, repeatedIdenticalActionWithoutProgress = 0)
        repeatedUnverifiedDone = 0
        emit(CycloneTraceEventType.GATE_RESUME); checkpoint(); return true
    }

    fun runUntilBoundary(): CycloneAgentRunResult = try { runLoop() }
        catch (error: ExecutionPhaseTimeout) { cancellation() ?: nonConvergence(error.message ?: "phase.timeout") }

    private fun runLoop(): CycloneAgentRunResult {
        if (state.currentStage == CycloneAgentStage.TERMINAL) return terminalResult()
        if (state.gateSuspended) return CycloneAgentRunResult.Suspended(state)
        while (true) {
            executionBoundary()?.let { return it }

            state = state.copy(currentStage = CycloneAgentStage.OBSERVE)
            val oldObs = state.latestObservationIdentity
            val oldPage = state.latestPageIdentity
            val observation = timed(ExecutionPhase.OBSERVATION) { tools.observe(state) }
            executionBoundary()?.let { return it }
            if (observation == null) {
                val health = tools.observationHealth()
                if (health != null) {
                    if (health.terminal) {
                        return if (health.state == ObservationState.CAPTURE_CHANGED) {
                            nonConvergence(health.message)
                        } else {
                            hardBlocker(health.message)
                        }
                    }
                    while (System.nanoTime() / 1_000_000 < health.cooldownUntilMs) {
                        executionBoundary()?.let { return it }
                        Thread.sleep(25)
                    }
                    if (health.state == ObservationState.CAPTURE_CHANGED) {
                        emit(CycloneTraceEventType.RECOVERY_CLASSIFIED, "observation.capture_changed.retry")
                        continue
                    }
                }
                recover(CycloneRecoveryKind.OBSERVATION_FAILURE, "observe.failed", false)?.let { return it }
                continue
            }
            val newEvidence = observation.identity != oldObs || observation.pageIdentity != oldPage
            state = state.copy(
                executionPackageName = observation.packageName,
                executionSessionId = observation.sessionId, executionDisplayId = observation.displayId, observationGeneration = observation.generation,
                latestObservationIdentity = observation.identity,
                latestPageIdentity = observation.pageIdentity,
                requireFreshObservation = false,
            )
            emit(CycloneTraceEventType.OBSERVE, observation = observation); checkpoint()
            cancellation()?.let { return it }

            state = state.copy(currentStage = CycloneAgentStage.PLAN_OR_RECALL)
            val plan = timed(ExecutionPhase.PLAN_OR_RECALL) { model.plan(state, observation) }
            state = state.copy(modelTurns = state.modelTurns + 1)
            // A slow provider may return after Stop or the task deadline. Never execute its plan.
            executionBoundary()?.let { return it }
            if (plan is CyclonePlanResult.Malformed) {
                emit(CycloneTraceEventType.PLAN, "model.malformed", observation)
                recover(CycloneRecoveryKind.MALFORMED_MODEL, "model.malformed", newEvidence)?.let { return it }
                continue
            }
            val turn = (plan as CyclonePlanResult.Valid).turn
            intendedEffect = turn.intendedEffect
            emit(CycloneTraceEventType.PLAN, turn.directive.name.lowercase(), observation, turn.actionSignature); checkpoint()

            when (turn.directive) {
                CycloneModelDirective.DONE -> {
                    state = state.copy(currentStage = CycloneAgentStage.VERIFY)
                    var completionObservation = observation
                    var v = tools.verifyCompletion(state, completionObservation, turn)
                    executionBoundary()?.let { return it }
                    emit(CycloneTraceEventType.VERIFY, if (v.verified && v.complete) "completion.verified" else "completion.unverified", completionObservation)
                    if (v.verified && v.complete) return complete(v.message)

                    // A rejected DONE is a verification problem first, not a planning problem. Re-read
                    // the phone locally and retry the same completion contract without burning another
                    // provider turn. This catches delayed browser/modal transitions and stale page cards.
                    state = state.copy(currentStage = CycloneAgentStage.OBSERVE, requireFreshObservation = true)
                    val fresh = tools.observe(state)
                    executionBoundary()?.let { return it }
                    if (fresh != null) {
                        completionObservation = fresh
                        state = state.copy(
                            latestObservationIdentity = fresh.identity,
                            latestPageIdentity = fresh.pageIdentity,
                            requireFreshObservation = false,
                            currentStage = CycloneAgentStage.VERIFY,
                        )
                        emit(CycloneTraceEventType.OBSERVE, "completion.reobserve", fresh)
                        v = tools.verifyCompletion(state, fresh, turn)
                        executionBoundary()?.let { return it }
                        emit(CycloneTraceEventType.VERIFY, if (v.verified && v.complete) "completion.verified_after_reobserve" else "completion.still_unverified", fresh)
                        if (v.verified && v.complete) return complete(v.message)
                    }

                    repeatedUnverifiedDone += 1
                    if (repeatedUnverifiedDone >= convergence.maxRepeatedUnverifiedDone) {
                        return nonConvergence("completion.ambiguous_after_recheck")
                    }
                    val freshEvidence = completionObservation.identity != observation.identity ||
                        completionObservation.pageIdentity != observation.pageIdentity
                    recover(CycloneRecoveryKind.VERIFICATION_FAILURE, "completion.unverified", freshEvidence)?.let { return it }
                    continue
                }
                CycloneModelDirective.BLOCKED, CycloneModelDirective.NEED_HUMAN -> {
                    state = state.copy(currentStage = CycloneAgentStage.CLASSIFY_RESULT)
                    when (tools.classifyModelBoundary(state, observation, turn)) {
                        CycloneTaskClassification.HUMAN_OR_GATE -> return suspendForGate(turn.reason)
                        CycloneTaskClassification.HARD_BLOCKER -> return hardBlocker(turn.reason)
                        CycloneTaskClassification.PROVIDER_RETRY_LATER -> return providerRetryLater(turn.reason)
                        CycloneTaskClassification.CANCELLED -> return cancelResult(turn.reason)
                        CycloneTaskClassification.NON_CONVERGENCE -> return nonConvergence("classifier.non_convergence")
                        CycloneTaskClassification.COMPLETE -> {
                            val v = tools.verifyCompletion(state, observation, turn)
                            if (v.verified && v.complete) return complete(v.message)
                            recover(CycloneRecoveryKind.VERIFICATION_FAILURE, "boundary.complete_unverified", newEvidence)?.let { return it }
                        }
                        CycloneTaskClassification.RECOVERABLE -> recover(CycloneRecoveryKind.MODEL_BLOCKED_UNCONFIRMED, "model.blocked_unconfirmed", newEvidence)?.let { return it }
                    }
                    continue
                }
                CycloneModelDirective.NEED_VISION -> {
                    val used = state.visionUseState[observation.identity] ?: 0
                    if (used >= convergence.maxVisionAttemptsOnUnchangedState) {
                        recover(CycloneRecoveryKind.VISION_UNCHANGED, "vision.unchanged", false)?.let { return it }
                        continue
                    }
                    state = state.copy(visionUseState = state.visionUseState + (observation.identity to used + 1))
                    emit(CycloneTraceEventType.VISION_ESCALATION, "vision.escalate", observation)
                }
                CycloneModelDirective.ACT -> Unit
            }

            if (turn.actionSignature.isNullOrBlank()) {
                recover(CycloneRecoveryKind.EMPTY_OR_INVALID_PLAN, "plan.no_action_signature", newEvidence)?.let { return it }
                continue
            }
            val repeated = if (turn.actionSignature == state.lastActionSignature) state.repeatedIdenticalActionWithoutProgress + 1 else 1
            state = state.copy(currentStage = CycloneAgentStage.ACT, lastActionSignature = turn.actionSignature, repeatedIdenticalActionWithoutProgress = repeated)
            emit(CycloneTraceEventType.TOOL_REQUESTED, "tool.requested", observation, turn.actionSignature)
            if (repeated > convergence.maxRepeatedIdenticalActionWithoutProgress) return nonConvergence("convergence.repeated_action")
            mutationsWithoutVerifiedProgress += 1
            if (mutationsWithoutVerifiedProgress > convergence.maxMutationsWithoutVerifiedProgress) {
                return nonConvergence("convergence.mutations_without_verified_progress")
            }

            executionBoundary()?.let { return it }
            val tool = timed(ExecutionPhase.DISPATCH) { tools.execute(state, observation, turn) }
            emit(CycloneTraceEventType.TOOL_RESULT, if (tool.ok) "tool.ok" else "tool.failed", observation,
                tool.actionSignature ?: turn.actionSignature, tool.message); checkpoint()
            executionBoundary()?.let { return it }
            if (!tool.policyAllowed) {
                if (tool.gateRequired) return suspendForGate(tool.message)
                if (tool.hardBlocker) return hardBlocker(tool.message)
                recover(CycloneRecoveryKind.POLICY_DENIED, "policy.denied", false)?.let { return it }
                continue
            }
            if (tool.gateRequired) return suspendForGate(tool.message)
            if (tool.hardBlocker) return hardBlocker(tool.message)
            if (tool.staleTarget) {
                state = state.copy(staleTargetRetries = state.staleTargetRetries + 1)
                if (state.staleTargetRetries > convergence.maxStaleTargetRetries) return nonConvergence("convergence.stale_target")
                recover(CycloneRecoveryKind.STALE_TARGET, "target.stale", tool.evidenceIdentity != null && tool.evidenceIdentity != observation.evidenceIdentity)?.let { return it }
                continue
            }
            if (tool.backtracked) {
                state = state.copy(backtrackAttempts = state.backtrackAttempts + 1)
                if (state.backtrackAttempts > convergence.maxBacktrackAttempts) return nonConvergence("convergence.backtrack")
            }
            if (!tool.ok) {
                recover(CycloneRecoveryKind.TOOL_FAILURE, "tool.failed", tool.evidenceIdentity != null && tool.evidenceIdentity != observation.evidenceIdentity)?.let { return it }
                continue
            }

            state = state.copy(currentStage = CycloneAgentStage.VERIFY)
            val verification = timed(ExecutionPhase.VERIFICATION) { tools.verify(state, observation, turn, tool) }
            executionBoundary()?.let { return it }
            emit(CycloneTraceEventType.VERIFY, when { verification.complete && verification.verified -> "verify.complete"; verification.verified && verification.progress -> "verify.progress"; else -> "verify.failed" }, observation, turn.actionSignature)
            if (verification.complete && verification.verified) return complete(verification.message)
            if (verification.verified && verification.progress) {
                state = state.copy(
                    currentStage = CycloneAgentStage.CLASSIFY_RESULT,
                    recentSuccessfulVerifiedActions = bounded(state.recentSuccessfulVerifiedActions + turn.actionSignature, 24),
                    recentFailedActions = emptyList(),
                    lastVerifiedProgressTimeMs = now(),
                    latestObservationIdentity = verification.evidenceIdentity ?: tool.evidenceIdentity ?: observation.evidenceIdentity,
                    consecutiveRecoveryCyclesWithoutNewEvidence = 0,
                    repeatedIdenticalActionWithoutProgress = 0,
                    staleTargetRetries = 0,
                    lastActionSignature = null,
                    finalClassification = CycloneTaskClassification.RECOVERABLE,
                )
                mutationsWithoutVerifiedProgress = 0
                repeatedUnverifiedDone = 0
                emit(CycloneTraceEventType.RECOVERY_CLASSIFIED, "progress.continue", observation, turn.actionSignature); checkpoint(); continue
            }
            recover(CycloneRecoveryKind.VERIFICATION_FAILURE, "verify.no_progress", verification.evidenceIdentity != null && verification.evidenceIdentity != observation.evidenceIdentity)?.let { return it }
        }
    }

    private fun recover(kind: CycloneRecoveryKind, code: String, newEvidence: Boolean): CycloneAgentRunResult? {
        val attempts = (state.recoveryAttempts[kind] ?: 0) + 1
        // Observation/page fingerprints can churn without helping the user's task. Only verified
        // semantic progress resets this counter (see the verified-progress branch above).
        val consecutive = state.consecutiveRecoveryCyclesWithoutNewEvidence + 1
        state = state.copy(
            incident = (state.incident?.takeIf { it.resolution == "OPEN" } ?: RecoveryIncident(
                taskId = state.taskId, sessionId = state.executionSessionId, displayId = state.executionDisplayId,
                openingGeneration = state.observationGeneration, category = kind.name, intendedEffect = intendedEffect, packageName = state.executionPackageName)).attempt(code),
            currentStage = CycloneAgentStage.CLASSIFY_RESULT,
            recoveryAttempts = state.recoveryAttempts + (kind to attempts),
            consecutiveRecoveryCyclesWithoutNewEvidence = consecutive,
            recentFailedActions = bounded(state.recentFailedActions + code, 24),
            finalClassification = CycloneTaskClassification.RECOVERABLE,
        )
        emit(CycloneTraceEventType.RECOVERY_CLASSIFIED, code); checkpoint()
        if (kind == CycloneRecoveryKind.MALFORMED_MODEL && attempts > convergence.maxMalformedModelResponses) return nonConvergence("convergence.malformed_model")
        if (consecutive > convergence.maxConsecutiveRecoveryCyclesWithoutNewEvidence) return nonConvergence("convergence.recovery_without_evidence")
        tools.onRecovery(state, kind, code)
        state = state.copy(currentStage = CycloneAgentStage.PLAN_OR_RECALL); emit(CycloneTraceEventType.REPLAN, code); checkpoint(); return null
    }

    private fun cancellation(): CycloneAgentRunResult? = if (cancelled || externallyCancelled()) cancelResult("user.cancel") else null
    private fun executionBoundary(): CycloneAgentRunResult? {
        cancellation()?.let { return it }
        if (externallyPaused()) return suspendForGate("Control is with you")
        return if (now() - state.taskStartTimeMs >= convergence.taskTimeoutMs) nonConvergence("convergence.task_timeout") else null
    }
    private fun cancelResult(message: String?) = finish(CycloneTaskClassification.CANCELLED, CycloneTraceEventType.CANCELLED, message) { CycloneAgentRunResult.Cancelled(it, message) }
    private fun complete(message: String?) = finish(CycloneTaskClassification.COMPLETE, CycloneTraceEventType.COMPLETE, "task.complete") { CycloneAgentRunResult.Completed(it, message) }
    private fun suspendForGate(message: String?): CycloneAgentRunResult.Suspended {
        if (suspendedAt == null) suspendedAt = now()
        state = state.copy(currentStage = CycloneAgentStage.SUSPENDED, gateSuspended = true, finalClassification = CycloneTaskClassification.HUMAN_OR_GATE)
        emit(CycloneTraceEventType.GATE_SUSPEND, message); checkpoint(); return CycloneAgentRunResult.Suspended(state, message)
    }
    private fun hardBlocker(message: String?) = finish(CycloneTaskClassification.HARD_BLOCKER, CycloneTraceEventType.HARD_BLOCKER, message) { CycloneAgentRunResult.Stopped(it, message) }
    private fun providerRetryLater(message: String?) = finish(CycloneTaskClassification.PROVIDER_RETRY_LATER, CycloneTraceEventType.PROVIDER_WAIT, message) { CycloneAgentRunResult.Stopped(it, message) }
    private fun nonConvergence(code: String) = finish(CycloneTaskClassification.NON_CONVERGENCE, CycloneTraceEventType.NON_CONVERGENCE, code) { CycloneAgentRunResult.Stopped(it, code) }
    private fun <T : CycloneAgentRunResult> finish(classification: CycloneTaskClassification, event: CycloneTraceEventType, code: String?, build: (CycloneTaskState) -> T): T {
        state = state.copy(incident = state.incident?.let {
            if (classification == CycloneTaskClassification.COMPLETE && it.intendedEffect == IncidentEffect.USER_GOAL_VERIFIED)
                it.verified(IncidentEffect.USER_GOAL_VERIFIED, state.executionSessionId, state.executionDisplayId)
            else if (it.resolution == "OPEN") it.copy(resolution = "TERMINAL") else it
        })
        state = state.copy(currentStage = CycloneAgentStage.TERMINAL, gateSuspended = false, finalClassification = classification)
        emit(event, code); checkpoint(); return build(state)
    }
    private fun terminalResult(): CycloneAgentRunResult = when (state.finalClassification) {
        CycloneTaskClassification.COMPLETE -> CycloneAgentRunResult.Completed(state)
        CycloneTaskClassification.CANCELLED -> CycloneAgentRunResult.Cancelled(state)
        CycloneTaskClassification.HUMAN_OR_GATE -> CycloneAgentRunResult.Suspended(state)
        CycloneTaskClassification.PROVIDER_RETRY_LATER -> CycloneAgentRunResult.Stopped(state)
        else -> CycloneAgentRunResult.Stopped(state)
    }
    private fun emit(type: CycloneTraceEventType, code: String? = null, observation: CycloneObservation? = null, actionSignature: String? = null, safeMessage: String? = null) {
        trace.emit(CycloneTraceEvent(type, now(), state.taskId, state.currentStage, code?.take(160), observation?.identity, observation?.pageIdentity, actionSignature?.take(160), safeMessage?.take(500)))
    }
    private fun checkpoint() {
        checkpoints.save(state)
        CycloneTaskJournal.save(state)
    }
    private fun bounded(values: List<String>, max: Int) = if (values.size <= max) values else values.takeLast(max)
}
