package com.cyclone.mobile.ai

import java.security.MessageDigest
import java.util.UUID

enum class ReliabilityFailureClass { OBSERVATION, ACTION, TRANSPORT, VERIFICATION }
enum class AgentTaskStatus { PLANNING, RUNNING, PAUSED, CANCELLED, COMPLETED, FAILED }
enum class ReliabilityDirective { CONTINUE, RETRY, PAUSE, FAIL }
enum class ReliabilityEventType {
    PLANNING,
    OBSERVATION,
    ACTION_REQUESTED,
    ACTION_RESULT,
    VERIFICATION,
    RETRY,
    RECOVERY,
    PAUSED,
    RESUMED,
    CANCELLED,
    COMPLETED,
    FAILED,
    ROUTINE_COMPILED,
}

data class AgentReliabilityConfig(
    val maxTurns: Int = 24,
    val maxConsecutiveFailures: Int = 3,
    val maxRepeatedActionWithoutProgress: Int = 2,
    val maxObservationOscillations: Int = 2,
    val observationRetries: Int = 2,
    val actionRetries: Int = 1,
    val transportRetries: Int = 2,
    val verificationRetries: Int = 1,
    val observationTimeoutMs: Long = 8_000,
    val actionTimeoutMs: Long = 12_000,
    val transportTimeoutMs: Long = 15_000,
    val verificationTimeoutMs: Long = 8_000,
    val taskTimeoutMs: Long = 180_000,
    val maxHistory: Int = 240,
) {
    init {
        require(maxTurns in 1..1_000)
        require(maxConsecutiveFailures in 1..20)
        require(maxRepeatedActionWithoutProgress in 1..10)
        require(maxObservationOscillations in 1..10)
        require(listOf(observationRetries, actionRetries, transportRetries, verificationRetries).all { it in 0..10 })
        require(listOf(observationTimeoutMs, actionTimeoutMs, transportTimeoutMs, verificationTimeoutMs, taskTimeoutMs).all { it in 1..600_000 })
        require(maxHistory in 20..2_000)
    }

    fun retriesFor(failureClass: ReliabilityFailureClass): Int = when (failureClass) {
        ReliabilityFailureClass.OBSERVATION -> observationRetries
        ReliabilityFailureClass.ACTION -> actionRetries
        ReliabilityFailureClass.TRANSPORT -> transportRetries
        ReliabilityFailureClass.VERIFICATION -> verificationRetries
    }

    fun timeoutFor(failureClass: ReliabilityFailureClass): Long = when (failureClass) {
        ReliabilityFailureClass.OBSERVATION -> observationTimeoutMs
        ReliabilityFailureClass.ACTION -> actionTimeoutMs
        ReliabilityFailureClass.TRANSPORT -> transportTimeoutMs
        ReliabilityFailureClass.VERIFICATION -> verificationTimeoutMs
    }
}

data class ReliabilityEvent(
    val id: String = UUID.randomUUID().toString(),
    val type: ReliabilityEventType,
    val timestampMs: Long,
    val turn: Int,
    val code: String,
    val stateFingerprint: String? = null,
    val actionSignature: String? = null,
    val retryOrdinal: Int? = null,
)

data class AgentReliabilitySnapshot(
    val sessionId: String,
    val status: AgentTaskStatus,
    val turn: Int,
    val consecutiveFailures: Int,
    val lastStateFingerprint: String?,
    val stopCode: String?,
    val events: List<ReliabilityEvent>,
)

/**
 * State and policy only: this class never calls a model, phone tool, shell, or network. Existing
 * Cyclone orchestrators consult it before using the canonical PhoneToolExecutor.
 */
class AgentReliabilitySession(
    private val config: AgentReliabilityConfig = AgentReliabilityConfig(),
    private val now: () -> Long = System::currentTimeMillis,
    val sessionId: String = "agent-${UUID.randomUUID()}",
) {
    private val startedAt = now()
    private val events = ArrayDeque<ReliabilityEvent>()
    private val observations = ArrayDeque<String>()
    private val retryCounts = mutableMapOf<ReliabilityFailureClass, Int>()
    private var status = AgentTaskStatus.PLANNING
    private var turn = 0
    private var consecutiveFailures = 0
    private var lastState: String? = null
    private var lastAction: String? = null
    private var repeatedActionCount = 0
    private var oscillationCount = 0
    private var stopCode: String? = null

    init { emit(ReliabilityEventType.PLANNING, "plan.bounded") }

    @Synchronized
    fun start(): ReliabilityDirective {
        if (status != AgentTaskStatus.PLANNING) return directive()
        status = AgentTaskStatus.RUNNING
        return ReliabilityDirective.CONTINUE
    }

    @Synchronized
    fun observe(stateFingerprint: String): ReliabilityDirective {
        if (!active()) return directive()
        if (timedOut()) return fail("task.timeout")
        val safe = safeFingerprint(stateFingerprint)
        if (lastState != safe) {
            repeatedActionCount = 0
        }
        lastState = safe
        observations.addLast(safe)
        while (observations.size > 6) observations.removeFirst()
        if (observations.size >= 4) {
            val tail = observations.takeLast(4)
            if (tail[0] == tail[2] && tail[1] == tail[3] && tail[0] != tail[1]) oscillationCount++
        }
        emit(ReliabilityEventType.OBSERVATION, "observe.fresh", stateFingerprint = safe)
        return if (oscillationCount >= config.maxObservationOscillations) pause("convergence.oscillation") else ReliabilityDirective.CONTINUE
    }

    @Synchronized
    fun requestAction(tool: String, stableTarget: String?): ReliabilityDirective {
        if (!active()) return directive()
        if (timedOut()) return fail("task.timeout")
        turn++
        if (turn > config.maxTurns) return pause("convergence.max_turns")
        val signature = safeActionSignature(tool, stableTarget)
        repeatedActionCount = if (signature == lastAction) repeatedActionCount + 1 else 1
        lastAction = signature
        if (repeatedActionCount > config.maxRepeatedActionWithoutProgress) return pause("convergence.repeated_action")
        emit(ReliabilityEventType.ACTION_REQUESTED, "action.typed", actionSignature = signature)
        return ReliabilityDirective.CONTINUE
    }

    @Synchronized
    fun result(ok: Boolean, verified: Boolean?, failureClass: ReliabilityFailureClass? = null): ReliabilityDirective {
        if (!active()) return directive()
        emit(ReliabilityEventType.ACTION_RESULT, if (ok) "action.ok" else "action.failed", actionSignature = lastAction)
        if (verified != null) emit(
            ReliabilityEventType.VERIFICATION,
            if (verified) "verify.passed" else "verify.failed",
            stateFingerprint = lastState,
            actionSignature = lastAction,
        )
        if (ok && verified == true) {
            retryCounts.clear()
            repeatedActionCount = 0
            oscillationCount = 0
            observations.clear()
            consecutiveFailures = 0
            return ReliabilityDirective.CONTINUE
        }
        consecutiveFailures++
        val category = failureClass ?: if (ok) ReliabilityFailureClass.VERIFICATION else ReliabilityFailureClass.ACTION
        val retry = (retryCounts[category] ?: 0) + 1
        retryCounts[category] = retry
        if (consecutiveFailures >= config.maxConsecutiveFailures) return pause("convergence.failures")
        if (retry <= config.retriesFor(category)) {
            emit(ReliabilityEventType.RETRY, "retry.${category.name.lowercase()}", retryOrdinal = retry)
            return ReliabilityDirective.RETRY
        }
        return pause("retry.${category.name.lowercase()}.exhausted")
    }

    @Synchronized fun pause(): ReliabilityDirective = pause("user.pause")

    @Synchronized
    fun resume(): ReliabilityDirective {
        if (status != AgentTaskStatus.PAUSED) return directive()
        status = AgentTaskStatus.RUNNING
        stopCode = null
        consecutiveFailures = 0
        retryCounts.clear()
        repeatedActionCount = 0
        emit(ReliabilityEventType.RESUMED, "user.resume")
        return ReliabilityDirective.CONTINUE
    }

    @Synchronized
    fun cancel(): ReliabilityDirective {
        if (status in TERMINAL) return directive()
        status = AgentTaskStatus.CANCELLED
        stopCode = "user.cancel"
        emit(ReliabilityEventType.CANCELLED, stopCode!!)
        return ReliabilityDirective.FAIL
    }

    @Synchronized
    fun complete(routineCompiled: Boolean = false): ReliabilityDirective {
        if (!active()) return directive()
        status = AgentTaskStatus.COMPLETED
        emit(ReliabilityEventType.COMPLETED, "task.completed")
        if (routineCompiled) emit(ReliabilityEventType.ROUTINE_COMPILED, "routine.compiled")
        return ReliabilityDirective.CONTINUE
    }

    @Synchronized
    fun snapshot(): AgentReliabilitySnapshot = AgentReliabilitySnapshot(
        sessionId = sessionId,
        status = status,
        turn = turn,
        consecutiveFailures = consecutiveFailures,
        lastStateFingerprint = lastState,
        stopCode = stopCode,
        events = events.toList(),
    )

    private fun timedOut() = now() - startedAt > config.taskTimeoutMs
    private fun active() = status == AgentTaskStatus.RUNNING
    private fun directive() = when (status) {
        AgentTaskStatus.PAUSED -> ReliabilityDirective.PAUSE
        AgentTaskStatus.CANCELLED, AgentTaskStatus.FAILED -> ReliabilityDirective.FAIL
        else -> ReliabilityDirective.CONTINUE
    }

    private fun pause(code: String): ReliabilityDirective {
        status = AgentTaskStatus.PAUSED
        stopCode = code
        emit(ReliabilityEventType.PAUSED, code)
        return ReliabilityDirective.PAUSE
    }

    private fun fail(code: String): ReliabilityDirective {
        status = AgentTaskStatus.FAILED
        stopCode = code
        emit(ReliabilityEventType.FAILED, code)
        return ReliabilityDirective.FAIL
    }

    private fun emit(
        type: ReliabilityEventType,
        code: String,
        stateFingerprint: String? = null,
        actionSignature: String? = null,
        retryOrdinal: Int? = null,
    ) {
        events.addLast(ReliabilityEvent(type = type, timestampMs = now(), turn = turn, code = code,
            stateFingerprint = stateFingerprint, actionSignature = actionSignature, retryOrdinal = retryOrdinal))
        while (events.size > config.maxHistory) events.removeFirst()
    }

    companion object {
        private val TERMINAL = setOf(AgentTaskStatus.CANCELLED, AgentTaskStatus.COMPLETED, AgentTaskStatus.FAILED)

        fun safeActionSignature(tool: String, stableTarget: String?): String {
            val safeTool = tool.take(80).replace(Regex("[^A-Za-z0-9._-]"), "_")
            // Targets can include visible labels. Persist only a bounded SHA-256 witness, never text or typed values.
            return "$safeTool:${safeFingerprint(stableTarget.orEmpty())}"
        }

        fun safeFingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
