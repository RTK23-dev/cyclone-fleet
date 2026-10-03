package com.cyclone.mobile.runtime.update

/** Compatibility version for non-native runtime resources, independent of the APK marketing version. */
data class RuntimeApiVersion(
    val major: Int,
    val minor: Int,
) : Comparable<RuntimeApiVersion> {
    init {
        require(major >= 0 && minor >= 0) { "Runtime API version parts must be non-negative" }
    }

    override fun compareTo(other: RuntimeApiVersion): Int =
        compareValuesBy(this, other, RuntimeApiVersion::major, RuntimeApiVersion::minor)

    override fun toString(): String = "$major.$minor"
}

data class RuntimeApiCompatibility(
    val minimumInclusive: RuntimeApiVersion,
    val maximumExclusive: RuntimeApiVersion,
) {
    init {
        require(minimumInclusive < maximumExclusive) { "Runtime API compatibility range must not be empty" }
    }

    fun supports(version: RuntimeApiVersion): Boolean =
        version >= minimumInclusive && version < maximumExclusive
}

/** The complete allowlist of content that may be updated without rebuilding the APK. */
enum class RuntimeResourceKind(val wireName: String) {
    WORKFLOW_DEFINITION("workflow-definition"),
    POLICY_DATA("policy-data"),
    MODEL_ROUTING_CONFIG("model-routing-config"),
    PROMPT_TEMPLATE("prompt-template"),
    SKILL_METADATA("skill-metadata"),
    APP_LEARNING_RULES("app-learning-rules"),
    SIGNED_STATIC_ASSET("signed-static-asset"),
    SIGNED_RUNTIME_ASSET("signed-runtime-asset"),
    ;

    companion object {
        fun fromWireName(value: String): RuntimeResourceKind? = entries.firstOrNull { it.wireName == value }
    }
}

data class RuntimeResourceDescriptor(
    val path: String,
    val kind: RuntimeResourceKind,
    val sha256: String,
    val sizeBytes: Long,
    val schemaId: String,
    val schemaVersion: Int,
)

data class RuntimeUpdateManifest(
    val schemaVersion: Int,
    val updateId: String,
    val compatibleRuntimeApi: RuntimeApiCompatibility,
    val resources: List<RuntimeResourceDescriptor>,
    val issuedAtEpochMillis: Long,
)

/**
 * Opaque input. The typed manifest is only obtained from [RuntimeManifestVerifier], so callers
 * cannot accidentally treat manifest-declared hashes as trusted before signature policy passes.
 */
class SignedRuntimeManifest(
    canonicalPayload: ByteArray,
    signature: ByteArray,
    val keyId: String,
    val algorithm: String,
) {
    private val canonicalPayloadBytes: ByteArray = canonicalPayload.copyOf()
    private val signatureBytes: ByteArray = signature.copyOf()

    val canonicalPayload: ByteArray get() = canonicalPayloadBytes.copyOf()
    val signature: ByteArray get() = signatureBytes.copyOf()
}

data class VerifiedSigner(
    val keyId: String,
    val algorithm: String,
    val trustPolicyId: String,
)

sealed interface ManifestVerification {
    data class Verified(
        val manifest: RuntimeUpdateManifest,
        val signer: VerifiedSigner,
    ) : ManifestVerification

    data class Rejected(val reason: ManifestRejection) : ManifestVerification
}

enum class ManifestRejection {
    INVALID_SIGNATURE,
    UNKNOWN_SIGNER,
    UNSUPPORTED_ALGORITHM,
    MALFORMED_SIGNED_PAYLOAD,
}

fun interface RuntimeManifestVerifier {
    fun verify(signedManifest: SignedRuntimeManifest): ManifestVerification
}

data class RuntimePayloadRead(
    val bytes: ByteArray,
    val complete: Boolean,
)

fun interface RuntimePayloadSource {
    @Throws(Exception::class)
    fun read(resource: RuntimeResourceDescriptor): RuntimePayloadRead
}

sealed interface SchemaValidation {
    data object Valid : SchemaValidation
    data class Invalid(val reasonCode: String) : SchemaValidation
}

fun interface RuntimeResourceSchemaValidator {
    fun validate(resource: RuntimeResourceDescriptor, bytes: ByteArray): SchemaValidation
}

sealed interface RuntimeHealthDecision {
    data object Healthy : RuntimeHealthDecision
    data class Unhealthy(val reasonCode: String) : RuntimeHealthDecision
}

fun interface RuntimeCandidateHealthPreflight {
    fun check(candidate: RuntimeSlotSnapshot): RuntimeHealthDecision
}

enum class RuntimeSlotId { A, B }

enum class RuntimeSlotState {
    ACTIVE_KNOWN_GOOD,
    STAGING,
    STAGED_COMPLETE,
    ACTIVATION_REQUESTED,
    FAILED,
}

data class StagedResourceMetadata(
    val path: String,
    val kind: RuntimeResourceKind,
    val sha256: String,
    val sizeBytes: Long,
    val schemaId: String,
    val schemaVersion: Int,
)

data class RuntimeSlotSnapshot(
    val slot: RuntimeSlotId,
    val state: RuntimeSlotState,
    val updateId: String,
    val manifestSha256: String,
    val resources: List<StagedResourceMetadata>,
    val activationRequest: RuntimeActivationRequest? = null,
)

/** Durable handoff intended for Agent 9. This package neither activates nor rolls back a slot. */
data class RuntimeActivationRequest(
    val updateId: String,
    val activeKnownGoodSlot: RuntimeSlotId,
    val candidateSlot: RuntimeSlotId,
    val runtimeApiVersion: RuntimeApiVersion,
    val manifestSha256: String,
    val resources: List<StagedResourceMetadata>,
    val requestedAtEpochMillis: Long,
)

sealed interface ActivationRequestDecision {
    data object Accepted : ActivationRequestDecision
    data class Rejected(val reasonCode: String) : ActivationRequestDecision
}

fun interface RuntimeActivationRequestSink {
    fun requestActivation(request: RuntimeActivationRequest): ActivationRequestDecision
}

enum class RuntimeUpdateFailureCode {
    MANIFEST_NOT_VERIFIED,
    UNSUPPORTED_MANIFEST_SCHEMA,
    INVALID_UPDATE_ID,
    INCOMPATIBLE_RUNTIME_API,
    EMPTY_MANIFEST,
    TOO_MANY_RESOURCES,
    UPDATE_TOO_LARGE,
    DUPLICATE_RESOURCE_PATH,
    INVALID_RESOURCE_PATH,
    FORBIDDEN_RESOURCE,
    INVALID_RESOURCE_METADATA,
    DOWNLOAD_INTERRUPTED,
    PARTIAL_DOWNLOAD,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    SCHEMA_INVALID,
    HEALTH_PREFLIGHT_FAILED,
    ACTIVATION_REQUEST_REJECTED,
    STORAGE_FAILURE,
}

sealed interface RuntimeUpdateOutcome {
    data class ActivationRequested(val request: RuntimeActivationRequest) : RuntimeUpdateOutcome
    data class AlreadyRequested(val request: RuntimeActivationRequest) : RuntimeUpdateOutcome
    data class Rejected(val code: RuntimeUpdateFailureCode) : RuntimeUpdateOutcome
}

enum class RuntimeUpdateAuditEvent {
    MANIFEST_REJECTED,
    PREFLIGHT_REJECTED,
    STAGING_STARTED,
    RESOURCE_REJECTED,
    CANDIDATE_FAILED,
    CANDIDATE_COMPLETE,
    ACTIVATION_REQUESTED,
    ACTIVATION_STATE_PERSIST_FAILED,
    ACTIVATION_ALREADY_REQUESTED,
}

/** Intentionally contains no payload bytes, signature bytes, URLs, or exception text. */
data class RuntimeUpdateAuditRecord(
    val event: RuntimeUpdateAuditEvent,
    val updateId: String?,
    val manifestSha256: String?,
    val activeSlot: RuntimeSlotId,
    val candidateSlot: RuntimeSlotId,
    val resourceCount: Int,
    val failureCode: RuntimeUpdateFailureCode?,
    val signerKeyId: String?,
    val timestampEpochMillis: Long,
)

fun interface RuntimeUpdateAuditSink {
    fun record(record: RuntimeUpdateAuditRecord)
}

fun interface RuntimeUpdateClock {
    fun nowEpochMillis(): Long
}
