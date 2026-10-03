package com.cyclone.mobile.infrastructure.v31

import com.cyclone.mobile.brain.memory.providers.LocalMemoryLoadState
import com.cyclone.mobile.brain.memory.providers.LocalTieredMemoryProvider
import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.modules.ModuleHealthState
import com.cyclone.mobile.platform.modules.ModuleState
import com.cyclone.mobile.platform.modules.ModuleSupervisor
import com.cyclone.mobile.runtime.recovery.RecoveryManager

enum class V31ServiceState {
    READY,
    DEGRADED,
    UNAVAILABLE,
    FAILED,
}

enum class V31RecoveryState {
    UNINITIALIZED,
    READY,
    CANDIDATE,
    COMMAND_PENDING,
    SAFE_MODE,
    FAILED,
}

/**
 * Compact diagnostics contract for Settings, AI, Gateway and agent tooling. It contains only
 * booleans, enums, built-in module ids and stable failure codes — never tokens, prompts or values.
 */
data class CycloneV31Health(
    val runtimeReady: Boolean,
    val policyReady: Boolean,
    val phoneExecutorReady: Boolean,
    val accessibilityReady: Boolean,
    val memoryReady: Boolean,
    val graphReady: Boolean,
    val automationReady: Boolean,
    val visionState: V31ServiceState,
    val gatewayState: V31ServiceState,
    val recoveryState: V31RecoveryState,
    val degradedModules: List<String>,
    val criticalFailures: List<String>,
) {
    init {
        require(degradedModules == degradedModules.distinct().sorted())
        require(criticalFailures == criticalFailures.distinct().sorted())
        require(criticalFailures.all { it.matches(Regex("[A-Z][A-Z0-9_]{0,127}")) })
    }
}

internal class V31HealthReporter(
    private val supervisor: ModuleSupervisor,
    private val bindings: V31RuntimeBindings,
    private val recovery: RecoveryManager,
    private val memoryProvider: LocalTieredMemoryProvider,
    private val accessibilityReady: () -> Boolean,
    private val phoneExecutorReady: () -> Boolean,
    private val capabilityBootstrapFailures: List<String>,
) {
    fun snapshot(): CycloneV31Health {
        val moduleSnapshot = supervisor.snapshot()
        val moduleById = moduleSnapshot.modules.associateBy { it.descriptor.id }
        val policyReady = moduleById[V31Bootstrap.CORE_POLICY]?.state.isServing()
        val executorReady = phoneExecutorReady() && moduleById[V31Bootstrap.CORE_PHONE]?.state.isServing()
        val memoryReady = memoryProvider.diagnostics().loadState != LocalMemoryLoadState.CORRUPT &&
            moduleById[V31Bootstrap.CORE_MEMORY]?.state.isServing()
        val graphReady = bindings.isBound(V31ExternalModule.APP_GRAPH) &&
            moduleById[V31Bootstrap.CORE_GRAPH]?.state.isServing() &&
            bindings.health(V31ExternalModule.APP_GRAPH).state != ModuleHealthState.FAILED
        val automationReady = bindings.isBound(V31ExternalModule.AUTOMATION) &&
            moduleById[V31Bootstrap.CORE_AUTOMATION]?.state.isServing() &&
            bindings.health(V31ExternalModule.AUTOMATION).state != ModuleHealthState.FAILED
        val recoveryState = safeRecoveryState()
        val degraded = moduleSnapshot.modules
            .filter { it.state != ModuleState.READY }
            .map { it.descriptor.id.value }
            .distinct()
            .sorted()
        val failures = buildList {
            addAll(capabilityBootstrapFailures)
            moduleSnapshot.modules
                .filter { it.descriptor.id in V31Bootstrap.CRITICAL_MODULES }
                .filter { it.state !in setOf(ModuleState.READY, ModuleState.DEGRADED) }
                .forEach { status ->
                    add("${status.descriptor.id.value.uppercase().replace('.', '_').replace('-', '_')}_NOT_SERVING")
                }
            if (recoveryState == V31RecoveryState.FAILED) add("RECOVERY_STATE_FAILED")
        }.distinct().sorted()
        val criticalServing = V31Bootstrap.CRITICAL_MODULES.all { moduleId ->
            moduleById[moduleId]?.state.isServing()
        }
        return CycloneV31Health(
            runtimeReady = criticalServing && policyReady && executorReady &&
                recoveryState !in setOf(V31RecoveryState.UNINITIALIZED, V31RecoveryState.FAILED) && failures.isEmpty(),
            policyReady = policyReady,
            phoneExecutorReady = executorReady,
            accessibilityReady = runCatching(accessibilityReady).getOrDefault(false),
            memoryReady = memoryReady,
            graphReady = graphReady,
            automationReady = automationReady,
            visionState = externalState(V31ExternalModule.VISION, V31Bootstrap.CORE_VISION),
            gatewayState = externalState(V31ExternalModule.GATEWAY, V31Bootstrap.CORE_GATEWAY),
            recoveryState = recoveryState,
            degradedModules = degraded,
            criticalFailures = failures,
        )
    }

    private fun externalState(module: V31ExternalModule, moduleId: ModuleId): V31ServiceState {
        val state = supervisor.status(moduleId)?.state
        if (state == ModuleState.FAILED || state == ModuleState.QUARANTINED) return V31ServiceState.FAILED
        if (state == ModuleState.DISABLED || state == ModuleState.UPDATE_PENDING) return V31ServiceState.UNAVAILABLE
        if (!bindings.isBound(module)) return V31ServiceState.UNAVAILABLE
        return when (bindings.health(module).state) {
            ModuleHealthState.HEALTHY -> if (state == ModuleState.READY) V31ServiceState.READY else V31ServiceState.DEGRADED
            ModuleHealthState.DEGRADED -> V31ServiceState.DEGRADED
            ModuleHealthState.FAILED -> V31ServiceState.FAILED
        }
    }

    private fun safeRecoveryState(): V31RecoveryState = try {
        val state = recovery.state()
        when {
            state.pendingCommand != null -> V31RecoveryState.COMMAND_PENDING
            state.safeModePlan != null -> V31RecoveryState.SAFE_MODE
            state.candidate != null -> V31RecoveryState.CANDIDATE
            state.lastKnownGood != null && state.activeRuntime != null -> V31RecoveryState.READY
            else -> V31RecoveryState.UNINITIALIZED
        }
    } catch (_: Exception) {
        V31RecoveryState.FAILED
    }
}

private fun ModuleState?.isServing(): Boolean = this == ModuleState.READY || this == ModuleState.DEGRADED
