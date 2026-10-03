package com.cyclone.mobile.platform.catalog

import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.module.RestartRequirement
import com.cyclone.mobile.platform.modules.ModuleDiagnostic
import com.cyclone.mobile.platform.modules.ModuleDiagnosticCode
import com.cyclone.mobile.platform.modules.ModuleDiagnosticSeverity
import com.cyclone.mobile.platform.modules.ModuleState
import com.cyclone.mobile.platform.modules.ModuleStatus
import com.cyclone.mobile.platform.modules.ModuleSupervisorSnapshot
import com.cyclone.mobile.platform.modules.TrustedModuleOrigin

class ModuleCatalogPresenter {
    fun present(
        snapshot: ModuleSupervisorSnapshot,
        metadata: Iterable<ModulePresentationMetadata>,
        nowEpochMillis: Long,
    ): ModuleCatalogViewState {
        require(nowEpochMillis >= 0) { "Timestamp must be non-negative" }
        val metadataById = metadata
            .groupBy { it.moduleId }
            .mapValues { (_, candidates) ->
                candidates.sortedWith(
                    compareBy<ModulePresentationMetadata>(
                        { it.displayName.lowercase() },
                        { it.providerName.lowercase() },
                        { it.releaseChannel.name },
                        { it.description },
                    ),
                ).first()
            }
        val modules = snapshot.modules
            .map { status -> moduleView(status, metadataById[status.descriptor.id], nowEpochMillis) }
            .sortedWith(
                compareBy<CatalogModuleView>(
                    { !it.isBuiltIn },
                    { it.displayName.lowercase() },
                    { it.moduleId.value },
                ),
            )
        return ModuleCatalogViewState(
            modules = modules,
            issueCount = modules.count { module ->
                module.healthTone == CatalogHealthTone.ATTENTION ||
                    module.healthTone == CatalogHealthTone.UNAVAILABLE
            },
            updateCount = modules.count { it.updateState != CatalogUpdateState.CURRENT },
        )
    }

    private fun moduleView(
        status: ModuleStatus,
        metadata: ModulePresentationMetadata?,
        nowEpochMillis: Long,
    ): CatalogModuleView {
        val descriptor = status.descriptor
        val displayName = metadata?.displayName ?: humanize(descriptor.id.value)
        val requiredDependencyNames = descriptor.dependencies.associate { dependency ->
            dependency.moduleId to humanize(dependency.moduleId.value)
        }
        val restartMessage = status.nextRestartAtEpochMillis?.let { retryAt ->
            if (retryAt <= nowEpochMillis) {
                "This module is ready for a supervised retry."
            } else {
                val seconds = ((retryAt - nowEpochMillis) + 999) / 1_000
                "Cyclone can retry this module in $seconds second${if (seconds == 1L) "" else "s"}."
            }
        }
        return CatalogModuleView(
            moduleId = descriptor.id,
            displayName = displayName,
            description = metadata?.description ?: "A declared Cyclone capability module.",
            providerName = metadata?.providerName ?: providerLabel(status.origin),
            version = descriptor.version.toString(),
            isBuiltIn = status.origin == TrustedModuleOrigin.COMPILED_IN,
            isCritical = status.importance.isCritical(),
            releaseChannel = metadata?.releaseChannel ?: CatalogReleaseChannel.STABLE,
            state = status.state,
            stateLabel = stateLabel(status.state),
            healthTone = healthTone(status.state),
            enabled = status.enabled,
            capabilities = descriptor.provides
                .sorted()
                .map { CatalogCapabilityView(it.value, humanize(it.value)) },
            permissions = descriptor.permissions
                .sortedBy { it.id }
                .map { permission ->
                    CatalogPermissionView(
                        id = permission.id,
                        label = humanize(permission.id),
                        required = permission.required,
                        rationale = permission.rationale,
                    )
                },
            compatibilityLabel = buildString {
                append("Cyclone API ")
                append(descriptor.compatibleCycloneApi.minimumInclusive)
                append("–")
                append(descriptor.compatibleCycloneApi.maximumExclusive)
                append(" (upper version excluded)")
            },
            restartRequirementLabel = restartRequirementLabel(descriptor.restartRequirement),
            updateState = updateState(status),
            diagnostics = status.diagnostics.map { diagnostic ->
                readableDiagnostic(diagnostic, displayName, requiredDependencyNames)
            },
            restartMessage = restartMessage,
            management = CatalogManagementView(
                canToggle = !status.importance.isCritical() && status.state != ModuleState.UPDATE_PENDING,
                toggleExplanation = when {
                    status.importance.isCritical() -> "Required for Cyclone's trusted core and cannot be disabled."
                    status.state == ModuleState.UPDATE_PENDING -> "Finish or cancel the pending update before changing this module."
                    else -> null
                },
                canClearQuarantine = status.state == ModuleState.QUARANTINED,
            ),
        )
    }

    private fun readableDiagnostic(
        diagnostic: ModuleDiagnostic,
        moduleName: String,
        dependencyNames: Map<ModuleId, String>,
    ): CatalogDiagnosticView {
        val related = diagnostic.relatedModuleIds.map { dependencyNames[it] ?: humanize(it.value) }
        val relatedText = related.joinToString()
        val (title, explanation, action) = when (diagnostic.code) {
            ModuleDiagnosticCode.DUPLICATE_MODULE -> Triple(
                "Duplicate module declaration",
                "Cyclone found more than one declaration for $moduleName and loaded neither one.",
                "Keep one trusted declaration, then restart Cyclone.",
            )
            ModuleDiagnosticCode.INCOMPATIBLE_CYCLONE_API -> Triple(
                "Incompatible Cyclone version",
                "$moduleName does not support this Cyclone runtime.",
                "Use a compatible module build.",
            )
            ModuleDiagnosticCode.MISSING_DEPENDENCY -> Triple(
                "Required module is missing",
                "$moduleName needs ${relatedText.ifBlank { "another Cyclone module" }}.",
                "Restore the required built-in module.",
            )
            ModuleDiagnosticCode.INCOMPATIBLE_DEPENDENCY -> Triple(
                "Module versions do not match",
                "$moduleName cannot use the installed version of ${relatedText.ifBlank { "a required module" }}.",
                "Update the modules as one compatible set.",
            )
            ModuleDiagnosticCode.DEPENDENCY_UNAVAILABLE -> Triple(
                "Required module is unavailable",
                "$moduleName is waiting for ${relatedText.ifBlank { "a required module" }} to become healthy.",
                "Check the required module's diagnostics first.",
            )
            ModuleDiagnosticCode.DEPENDENCY_CYCLE -> Triple(
                "Modules depend on each other",
                "Cyclone stopped these modules safely because their startup order cannot be resolved.",
                "Correct the declared dependencies.",
            )
            ModuleDiagnosticCode.DUPLICATE_PROVIDER -> Triple(
                "Capability conflict",
                "More than one module claims the same Cyclone capability, so none was selected.",
                "Enable only one trusted provider for that capability.",
            )
            ModuleDiagnosticCode.MIGRATION_REQUIRED -> Triple(
                "Data update required",
                "$moduleName needs a supervised data migration before it can run.",
                "Review and apply the pending runtime update.",
            )
            ModuleDiagnosticCode.MIGRATION_BLOCKED -> Triple(
                "Data update blocked",
                "Cyclone could not create a safe migration plan for $moduleName.",
                "Keep the current data and review diagnostics.",
            )
            ModuleDiagnosticCode.START_FAILED -> Triple(
                "Module did not start",
                "$moduleName failed without stopping unrelated Cyclone modules.",
                "Wait for the supervised retry or review its requirements.",
            )
            ModuleDiagnosticCode.STOP_FAILED -> Triple(
                "Module did not stop cleanly",
                "Cyclone kept the failure isolated and recorded it for recovery.",
                "Restart Cyclone if the module remains active.",
            )
            ModuleDiagnosticCode.HEALTH_CHECK_FAILED -> Triple(
                "Module needs attention",
                "$moduleName reported degraded or failed health.",
                "Check its required capabilities and try again.",
            )
            ModuleDiagnosticCode.RESTART_SCHEDULED -> Triple(
                "Retry scheduled",
                "Cyclone will retry this module through the trusted supervisor.",
                null,
            )
            ModuleDiagnosticCode.RESTART_EXHAUSTED -> Triple(
                "Automatic retries exhausted",
                "$moduleName reached its bounded retry limit.",
                "Review the cause before clearing quarantine.",
            )
            ModuleDiagnosticCode.QUARANTINE_REASON,
            ModuleDiagnosticCode.RECOVERY_QUARANTINE,
            -> Triple(
                "Module quarantined",
                "Cyclone isolated $moduleName so other modules can continue safely.",
                "Resolve the problem before clearing quarantine.",
            )
            ModuleDiagnosticCode.CRITICAL_MODULE -> Triple(
                "Required Cyclone module",
                "$moduleName is part of Cyclone's trusted core and cannot be disabled.",
                null,
            )
            ModuleDiagnosticCode.ACTIVE_DEPENDENTS -> Triple(
                "Module is still required",
                "Another active Cyclone module depends on $moduleName.",
                "Disable dependent modules first.",
            )
            ModuleDiagnosticCode.UPDATE_REJECTED -> Triple(
                "Update not accepted",
                "The candidate did not pass Cyclone's compatibility checks.",
                "Keep the current known-good module.",
            )
            ModuleDiagnosticCode.UPDATE_PENDING -> Triple(
                "Update pending",
                "$moduleName passed metadata checks and is waiting for the trusted update path.",
                "Restart only when Cyclone requests it.",
            )
            ModuleDiagnosticCode.ROLLBACK_UNAVAILABLE -> Triple(
                "Rollback unavailable",
                "$moduleName does not provide a trusted rollback path.",
                "Keep the current version and review recovery options.",
            )
            ModuleDiagnosticCode.ROLLBACK_FAILED -> Triple(
                "Rollback failed",
                "Cyclone could not restore the requested version of $moduleName.",
                "Keep the current state and review recovery diagnostics.",
            )
            ModuleDiagnosticCode.ROLLBACK_COMPLETED -> Triple(
                "Rollback completed",
                "$moduleName returned to the requested known version.",
                null,
            )
        }
        return CatalogDiagnosticView(diagnostic.severity, title, explanation, action)
    }

    private fun stateLabel(state: ModuleState): String = when (state) {
        ModuleState.INSTALLED -> "Ready to start"
        ModuleState.DISABLED -> "Disabled"
        ModuleState.STARTING -> "Starting"
        ModuleState.READY -> "Healthy"
        ModuleState.DEGRADED -> "Needs attention"
        ModuleState.QUARANTINED -> "Safely quarantined"
        ModuleState.UPDATE_PENDING -> "Update pending"
        ModuleState.FAILED -> "Unavailable"
    }

    private fun healthTone(state: ModuleState): CatalogHealthTone = when (state) {
        ModuleState.READY -> CatalogHealthTone.HEALTHY
        ModuleState.STARTING, ModuleState.DEGRADED, ModuleState.UPDATE_PENDING -> CatalogHealthTone.ATTENTION
        ModuleState.FAILED, ModuleState.QUARANTINED -> CatalogHealthTone.UNAVAILABLE
        ModuleState.INSTALLED, ModuleState.DISABLED -> CatalogHealthTone.INACTIVE
    }

    private fun updateState(status: ModuleStatus): CatalogUpdateState = when {
        status.diagnostics.any { it.code == ModuleDiagnosticCode.MIGRATION_REQUIRED } ->
            CatalogUpdateState.MIGRATION_REQUIRED
        status.state == ModuleState.UPDATE_PENDING ||
            status.diagnostics.any { it.code == ModuleDiagnosticCode.UPDATE_PENDING } ->
            CatalogUpdateState.UPDATE_PENDING
        else -> CatalogUpdateState.CURRENT
    }

    private fun restartRequirementLabel(requirement: RestartRequirement): String = when (requirement) {
        RestartRequirement.NONE -> "No restart required"
        RestartRequirement.MODULE -> "Module restart required"
        RestartRequirement.APPLICATION -> "Cyclone restart required"
        RestartRequirement.DEVICE -> "Phone restart required"
    }

    private fun providerLabel(origin: TrustedModuleOrigin): String = when (origin) {
        TrustedModuleOrigin.COMPILED_IN -> "Cyclone"
        TrustedModuleOrigin.DECLARED_TRUSTED -> "Approved Cyclone provider"
    }
}

internal fun humanize(identifier: String): String = identifier
    .split('.', '-', '_')
    .filter { it.isNotBlank() }
    .joinToString(" ") { part -> part.replaceFirstChar { character -> character.uppercase() } }
