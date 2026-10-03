package com.cyclone.mobile.platform.modules

import com.cyclone.mobile.platform.lifecycle.ServiceLifecycleState
import com.cyclone.mobile.platform.module.CycloneApiVersion
import com.cyclone.mobile.platform.module.ModuleDescriptor
import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.module.ModuleVersion

/** Runtime modules are declarations compiled into, or explicitly assembled with, Cyclone. */
enum class TrustedModuleOrigin {
    COMPILED_IN,
    DECLARED_TRUSTED,
}

enum class ModuleImportance {
    CRITICAL_BUILT_IN,
    OPTIONAL,
}

enum class ModuleState {
    INSTALLED,
    DISABLED,
    STARTING,
    READY,
    DEGRADED,
    QUARANTINED,
    UPDATE_PENDING,
    FAILED,
}

enum class ModuleHealthState {
    HEALTHY,
    DEGRADED,
    FAILED,
}

data class ModuleHealthReport(
    val state: ModuleHealthState,
    val reason: String? = null,
) {
    init {
        require(reason == null || reason.isNotBlank()) { "Health reason must not be blank" }
    }

    companion object {
        fun healthy() = ModuleHealthReport(ModuleHealthState.HEALTHY)
        fun degraded(reason: String) = ModuleHealthReport(ModuleHealthState.DEGRADED, reason)
        fun failed(reason: String) = ModuleHealthReport(ModuleHealthState.FAILED, reason)
    }
}

sealed interface ModuleOperationResult {
    data object Success : ModuleOperationResult
    data class Failure(val reason: String) : ModuleOperationResult {
        init {
            require(reason.isNotBlank()) { "Failure reason must not be blank" }
        }
    }
}

/**
 * Hooks implemented by a trusted, already-compiled Cyclone component. There is intentionally no
 * class loader, script evaluator, installer, shell command, or downloaded-code entry point.
 */
interface TrustedModuleRuntime {
    fun start(): ModuleOperationResult
    fun stop(): ModuleOperationResult
    fun health(): ModuleHealthReport
}

enum class MigrationDisposition {
    CURRENT,
    REQUIRED,
    BLOCKED,
}

data class ModuleMigrationPlan(
    val disposition: MigrationDisposition,
    val targetMigrationVersion: Int,
    val steps: List<String> = emptyList(),
    val reason: String? = null,
) {
    init {
        require(targetMigrationVersion >= 0) { "Migration version must be non-negative" }
        require(steps.none { it.isBlank() }) { "Migration steps must not be blank" }
        require(reason == null || reason.isNotBlank()) { "Migration reason must not be blank" }
        require(disposition != MigrationDisposition.BLOCKED || reason != null) {
            "A blocked migration needs an explanation"
        }
    }
}

fun interface ModuleMigrationPlanner {
    fun plan(descriptor: ModuleDescriptor): ModuleMigrationPlan
}

object CurrentModuleMigrationPlanner : ModuleMigrationPlanner {
    override fun plan(descriptor: ModuleDescriptor) = ModuleMigrationPlan(
        disposition = MigrationDisposition.CURRENT,
        targetMigrationVersion = descriptor.migrationVersion,
    )
}

enum class UpdatePreflightDecision {
    READY,
    MIGRATION_REQUIRED,
    REJECTED,
}

data class ModuleUpdateCandidate(
    val descriptor: ModuleDescriptor,
    val manifestDigestSha256: String,
) {
    init {
        require(SHA_256.matches(manifestDigestSha256)) { "Update manifest digest must be lowercase SHA-256" }
    }

    private companion object {
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}

data class ModuleUpdatePreflightResult(
    val decision: UpdatePreflightDecision,
    val reasons: List<String> = emptyList(),
) {
    init {
        require(reasons.none { it.isBlank() }) { "Preflight reasons must not be blank" }
        require(decision != UpdatePreflightDecision.REJECTED || reasons.isNotEmpty()) {
            "A rejected update needs an explanation"
        }
    }
}

fun interface ModuleUpdatePreflight {
    fun inspect(
        current: ModuleDescriptor,
        candidate: ModuleUpdateCandidate,
        cycloneApiVersion: CycloneApiVersion,
    ): ModuleUpdatePreflightResult
}

object DefaultModuleUpdatePreflight : ModuleUpdatePreflight {
    override fun inspect(
        current: ModuleDescriptor,
        candidate: ModuleUpdateCandidate,
        cycloneApiVersion: CycloneApiVersion,
    ): ModuleUpdatePreflightResult {
        val next = candidate.descriptor
        val reasons = buildList {
            if (next.id != current.id) add("Candidate module id does not match ${current.id}")
            if (next.version <= current.version) add("Candidate version must be newer than ${current.version}")
            if (!next.compatibleCycloneApi.supports(cycloneApiVersion)) {
                add("Candidate is incompatible with Cyclone API $cycloneApiVersion")
            }
        }
        if (reasons.isNotEmpty()) return ModuleUpdatePreflightResult(UpdatePreflightDecision.REJECTED, reasons)

        return if (next.migrationVersion > current.migrationVersion) {
            ModuleUpdatePreflightResult(
                UpdatePreflightDecision.MIGRATION_REQUIRED,
                listOf("Schema migration ${current.migrationVersion} -> ${next.migrationVersion} is required"),
            )
        } else {
            ModuleUpdatePreflightResult(UpdatePreflightDecision.READY)
        }
    }
}

fun interface ModuleRollbackHook {
    fun rollback(fromVersion: ModuleVersion, targetVersion: ModuleVersion): ModuleOperationResult
}

data class TrustedModuleDeclaration(
    val descriptor: ModuleDescriptor,
    val runtime: TrustedModuleRuntime,
    val origin: TrustedModuleOrigin = TrustedModuleOrigin.COMPILED_IN,
    val importance: ModuleImportance = ModuleImportance.OPTIONAL,
    val migrationPlanner: ModuleMigrationPlanner = CurrentModuleMigrationPlanner,
    val updatePreflight: ModuleUpdatePreflight = DefaultModuleUpdatePreflight,
    val rollbackHook: ModuleRollbackHook? = null,
)

data class RestartPolicy(
    /** Maximum start attempts in one failure streak, including the initial attempt. */
    val maxStartAttempts: Int = 3,
    val initialBackoffMillis: Long = 1_000,
    val backoffMultiplier: Int = 2,
    val maximumBackoffMillis: Long = 60_000,
) {
    init {
        require(maxStartAttempts >= 1) { "Restart policy must allow at least one start attempt" }
        require(initialBackoffMillis >= 0) { "Initial backoff must be non-negative" }
        require(backoffMultiplier >= 1) { "Backoff multiplier must be at least one" }
        require(maximumBackoffMillis >= initialBackoffMillis) {
            "Maximum backoff must be at least the initial backoff"
        }
    }

    fun delayAfterFailure(failureCount: Int): Long {
        require(failureCount >= 1) { "Failure count must be positive" }
        var delay = initialBackoffMillis
        repeat(failureCount - 1) {
            delay = if (delay == 0L) {
                0L
            } else if (delay > maximumBackoffMillis / backoffMultiplier) {
                maximumBackoffMillis
            } else {
                (delay * backoffMultiplier).coerceAtMost(maximumBackoffMillis)
            }
        }
        return delay
    }
}

enum class ModuleDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

enum class ModuleDiagnosticCode {
    DUPLICATE_MODULE,
    INCOMPATIBLE_CYCLONE_API,
    MISSING_DEPENDENCY,
    INCOMPATIBLE_DEPENDENCY,
    DEPENDENCY_UNAVAILABLE,
    DEPENDENCY_CYCLE,
    DUPLICATE_PROVIDER,
    MIGRATION_REQUIRED,
    MIGRATION_BLOCKED,
    START_FAILED,
    STOP_FAILED,
    HEALTH_CHECK_FAILED,
    RESTART_SCHEDULED,
    RESTART_EXHAUSTED,
    QUARANTINE_REASON,
    RECOVERY_QUARANTINE,
    CRITICAL_MODULE,
    ACTIVE_DEPENDENTS,
    UPDATE_REJECTED,
    UPDATE_PENDING,
    ROLLBACK_UNAVAILABLE,
    ROLLBACK_FAILED,
    ROLLBACK_COMPLETED,
}

data class ModuleDiagnostic(
    val code: ModuleDiagnosticCode,
    val severity: ModuleDiagnosticSeverity,
    val message: String,
    val moduleId: ModuleId? = null,
    val relatedModuleIds: List<ModuleId> = emptyList(),
) {
    init {
        require(message.isNotBlank()) { "Diagnostic message must not be blank" }
        require(relatedModuleIds == relatedModuleIds.distinct().sorted()) {
            "Related module ids must be unique and sorted"
        }
    }
}

data class ModuleStatus(
    val descriptor: ModuleDescriptor,
    val origin: TrustedModuleOrigin,
    val importance: ModuleImportance,
    val state: ModuleState,
    val enabled: Boolean,
    val lifecycleState: ServiceLifecycleState,
    val failedStartAttempts: Int,
    val nextRestartAtEpochMillis: Long?,
    val quarantineReason: String?,
    val diagnostics: List<ModuleDiagnostic>,
)

data class ModuleSupervisorSnapshot(
    val cycloneApiVersion: CycloneApiVersion,
    val deterministicStartOrder: List<ModuleId>,
    val modules: List<ModuleStatus>,
    val discoveryDiagnostics: List<ModuleDiagnostic>,
)

sealed interface SupervisorCommandResult {
    data class Applied(val status: ModuleStatus) : SupervisorCommandResult
    data class Rejected(val diagnostic: ModuleDiagnostic) : SupervisorCommandResult
    data class Missing(val moduleId: ModuleId) : SupervisorCommandResult
}
