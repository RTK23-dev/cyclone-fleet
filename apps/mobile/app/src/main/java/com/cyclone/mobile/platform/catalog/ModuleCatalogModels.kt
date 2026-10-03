package com.cyclone.mobile.platform.catalog

import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.modules.ModuleDiagnosticSeverity
import com.cyclone.mobile.platform.modules.ModuleImportance
import com.cyclone.mobile.platform.modules.ModuleState

enum class CatalogReleaseChannel {
    STABLE,
    BETA,
    EXPERIMENTAL,
}

data class ModulePresentationMetadata(
    val moduleId: ModuleId,
    val displayName: String,
    val description: String,
    val providerName: String = "Cyclone",
    val releaseChannel: CatalogReleaseChannel = CatalogReleaseChannel.STABLE,
) {
    init {
        require(displayName.isNotBlank()) { "Module display name must not be blank" }
        require(description.isNotBlank()) { "Module description must not be blank" }
        require(providerName.isNotBlank()) { "Module provider must not be blank" }
    }
}

/** Local presentation metadata only. It performs no discovery, download, or lifecycle mutation. */
fun interface ModuleCatalogMetadataSource {
    fun load(): List<ModulePresentationMetadata>
}

class BundledModuleCatalogMetadataSource(
    metadata: Iterable<ModulePresentationMetadata>,
) : ModuleCatalogMetadataSource {
    private val metadata: List<ModulePresentationMetadata>

    init {
        val entries = metadata.toList()
        require(entries.map { it.moduleId }.distinct().size == entries.size) {
            "Bundled module metadata ids must be unique"
        }
        this.metadata = entries.sortedBy { it.moduleId }
    }

    override fun load(): List<ModulePresentationMetadata> = metadata
}

enum class CatalogHealthTone {
    HEALTHY,
    ATTENTION,
    UNAVAILABLE,
    INACTIVE,
}

enum class CatalogUpdateState {
    CURRENT,
    UPDATE_PENDING,
    MIGRATION_REQUIRED,
}

data class CatalogCapabilityView(
    val id: String,
    val label: String,
)

data class CatalogPermissionView(
    val id: String,
    val label: String,
    val required: Boolean,
    val rationale: String?,
)

data class CatalogDiagnosticView(
    val severity: ModuleDiagnosticSeverity,
    val title: String,
    val explanation: String,
    val suggestedAction: String? = null,
) {
    init {
        require(title.isNotBlank())
        require(explanation.isNotBlank())
        require(suggestedAction == null || suggestedAction.isNotBlank())
    }
}

data class CatalogManagementView(
    val canToggle: Boolean,
    val toggleExplanation: String?,
    val canClearQuarantine: Boolean,
    val updateMetadataOnly: Boolean = true,
)

data class CatalogModuleView(
    val moduleId: ModuleId,
    val displayName: String,
    val description: String,
    val providerName: String,
    val version: String,
    val isBuiltIn: Boolean,
    val isCritical: Boolean,
    val releaseChannel: CatalogReleaseChannel,
    val state: ModuleState,
    val stateLabel: String,
    val healthTone: CatalogHealthTone,
    val enabled: Boolean,
    val capabilities: List<CatalogCapabilityView>,
    val permissions: List<CatalogPermissionView>,
    val compatibilityLabel: String,
    val restartRequirementLabel: String,
    val updateState: CatalogUpdateState,
    val diagnostics: List<CatalogDiagnosticView>,
    val restartMessage: String?,
    val management: CatalogManagementView,
)

data class ModuleCatalogViewState(
    val title: String = "Cyclone Modules",
    val subtitle: String = "Built-in capabilities and their current health",
    val localOnlyMessage: String = "Showing modules included with this Cyclone build. No network is required.",
    val modules: List<CatalogModuleView>,
    val issueCount: Int,
    val updateCount: Int,
    val emptyMessage: String = "No Cyclone modules are declared in this build.",
) {
    val isEmpty: Boolean get() = modules.isEmpty()
}

sealed interface ModuleCatalogCommandResult {
    data class Applied(val state: ModuleCatalogViewState) : ModuleCatalogCommandResult
    data class Rejected(val title: String, val explanation: String) : ModuleCatalogCommandResult
    data class Missing(val moduleId: ModuleId) : ModuleCatalogCommandResult
}

/**
 * Future remote catalogs may contribute verified descriptive metadata only. This type never carries
 * an APK, Dex file, script, native library, URL to executable code, or installation callback.
 */
data class SignedCatalogMetadata(
    val moduleId: ModuleId,
    val displayName: String,
    val description: String,
    val providerName: String,
    val version: String,
    val descriptorDigestSha256: String,
    val signingKeyId: String,
    val signatureBase64: String,
    val verified: Boolean,
) {
    init {
        require(displayName.isNotBlank() && description.isNotBlank() && providerName.isNotBlank())
        require(version.isNotBlank() && signingKeyId.isNotBlank() && signatureBase64.isNotBlank())
        require(SHA_256.matches(descriptorDigestSha256)) { "Descriptor digest must be lowercase SHA-256" }
    }

    private companion object {
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}

internal fun ModuleImportance.isCritical(): Boolean = this == ModuleImportance.CRITICAL_BUILT_IN
