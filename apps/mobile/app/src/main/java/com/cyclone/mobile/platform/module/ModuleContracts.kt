package com.cyclone.mobile.platform.module

import com.cyclone.mobile.platform.capability.CapabilityId
import com.cyclone.mobile.platform.capability.CapabilityPermission

private val MODULE_ID_PATTERN = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")

@JvmInline
value class ModuleId(val value: String) : Comparable<ModuleId> {
    init {
        require(MODULE_ID_PATTERN.matches(value)) {
            "Module id must be lowercase dot/dash-separated identifier: $value"
        }
    }

    override fun compareTo(other: ModuleId): Int = value.compareTo(other.value)
    override fun toString(): String = value
}

data class ModuleVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<ModuleVersion> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "Module version parts must be non-negative" }
    }

    override fun compareTo(other: ModuleVersion): Int =
        compareValuesBy(this, other, ModuleVersion::major, ModuleVersion::minor, ModuleVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"
}

data class CycloneApiVersion(
    val major: Int,
    val minor: Int,
) : Comparable<CycloneApiVersion> {
    init {
        require(major >= 0 && minor >= 0) { "Cyclone API version parts must be non-negative" }
    }

    override fun compareTo(other: CycloneApiVersion): Int =
        compareValuesBy(this, other, CycloneApiVersion::major, CycloneApiVersion::minor)

    override fun toString(): String = "$major.$minor"
}

data class CycloneApiCompatibility(
    val minimumInclusive: CycloneApiVersion,
    val maximumExclusive: CycloneApiVersion,
) {
    init {
        require(minimumInclusive < maximumExclusive) { "Cyclone API compatibility range must not be empty" }
    }

    fun supports(version: CycloneApiVersion): Boolean =
        version >= minimumInclusive && version < maximumExclusive
}

data class ModuleDependency(
    val moduleId: ModuleId,
    val minimumVersion: ModuleVersion? = null,
    val maximumVersionExclusive: ModuleVersion? = null,
) {
    init {
        require(
            minimumVersion == null || maximumVersionExclusive == null || minimumVersion < maximumVersionExclusive,
        ) { "Module dependency version range must not be empty" }
    }

    fun accepts(version: ModuleVersion): Boolean =
        (minimumVersion == null || version >= minimumVersion) &&
            (maximumVersionExclusive == null || version < maximumVersionExclusive)
}

data class HealthProbeDescriptor(
    val id: String,
    val timeoutMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "Health probe id must not be blank" }
        require(timeoutMillis > 0) { "Health probe timeout must be positive" }
    }
}

data class PersistentSchemaDescriptor(
    val id: String,
    val version: Int,
    val containsSensitiveData: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Persistent schema id must not be blank" }
        require(version >= 1) { "Persistent schema version must be at least 1" }
    }
}

enum class RestartRequirement {
    NONE,
    MODULE,
    APPLICATION,
    DEVICE,
}

data class ModuleDescriptor(
    val id: ModuleId,
    val version: ModuleVersion,
    val compatibleCycloneApi: CycloneApiCompatibility,
    val provides: Set<CapabilityId> = emptySet(),
    val consumes: Set<CapabilityId> = emptySet(),
    val dependencies: List<ModuleDependency> = emptyList(),
    val optionalDependencies: List<ModuleDependency> = emptyList(),
    val permissions: Set<CapabilityPermission> = emptySet(),
    val healthProbes: List<HealthProbeDescriptor> = emptyList(),
    val persistentSchemas: List<PersistentSchemaDescriptor> = emptyList(),
    val restartRequirement: RestartRequirement = RestartRequirement.NONE,
    val migrationVersion: Int = 0,
) {
    init {
        require(migrationVersion >= 0) { "Migration version must be non-negative" }
        require(dependencies.none { it.moduleId == id }) { "A module cannot depend on itself" }
        require(optionalDependencies.none { it.moduleId == id }) { "A module cannot optionally depend on itself" }

        val requiredIds = dependencies.map { it.moduleId }
        val optionalIds = optionalDependencies.map { it.moduleId }
        require(requiredIds.distinct().size == requiredIds.size) { "Required dependencies must be unique" }
        require(optionalIds.distinct().size == optionalIds.size) { "Optional dependencies must be unique" }
        require(requiredIds.intersect(optionalIds.toSet()).isEmpty()) {
            "A dependency cannot be both required and optional"
        }

        val probeIds = healthProbes.map { it.id }
        require(probeIds.distinct().size == probeIds.size) { "Health probe ids must be unique" }
        val schemaIds = persistentSchemas.map { it.id }
        require(schemaIds.distinct().size == schemaIds.size) { "Persistent schema ids must be unique" }
    }
}
