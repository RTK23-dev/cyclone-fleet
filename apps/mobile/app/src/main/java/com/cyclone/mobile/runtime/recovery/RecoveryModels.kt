package com.cyclone.mobile.runtime.recovery

import com.cyclone.mobile.platform.module.ModuleId
import com.cyclone.mobile.platform.module.ModuleVersion

private val RECOVERY_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")
private val RUNTIME_ID = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
private val SCHEMA_ID = Regex("[a-z][A-Za-z0-9_.-]*")
private val SHA_256 = Regex("[a-f0-9]{64}")
private val SENSITIVE_SHAPE = Regex("(?i)(password|passcode|otp|token|api[_-]?key|secret|bearer)\\s*[:=]")

internal fun requireRecoveryId(value: String, label: String) {
    require(RECOVERY_ID.matches(value)) { "$label is invalid" }
    require(!SENSITIVE_SHAPE.containsMatchIn(value)) { "$label resembles sensitive material" }
}

data class RuntimeIdentity(
    val runtimeId: String,
    val runtimeApiVersion: String,
    val manifestSha256: String,
) : Comparable<RuntimeIdentity> {
    init {
        require(RUNTIME_ID.matches(runtimeId)) { "Runtime id is invalid" }
        require(Regex("[0-9]+(?:\\.[0-9]+){1,2}").matches(runtimeApiVersion)) { "Runtime API version is invalid" }
        require(SHA_256.matches(manifestSha256)) { "Runtime manifest hash must be SHA-256" }
    }

    override fun compareTo(other: RuntimeIdentity): Int = compareValuesBy(
        this,
        other,
        RuntimeIdentity::runtimeId,
        RuntimeIdentity::runtimeApiVersion,
        RuntimeIdentity::manifestSha256,
    )
}

data class RecoveryModuleSnapshot(
    val moduleId: ModuleId,
    val version: ModuleVersion,
    val enabled: Boolean,
    val essential: Boolean,
)

data class RecoverySchemaVersion(
    val schemaId: String,
    val version: Int,
) {
    init {
        require(SCHEMA_ID.matches(schemaId)) { "Schema id is invalid" }
        require(version >= 1)
    }
}

/** Secret-free recovery material. Configuration values and user data are intentionally absent. */
data class RecoverySnapshot(
    val snapshotId: String,
    val capturedAtEpochMillis: Long,
    val runtime: RuntimeIdentity,
    val configurationSha256: String,
    val modules: List<RecoveryModuleSnapshot>,
    val schemas: List<RecoverySchemaVersion>,
    val lastUpdateId: String?,
) {
    init {
        requireRecoveryId(snapshotId, "Snapshot id")
        require(capturedAtEpochMillis >= 0)
        require(SHA_256.matches(configurationSha256)) { "Configuration hash must be SHA-256" }
        require(modules.map { it.moduleId }.distinct().size == modules.size)
        require(schemas.map { it.schemaId }.distinct().size == schemas.size)
        lastUpdateId?.let { requireRecoveryId(it, "Update id") }
    }

    fun normalized(): RecoverySnapshot = copy(
        modules = modules.sortedBy { it.moduleId }.map { it.copy(version = it.version.copy()) },
        schemas = schemas.sortedBy { it.schemaId }.map { it.copy() },
        runtime = runtime.copy(),
    )
}

/**
 * Recovery-owned activation handoff. Agent 15 can map Agent 8's RuntimeActivationRequest into this
 * value without granting the updater promotion or rollback authority.
 */
data class RecoveryActivationHandoff(
    val requestId: String,
    val updateId: String,
    val activeKnownGood: RecoverySnapshot,
    val candidate: RecoverySnapshot,
    val requestedAtEpochMillis: Long,
) {
    init {
        requireRecoveryId(requestId, "Activation request id")
        requireRecoveryId(updateId, "Update id")
        require(requestedAtEpochMillis >= 0)
        require(candidate.lastUpdateId == updateId) { "Candidate must attribute the requested update" }
        require(activeKnownGood.runtime != candidate.runtime) { "Candidate must differ from known-good runtime" }
    }
}

sealed interface RecoveryActivationDecision {
    data object Accepted : RecoveryActivationDecision
    data class Rejected(val reason: RecoveryRejection) : RecoveryActivationDecision
}

fun interface RecoveryActivationHandoffSink {
    fun requestActivation(request: RecoveryActivationHandoff): RecoveryActivationDecision
}

enum class TrustedCoreService {
    ACCESSIBILITY,
    PHONE_TOOL_EXECUTOR,
    PAGE_AWARENESS,
    POLICY,
    RECOVERY,
    MINIMAL_UI,
}

enum class ObservedHealth { HEALTHY, DEGRADED, FAILED, UNAVAILABLE }

data class RecoveryModuleHealth(
    val moduleId: ModuleId,
    val health: ObservedHealth,
)

data class RecoveryHealthObservation(
    val observationId: String,
    val updateId: String,
    val runtime: RuntimeIdentity,
    val observedAtEpochMillis: Long,
    val trustedCore: Map<TrustedCoreService, ObservedHealth>,
    val modules: List<RecoveryModuleHealth>,
    val runtimeStable: Boolean,
    val schemasReadable: Boolean,
) {
    init {
        requireRecoveryId(observationId, "Observation id")
        requireRecoveryId(updateId, "Update id")
        require(observedAtEpochMillis >= 0)
        require(modules.map { it.moduleId }.distinct().size == modules.size)
    }

    fun normalized(): RecoveryHealthObservation = copy(
        trustedCore = trustedCore.toSortedMap(),
        modules = modules.sortedBy { it.moduleId },
        runtime = runtime.copy(),
    )
}

data class RecoveryHealthCriteria(
    val requiredConsecutiveHealthyObservations: Int = 3,
    val minimumHealthyDurationMillis: Long = 30_000,
    val crashLoopThreshold: Int = 3,
    val maximumCandidateBootAttempts: Int = 3,
) {
    init {
        require(requiredConsecutiveHealthyObservations in 1..20)
        require(minimumHealthyDurationMillis in 0..86_400_000)
        require(crashLoopThreshold in 1..20)
        require(maximumCandidateBootAttempts in 1..20)
    }
}

enum class RecoveryFailureReason {
    CANDIDATE_RUNTIME_UNHEALTHY,
    REQUIRED_MODULE_UNHEALTHY,
    OPTIONAL_MODULE_UNHEALTHY,
    TRUSTED_CORE_UNHEALTHY,
    SCHEMA_UNREADABLE,
    CANDIDATE_BOOT_LIMIT,
    CRASH_LOOP,
    ROLLBACK_FAILED,
    QUARANTINE_FAILED,
    PROMOTION_FAILED,
    SAFE_MODE_ACTIVATION_FAILED,
}

enum class RecoveryRejection {
    ACTIVE_RUNTIME_MISMATCH,
    REQUEST_CONFLICT,
    STALE_TIMESTAMP,
    NO_KNOWN_GOOD_STATE,
    NO_ACTIVE_CANDIDATE,
    OBSERVATION_MISMATCH,
    COMMAND_PENDING,
    RESULT_MISMATCH,
    CANDIDATE_OMITS_ESSENTIAL_MODULE,
}

data class CrashAttribution(
    val previousActiveRuntime: RuntimeIdentity,
    val moduleSet: List<RecoveryModuleSnapshot>,
    val schemas: List<RecoverySchemaVersion>,
    val lastUpdateId: String?,
    val bootAttempts: Int,
    val safeFailureReason: RecoveryFailureReason,
    val recordedAtEpochMillis: Long,
) {
    init {
        require(bootAttempts >= 1)
        require(recordedAtEpochMillis >= 0)
        require(moduleSet.map { it.moduleId }.distinct().size == moduleSet.size)
        require(schemas.map { it.schemaId }.distinct().size == schemas.size)
        lastUpdateId?.let { requireRecoveryId(it, "Update id") }
    }

    fun normalized() = copy(
        previousActiveRuntime = previousActiveRuntime.copy(),
        moduleSet = moduleSet.sortedBy { it.moduleId }.map { it.copy(version = it.version.copy()) },
        schemas = schemas.sortedBy { it.schemaId }.map { it.copy() },
    )
}

data class CandidateRecoveryState(
    val requestId: String,
    val updateId: String,
    val previousKnownGood: RecoverySnapshot,
    val candidate: RecoverySnapshot,
    val requestedAtEpochMillis: Long,
    val bootAttempts: Int = 0,
    val consecutiveHealthyObservations: Int = 0,
    val firstHealthyAtEpochMillis: Long? = null,
    val lastObservationId: String? = null,
    val lastObservationAtEpochMillis: Long? = null,
) {
    init {
        requireRecoveryId(requestId, "Activation request id")
        requireRecoveryId(updateId, "Update id")
        require(requestedAtEpochMillis >= 0)
        require(bootAttempts >= 0 && consecutiveHealthyObservations >= 0)
        require(firstHealthyAtEpochMillis == null || firstHealthyAtEpochMillis >= requestedAtEpochMillis)
        lastObservationId?.let { requireRecoveryId(it, "Observation id") }
        require(lastObservationAtEpochMillis == null || lastObservationAtEpochMillis >= requestedAtEpochMillis)
    }
}
