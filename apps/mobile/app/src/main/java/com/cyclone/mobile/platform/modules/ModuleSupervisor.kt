package com.cyclone.mobile.platform.modules

import com.cyclone.mobile.platform.lifecycle.ServiceLifecycleState
import com.cyclone.mobile.platform.module.CycloneApiVersion
import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.module.ModuleVersion

/**
 * The only mutable lifecycle authority for trusted Cyclone module declarations.
 *
 * Time is always supplied by the caller. Restart scheduling therefore never sleeps and is fully
 * deterministic in unit tests and recovery replays.
 */
class ModuleSupervisor private constructor(
    val cycloneApiVersion: CycloneApiVersion,
    private val restartPolicy: RestartPolicy,
    private val analysis: AnalyzedDeclarations,
) {
    private data class MutableRecord(
        val declaration: TrustedModuleDeclaration,
        var state: ModuleState,
        var enabled: Boolean = true,
        var failedStartAttempts: Int = 0,
        var nextRestartAtEpochMillis: Long? = null,
        var quarantineReason: String? = null,
        val diagnostics: MutableList<ModuleDiagnostic>,
        var migrationPlan: ModuleMigrationPlan,
    )

    private val records = analysis.declarations.mapValuesTo(sortedMapOf()) { (id, declaration) ->
        val baseDiagnostics = analysis.moduleDiagnostics.getValue(id).toMutableList()
        val validationFailed = baseDiagnostics.any { it.severity == ModuleDiagnosticSeverity.ERROR }
        val migrationPlan = safeMigrationPlan(declaration)
        val state = when {
            validationFailed -> ModuleState.FAILED
            migrationPlan.disposition == MigrationDisposition.REQUIRED -> ModuleState.UPDATE_PENDING
            migrationPlan.disposition == MigrationDisposition.BLOCKED -> ModuleState.FAILED
            else -> ModuleState.INSTALLED
        }
        when (migrationPlan.disposition) {
            MigrationDisposition.REQUIRED -> baseDiagnostics += ModuleDiagnostic(
                ModuleDiagnosticCode.MIGRATION_REQUIRED,
                ModuleDiagnosticSeverity.WARNING,
                migrationPlan.reason ?: "Module $id requires migration ${migrationPlan.targetMigrationVersion}",
                id,
            )
            MigrationDisposition.BLOCKED -> baseDiagnostics += ModuleDiagnostic(
                ModuleDiagnosticCode.MIGRATION_BLOCKED,
                ModuleDiagnosticSeverity.ERROR,
                migrationPlan.reason ?: "Module $id migration is blocked",
                id,
            )
            MigrationDisposition.CURRENT -> Unit
        }
        MutableRecord(
            declaration = declaration,
            state = state,
            diagnostics = sortDiagnostics(baseDiagnostics).toMutableList(),
            migrationPlan = migrationPlan,
        )
    }

    @Synchronized
    fun snapshot(): ModuleSupervisorSnapshot = ModuleSupervisorSnapshot(
        cycloneApiVersion = cycloneApiVersion,
        deterministicStartOrder = analysis.startOrder,
        modules = records.values.map(::status),
        discoveryDiagnostics = analysis.discoveryDiagnostics,
    )

    @Synchronized
    fun status(moduleId: ModuleId): ModuleStatus? = records[moduleId]?.let(::status)

    @Synchronized
    fun migrationPlan(moduleId: ModuleId): ModuleMigrationPlan? = records[moduleId]?.migrationPlan

    @Synchronized
    fun startAll(nowEpochMillis: Long): ModuleSupervisorSnapshot {
        requireTimestamp(nowEpochMillis)
        analysis.startOrder.forEach { moduleId ->
            val record = records.getValue(moduleId)
            if (record.enabled && (record.state == ModuleState.INSTALLED || canRetry(record, nowEpochMillis))) {
                startInternal(moduleId, record, nowEpochMillis)
            }
        }
        return snapshot()
    }

    @Synchronized
    fun start(moduleId: ModuleId, nowEpochMillis: Long): SupervisorCommandResult {
        requireTimestamp(nowEpochMillis)
        val record = records[moduleId] ?: return SupervisorCommandResult.Missing(moduleId)
        if (record.state == ModuleState.READY || record.state == ModuleState.DEGRADED) {
            return SupervisorCommandResult.Applied(status(record))
        }
        startRejection(moduleId, record, nowEpochMillis)?.let { return SupervisorCommandResult.Rejected(it) }
        startInternal(moduleId, record, nowEpochMillis)
        return SupervisorCommandResult.Applied(status(record))
    }

    @Synchronized
    fun restartDue(nowEpochMillis: Long): ModuleSupervisorSnapshot {
        requireTimestamp(nowEpochMillis)
        analysis.startOrder.forEach { moduleId ->
            val record = records.getValue(moduleId)
            if (record.enabled && canRetry(record, nowEpochMillis)) {
                startInternal(moduleId, record, nowEpochMillis)
            }
        }
        return snapshot()
    }

    @Synchronized
    fun refreshHealth(nowEpochMillis: Long): ModuleSupervisorSnapshot {
        requireTimestamp(nowEpochMillis)
        analysis.startOrder.forEach { moduleId ->
            val record = records.getValue(moduleId)
            if (record.state == ModuleState.READY || record.state == ModuleState.DEGRADED) {
                val missingDependencies = unavailableRequiredDependencies(record)
                if (missingDependencies.isNotEmpty()) {
                    safeStop(record)
                    record.state = ModuleState.FAILED
                    record.nextRestartAtEpochMillis = null
                    replaceDiagnostic(
                        record,
                        ModuleDiagnostic(
                            ModuleDiagnosticCode.DEPENDENCY_UNAVAILABLE,
                            ModuleDiagnosticSeverity.ERROR,
                            "Module $moduleId lost required modules: ${missingDependencies.joinToString()}",
                            moduleId,
                            missingDependencies,
                        ),
                    )
                } else {
                    applyHealth(moduleId, record, safeHealth(record), nowEpochMillis)
                }
            }
        }
        return snapshot()
    }

    @Synchronized
    fun stop(moduleId: ModuleId): SupervisorCommandResult {
        val record = records[moduleId] ?: return SupervisorCommandResult.Missing(moduleId)
        if (record.state !in ACTIVE_STATES) {
            return SupervisorCommandResult.Applied(status(record))
        }
        return when (val outcome = safeStop(record)) {
            ModuleOperationResult.Success -> {
                record.state = if (record.enabled) ModuleState.INSTALLED else ModuleState.DISABLED
                record.nextRestartAtEpochMillis = null
                SupervisorCommandResult.Applied(status(record))
            }
            is ModuleOperationResult.Failure -> {
                record.state = ModuleState.FAILED
                val diagnostic = ModuleDiagnostic(
                    ModuleDiagnosticCode.STOP_FAILED,
                    ModuleDiagnosticSeverity.ERROR,
                    "Module $moduleId failed to stop: ${outcome.reason}",
                    moduleId,
                )
                replaceDiagnostic(record, diagnostic)
                SupervisorCommandResult.Rejected(diagnostic)
            }
        }
    }

    @Synchronized
    fun stopAll(): ModuleSupervisorSnapshot {
        analysis.startOrder.asReversed().forEach { moduleId -> stop(moduleId) }
        return snapshot()
    }

    @Synchronized
    fun disable(moduleId: ModuleId): SupervisorCommandResult {
        val record = records[moduleId] ?: return SupervisorCommandResult.Missing(moduleId)
        if (record.declaration.importance == ModuleImportance.CRITICAL_BUILT_IN) {
            return SupervisorCommandResult.Rejected(
                ModuleDiagnostic(
                    ModuleDiagnosticCode.CRITICAL_MODULE,
                    ModuleDiagnosticSeverity.ERROR,
                    "Critical built-in module $moduleId cannot be disabled",
                    moduleId,
                ),
            )
        }
        val activeDependents = activeRequiredDependents(moduleId)
        if (activeDependents.isNotEmpty()) {
            return SupervisorCommandResult.Rejected(
                ModuleDiagnostic(
                    ModuleDiagnosticCode.ACTIVE_DEPENDENTS,
                    ModuleDiagnosticSeverity.ERROR,
                    "Module $moduleId is required by active modules: ${activeDependents.joinToString()}",
                    moduleId,
                    activeDependents,
                ),
            )
        }
        record.enabled = false
        if (record.state in ACTIVE_STATES) {
            when (val stopped = safeStop(record)) {
                ModuleOperationResult.Success -> record.state = ModuleState.DISABLED
                is ModuleOperationResult.Failure -> {
                    record.state = ModuleState.FAILED
                    val diagnostic = ModuleDiagnostic(
                        ModuleDiagnosticCode.STOP_FAILED,
                        ModuleDiagnosticSeverity.ERROR,
                        "Module $moduleId failed to stop while disabling: ${stopped.reason}",
                        moduleId,
                    )
                    replaceDiagnostic(record, diagnostic)
                    return SupervisorCommandResult.Rejected(diagnostic)
                }
            }
        } else {
            record.state = ModuleState.DISABLED
        }
        record.nextRestartAtEpochMillis = null
        return SupervisorCommandResult.Applied(status(record))
    }

    @Synchronized
    fun enable(moduleId: ModuleId): SupervisorCommandResult {
        val record = records[moduleId] ?: return SupervisorCommandResult.Missing(moduleId)
        if (hasStaticErrors(record)) {
            return SupervisorCommandResult.Rejected(firstStaticError(record))
        }
        record.enabled = true
        if (record.state == ModuleState.DISABLED) record.state = ModuleState.INSTALLED
        return SupervisorCommandResult.Applied(status(record))
    }

    @Synchronized
    fun clearQuarantine(moduleId: ModuleId): SupervisorCommandResult {
        val record = records[moduleId] ?: return SupervisorCommandResult.Missing(moduleId)
        if (record.state != ModuleState.QUARANTINED) {
            return SupervisorCommandResult.Applied(status(record))
        }
        record.state = if (record.enabled) ModuleState.INSTALLED else ModuleState.DISABLED
        record.failedStartAttempts = 0
        record.nextRestartAtEpochMillis = null
        record.quarantineReason = null
        removeDiagnostics(record, FAILURE_DIAGNOSTICS)
        return SupervisorCommandResult.Applied(status(record))
    }

    /**
     * Public recovery seam. Recovery may request isolation, but only this supervisor validates and
     * mutates lifecycle state. Critical built-ins and active dependency roots remain protected.
     */
    @Synchronized
    fun quarantineOptional(moduleId: ModuleId, reasonCode: String): SupervisorCommandResult {
        val record = records[moduleId] ?: return SupervisorCommandResult.Missing(moduleId)
        if (record.declaration.importance == ModuleImportance.CRITICAL_BUILT_IN) {
            return SupervisorCommandResult.Rejected(
                ModuleDiagnostic(
                    ModuleDiagnosticCode.CRITICAL_MODULE,
                    ModuleDiagnosticSeverity.ERROR,
                    "Critical built-in module $moduleId cannot be quarantined",
                    moduleId,
                ),
            )
        }
        val activeDependents = activeRequiredDependents(moduleId)
        if (activeDependents.isNotEmpty()) {
            return SupervisorCommandResult.Rejected(
                ModuleDiagnostic(
                    ModuleDiagnosticCode.ACTIVE_DEPENDENTS,
                    ModuleDiagnosticSeverity.ERROR,
                    "Module $moduleId is required by active modules: ${activeDependents.joinToString()}",
                    moduleId,
                    activeDependents,
                ),
            )
        }
        val safeReason = reasonCode.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) }
            ?: return SupervisorCommandResult.Rejected(
                ModuleDiagnostic(
                    ModuleDiagnosticCode.RECOVERY_QUARANTINE,
                    ModuleDiagnosticSeverity.ERROR,
                    "Recovery quarantine reason must be a safe code",
                    moduleId,
                ),
            )
        if (record.state in ACTIVE_STATES) {
            when (safeStop(record)) {
                ModuleOperationResult.Success -> Unit
                is ModuleOperationResult.Failure -> return SupervisorCommandResult.Rejected(
                    ModuleDiagnostic(
                        ModuleDiagnosticCode.STOP_FAILED,
                        ModuleDiagnosticSeverity.ERROR,
                        "Module $moduleId could not be stopped for recovery quarantine",
                        moduleId,
                    ),
                )
            }
        }
        record.enabled = false
        record.state = ModuleState.QUARANTINED
        record.nextRestartAtEpochMillis = null
        record.quarantineReason = safeReason
        replaceDiagnostic(
            record,
            ModuleDiagnostic(
                ModuleDiagnosticCode.RECOVERY_QUARANTINE,
                ModuleDiagnosticSeverity.ERROR,
                "Recovery quarantined optional module $moduleId ($safeReason)",
                moduleId,
            ),
        )
        return SupervisorCommandResult.Applied(status(record))
    }

    @Synchronized
    fun preflightUpdate(moduleId: ModuleId, candidate: ModuleUpdateCandidate): ModuleUpdatePreflightResult {
        val record = records[moduleId]
            ?: return ModuleUpdatePreflightResult(
                UpdatePreflightDecision.REJECTED,
                listOf("Unknown module $moduleId"),
            )
        val baseline = DefaultModuleUpdatePreflight.inspect(
            record.declaration.descriptor,
            candidate,
            cycloneApiVersion,
        )
        if (baseline.decision == UpdatePreflightDecision.REJECTED) return baseline

        val moduleSpecific = try {
            record.declaration.updatePreflight.inspect(record.declaration.descriptor, candidate, cycloneApiVersion)
        } catch (error: Exception) {
            ModuleUpdatePreflightResult(
                UpdatePreflightDecision.REJECTED,
                listOf("Update preflight threw ${safeThrowableName(error)}"),
            )
        }
        if (moduleSpecific.decision == UpdatePreflightDecision.REJECTED) return moduleSpecific

        val dependencyReasons = candidate.descriptor.dependencies.sortedBy { it.moduleId }.mapNotNull { dependency ->
            val installed = records[dependency.moduleId]?.declaration?.descriptor
            when {
                installed == null -> "Candidate requires missing module ${dependency.moduleId}"
                !dependency.accepts(installed.version) ->
                    "Candidate does not accept ${dependency.moduleId} ${installed.version}"
                else -> null
            }
        }
        return if (dependencyReasons.isNotEmpty()) {
            ModuleUpdatePreflightResult(
                UpdatePreflightDecision.REJECTED,
                (baseline.reasons + moduleSpecific.reasons + dependencyReasons).distinct(),
            )
        } else {
            val migrationRequired = baseline.decision == UpdatePreflightDecision.MIGRATION_REQUIRED ||
                moduleSpecific.decision == UpdatePreflightDecision.MIGRATION_REQUIRED
            ModuleUpdatePreflightResult(
                decision = if (migrationRequired) {
                    UpdatePreflightDecision.MIGRATION_REQUIRED
                } else {
                    UpdatePreflightDecision.READY
                },
                reasons = (baseline.reasons + moduleSpecific.reasons).distinct(),
            )
        }
    }

    @Synchronized
    fun prepareUpdate(moduleId: ModuleId, candidate: ModuleUpdateCandidate): SupervisorCommandResult {
        val record = records[moduleId] ?: return SupervisorCommandResult.Missing(moduleId)
        if (record.state in ACTIVE_STATES) {
            return SupervisorCommandResult.Rejected(
                ModuleDiagnostic(
                    ModuleDiagnosticCode.UPDATE_REJECTED,
                    ModuleDiagnosticSeverity.ERROR,
                    "Stop module $moduleId before preparing an update",
                    moduleId,
                ),
            )
        }
        val preflight = preflightUpdate(moduleId, candidate)
        if (preflight.decision == UpdatePreflightDecision.REJECTED) {
            val diagnostic = ModuleDiagnostic(
                ModuleDiagnosticCode.UPDATE_REJECTED,
                ModuleDiagnosticSeverity.ERROR,
                preflight.reasons.joinToString("; "),
                moduleId,
            )
            replaceDiagnostic(record, diagnostic)
            return SupervisorCommandResult.Rejected(diagnostic)
        }
        record.state = ModuleState.UPDATE_PENDING
        val diagnostic = ModuleDiagnostic(
            if (preflight.decision == UpdatePreflightDecision.MIGRATION_REQUIRED) {
                ModuleDiagnosticCode.MIGRATION_REQUIRED
            } else {
                ModuleDiagnosticCode.UPDATE_PENDING
            },
            ModuleDiagnosticSeverity.WARNING,
            preflight.reasons.firstOrNull() ?: "Module $moduleId update passed preflight and is pending",
            moduleId,
        )
        replaceDiagnostic(record, diagnostic)
        return SupervisorCommandResult.Applied(status(record))
    }

    @Synchronized
    fun rollback(
        moduleId: ModuleId,
        targetVersion: ModuleVersion,
    ): SupervisorCommandResult {
        val record = records[moduleId] ?: return SupervisorCommandResult.Missing(moduleId)
        if (record.state in ACTIVE_STATES) {
            return SupervisorCommandResult.Rejected(
                ModuleDiagnostic(
                    ModuleDiagnosticCode.ROLLBACK_FAILED,
                    ModuleDiagnosticSeverity.ERROR,
                    "Stop module $moduleId before rollback",
                    moduleId,
                ),
            )
        }
        if (targetVersion >= record.declaration.descriptor.version) {
            return SupervisorCommandResult.Rejected(
                ModuleDiagnostic(
                    ModuleDiagnosticCode.ROLLBACK_FAILED,
                    ModuleDiagnosticSeverity.ERROR,
                    "Rollback target $targetVersion must be older than ${record.declaration.descriptor.version}",
                    moduleId,
                ),
            )
        }
        val hook = record.declaration.rollbackHook ?: return SupervisorCommandResult.Rejected(
            ModuleDiagnostic(
                ModuleDiagnosticCode.ROLLBACK_UNAVAILABLE,
                ModuleDiagnosticSeverity.ERROR,
                "Module $moduleId does not declare a rollback hook",
                moduleId,
            ),
        )
        val result = try {
            hook.rollback(record.declaration.descriptor.version, targetVersion)
        } catch (error: Exception) {
            ModuleOperationResult.Failure("rollback threw ${safeThrowableName(error)}")
        }
        return when (result) {
            ModuleOperationResult.Success -> {
                record.state = ModuleState.INSTALLED
                replaceDiagnostic(
                    record,
                    ModuleDiagnostic(
                        ModuleDiagnosticCode.ROLLBACK_COMPLETED,
                        ModuleDiagnosticSeverity.INFO,
                        "Module $moduleId rollback to $targetVersion completed",
                        moduleId,
                    ),
                )
                SupervisorCommandResult.Applied(status(record))
            }
            is ModuleOperationResult.Failure -> {
                val diagnostic = ModuleDiagnostic(
                    ModuleDiagnosticCode.ROLLBACK_FAILED,
                    ModuleDiagnosticSeverity.ERROR,
                    "Module $moduleId rollback failed: ${result.reason}",
                    moduleId,
                )
                replaceDiagnostic(record, diagnostic)
                SupervisorCommandResult.Rejected(diagnostic)
            }
        }
    }

    private fun startRejection(
        moduleId: ModuleId,
        record: MutableRecord,
        nowEpochMillis: Long,
    ): ModuleDiagnostic? {
        if (!record.enabled || record.state == ModuleState.DISABLED) {
            return commandDiagnostic(moduleId, "Module $moduleId is disabled")
        }
        if (record.state == ModuleState.QUARANTINED) {
            return commandDiagnostic(moduleId, "Module $moduleId is quarantined: ${record.quarantineReason}")
        }
        if (record.state == ModuleState.UPDATE_PENDING) {
            return commandDiagnostic(moduleId, "Module $moduleId has an update or migration pending")
        }
        if (hasStaticErrors(record)) return firstStaticError(record)
        val retryAt = record.nextRestartAtEpochMillis
        if (retryAt != null && nowEpochMillis < retryAt) {
            return ModuleDiagnostic(
                ModuleDiagnosticCode.RESTART_SCHEDULED,
                ModuleDiagnosticSeverity.WARNING,
                "Module $moduleId may retry at $retryAt",
                moduleId,
            )
        }
        val unavailable = unavailableRequiredDependencies(record)
        if (unavailable.isNotEmpty()) {
            return ModuleDiagnostic(
                ModuleDiagnosticCode.DEPENDENCY_UNAVAILABLE,
                ModuleDiagnosticSeverity.ERROR,
                "Module $moduleId requires ready modules: ${unavailable.joinToString()}",
                moduleId,
                unavailable,
            )
        }
        return null
    }

    private fun startInternal(moduleId: ModuleId, record: MutableRecord, nowEpochMillis: Long) {
        val rejection = startRejection(moduleId, record, nowEpochMillis)
        if (rejection != null) {
            replaceDiagnostic(record, rejection)
            if (rejection.code == ModuleDiagnosticCode.DEPENDENCY_UNAVAILABLE) record.state = ModuleState.FAILED
            return
        }
        record.state = ModuleState.STARTING
        record.nextRestartAtEpochMillis = null
        when (val start = safeStart(record)) {
            ModuleOperationResult.Success -> applyHealth(moduleId, record, safeHealth(record), nowEpochMillis)
            is ModuleOperationResult.Failure -> recordFailure(
                moduleId,
                record,
                nowEpochMillis,
                ModuleDiagnosticCode.START_FAILED,
                "Module $moduleId failed to start: ${start.reason}",
            )
        }
    }

    private fun applyHealth(
        moduleId: ModuleId,
        record: MutableRecord,
        health: ModuleHealthReport,
        nowEpochMillis: Long,
    ) {
        when (health.state) {
            ModuleHealthState.HEALTHY -> {
                record.state = ModuleState.READY
                record.failedStartAttempts = 0
                record.nextRestartAtEpochMillis = null
                record.quarantineReason = null
                removeDiagnostics(record, FAILURE_DIAGNOSTICS)
            }
            ModuleHealthState.DEGRADED -> {
                record.state = ModuleState.DEGRADED
                record.failedStartAttempts = 0
                record.nextRestartAtEpochMillis = null
                record.quarantineReason = null
                removeDiagnostics(record, FAILURE_DIAGNOSTICS)
                replaceDiagnostic(
                    record,
                    ModuleDiagnostic(
                        ModuleDiagnosticCode.HEALTH_CHECK_FAILED,
                        ModuleDiagnosticSeverity.WARNING,
                        "Module $moduleId is degraded: ${health.reason}",
                        moduleId,
                    ),
                )
            }
            ModuleHealthState.FAILED -> recordFailure(
                moduleId,
                record,
                nowEpochMillis,
                ModuleDiagnosticCode.HEALTH_CHECK_FAILED,
                "Module $moduleId health failed: ${health.reason}",
            )
        }
    }

    private fun recordFailure(
        moduleId: ModuleId,
        record: MutableRecord,
        nowEpochMillis: Long,
        code: ModuleDiagnosticCode,
        reason: String,
    ) {
        record.failedStartAttempts += 1
        replaceDiagnostic(record, ModuleDiagnostic(code, ModuleDiagnosticSeverity.ERROR, reason, moduleId))
        if (record.failedStartAttempts >= restartPolicy.maxStartAttempts) {
            record.state = ModuleState.QUARANTINED
            record.nextRestartAtEpochMillis = null
            val quarantineReason =
                "Restart budget exhausted after ${record.failedStartAttempts} failed start/health attempts"
            record.quarantineReason = quarantineReason
            replaceDiagnostic(
                record,
                ModuleDiagnostic(
                    ModuleDiagnosticCode.RESTART_EXHAUSTED,
                    ModuleDiagnosticSeverity.ERROR,
                    quarantineReason,
                    moduleId,
                ),
            )
            replaceDiagnostic(
                record,
                ModuleDiagnostic(
                    ModuleDiagnosticCode.QUARANTINE_REASON,
                    ModuleDiagnosticSeverity.ERROR,
                    "Module $moduleId was quarantined to isolate its failure",
                    moduleId,
                ),
            )
        } else {
            record.state = ModuleState.FAILED
            record.nextRestartAtEpochMillis = safeTimestampAdd(
                nowEpochMillis,
                restartPolicy.delayAfterFailure(record.failedStartAttempts),
            )
            replaceDiagnostic(
                record,
                ModuleDiagnostic(
                    ModuleDiagnosticCode.RESTART_SCHEDULED,
                    ModuleDiagnosticSeverity.WARNING,
                    "Module $moduleId retry scheduled at ${record.nextRestartAtEpochMillis}",
                    moduleId,
                ),
            )
        }
    }

    private fun unavailableRequiredDependencies(record: MutableRecord): List<ModuleId> =
        record.declaration.descriptor.dependencies
            .map { it.moduleId }
            .filter { dependency -> records[dependency]?.state !in SERVING_STATES }
            .sorted()

    private fun activeRequiredDependents(moduleId: ModuleId): List<ModuleId> = records
        .filter { (_, record) ->
            record.state in ACTIVE_STATES && record.declaration.descriptor.dependencies.any { it.moduleId == moduleId }
        }
        .keys
        .sorted()

    private fun canRetry(record: MutableRecord, nowEpochMillis: Long): Boolean =
        record.state == ModuleState.FAILED &&
            record.nextRestartAtEpochMillis?.let { nowEpochMillis >= it } == true

    private fun safeStart(record: MutableRecord): ModuleOperationResult = try {
        record.declaration.runtime.start()
    } catch (error: Exception) {
        ModuleOperationResult.Failure("start hook threw ${safeThrowableName(error)}")
    }

    private fun safeStop(record: MutableRecord): ModuleOperationResult = try {
        record.declaration.runtime.stop()
    } catch (error: Exception) {
        ModuleOperationResult.Failure("stop hook threw ${safeThrowableName(error)}")
    }

    private fun safeHealth(record: MutableRecord): ModuleHealthReport = try {
        record.declaration.runtime.health()
    } catch (error: Exception) {
        ModuleHealthReport.failed("health hook threw ${safeThrowableName(error)}")
    }

    private fun status(record: MutableRecord): ModuleStatus = ModuleStatus(
        descriptor = record.declaration.descriptor,
        origin = record.declaration.origin,
        importance = record.declaration.importance,
        state = record.state,
        enabled = record.enabled,
        lifecycleState = record.state.toServiceLifecycleState(),
        failedStartAttempts = record.failedStartAttempts,
        nextRestartAtEpochMillis = record.nextRestartAtEpochMillis,
        quarantineReason = record.quarantineReason,
        diagnostics = sortDiagnostics(record.diagnostics),
    )

    private fun hasStaticErrors(record: MutableRecord): Boolean = record.diagnostics.any {
        it.severity == ModuleDiagnosticSeverity.ERROR && it.code in STATIC_ERROR_CODES
    }

    private fun firstStaticError(record: MutableRecord): ModuleDiagnostic =
        sortDiagnostics(record.diagnostics.filter { it.severity == ModuleDiagnosticSeverity.ERROR && it.code in STATIC_ERROR_CODES })
            .first()

    private fun replaceDiagnostic(record: MutableRecord, diagnostic: ModuleDiagnostic) {
        record.diagnostics.removeAll { it.code == diagnostic.code }
        record.diagnostics += diagnostic
    }

    private fun removeDiagnostics(record: MutableRecord, codes: Set<ModuleDiagnosticCode>) {
        record.diagnostics.removeAll { it.code in codes }
    }

    private fun commandDiagnostic(moduleId: ModuleId, message: String) = ModuleDiagnostic(
        ModuleDiagnosticCode.DEPENDENCY_UNAVAILABLE,
        ModuleDiagnosticSeverity.ERROR,
        message,
        moduleId,
    )

    private fun requireTimestamp(nowEpochMillis: Long) {
        require(nowEpochMillis >= 0) { "Timestamp must be non-negative" }
    }

    companion object {
        private val ACTIVE_STATES = setOf(ModuleState.STARTING, ModuleState.READY, ModuleState.DEGRADED)
        private val SERVING_STATES = setOf(ModuleState.READY, ModuleState.DEGRADED)
        private val STATIC_ERROR_CODES = setOf(
            ModuleDiagnosticCode.INCOMPATIBLE_CYCLONE_API,
            ModuleDiagnosticCode.MISSING_DEPENDENCY,
            ModuleDiagnosticCode.INCOMPATIBLE_DEPENDENCY,
            ModuleDiagnosticCode.DEPENDENCY_CYCLE,
            ModuleDiagnosticCode.DUPLICATE_PROVIDER,
            ModuleDiagnosticCode.MIGRATION_BLOCKED,
        )
        private val FAILURE_DIAGNOSTICS = setOf(
            ModuleDiagnosticCode.START_FAILED,
            ModuleDiagnosticCode.HEALTH_CHECK_FAILED,
            ModuleDiagnosticCode.RESTART_SCHEDULED,
            ModuleDiagnosticCode.RESTART_EXHAUSTED,
            ModuleDiagnosticCode.QUARANTINE_REASON,
            ModuleDiagnosticCode.DEPENDENCY_UNAVAILABLE,
        )

        private fun safeThrowableName(error: Exception): String = error::class.simpleName ?: "exception"

        private fun safeTimestampAdd(timestamp: Long, delay: Long): Long =
            if (timestamp > Long.MAX_VALUE - delay) Long.MAX_VALUE else timestamp + delay

        private fun safeMigrationPlan(declaration: TrustedModuleDeclaration): ModuleMigrationPlan = try {
            declaration.migrationPlanner.plan(declaration.descriptor)
        } catch (error: Exception) {
            ModuleMigrationPlan(
                MigrationDisposition.BLOCKED,
                declaration.descriptor.migrationVersion,
                reason = "Migration planner threw ${safeThrowableName(error)}",
            )
        }

        /**
         * Performs one deterministic discovery pass. Duplicate module IDs are all rejected instead
         * of selecting a winner based on iteration order.
         */
        fun fromDeclared(
            cycloneApiVersion: CycloneApiVersion,
            declarations: Iterable<TrustedModuleDeclaration>,
            restartPolicy: RestartPolicy = RestartPolicy(),
        ): ModuleSupervisor = ModuleSupervisor(
            cycloneApiVersion = cycloneApiVersion,
            restartPolicy = restartPolicy,
            analysis = ModuleDependencyGraph.analyze(declarations, cycloneApiVersion),
        )
    }
}

private fun ModuleState.toServiceLifecycleState(): ServiceLifecycleState = when (this) {
    ModuleState.INSTALLED -> ServiceLifecycleState.REGISTERED
    ModuleState.DISABLED -> ServiceLifecycleState.STOPPED
    ModuleState.STARTING -> ServiceLifecycleState.STARTING
    ModuleState.READY -> ServiceLifecycleState.READY
    ModuleState.DEGRADED -> ServiceLifecycleState.DEGRADED
    ModuleState.QUARANTINED -> ServiceLifecycleState.FAILED
    ModuleState.UPDATE_PENDING -> ServiceLifecycleState.STOPPED
    ModuleState.FAILED -> ServiceLifecycleState.FAILED
}
