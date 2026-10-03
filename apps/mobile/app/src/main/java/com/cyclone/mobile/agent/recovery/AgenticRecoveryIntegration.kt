package com.cyclone.mobile.agent.recovery

/**
 * Parallel-integration seam. Agent 1's persistent local task runtime should depend on this port,
 * while Agent 2 remains authoritative for actual phone actions and Android verification.
 */
interface AgenticRecoveryRuntimePort {
    fun classifyProgress(before: ObservationEvidence?, after: ObservationEvidence): ProgressResult
    fun selectRecovery(request: RecoveryRequest): RecoveryDecision
    fun classifyFailure(evidence: FailureEvidence): TaskFailureClassification
    fun traceViolations(events: List<AgenticTraceEvent>): List<String>
}

class DefaultAgenticRecoveryRuntimePort(
    private val recoveryPolicy: AgenticRecoveryPolicy = AgenticRecoveryPolicy(),
) : AgenticRecoveryRuntimePort {
    override fun classifyProgress(before: ObservationEvidence?, after: ObservationEvidence): ProgressResult =
        AgenticProgressClassifier.classify(before, after)

    override fun selectRecovery(request: RecoveryRequest): RecoveryDecision = recoveryPolicy.next(request)

    override fun classifyFailure(evidence: FailureEvidence): TaskFailureClassification =
        AgenticFailureClassifier.classify(evidence)

    override fun traceViolations(events: List<AgenticTraceEvent>): List<String> = AgenticTraceContract.violations(events)
}

object AgenticRecoveryIntegrationContract {
    const val PRODUCTION_BINDING_SYMBOL = "AgenticRecoveryRuntimePort"
    const val PRODUCTION_WIRING_GATE = "CYCLONE_REQUIRE_AGENTIC_PRODUCTION_WIRING"
    const val RUNTIME_SYMBOL_OVERRIDE = "CYCLONE_AGENTIC_RUNTIME_SYMBOL"
}
