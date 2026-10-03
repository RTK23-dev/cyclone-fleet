package com.cyclone.mobile.ai.vision

import com.cyclone.mobile.platform.event.DataClassification

class VisionRouter(
    providers: Collection<VisionProvider>,
    private val clock: VisionMonotonicClock = VisionMonotonicClock { System.nanoTime() / 1_000_000L },
) {
    private val providers = providers.sortedWith(PROVIDER_ORDER)

    init {
        require(this.providers.map { it.descriptor.providerId }.distinct().size == this.providers.size) {
            "Vision provider ids must be unique"
        }
    }

    fun route(request: VisionRequest): VisionResult {
        val structured = request.structuredEvidence.routingDecision()
        if (!structured.shouldInvokeVision) {
            return VisionResult(
                requestId = request.requestId,
                status = VisionResultStatus.SKIPPED_STRUCTURED_SUFFICIENT,
                latencyMillis = 0,
                attempts = emptyList(),
                failureReason = VisionFailureReason.STRUCTURED_EVIDENCE_SUFFICIENT,
                structuredRouting = structured,
            )
        }
        if (providers.isEmpty()) {
            return VisionResult(
                requestId = request.requestId,
                status = VisionResultStatus.UNAVAILABLE,
                latencyMillis = 0,
                attempts = emptyList(),
                failureReason = VisionFailureReason.NO_PROVIDER_CONFIGURED,
                structuredRouting = structured,
            )
        }

        val attempts = mutableListOf<VisionAttempt>()
        val warnings = linkedSetOf<VisionWarning>()
        var spentMillis = 0L
        var terminalFailure = VisionFailureReason.PROVIDER_UNAVAILABLE

        providers.forEach { provider ->
            val descriptor = provider.descriptor
            val sequence = attempts.size + 1
            fun skipped(disposition: VisionAttemptDisposition, reason: VisionFailureReason) {
                attempts += VisionAttempt(sequence, descriptor.providerId, descriptor.location, false, 0, disposition, reason)
                terminalFailure = reason
            }

            if (request.purpose !in descriptor.supportedPurposes) {
                skipped(VisionAttemptDisposition.SKIPPED_UNSUPPORTED, VisionFailureReason.PROVIDER_UNAVAILABLE)
                return@forEach
            }
            val health = try {
                provider.health()
            } catch (_: Throwable) {
                VisionProviderHealth.UNAVAILABLE
            }
            if (health == VisionProviderHealth.UNAVAILABLE) {
                skipped(VisionAttemptDisposition.SKIPPED_UNAVAILABLE, VisionFailureReason.PROVIDER_UNAVAILABLE)
                return@forEach
            }
            if (health == VisionProviderHealth.DEGRADED) warnings += VisionWarning.DEGRADED_PROVIDER_USED
            if (request.privacyClassification > descriptor.maximumClassification ||
                (descriptor.location != VisionProviderLocation.ON_DEVICE_LOCAL &&
                    request.privacyClassification == DataClassification.RESTRICTED)
            ) {
                skipped(VisionAttemptDisposition.SKIPPED_PRIVACY, VisionFailureReason.REMOTE_PRIVACY_DENIED)
                return@forEach
            }
            if (descriptor.location != VisionProviderLocation.ON_DEVICE_LOCAL &&
                request.remoteAuthorization != RemoteVisionAuthorization.ALLOWED
            ) {
                skipped(VisionAttemptDisposition.SKIPPED_POLICY, VisionFailureReason.REMOTE_POLICY_DENIED)
                return@forEach
            }

            val remaining = request.latencyBudgetMillis - spentMillis
            if (remaining <= 0) {
                skipped(VisionAttemptDisposition.SKIPPED_BUDGET, VisionFailureReason.BUDGET_EXHAUSTED)
                return@forEach
            }

            val started = clock.nowMillis()
            val response = try {
                provider.observe(request, remaining)
            } catch (_: Throwable) {
                val observed = (clock.nowMillis() - started).coerceAtLeast(0)
                spentMillis += observed
                attempts += VisionAttempt(
                    sequence, descriptor.providerId, descriptor.location, true, observed,
                    VisionAttemptDisposition.FAILED, VisionFailureReason.PROVIDER_EXCEPTION,
                )
                terminalFailure = VisionFailureReason.PROVIDER_EXCEPTION
                return@forEach
            }
            val observed = (clock.nowMillis() - started).coerceAtLeast(0)
            val attemptLatency = maxOf(observed, response.latencyMillis)
            spentMillis += attemptLatency
            if (response.warning) warnings += VisionWarning.PROVIDER_REPORTED_WARNING

            if (attemptLatency > remaining) {
                attempts += VisionAttempt(
                    sequence, descriptor.providerId, descriptor.location, true, attemptLatency,
                    VisionAttemptDisposition.TIMED_OUT, VisionFailureReason.PROVIDER_TIMEOUT,
                )
                terminalFailure = VisionFailureReason.BUDGET_EXHAUSTED
                return@forEach
            }

            when (response) {
                is ProviderVisionResponse.Failure -> {
                    val disposition = if (response.reason == VisionFailureReason.PROVIDER_TIMEOUT) {
                        VisionAttemptDisposition.TIMED_OUT
                    } else {
                        VisionAttemptDisposition.FAILED
                    }
                    attempts += VisionAttempt(
                        sequence, descriptor.providerId, descriptor.location, true, attemptLatency,
                        disposition, response.reason,
                    )
                    terminalFailure = response.reason
                }
                is ProviderVisionResponse.Success -> {
                    val validationFailure = validate(response, request)
                    if (validationFailure != null) {
                        attempts += VisionAttempt(
                            sequence, descriptor.providerId, descriptor.location, true, attemptLatency,
                            VisionAttemptDisposition.INVALID_RESULT, validationFailure,
                        )
                        warnings += VisionWarning.PARTIAL_EVIDENCE_DISCARDED
                        terminalFailure = validationFailure
                    } else {
                        attempts += VisionAttempt(
                            sequence, descriptor.providerId, descriptor.location, true, attemptLatency,
                            VisionAttemptDisposition.SUCCEEDED,
                        )
                        if (attempts.size > 1) warnings += VisionWarning.FALLBACK_USED
                        return VisionResult(
                            requestId = request.requestId,
                            status = VisionResultStatus.SUCCESS,
                            providerId = descriptor.providerId,
                            evidence = response.evidence.sortedWith(
                                compareBy<VisionEvidence>({ it.kind.name }, { it.valueRef }),
                            ),
                            confidence = response.confidence,
                            latencyMillis = spentMillis,
                            attempts = attempts.toList(),
                            warnings = warnings.toSet(),
                            structuredRouting = structured,
                        )
                    }
                }
            }
        }

        val budgetExhausted = spentMillis >= request.latencyBudgetMillis ||
            attempts.any { it.disposition == VisionAttemptDisposition.SKIPPED_BUDGET }
        val onlyPolicyOrPrivacy = attempts.isNotEmpty() && attempts.all {
            it.disposition in setOf(
                VisionAttemptDisposition.SKIPPED_POLICY,
                VisionAttemptDisposition.SKIPPED_PRIVACY,
                VisionAttemptDisposition.SKIPPED_UNSUPPORTED,
                VisionAttemptDisposition.SKIPPED_UNAVAILABLE,
            )
        } && attempts.any {
            it.disposition == VisionAttemptDisposition.SKIPPED_POLICY ||
                it.disposition == VisionAttemptDisposition.SKIPPED_PRIVACY
        }
        return VisionResult(
            requestId = request.requestId,
            status = when {
                budgetExhausted -> VisionResultStatus.BUDGET_EXHAUSTED
                onlyPolicyOrPrivacy -> VisionResultStatus.POLICY_DENIED
                else -> VisionResultStatus.UNAVAILABLE
            },
            latencyMillis = spentMillis,
            attempts = attempts.toList(),
            warnings = warnings.toSet(),
            failureReason = when {
                budgetExhausted -> VisionFailureReason.BUDGET_EXHAUSTED
                onlyPolicyOrPrivacy && attempts.any { it.failureReason == VisionFailureReason.REMOTE_PRIVACY_DENIED } ->
                    VisionFailureReason.REMOTE_PRIVACY_DENIED
                onlyPolicyOrPrivacy -> VisionFailureReason.REMOTE_POLICY_DENIED
                else -> terminalFailure
            },
            structuredRouting = structured,
        )
    }

    private fun validate(response: ProviderVisionResponse.Success, request: VisionRequest): VisionFailureReason? {
        if (response.evidence.isEmpty()) return VisionFailureReason.INVALID_PROVIDER_RESULT
        if (!response.evidence.all { it.region == null || it.region.fits(request.imageRef) }) {
            return VisionFailureReason.INVALID_PROVIDER_RESULT
        }
        if (!response.evidence.map { it.kind }.toSet().containsAll(request.requiredEvidence)) {
            return VisionFailureReason.MISSING_REQUIRED_EVIDENCE
        }
        return null
    }

    private companion object {
        val PROVIDER_ORDER = compareBy<VisionProvider>(
            { locationRank(it.descriptor.location) },
            { it.descriptor.priority },
            { it.descriptor.providerId },
        )

        fun locationRank(location: VisionProviderLocation): Int = when (location) {
            VisionProviderLocation.ON_DEVICE_LOCAL -> 0
            VisionProviderLocation.PC_GATEWAY -> 1
            VisionProviderLocation.REMOTE_SERVICE -> 2
        }
    }
}
