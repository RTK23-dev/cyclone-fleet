package com.cyclone.mobile.agent

/** Monotonic, payload-free causal spans. No UI text, model content or action arguments. */
enum class ExecutionPhase { OBSERVATION, PLAN_OR_RECALL, LOCAL_POLICY, ROUTE_RECALL, PROMPT, PROVIDER_WAIT, GROUNDING, DISPATCH, SETTLING, VERIFICATION }
object ExecutionBudgets {
    fun milliseconds(phase: ExecutionPhase): Long = when (phase) {
        ExecutionPhase.OBSERVATION, ExecutionPhase.VERIFICATION -> 10_000
        ExecutionPhase.PLAN_OR_RECALL -> 35_000
        ExecutionPhase.PROVIDER_WAIT -> 30_000
        ExecutionPhase.DISPATCH -> 20_000
        ExecutionPhase.GROUNDING -> 5_000
        ExecutionPhase.SETTLING -> 3_000
        ExecutionPhase.ROUTE_RECALL -> 1_500
        ExecutionPhase.PROMPT -> 1_000
        ExecutionPhase.LOCAL_POLICY -> 200
    }
}
class ExecutionPhaseTimeout(val phase: ExecutionPhase) : RuntimeException("phase.timeout.${phase.name.lowercase()}")
data class ExecutionSpan(
    val schema: Int = 1,
    val decisionId: Int,
    val spanId: Long,
    val phase: ExecutionPhase,
    val startMs: Long,
    val durationMs: Long,
    val result: String,
)
class ExecutionTiming(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sink: (ExecutionSpan) -> Unit = {},
) {
    companion object { private val sequence = java.util.concurrent.atomic.AtomicLong() }
    fun <T> bounded(decision: Int, phase: ExecutionPhase, cancelled: () -> Boolean = { false }, block: () -> T): T =
        measure(decision, phase, cancelled) {
            val start = clock()
            val value = block()
            if (!cancelled() && clock() - start > ExecutionBudgets.milliseconds(phase)) throw ExecutionPhaseTimeout(phase)
            value
        }
    fun <T> measure(decision: Int, phase: ExecutionPhase, cancelled: () -> Boolean = { false }, block: () -> T): T {
        val start = clock()
        val id = sequence.incrementAndGet()
        var result = "incomplete"
        try {
            val value = block()
            result = if (cancelled()) "cancelled" else "returned"
            return value
        } catch (error: ExecutionPhaseTimeout) {
            result = "deadline"
            throw error
        } finally {
            sink(ExecutionSpan(decisionId = decision, spanId = id, phase = phase,
                startMs = start, durationMs = (clock() - start).coerceAtLeast(0), result = result))
        }
    }
}

