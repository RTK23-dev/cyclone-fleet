package com.cyclone.mobile.platform.capability

/**
 * Small process-local registry for declared, compiled-in Cyclone capabilities.
 *
 * Multiple modules declaring the same capability makes that capability unavailable. Providers
 * are sorted by stable identity in diagnostics, so the outcome is independent of registration
 * order. A later lifecycle authority may resolve a conflict before registering providers; this
 * foundation intentionally does not choose a winner or execute capabilities.
 */
class InMemoryCapabilityRegistry : CapabilityRegistry {
    private val lock = Any()
    private val providers = mutableMapOf<CapabilityId, MutableList<CapabilityProvider<*>>>()

    override fun <T : Any> register(provider: CapabilityProvider<T>): CapabilityRegistration = synchronized(lock) {
        val identity = provider.identity()
        if (!provider.descriptor.key.contract.isInstance(provider.implementation)) {
            return@synchronized CapabilityRegistration.InvalidContract(
                provider = identity,
                expectedContract = provider.descriptor.key.contract.displayName(),
            )
        }

        val registered = providers.getOrPut(provider.descriptor.key.id) { mutableListOf() }
        val sameModule = registered.firstOrNull { it.moduleId == provider.moduleId }
        if (sameModule != null) {
            return@synchronized CapabilityRegistration.AlreadyRegistered(sameModule.identity())
        }

        registered += provider
        if (registered.size == 1) {
            CapabilityRegistration.Registered(identity)
        } else {
            CapabilityRegistration.Conflict(
                capabilityId = provider.descriptor.key.id,
                providers = registered.map { it.identity() }.sorted(),
            )
        }
    }

    override fun <T : Any> lookup(key: CapabilityKey<T>): CapabilityLookup<T> {
        val candidates = synchronized(lock) { providers[key.id]?.toList().orEmpty() }
        if (candidates.isEmpty()) return CapabilityLookup.Missing(key.id)
        if (candidates.size > 1) {
            return CapabilityLookup.Conflict(key.id, candidates.map { it.identity() }.sorted())
        }

        val provider = candidates.single()
        if (provider.descriptor.key.contract != key.contract) {
            return CapabilityLookup.TypeMismatch(
                capabilityId = key.id,
                requestedContract = key.contract.displayName(),
                registeredContract = provider.descriptor.key.contract.displayName(),
            )
        }

        val health = try {
            provider.health()
        } catch (error: Exception) {
            CapabilityHealth(
                state = CapabilityHealthState.FAILED,
                message = "Health probe failed: ${error::class.simpleName ?: "unknown error"}",
            )
        }
        if (!health.canServe) return CapabilityLookup.Unhealthy(provider.identity(), health)

        @Suppress("UNCHECKED_CAST")
        return CapabilityLookup.Available(
            provider = provider.identity(),
            implementation = provider.implementation as T,
            health = health,
        )
    }

    override fun snapshot(): List<RegisteredCapability> = synchronized(lock) {
        providers.values
            .flatten()
            .map { provider ->
                RegisteredCapability(
                    provider = provider.identity(),
                    contract = provider.descriptor.key.contract.displayName(),
                    summary = provider.descriptor.summary,
                    permissions = provider.descriptor.permissions,
                )
            }
            .sortedBy { it.provider }
    }
}

private fun CapabilityProvider<*>.identity() = CapabilityProviderIdentity(
    moduleId = moduleId,
    capabilityId = descriptor.key.id,
    capabilityVersion = descriptor.version,
)

private fun kotlin.reflect.KClass<*>.displayName(): String = qualifiedName ?: simpleName ?: "unknown"
