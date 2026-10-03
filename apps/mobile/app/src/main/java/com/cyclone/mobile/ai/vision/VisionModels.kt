package com.cyclone.mobile.ai.vision

import com.cyclone.mobile.platform.event.DataClassification
import java.security.MessageDigest

private val SAFE_CODE = Regex("[a-z][a-z0-9_.-]{0,95}")
private val SHA256 = Regex("[0-9a-f]{64}")

/** Content-addressed image handle. Image bytes and paths never enter the router contract. */
data class VisionImageRef(
    val sha256: String,
    val widthPixels: Int,
    val heightPixels: Int,
) {
    init {
        require(SHA256.matches(sha256)) { "Image reference must use lowercase SHA-256" }
        require(widthPixels > 0 && heightPixels > 0) { "Image dimensions must be positive" }
    }

    companion object {
        fun fromBytes(bytes: ByteArray, widthPixels: Int, heightPixels: Int): VisionImageRef =
            VisionImageRef(sha256(bytes), widthPixels, heightPixels)
    }
}

data class VisionRegion(
    val left: Int,
    val top: Int,
    val rightExclusive: Int,
    val bottomExclusive: Int,
) {
    init {
        require(left >= 0 && top >= 0) { "Region origin must be non-negative" }
        require(rightExclusive > left && bottomExclusive > top) { "Region must have positive area" }
    }

    fun fits(image: VisionImageRef): Boolean =
        rightExclusive <= image.widthPixels && bottomExclusive <= image.heightPixels
}

enum class VisionPurpose {
    PAGE_DISAMBIGUATION,
    CONTROL_DISCOVERY,
    TEXT_EVIDENCE,
    STATE_CLASSIFICATION,
    CHANGE_VERIFICATION,
}

enum class VisionEvidenceKind {
    PAGE_IDENTITY,
    CONTROL_BOUNDS,
    TEXT_REFERENCE,
    VISUAL_STATE,
    CHANGE_CONFIRMATION,
}

enum class EvidenceSufficiency { SUFFICIENT, INSUFFICIENT, UNAVAILABLE }
enum class StructuredEvidenceStage { PAGE_AWARENESS, APP_GRAPH, DETERMINISTIC_SEMANTIC_SEARCH, VISION }

data class StructuredEvidenceState(
    val pageAwareness: EvidenceSufficiency,
    val appGraph: EvidenceSufficiency,
    val deterministicSemanticSearch: EvidenceSufficiency,
) {
    fun routingDecision(): StructuredRoutingDecision {
        if (pageAwareness == EvidenceSufficiency.SUFFICIENT) {
            return StructuredRoutingDecision(false, StructuredEvidenceStage.PAGE_AWARENESS)
        }
        if (appGraph == EvidenceSufficiency.SUFFICIENT) {
            return StructuredRoutingDecision(false, StructuredEvidenceStage.APP_GRAPH)
        }
        if (deterministicSemanticSearch == EvidenceSufficiency.SUFFICIENT) {
            return StructuredRoutingDecision(false, StructuredEvidenceStage.DETERMINISTIC_SEMANTIC_SEARCH)
        }
        return StructuredRoutingDecision(true, StructuredEvidenceStage.VISION)
    }
}

data class StructuredRoutingDecision(
    val shouldInvokeVision: Boolean,
    val resolvedAt: StructuredEvidenceStage,
)

enum class RemoteVisionAuthorization { ALLOWED, DENIED, UNCONFIRMED }

data class VisionRequest(
    val requestId: String,
    val imageRef: VisionImageRef,
    val purpose: VisionPurpose,
    val region: VisionRegion? = null,
    val requiredEvidence: Set<VisionEvidenceKind>,
    val privacyClassification: DataClassification,
    val latencyBudgetMillis: Long,
    val structuredEvidence: StructuredEvidenceState,
    val remoteAuthorization: RemoteVisionAuthorization = RemoteVisionAuthorization.UNCONFIRMED,
) {
    init {
        require(SAFE_CODE.matches(requestId)) { "Vision request id must be a safe code" }
        require(region == null || region.fits(imageRef)) { "Vision region must fit the image" }
        require(requiredEvidence.isNotEmpty()) { "At least one evidence kind is required" }
        require(latencyBudgetMillis > 0) { "Vision latency budget must be positive" }
    }
}

/** A structured, content-addressed observation. It cannot represent an action or authority. */
data class VisionEvidence(
    val kind: VisionEvidenceKind,
    val valueRef: String,
    val confidence: Double,
    val region: VisionRegion? = null,
    val classification: DataClassification = DataClassification.INTERNAL,
) {
    init {
        require(SHA256.matches(valueRef)) { "Vision evidence value must be a lowercase SHA-256 reference" }
        require(confidence in 0.0..1.0 && confidence.isFinite()) { "Evidence confidence must be finite and normalized" }
    }

    companion object {
        fun fromRaw(
            kind: VisionEvidenceKind,
            value: CharSequence,
            confidence: Double,
            region: VisionRegion? = null,
            classification: DataClassification = DataClassification.INTERNAL,
        ): VisionEvidence = VisionEvidence(kind, sha256(value.toString().toByteArray()), confidence, region, classification)
    }
}

enum class VisionResultStatus { SUCCESS, UNAVAILABLE, SKIPPED_STRUCTURED_SUFFICIENT, POLICY_DENIED, BUDGET_EXHAUSTED }
enum class VisionFailureReason {
    NO_PROVIDER_CONFIGURED,
    STRUCTURED_EVIDENCE_SUFFICIENT,
    REMOTE_POLICY_DENIED,
    REMOTE_PRIVACY_DENIED,
    PROVIDER_UNAVAILABLE,
    PROVIDER_FAILURE,
    PROVIDER_EXCEPTION,
    PROVIDER_TIMEOUT,
    INVALID_PROVIDER_RESULT,
    MISSING_REQUIRED_EVIDENCE,
    BUDGET_EXHAUSTED,
}

enum class VisionWarning {
    FALLBACK_USED,
    PROVIDER_REPORTED_WARNING,
    PARTIAL_EVIDENCE_DISCARDED,
    DEGRADED_PROVIDER_USED,
}

enum class VisionAttemptDisposition {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    INVALID_RESULT,
    SKIPPED_UNSUPPORTED,
    SKIPPED_UNAVAILABLE,
    SKIPPED_POLICY,
    SKIPPED_PRIVACY,
    SKIPPED_BUDGET,
}

data class VisionAttempt(
    val sequence: Int,
    val providerId: String,
    val location: VisionProviderLocation,
    val invoked: Boolean,
    val latencyMillis: Long,
    val disposition: VisionAttemptDisposition,
    val failureReason: VisionFailureReason? = null,
) {
    init {
        require(sequence >= 1 && SAFE_CODE.matches(providerId))
        require(latencyMillis >= 0)
    }
}

data class VisionResult(
    val requestId: String,
    val status: VisionResultStatus,
    val providerId: String? = null,
    val evidence: List<VisionEvidence> = emptyList(),
    val confidence: Double? = null,
    val latencyMillis: Long,
    val attempts: List<VisionAttempt>,
    val warnings: Set<VisionWarning> = emptySet(),
    val failureReason: VisionFailureReason? = null,
    val structuredRouting: StructuredRoutingDecision,
) {
    init {
        require(SAFE_CODE.matches(requestId))
        require(providerId == null || SAFE_CODE.matches(providerId))
        require(latencyMillis >= 0)
        require(confidence == null || (confidence in 0.0..1.0 && confidence.isFinite()))
        require(status != VisionResultStatus.SUCCESS || providerId != null) { "Successful result requires a provider" }
        require(status != VisionResultStatus.SUCCESS || evidence.isNotEmpty()) { "Successful result requires evidence" }
        require(status == VisionResultStatus.SUCCESS || evidence.isEmpty()) { "Failed/skipped results cannot expose partial evidence" }
        require(attempts.map { it.sequence } == (1..attempts.size).toList()) { "Attempt sequence must be contiguous" }
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02x".format(it) }
