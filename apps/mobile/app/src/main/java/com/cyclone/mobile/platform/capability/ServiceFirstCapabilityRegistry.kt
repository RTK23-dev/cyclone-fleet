package com.cyclone.mobile.platform.capability

import com.cyclone.mobile.platform.module.CycloneApiVersion

/**
 * Service-first extension of the frozen capability contract.
 *
 * [lookup] preserves the Phase 0 contract exactly. [resolve] additionally gates access on declared
 * API compatibility, read-only provider status and dependency readiness. Neither path invokes a
 * capability implementation or changes module state.
 */
class ServiceFirstCapabilityRegistry(
    private val currentCycloneApi: CycloneApiVersion,
) : CapabilityRegistry {
    private data class Entry(
        val provider: CapabilityProvider<*>,
        val service: CapabilityServiceDeclaration<*>?,
    )

    private data class StatusObservation(
        val status: CapabilityProviderStatus,
        val probeFailed: Boolean,
    )

    private val lock = Any()
    private val contractRegistry = InMemoryCapabilityRegistry()
    private val entries = mutableMapOf<CapabilityId, MutableList<Entry>>()

    override fun <T : Any> register(provider: CapabilityProvider<T>): CapabilityRegistration =
        registerInternal(provider, null)

    fun <T : Any> declare(declaration: CapabilityServiceDeclaration<T>): CapabilityRegistration =
        registerInternal(declaration.provider, declaration)

    private fun <T : Any> registerInternal(
        provider: CapabilityProvider<T>,
        declaration: CapabilityServiceDeclaration<T>?,
    ): CapabilityRegistration = synchronized(lock) {
        val result = contractRegistry.register(provider)
        if (result is CapabilityRegistration.Registered || result is CapabilityRegistration.Conflict) {
            entries.getOrPut(provider.descriptor.key.id) { mutableListOf() }
                .add(Entry(provider, declaration))
        }
        result
    }

    override fun <T : Any> lookup(key: CapabilityKey<T>): CapabilityLookup<T> = contractRegistry.lookup(key)

    override fun snapshot(): List<RegisteredCapability> = contractRegistry.snapshot()

    fun <T : Any> resolve(key: CapabilityKey<T>): CapabilityServiceLookup<T> {
        return when (val lookup = contractRegistry.lookup(key)) {
            is CapabilityLookup.Available -> resolveAvailable(key.id, lookup)
            is CapabilityLookup.Missing -> unavailable(
                key.id,
                CapabilityDiagnosticCode.CAPABILITY_MISSING,
                "Capability ${key.id} is not declared",
            )
            is CapabilityLookup.Conflict -> unavailable(
                key.id,
                CapabilityDiagnosticCode.CAPABILITY_CONFLICT,
                "Capability ${key.id} has conflicting providers: " +
                    lookup.providers.joinToString { it.moduleId.value },
            )
            is CapabilityLookup.Unhealthy -> unavailable(
                key.id,
                CapabilityDiagnosticCode.CAPABILITY_UNHEALTHY,
                "Capability ${key.id} is ${lookup.health.state.name.lowercase()}",
            )
            is CapabilityLookup.TypeMismatch -> unavailable(
                key.id,
                CapabilityDiagnosticCode.CONTRACT_MISMATCH,
                "Capability ${key.id} uses ${lookup.registeredContract}, not ${lookup.requestedContract}",
            )
        }
    }

    private fun <T : Any> resolveAvailable(
        capabilityId: CapabilityId,
        lookup: CapabilityLookup.Available<T>,
    ): CapabilityServiceLookup<T> {
        val entry = entriesSnapshot()
            .singleOrNull { it.provider.identity() == lookup.provider }
            ?: return CapabilityServiceLookup.Available(
                lookup.provider,
                lookup.implementation,
                lookup.health,
                emptyList(),
            )

        val diagnostics = mutableListOf<CapabilityDiagnostic>()
        diagnostics += providerReadiness(entry, capabilityId, CapabilityDiagnosticSeverity.BLOCKING)

        val declaration = entry.service
        if (declaration != null) {
            diagnostics += dependencyDiagnostics(
                owner = capabilityId,
                declaration = declaration,
                path = listOf(capabilityId),
            )
        }

        val ordered = diagnostics.distinct().sorted()
        return if (ordered.any { it.severity == CapabilityDiagnosticSeverity.BLOCKING }) {
            CapabilityServiceLookup.Unavailable(capabilityId, ordered)
        } else {
            CapabilityServiceLookup.Available(
                provider = lookup.provider,
                implementation = lookup.implementation,
                health = lookup.health,
                warnings = ordered,
            )
        }
    }

    fun dependencyCycles(): List<CapabilityDependencyCycle> =
        CapabilityDependencyAnalyzer.cycles(buildDependencyGraph())

    fun inventory(): List<CapabilityInventoryEntry> {
        val snapshot = entriesSnapshot()
        val conflictIds = snapshot.groupBy { it.provider.descriptor.key.id }
            .filterValues { it.size > 1 }
            .keys
        return snapshot.map { entry -> inventoryEntry(entry, entry.provider.descriptor.key.id in conflictIds) }
            .sorted()
    }

    fun describe(capabilityId: CapabilityId): List<CapabilityInventoryEntry> =
        inventory().filter { it.provider.capabilityId == capabilityId }

    /** Deterministic, metadata-only dump suitable for future agent context tooling. */
    fun agentReadableDump(): String = renderCapabilityInventory(inventory(), dependencyCycles())

    private fun dependencyDiagnostics(
        owner: CapabilityId,
        declaration: CapabilityServiceDeclaration<*>,
        path: List<CapabilityId>,
    ): List<CapabilityDiagnostic> {
        val diagnostics = mutableListOf<CapabilityDiagnostic>()
        declaration.dependencies.required.sorted().forEach { requirement ->
            diagnostics += assessDependency(
                owner,
                requirement,
                CapabilityDiagnosticSeverity.BLOCKING,
                path,
            )
        }
        declaration.dependencies.optional.sorted().forEach { requirement ->
            diagnostics += assessDependency(
                owner,
                requirement,
                CapabilityDiagnosticSeverity.WARNING,
                path,
            )
        }
        return diagnostics
    }

    private fun assessDependency(
        owner: CapabilityId,
        requirement: CapabilityVersionRequirement,
        severity: CapabilityDiagnosticSeverity,
        path: List<CapabilityId>,
    ): List<CapabilityDiagnostic> {
        if (requirement.capabilityId in path) {
            val cycleStart = path.indexOf(requirement.capabilityId)
            val cycle = CapabilityDependencyCycle(
                (path.drop(cycleStart) + requirement.capabilityId).distinct().sorted(),
            )
            return listOf(cycleDiagnostic(owner, cycle, severity))
        }

        val candidates = entriesFor(requirement.capabilityId)
        if (candidates.isEmpty()) {
            val code = if (severity == CapabilityDiagnosticSeverity.BLOCKING) {
                CapabilityDiagnosticCode.REQUIRED_DEPENDENCY_MISSING
            } else {
                CapabilityDiagnosticCode.OPTIONAL_DEPENDENCY_MISSING
            }
            return listOf(
                CapabilityDiagnostic(
                    severity,
                    code,
                    owner,
                    requirement.capabilityId,
                    "${requirement.capabilityId} is not registered",
                ),
            )
        }
        if (candidates.size > 1) {
            return listOf(
                CapabilityDiagnostic(
                    severity,
                    CapabilityDiagnosticCode.DEPENDENCY_CONFLICT,
                    owner,
                    requirement.capabilityId,
                    "${requirement.capabilityId} has conflicting providers: " +
                        candidates.joinToString { it.provider.moduleId.value },
                ),
            )
        }

        val candidate = candidates.single()
        if (!requirement.accepts(candidate.provider.descriptor.version)) {
            return listOf(
                CapabilityDiagnostic(
                    severity,
                    CapabilityDiagnosticCode.DEPENDENCY_VERSION_INCOMPATIBLE,
                    owner,
                    requirement.capabilityId,
                    "${requirement.capabilityId} version ${candidate.provider.descriptor.version} " +
                        "does not satisfy ${requirement.displayRange()}",
                ),
            )
        }

        val diagnostics = mutableListOf<CapabilityDiagnostic>()
        diagnostics += providerReadiness(candidate, owner, severity, requirement.capabilityId)
        if (diagnostics.any { it.severity == CapabilityDiagnosticSeverity.BLOCKING } ||
            diagnostics.any { it.code == CapabilityDiagnosticCode.DEPENDENCY_UNHEALTHY }
        ) {
            return diagnostics
        }

        candidate.service?.let { nested ->
            diagnostics += dependencyDiagnostics(owner, nested, path + requirement.capabilityId)
                .map { diagnostic ->
                    if (severity == CapabilityDiagnosticSeverity.WARNING) {
                        diagnostic.copy(severity = CapabilityDiagnosticSeverity.WARNING)
                    } else {
                        diagnostic
                    }
                }
        }
        return diagnostics
    }

    private fun providerReadiness(
        entry: Entry,
        owner: CapabilityId,
        severity: CapabilityDiagnosticSeverity,
        related: CapabilityId? = null,
    ): List<CapabilityDiagnostic> {
        val diagnostics = mutableListOf<CapabilityDiagnostic>()
        val service = entry.service
        if (service != null && !service.compatibleCycloneApi.supports(currentCycloneApi)) {
            diagnostics += CapabilityDiagnostic(
                severity,
                CapabilityDiagnosticCode.CYCLONE_API_INCOMPATIBLE,
                owner,
                related,
                "Provider ${entry.provider.moduleId} is incompatible with Cyclone API $currentCycloneApi",
            )
        }

        val statusObservation = safeStatus(service)
        val status = statusObservation.status
        val statusCode = when {
            statusObservation.probeFailed -> CapabilityDiagnosticCode.PROVIDER_STATUS_FAILED
            else -> when (status.state) {
                CapabilityProviderState.ENABLED -> null
                CapabilityProviderState.DISABLED -> CapabilityDiagnosticCode.PROVIDER_DISABLED
                CapabilityProviderState.QUARANTINED -> CapabilityDiagnosticCode.PROVIDER_QUARANTINED
            }
        }
        if (statusCode != null) {
            diagnostics += CapabilityDiagnostic(
                severity,
                statusCode,
                owner,
                related,
                "Provider ${entry.provider.moduleId} is ${status.state.name.lowercase()}",
            )
        }

        val health = safeHealth(entry.provider)
        if (!health.canServe) {
            diagnostics += CapabilityDiagnostic(
                severity,
                CapabilityDiagnosticCode.DEPENDENCY_UNHEALTHY,
                owner,
                related,
                "Provider ${entry.provider.moduleId} is ${health.state.name.lowercase()}",
            )
        }
        return diagnostics
    }

    private fun inventoryEntry(entry: Entry, conflicted: Boolean): CapabilityInventoryEntry {
        val health = safeHealth(entry.provider)
        val statusObservation = safeStatus(entry.service)
        val status = statusObservation.status
        val compatible = entry.service?.compatibleCycloneApi?.supports(currentCycloneApi) ?: true
        val inventoryStatus = when {
            conflicted -> CapabilityInventoryStatus.CONFLICT
            statusObservation.probeFailed -> CapabilityInventoryStatus.STATUS_FAILED
            status.state == CapabilityProviderState.DISABLED -> CapabilityInventoryStatus.DISABLED
            status.state == CapabilityProviderState.QUARANTINED -> CapabilityInventoryStatus.QUARANTINED
            !compatible -> CapabilityInventoryStatus.INCOMPATIBLE
            health.state == CapabilityHealthState.DEGRADED -> CapabilityInventoryStatus.DEGRADED
            !health.canServe -> CapabilityInventoryStatus.UNHEALTHY
            else -> CapabilityInventoryStatus.READY
        }
        val service = entry.service
        return CapabilityInventoryEntry(
            provider = entry.provider.identity(),
            contract = entry.provider.descriptor.key.contract.displayName(),
            summary = entry.provider.descriptor.summary,
            moduleVersion = service?.moduleVersion,
            compatibleCycloneApi = service?.compatibleCycloneApi,
            policyCategory = service?.policyCategory
                ?: CycloneCapabilityFamilies.describe(entry.provider.descriptor.key.id)?.policyCategory
                ?: CyclonePolicyCategories.ROUTINE,
            requiredDependencies = service?.dependencies?.required.orEmpty().sorted(),
            optionalDependencies = service?.dependencies?.optional.orEmpty().sorted(),
            permissions = entry.provider.descriptor.permissions.sortedBy { it.id },
            health = health,
            providerStatus = status,
            inventoryStatus = inventoryStatus,
        )
    }

    private fun buildDependencyGraph(): Map<CapabilityId, Set<CapabilityId>> {
        val snapshot = entriesSnapshot()
        val nodes = buildSet {
            snapshot.forEach { entry ->
                add(entry.provider.descriptor.key.id)
                entry.service?.dependencies?.required?.forEach { add(it.capabilityId) }
                entry.service?.dependencies?.optional?.forEach { add(it.capabilityId) }
            }
        }
        return nodes.sorted().associateWith { node ->
            snapshot.filter { it.provider.descriptor.key.id == node }
                .flatMap { entry ->
                    entry.service?.dependencies?.let { it.required + it.optional }.orEmpty()
                }
                .map { it.capabilityId }
                .toSortedSet()
        }
    }

    private fun entriesFor(capabilityId: CapabilityId): List<Entry> =
        synchronized(lock) { entries[capabilityId]?.toList().orEmpty() }
            .sortedBy { it.provider.identity() }

    private fun entriesSnapshot(): List<Entry> = synchronized(lock) {
        entries.values.flatten().toList()
    }

    private fun safeStatus(service: CapabilityServiceDeclaration<*>?): StatusObservation {
        if (service == null) return StatusObservation(CapabilityProviderStatus.enabled(), false)
        return try {
            StatusObservation(service.status(), false)
        } catch (error: Exception) {
            StatusObservation(
                CapabilityProviderStatus(
                    CapabilityProviderState.QUARANTINED,
                    "Provider status probe failed: ${error::class.simpleName ?: "unknown error"}",
                ),
                true,
            )
        }
    }

    private fun safeHealth(provider: CapabilityProvider<*>): CapabilityHealth = try {
        provider.health()
    } catch (error: Exception) {
        CapabilityHealth(
            CapabilityHealthState.FAILED,
            "Health probe failed: ${error::class.simpleName ?: "unknown error"}",
        )
    }

    private fun unavailable(
        capabilityId: CapabilityId,
        code: CapabilityDiagnosticCode,
        message: String,
    ) = CapabilityServiceLookup.Unavailable(
        capabilityId,
        listOf(CapabilityDiagnostic(CapabilityDiagnosticSeverity.BLOCKING, code, capabilityId, message = message)),
    )

    private fun cycleDiagnostic(
        owner: CapabilityId,
        cycle: CapabilityDependencyCycle,
        severity: CapabilityDiagnosticSeverity,
    ) = CapabilityDiagnostic(
        severity,
        CapabilityDiagnosticCode.DEPENDENCY_CYCLE,
        owner,
        message = "Capability dependency cycle: ${cycle.members.joinToString(" -> ")}",
    )
}

private fun CapabilityProvider<*>.identity() = CapabilityProviderIdentity(
    moduleId = moduleId,
    capabilityId = descriptor.key.id,
    capabilityVersion = descriptor.version,
)

private fun kotlin.reflect.KClass<*>.displayName(): String = qualifiedName ?: simpleName ?: "unknown"
