package com.cyclone.mobile.automation.skill

import com.cyclone.mobile.policy.GateClass
import com.cyclone.mobile.policy.GateClassifier
import com.cyclone.mobile.policy.GateDecision
import com.cyclone.mobile.policy.GatePolicy
import com.cyclone.mobile.policy.PcGateEnvelope
import com.cyclone.mobile.policy.PolicyEvaluation
import com.cyclone.mobile.policy.PolicyPrincipal

enum class SkillSaveSource {
    COMPILE,
    OVERLAY_DONE,
    MCP_SKILL_SAVE,
}

sealed interface SkillDraftSinkResult {
    data class DraftWritten(val capsule: SkillCapsule, val source: SkillSaveSource) : SkillDraftSinkResult
    data class Rejected(val reason: String) : SkillDraftSinkResult
    data class GateDenied(
        val gateClass: GateClass,
        val evaluation: PolicyEvaluation,
        val ignoredPcAutoApprove: Boolean,
    ) : SkillDraftSinkResult {
        val mutationAllowed: Boolean get() = false
        val writesVerified: Boolean get() = false
    }
    data object Ignored : SkillDraftSinkResult
}

/**
 * Phone-side sink for overlay DONE and MCP `phone_skill_save`.
 *
 * Always lands in the existing [com.cyclone.mobile.automation.AutomationStore] through
 * [SkillCompiler.compile]. GATE deny stops mutation and never writes a verified capsule.
 */
class SkillDraftSink(
    private val compiler: SkillCompiler,
    private val gate: GatePolicy,
    private val principal: PolicyPrincipal,
) {
    fun saveFromOverlayDone(input: SkillCompileInput, nowEpochMillis: Long = input.nowEpochMillis): SkillDraftSinkResult =
        save(input, SkillSaveSource.OVERLAY_DONE, pcEnvelope = null, nowEpochMillis = nowEpochMillis)

    fun saveFromMcp(
        input: SkillCompileInput,
        pcEnvelope: PcGateEnvelope? = null,
        nowEpochMillis: Long = input.nowEpochMillis,
    ): SkillDraftSinkResult = save(input, SkillSaveSource.MCP_SKILL_SAVE, pcEnvelope, nowEpochMillis)

    /**
     * Consume overlay chrome states without restyling. Only GATE and DONE are handled here.
     */
    fun consumeOverlayState(
        state: String,
        input: SkillCompileInput?,
        nowEpochMillis: Long = input?.nowEpochMillis ?: 0L,
    ): SkillDraftSinkResult = when (state.trim().uppercase()) {
        OVERLAY_DONE -> input?.let { saveFromOverlayDone(it, nowEpochMillis) }
            ?: SkillDraftSinkResult.Rejected("DONE without a verified path")
        OVERLAY_GATE -> input?.let { evaluateGate(it, pcEnvelope = null, nowEpochMillis) }
            ?: SkillDraftSinkResult.Ignored
        else -> SkillDraftSinkResult.Ignored
    }

    private fun save(
        input: SkillCompileInput,
        source: SkillSaveSource,
        pcEnvelope: PcGateEnvelope?,
        nowEpochMillis: Long,
    ): SkillDraftSinkResult {
        val gated = evaluateGate(input, pcEnvelope, nowEpochMillis)
        if (gated is SkillDraftSinkResult.GateDenied) return gated
        if (pcEnvelope?.requestedCapsuleStatus.equals("verified", ignoreCase = true)) {
            return SkillDraftSinkResult.Rejected("workers/PC cannot flip draft → verified")
        }
        return when (val compiled = compiler.compile(input)) {
            is SkillCompileResult.DraftWritten -> SkillDraftSinkResult.DraftWritten(compiled.capsule, source)
            is SkillCompileResult.Rejected -> SkillDraftSinkResult.Rejected(compiled.reason)
        }
    }

    private fun evaluateGate(
        input: SkillCompileInput,
        pcEnvelope: PcGateEnvelope?,
        nowEpochMillis: Long,
    ): SkillDraftSinkResult {
        input.steps.forEachIndexed { index, step ->
            val labels = buildList {
                add(step.whenClause)
                add(step.thenClause)
                add(step.checkClause)
                add(step.action)
                step.selectors.forEach { add(it.value) }
            }
            val gateClass = GateClassifier.classify(step.action, labels) ?: return@forEachIndexed
            val decision = gate.evaluate(
                actionId = "gate:${input.app}:step-$index:${gateClass.name.lowercase()}",
                action = step.thenClause.ifBlank { step.action },
                labels = labels,
                packageName = input.app,
                principal = principal,
                requestedAtEpochMillis = nowEpochMillis,
                missionId = null,
                grantId = null,
                pcEnvelope = pcEnvelope,
                gateClass = gateClass,
            )
            if (decision is GateDecision.Blocked) {
                return SkillDraftSinkResult.GateDenied(
                    gateClass = decision.gateClass,
                    evaluation = decision.evaluation,
                    ignoredPcAutoApprove = decision.ignoredPcAutoApprove,
                )
            }
        }
        return SkillDraftSinkResult.Ignored
    }

    companion object {
        const val OVERLAY_IDLE = "IDLE"
        const val OVERLAY_ANALYSIS = "ANALYSIS"
        const val OVERLAY_WORKING = "WORKING"
        const val OVERLAY_LIVE = "LIVE"
        const val OVERLAY_GATE = "GATE"
        const val OVERLAY_DONE = "DONE"
        val OVERLAY_STATES = listOf(
            OVERLAY_IDLE, OVERLAY_ANALYSIS, OVERLAY_WORKING, OVERLAY_LIVE, OVERLAY_GATE, OVERLAY_DONE,
        )
    }
}
