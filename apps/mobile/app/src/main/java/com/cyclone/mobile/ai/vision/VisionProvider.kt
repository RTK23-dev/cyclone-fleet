package com.cyclone.mobile.ai.vision

import com.cyclone.mobile.platform.event.DataClassification

private val PROVIDER_CODE = Regex("[a-z][a-z0-9_.-]{0,95}")

enum class VisionProviderLocation { ON_DEVICE_LOCAL, PC_GATEWAY, REMOTE_SERVICE }
enum class VisionProviderHealth { AVAILABLE, DEGRADED, UNAVAILABLE }

data class VisionProviderDescriptor(
    val providerId: String,
    val location: VisionProviderLocation,
    val priority: Int,
    val supportedPurposes: Set<VisionPurpose>,
    val maximumClassification: DataClassification,
) {
    init {
        require(PROVIDER_CODE.matches(providerId)) { "Provider id must be a safe code" }
        require(priority >= 0) { "Provider priority must be non-negative" }
        require(supportedPurposes.isNotEmpty()) { "Provider must declare a supported purpose" }
    }
}

sealed interface ProviderVisionResponse {
    val latencyMillis: Long
    val warning: Boolean

    data class Success(
        val evidence: List<VisionEvidence>,
        val confidence: Double,
        override val latencyMillis: Long,
        override val warning: Boolean = false,
    ) : ProviderVisionResponse {
        init {
            require(confidence in 0.0..1.0 && confidence.isFinite())
            require(latencyMillis >= 0)
        }
    }

    data class Failure(
        val reason: VisionFailureReason,
        override val latencyMillis: Long,
        override val warning: Boolean = false,
    ) : ProviderVisionResponse {
        init {
            require(latencyMillis >= 0)
            require(reason in PROVIDER_FAILURES) { "Provider returned a non-provider failure reason" }
        }
    }

    companion object {
        private val PROVIDER_FAILURES = setOf(
            VisionFailureReason.PROVIDER_UNAVAILABLE,
            VisionFailureReason.PROVIDER_FAILURE,
            VisionFailureReason.PROVIDER_TIMEOUT,
        )
    }
}

/** Provider seam only. Implementations observe an image and cannot propose or execute actions. */
interface VisionProvider {
    val descriptor: VisionProviderDescriptor

    fun health(): VisionProviderHealth

    fun observe(request: VisionRequest, remainingBudgetMillis: Long): ProviderVisionResponse
}

fun interface VisionMonotonicClock {
    fun nowMillis(): Long
}
