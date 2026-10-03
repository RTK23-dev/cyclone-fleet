package com.cyclone.mobile.platform.catalog

import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.module.ModuleVersion
import com.cyclone.mobile.platform.modules.ModuleSupervisor
import com.cyclone.mobile.platform.modules.ModuleUpdateCandidate
import com.cyclone.mobile.platform.modules.SupervisorCommandResult

/**
 * Catalog command boundary. It owns no module state and delegates every supported mutation to the
 * public ModuleSupervisor API before rebuilding view state from a fresh supervisor snapshot.
 */
class ModuleCatalogController(
    private val supervisor: ModuleSupervisor,
    private val metadataSource: ModuleCatalogMetadataSource,
    private val presenter: ModuleCatalogPresenter = ModuleCatalogPresenter(),
    private val nowEpochMillis: () -> Long,
) {
    fun state(): ModuleCatalogViewState = presenter.present(
        supervisor.snapshot(),
        safeMetadata(),
        nowEpochMillis(),
    )

    fun setEnabled(moduleId: ModuleId, enabled: Boolean): ModuleCatalogCommandResult =
        delegated(if (enabled) supervisor.enable(moduleId) else supervisor.disable(moduleId))

    fun clearQuarantine(moduleId: ModuleId): ModuleCatalogCommandResult =
        delegated(supervisor.clearQuarantine(moduleId))

    fun prepareUpdate(
        moduleId: ModuleId,
        candidate: ModuleUpdateCandidate,
    ): ModuleCatalogCommandResult = delegated(supervisor.prepareUpdate(moduleId, candidate))

    fun rollback(
        moduleId: ModuleId,
        targetVersion: ModuleVersion,
    ): ModuleCatalogCommandResult = delegated(supervisor.rollback(moduleId, targetVersion))

    private fun delegated(result: SupervisorCommandResult): ModuleCatalogCommandResult = when (result) {
        is SupervisorCommandResult.Applied -> ModuleCatalogCommandResult.Applied(state())
        is SupervisorCommandResult.Missing -> ModuleCatalogCommandResult.Missing(result.moduleId)
        is SupervisorCommandResult.Rejected -> ModuleCatalogCommandResult.Rejected(
            title = humanize(result.diagnostic.code.name),
            explanation = "Cyclone's module supervisor rejected this request. The module state was not changed.",
        )
    }

    private fun safeMetadata(): List<ModulePresentationMetadata> = try {
        metadataSource.load()
    } catch (_: Exception) {
        // The catalog remains usable from the authoritative local supervisor inventory.
        emptyList()
    }
}
