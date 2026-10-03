package com.cyclone.mobile.platform.capability

import com.cyclone.mobile.platform.module.ModuleId
import kotlin.reflect.KClass

private val CAPABILITY_ID_PATTERN = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+")
private val PERMISSION_ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.-]*")

@JvmInline
value class CapabilityId(val value: String) : Comparable<CapabilityId> {
    init {
        require(CAPABILITY_ID_PATTERN.matches(value)) {
            "Capability id must be a lowercase namespaced identifier: $value"
        }
    }

    override fun compareTo(other: CapabilityId): Int = value.compareTo(other.value)
    override fun toString(): String = value
}

data class CapabilityVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<CapabilityVersion> {
    init {
        require(major >= 0 && minor >= 0 && patch >= 0) { "Capability version parts must be non-negative" }
    }

    override fun compareTo(other: CapabilityVersion): Int =
        compareValuesBy(this, other, CapabilityVersion::major, CapabilityVersion::minor, CapabilityVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"
}

data class CapabilityPermission(
    val id: String,
    val required: Boolean = true,
    val rationale: String? = null,
) {
    init {
        require(PERMISSION_ID_PATTERN.matches(id)) { "Permission id is invalid: $id" }
        require(rationale == null || rationale.isNotBlank()) { "Permission rationale must not be blank" }
    }
}

enum class CapabilityHealthState {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
    FAILED,
}

data class CapabilityHealth(
    val state: CapabilityHealthState,
    val message: String? = null,
    val checkedAtEpochMillis: Long? = null,
) {
    init {
        require(message == null || message.isNotBlank()) { "Health message must not be blank" }
        require(checkedAtEpochMillis == null || checkedAtEpochMillis >= 0) {
            "Health check timestamp must be non-negative"
        }
    }

    val canServe: Boolean
        get() = state == CapabilityHealthState.HEALTHY || state == CapabilityHealthState.DEGRADED

    companion object {
        fun healthy(checkedAtEpochMillis: Long? = null) =
            CapabilityHealth(CapabilityHealthState.HEALTHY, checkedAtEpochMillis = checkedAtEpochMillis)
    }
}

data class CapabilityKey<T : Any>(
    val id: CapabilityId,
    val contract: KClass<T>,
)

data class CapabilityDescriptor<T : Any>(
    val key: CapabilityKey<T>,
    val version: CapabilityVersion,
    val summary: String,
    val permissions: Set<CapabilityPermission> = emptySet(),
) {
    init {
        require(summary.isNotBlank()) { "Capability summary must not be blank" }
    }
}

/**
 * A provider publishes one explicit typed contract. The registry stores and describes the
 * contract; it never invokes phone actions or bypasses the canonical action/policy layers.
 */
interface CapabilityProvider<T : Any> {
    val moduleId: ModuleId
    val descriptor: CapabilityDescriptor<T>
    val implementation: T
    fun health(): CapabilityHealth
}

data class CapabilityProviderIdentity(
    val moduleId: ModuleId,
    val capabilityId: CapabilityId,
    val capabilityVersion: CapabilityVersion,
) : Comparable<CapabilityProviderIdentity> {
    override fun compareTo(other: CapabilityProviderIdentity): Int =
        compareValuesBy(
            this,
            other,
            { it.moduleId },
            { it.capabilityId },
            { it.capabilityVersion },
        )
}

sealed interface CapabilityRegistration {
    data class Registered(val provider: CapabilityProviderIdentity) : CapabilityRegistration
    data class AlreadyRegistered(val provider: CapabilityProviderIdentity) : CapabilityRegistration
    data class Conflict(val capabilityId: CapabilityId, val providers: List<CapabilityProviderIdentity>) :
        CapabilityRegistration
    data class InvalidContract(val provider: CapabilityProviderIdentity, val expectedContract: String) :
        CapabilityRegistration
}

sealed interface CapabilityLookup<out T : Any> {
    data class Available<T : Any>(
        val provider: CapabilityProviderIdentity,
        val implementation: T,
        val health: CapabilityHealth,
    ) : CapabilityLookup<T>

    data class Unhealthy(
        val provider: CapabilityProviderIdentity,
        val health: CapabilityHealth,
    ) : CapabilityLookup<Nothing>

    data class Missing(val capabilityId: CapabilityId) : CapabilityLookup<Nothing>
    data class TypeMismatch(
        val capabilityId: CapabilityId,
        val requestedContract: String,
        val registeredContract: String,
    ) : CapabilityLookup<Nothing>

    data class Conflict(
        val capabilityId: CapabilityId,
        val providers: List<CapabilityProviderIdentity>,
    ) : CapabilityLookup<Nothing>
}

data class RegisteredCapability(
    val provider: CapabilityProviderIdentity,
    val contract: String,
    val summary: String,
    val permissions: Set<CapabilityPermission>,
)

interface CapabilityRegistry {
    fun <T : Any> register(provider: CapabilityProvider<T>): CapabilityRegistration
    fun <T : Any> lookup(key: CapabilityKey<T>): CapabilityLookup<T>
    fun snapshot(): List<RegisteredCapability>
}
