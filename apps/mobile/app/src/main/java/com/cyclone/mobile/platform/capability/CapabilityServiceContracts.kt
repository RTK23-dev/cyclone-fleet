package com.cyclone.mobile.platform.capability

import com.cyclone.mobile.platform.module.CycloneApiCompatibility
import com.cyclone.mobile.platform.module.ModuleVersion

data class CapabilityVersionRequirement(
    val capabilityId: CapabilityId,
    val minimumInclusive: CapabilityVersion? = null,
    val maximumExclusive: CapabilityVersion? = null,
) : Comparable<CapabilityVersionRequirement> {
    init {
        require(
            minimumInclusive == null || maximumExclusive == null || minimumInclusive < maximumExclusive,
        ) { "Capability dependency version range must not be empty" }
    }

    fun accepts(version: CapabilityVersion): Boolean =
        (minimumInclusive == null || version >= minimumInclusive) &&
            (maximumExclusive == null || version < maximumExclusive)

    override fun compareTo(other: CapabilityVersionRequirement): Int = capabilityId.compareTo(other.capabilityId)

    fun displayRange(): String = when {
        minimumInclusive != null && maximumExclusive != null -> "[$minimumInclusive, $maximumExclusive)"
        minimumInclusive != null -> ">=$minimumInclusive"
        maximumExclusive != null -> "<$maximumExclusive"
        else -> "any"
    }
}

data class CapabilityDependencies(
    val required: List<CapabilityVersionRequirement> = emptyList(),
    val optional: List<CapabilityVersionRequirement> = emptyList(),
) {
    init {
        val requiredIds = required.map { it.capabilityId }
        val optionalIds = optional.map { it.capabilityId }
        require(requiredIds.distinct().size == requiredIds.size) {
            "Required capability dependencies must be unique"
        }
        require(optionalIds.distinct().size == optionalIds.size) {
            "Optional capability dependencies must be unique"
        }
        require(requiredIds.intersect(optionalIds.toSet()).isEmpty()) {
            "A capability dependency cannot be both required and optional"
        }
    }
}

enum class CapabilityProviderState {
    ENABLED,
    DISABLED,
    QUARANTINED,
}

data class CapabilityProviderStatus(
    val state: CapabilityProviderState,
    val reason: String? = null,
) {
    init {
        require(reason == null || reason.isNotBlank()) { "Provider state reason must not be blank" }
    }

    val canServe: Boolean
        get() = state == CapabilityProviderState.ENABLED

    companion object {
        fun enabled() = CapabilityProviderStatus(CapabilityProviderState.ENABLED)
    }
}

/**
 * Rich metadata around a compiled provider. The status callback is read-only: Module Supervisor
 * may eventually supply it, while this registry remains unable to enable, disable or quarantine
 * modules itself.
 */
data class CapabilityServiceDeclaration<T : Any>(
    val provider: CapabilityProvider<T>,
    val moduleVersion: ModuleVersion,
    val compatibleCycloneApi: CycloneApiCompatibility,
    val dependencies: CapabilityDependencies = CapabilityDependencies(),
    val policyCategory: CapabilityPolicyCategory =
        CycloneCapabilityFamilies.describe(provider.descriptor.key.id)?.policyCategory
            ?: CyclonePolicyCategories.ROUTINE,
    val status: () -> CapabilityProviderStatus = { CapabilityProviderStatus.enabled() },
)

/** Adapter for existing compiled Cyclone services; it publishes metadata and never invokes them. */
class CompiledCapabilityAdapter<T : Any>(
    override val moduleId: com.cyclone.mobile.platform.module.ModuleId,
    override val descriptor: CapabilityDescriptor<T>,
    override val implementation: T,
    private val healthProbe: () -> CapabilityHealth = { CapabilityHealth.healthy() },
) : CapabilityProvider<T> {
    override fun health(): CapabilityHealth = healthProbe()
}

enum class CapabilityDiagnosticSeverity {
    BLOCKING,
    WARNING,
}

enum class CapabilityDiagnosticCode {
    CAPABILITY_MISSING,
    CAPABILITY_CONFLICT,
    CAPABILITY_UNHEALTHY,
    CONTRACT_MISMATCH,
    CYCLONE_API_INCOMPATIBLE,
    PROVIDER_DISABLED,
    PROVIDER_QUARANTINED,
    PROVIDER_STATUS_FAILED,
    REQUIRED_DEPENDENCY_MISSING,
    OPTIONAL_DEPENDENCY_MISSING,
    DEPENDENCY_CONFLICT,
    DEPENDENCY_UNHEALTHY,
    DEPENDENCY_VERSION_INCOMPATIBLE,
    DEPENDENCY_CYCLE,
}

data class CapabilityDiagnostic(
    val severity: CapabilityDiagnosticSeverity,
    val code: CapabilityDiagnosticCode,
    val capabilityId: CapabilityId,
    val relatedCapabilityId: CapabilityId? = null,
    val message: String,
) : Comparable<CapabilityDiagnostic> {
    init {
        require(message.isNotBlank()) { "Capability diagnostic message must not be blank" }
    }

    override fun compareTo(other: CapabilityDiagnostic): Int = compareValuesBy(
        this,
        other,
        { it.severity },
        { it.code },
        { it.capabilityId },
        { it.relatedCapabilityId },
        { it.message },
    )
}

data class CapabilityDependencyCycle(val members: List<CapabilityId>) : Comparable<CapabilityDependencyCycle> {
    init {
        require(members.isNotEmpty()) { "Dependency cycle members must not be empty" }
        require(members == members.distinct().sorted()) { "Dependency cycle members must be unique and sorted" }
    }

    override fun compareTo(other: CapabilityDependencyCycle): Int =
        members.joinToString("\u0000") { it.value }.compareTo(other.members.joinToString("\u0000") { it.value })
}

sealed interface CapabilityServiceLookup<out T : Any> {
    data class Available<T : Any>(
        val provider: CapabilityProviderIdentity,
        val implementation: T,
        val health: CapabilityHealth,
        val warnings: List<CapabilityDiagnostic>,
    ) : CapabilityServiceLookup<T>

    data class Unavailable(
        val capabilityId: CapabilityId,
        val diagnostics: List<CapabilityDiagnostic>,
    ) : CapabilityServiceLookup<Nothing>
}

enum class CapabilityInventoryStatus {
    READY,
    DEGRADED,
    DISABLED,
    QUARANTINED,
    UNHEALTHY,
    INCOMPATIBLE,
    CONFLICT,
    STATUS_FAILED,
}

data class CapabilityInventoryEntry(
    val provider: CapabilityProviderIdentity,
    val contract: String,
    val summary: String,
    val moduleVersion: ModuleVersion?,
    val compatibleCycloneApi: CycloneApiCompatibility?,
    val policyCategory: CapabilityPolicyCategory,
    val requiredDependencies: List<CapabilityVersionRequirement>,
    val optionalDependencies: List<CapabilityVersionRequirement>,
    val permissions: List<CapabilityPermission>,
    val health: CapabilityHealth,
    val providerStatus: CapabilityProviderStatus,
    val inventoryStatus: CapabilityInventoryStatus,
) : Comparable<CapabilityInventoryEntry> {
    override fun compareTo(other: CapabilityInventoryEntry): Int = provider.compareTo(other.provider)
}
