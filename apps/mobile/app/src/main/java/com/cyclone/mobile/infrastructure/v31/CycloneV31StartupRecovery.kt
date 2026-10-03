package com.cyclone.mobile.infrastructure.v31

import com.cyclone.mobile.infrastructure.v3.RecoveryModuleCommandBridge
import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.modules.ModuleState
import com.cyclone.mobile.platform.modules.ModuleSupervisor
import com.cyclone.mobile.platform.modules.SupervisorCommandResult
import com.cyclone.mobile.runtime.recovery.ObservedHealth
import com.cyclone.mobile.runtime.recovery.RecoveryCommand
import com.cyclone.mobile.runtime.recovery.RecoveryCommandOutcome
import com.cyclone.mobile.runtime.recovery.RecoveryCommandResult
import com.cyclone.mobile.runtime.recovery.RecoveryDecision
import com.cyclone.mobile.runtime.recovery.RecoveryFailureReason
import com.cyclone.mobile.runtime.recovery.RecoveryHealthObservation
import com.cyclone.mobile.runtime.recovery.RecoveryManager
import com.cyclone.mobile.runtime.recovery.RecoveryModuleHealth
import com.cyclone.mobile.runtime.recovery.RecoveryModuleSnapshot
import com.cyclone.mobile.runtime.recovery.RecoverySnapshot
import com.cyclone.mobile.runtime.recovery.RuntimeIdentity
import com.cyclone.mobile.runtime.recovery.SafeModePlan
import com.cyclone.mobile.runtime.recovery.TrustedCoreService
import java.security.MessageDigest

sealed interface V31StartupPreparation {
    data class Ready(
        val safeMode: Boolean,
        val recoveryDecision: RecoveryDecision,
        val externalRecoveryCommand: RecoveryCommand? = null,
    ) : V31StartupPreparation

    data class Failed(val failureCode: String) : V31StartupPreparation
}

/**
 * Startup/recovery coordinator. Recovery makes every recovery decision; this class can only apply
 * Recovery-issued optional-module/safe-mode commands through ModuleSupervisor. Runtime promotion
 * and rollback commands remain pending for a separately bound data-slot activator.
 */
class CycloneV31StartupRecovery internal constructor(
    private val supervisor: ModuleSupervisor,
    private val recovery: RecoveryManager,
    private val clock: V31Clock = V31Clock(System::currentTimeMillis),
) {
    private val moduleBridge = RecoveryModuleCommandBridge(supervisor)
    private val currentSnapshot = buildCompiledSnapshot(supervisor)
    private var observationSequence = 0L

    fun compiledKnownGoodSnapshot(): RecoverySnapshot = currentSnapshot.normalized()

    @Synchronized
    fun prepareStartup(): V31StartupPreparation {
        val now = clock.nowEpochMillis()
        return try {
            var decision: RecoveryDecision = if (recovery.state().lastKnownGood == null) {
                recovery.initializeKnownGood(currentSnapshot, now)
            } else {
                RecoveryDecision.NoChange("known-good-restored")
            }

            val pendingBeforeStart = recovery.state().pendingCommand
            val external = if (pendingBeforeStart != null) {
                when (val local = applyLocalRecoveryCommand(pendingBeforeStart, now)) {
                    is LocalRecoveryCommandResult.Applied -> {
                        decision = local.decision
                        null
                    }
                    LocalRecoveryCommandResult.ExternalRequired -> pendingBeforeStart
                }
            } else null

            recovery.state().safeModePlan?.let(::applySafeModePlan)
            val candidate = recovery.state().candidate
            if (candidate != null && recovery.state().pendingCommand == null) {
                decision = recovery.recordCandidateBootAttempt(candidate.candidate.runtime, now)
                val command = (decision as? RecoveryDecision.CommandRequired)?.command
                if (command != null) {
                    when (val local = applyLocalRecoveryCommand(command, now)) {
                        is LocalRecoveryCommandResult.Applied -> decision = local.decision
                        LocalRecoveryCommandResult.ExternalRequired -> Unit
                    }
                }
            }

            supervisor.startAll(now)
            supervisor.refreshHealth(now)
            val pendingAfterStart = recovery.state().pendingCommand
            V31StartupPreparation.Ready(
                safeMode = recovery.state().safeModePlan != null,
                recoveryDecision = decision,
                externalRecoveryCommand = when (pendingAfterStart) {
                    is RecoveryCommand.PromoteCandidate,
                    is RecoveryCommand.RollbackRuntime,
                    -> pendingAfterStart
                    else -> external
                },
            )
        } catch (_: Exception) {
            V31StartupPreparation.Failed("V31_STARTUP_RECOVERY_FAILED")
        }
    }

    @Synchronized
    fun recordSuccessfulStartup(health: CycloneV31Health): RecoveryDecision {
        val now = clock.nowEpochMillis()
        supervisor.restartDue(now)
        supervisor.refreshHealth(now)
        val state = recovery.state()
        if (state.pendingCommand != null) return RecoveryDecision.NoChange("recovery-command-pending")
        val candidate = state.candidate
        val decision = if (candidate != null) {
            observationSequence += 1
            recovery.observeCandidateHealth(
                RecoveryHealthObservation(
                    observationId = "startup.health.$observationSequence",
                    updateId = candidate.updateId,
                    runtime = candidate.candidate.runtime,
                    observedAtEpochMillis = now,
                    trustedCore = trustedCoreHealth(health),
                    modules = moduleHealth(),
                    runtimeStable = health.runtimeReady,
                    schemasReadable = supervisor.status(V31Bootstrap.CORE_MEMORY)?.state != ModuleState.FAILED,
                ),
            )
        } else {
            val active = state.activeRuntime ?: return RecoveryDecision.NoChange("runtime-not-initialized")
            recovery.recordStableActiveHealth(active, now)
        }
        return settleLocalDecision(decision, now)
    }

    @Synchronized
    fun recordFailedStartup(
        reason: RecoveryFailureReason = RecoveryFailureReason.TRUSTED_CORE_UNHEALTHY,
    ): RecoveryDecision {
        val now = clock.nowEpochMillis()
        val active = recovery.state().activeRuntime
            ?: return RecoveryDecision.NoChange("runtime-not-initialized")
        return settleLocalDecision(recovery.recordCrash(active, reason, now), now)
    }

    fun pendingExternalRecoveryCommand(): RecoveryCommand? = when (val command = recovery.state().pendingCommand) {
        is RecoveryCommand.PromoteCandidate,
        is RecoveryCommand.RollbackRuntime,
        -> command
        else -> null
    }

    private fun settleLocalDecision(decision: RecoveryDecision, now: Long): RecoveryDecision {
        val command = (decision as? RecoveryDecision.CommandRequired)?.command ?: return decision
        return when (val local = applyLocalRecoveryCommand(command, now)) {
            is LocalRecoveryCommandResult.Applied -> local.decision
            LocalRecoveryCommandResult.ExternalRequired -> decision
        }
    }

    private sealed interface LocalRecoveryCommandResult {
        data class Applied(val decision: RecoveryDecision) : LocalRecoveryCommandResult
        data object ExternalRequired : LocalRecoveryCommandResult
    }

    private fun applyLocalRecoveryCommand(command: RecoveryCommand, now: Long): LocalRecoveryCommandResult = when (command) {
        is RecoveryCommand.QuarantineOptionalModule -> {
            val result = moduleBridge.execute(command).copy(completedAtEpochMillis = maxOf(now, command.issuedAtEpochMillis))
            LocalRecoveryCommandResult.Applied(recovery.recordCommandResult(result))
        }
        is RecoveryCommand.EnterSafeMode -> {
            val applied = applySafeModePlan(command.plan)
            val result = RecoveryCommandResult(
                commandId = command.commandId,
                outcome = if (applied) RecoveryCommandOutcome.SUCCEEDED else RecoveryCommandOutcome.FAILED,
                completedAtEpochMillis = maxOf(now, command.issuedAtEpochMillis),
            )
            LocalRecoveryCommandResult.Applied(recovery.recordCommandResult(result))
        }
        is RecoveryCommand.PromoteCandidate,
        is RecoveryCommand.RollbackRuntime,
        -> LocalRecoveryCommandResult.ExternalRequired
    }

    private fun applySafeModePlan(plan: SafeModePlan): Boolean = plan.disabledOptionalModules.sorted().all { moduleId ->
        when (supervisor.disable(moduleId)) {
            is SupervisorCommandResult.Applied -> true
            is SupervisorCommandResult.Missing,
            is SupervisorCommandResult.Rejected,
            -> false
        }
    }

    private fun trustedCoreHealth(health: CycloneV31Health): Map<TrustedCoreService, ObservedHealth> = mapOf(
        TrustedCoreService.ACCESSIBILITY to if (health.accessibilityReady) ObservedHealth.HEALTHY else ObservedHealth.DEGRADED,
        TrustedCoreService.PHONE_TOOL_EXECUTOR to if (health.phoneExecutorReady) ObservedHealth.HEALTHY else ObservedHealth.FAILED,
        TrustedCoreService.PAGE_AWARENESS to moduleObservedHealth(V31Bootstrap.CORE_PAGE),
        TrustedCoreService.POLICY to if (health.policyReady) ObservedHealth.HEALTHY else ObservedHealth.FAILED,
        TrustedCoreService.RECOVERY to if (health.recoveryState == V31RecoveryState.FAILED) ObservedHealth.FAILED else ObservedHealth.HEALTHY,
        TrustedCoreService.MINIMAL_UI to ObservedHealth.HEALTHY,
    )

    private fun moduleHealth(): List<RecoveryModuleHealth> = supervisor.snapshot().modules
        .map { RecoveryModuleHealth(it.descriptor.id, it.state.toObservedHealth()) }
        .sortedBy { it.moduleId }

    private fun moduleObservedHealth(moduleId: ModuleId): ObservedHealth =
        supervisor.status(moduleId)?.state.toObservedHealth()

    private fun buildCompiledSnapshot(supervisor: ModuleSupervisor): RecoverySnapshot {
        val modules = supervisor.snapshot().modules.map { status ->
            RecoveryModuleSnapshot(
                moduleId = status.descriptor.id,
                version = status.descriptor.version,
                enabled = status.enabled,
                essential = status.descriptor.id in V31Bootstrap.CRITICAL_MODULES,
            )
        }.sortedBy { it.moduleId }
        val inventory = modules.joinToString("\n") {
            "${it.moduleId.value}|${it.version}|${it.enabled}|${it.essential}"
        }
        return RecoverySnapshot(
            snapshotId = "cyclone.v31.compiled",
            capturedAtEpochMillis = 0L,
            runtime = RuntimeIdentity(
                runtimeId = "cyclone-v31",
                runtimeApiVersion = "3.1",
                manifestSha256 = sha256("cyclone-v31-runtime\n$inventory"),
            ),
            configurationSha256 = sha256("cyclone-v31-config\n$inventory"),
            modules = modules,
            schemas = emptyList(),
            lastUpdateId = null,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

private fun ModuleState?.toObservedHealth(): ObservedHealth = when (this) {
    ModuleState.READY -> ObservedHealth.HEALTHY
    ModuleState.DEGRADED -> ObservedHealth.DEGRADED
    ModuleState.FAILED,
    ModuleState.QUARANTINED,
    -> ObservedHealth.FAILED
    ModuleState.INSTALLED,
    ModuleState.DISABLED,
    ModuleState.STARTING,
    ModuleState.UPDATE_PENDING,
    null,
    -> ObservedHealth.UNAVAILABLE
}
