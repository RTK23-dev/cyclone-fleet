package com.cyclone.mobile.ai.vision

import com.cyclone.mobile.platform.event.DataClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionRouterTest {
    @Test
    fun `no configured provider returns clean unavailable`() {
        val result = VisionRouter(emptyList()).route(request())

        assertEquals(VisionResultStatus.UNAVAILABLE, result.status)
        assertEquals(VisionFailureReason.NO_PROVIDER_CONFIGURED, result.failureReason)
        assertTrue(result.attempts.isEmpty())
        assertTrue(result.evidence.isEmpty())
        assertNull(result.providerId)
    }

    @Test
    fun `each structured evidence layer can skip vision before providers`() {
        val provider = FakeProvider("local", VisionProviderLocation.ON_DEVICE_LOCAL, success())
        val states = listOf(
            StructuredEvidenceState(EvidenceSufficiency.SUFFICIENT, EvidenceSufficiency.INSUFFICIENT, EvidenceSufficiency.INSUFFICIENT) to StructuredEvidenceStage.PAGE_AWARENESS,
            StructuredEvidenceState(EvidenceSufficiency.INSUFFICIENT, EvidenceSufficiency.SUFFICIENT, EvidenceSufficiency.INSUFFICIENT) to StructuredEvidenceStage.APP_GRAPH,
            StructuredEvidenceState(EvidenceSufficiency.UNAVAILABLE, EvidenceSufficiency.INSUFFICIENT, EvidenceSufficiency.SUFFICIENT) to StructuredEvidenceStage.DETERMINISTIC_SEMANTIC_SEARCH,
        )

        states.forEach { (state, expectedStage) ->
            val result = VisionRouter(listOf(provider)).route(request(structured = state))
            assertEquals(VisionResultStatus.SKIPPED_STRUCTURED_SUFFICIENT, result.status)
            assertEquals(expectedStage, result.structuredRouting.resolvedAt)
            assertTrue(result.attempts.isEmpty())
        }
        assertEquals(0, provider.invocations)
    }

    @Test
    fun `provider failure falls back explicitly within remaining budget`() {
        val first = FakeProvider(
            "local-a", VisionProviderLocation.ON_DEVICE_LOCAL,
            ProviderVisionResponse.Failure(VisionFailureReason.PROVIDER_FAILURE, 30),
        )
        val second = FakeProvider("local-b", VisionProviderLocation.ON_DEVICE_LOCAL, success(latency = 20))

        val result = VisionRouter(listOf(second, first)).route(request(latencyBudget = 100))

        assertEquals(VisionResultStatus.SUCCESS, result.status)
        assertEquals("local-b", result.providerId)
        assertEquals(listOf("local-a", "local-b"), result.attempts.map { it.providerId })
        assertEquals(listOf(VisionAttemptDisposition.FAILED, VisionAttemptDisposition.SUCCEEDED), result.attempts.map { it.disposition })
        assertEquals(listOf(100L), first.receivedBudgets)
        assertEquals(listOf(70L), second.receivedBudgets)
        assertEquals(50, result.latencyMillis)
        assertTrue(VisionWarning.FALLBACK_USED in result.warnings)
    }

    @Test
    fun `provider timeout can fall back when budget remains`() {
        val timedOut = FakeProvider(
            "local-timeout", VisionProviderLocation.ON_DEVICE_LOCAL,
            ProviderVisionResponse.Failure(VisionFailureReason.PROVIDER_TIMEOUT, 60),
            priority = 0,
        )
        val recovery = FakeProvider("local-recovery", VisionProviderLocation.ON_DEVICE_LOCAL, success(latency = 25), priority = 1)

        val result = VisionRouter(listOf(recovery, timedOut)).route(request(latencyBudget = 100))

        assertEquals(VisionResultStatus.SUCCESS, result.status)
        assertEquals(VisionAttemptDisposition.TIMED_OUT, result.attempts.first().disposition)
        assertEquals(listOf(40L), recovery.receivedBudgets)
    }

    @Test
    fun `budget overrun prevents later provider invocation but remains observable`() {
        val slow = FakeProvider("local-slow", VisionProviderLocation.ON_DEVICE_LOCAL, success(latency = 101), priority = 0)
        val later = FakeProvider("local-later", VisionProviderLocation.ON_DEVICE_LOCAL, success(), priority = 1)

        val result = VisionRouter(listOf(later, slow)).route(request(latencyBudget = 100))

        assertEquals(VisionResultStatus.BUDGET_EXHAUSTED, result.status)
        assertEquals(VisionAttemptDisposition.TIMED_OUT, result.attempts[0].disposition)
        assertEquals(VisionAttemptDisposition.SKIPPED_BUDGET, result.attempts[1].disposition)
        assertEquals(0, later.invocations)
        assertTrue(result.evidence.isEmpty())
    }

    @Test
    fun `restricted data and unconfirmed authority fail closed for off-device providers`() {
        val remote = FakeProvider(
            "remote", VisionProviderLocation.REMOTE_SERVICE, success(),
            maximumClassification = DataClassification.RESTRICTED,
        )
        val restricted = VisionRouter(listOf(remote)).route(
            request(classification = DataClassification.RESTRICTED, remote = RemoteVisionAuthorization.ALLOWED),
        )
        val unconfirmed = VisionRouter(listOf(remote)).route(
            request(classification = DataClassification.INTERNAL, remote = RemoteVisionAuthorization.UNCONFIRMED),
        )

        assertEquals(VisionFailureReason.REMOTE_PRIVACY_DENIED, restricted.failureReason)
        assertEquals(VisionAttemptDisposition.SKIPPED_PRIVACY, restricted.attempts.single().disposition)
        assertEquals(VisionFailureReason.REMOTE_POLICY_DENIED, unconfirmed.failureReason)
        assertEquals(VisionAttemptDisposition.SKIPPED_POLICY, unconfirmed.attempts.single().disposition)
        assertEquals(0, remote.invocations)
    }

    @Test
    fun `local provider is preferred over lower priority remote provider`() {
        val remote = FakeProvider("remote", VisionProviderLocation.REMOTE_SERVICE, success(), priority = 0)
        val local = FakeProvider("local", VisionProviderLocation.ON_DEVICE_LOCAL, success(), priority = 99)

        val result = VisionRouter(listOf(remote, local)).route(
            request(remote = RemoteVisionAuthorization.ALLOWED),
        )

        assertEquals("local", result.providerId)
        assertEquals(1, local.invocations)
        assertEquals(0, remote.invocations)
        assertEquals(listOf("local"), result.attempts.map { it.providerId })
    }

    @Test
    fun `same location and priority use stable provider id order`() {
        val zeta = FakeProvider("zeta", VisionProviderLocation.ON_DEVICE_LOCAL, success(), priority = 4)
        val alpha = FakeProvider("alpha", VisionProviderLocation.ON_DEVICE_LOCAL, success(), priority = 4)

        val result = VisionRouter(listOf(zeta, alpha)).route(request())

        assertEquals("alpha", result.providerId)
        assertEquals(0, zeta.invocations)
    }

    @Test
    fun `missing required evidence invalidates result and triggers audited fallback`() {
        val invalid = FakeProvider(
            "local-invalid", VisionProviderLocation.ON_DEVICE_LOCAL,
            ProviderVisionResponse.Success(
                evidence = listOf(evidence(VisionEvidenceKind.PAGE_IDENTITY)),
                confidence = .9,
                latencyMillis = 10,
            ),
            priority = 0,
        )
        val valid = FakeProvider("local-valid", VisionProviderLocation.ON_DEVICE_LOCAL, success(), priority = 1)

        val result = VisionRouter(listOf(valid, invalid)).route(
            request(required = setOf(VisionEvidenceKind.CONTROL_BOUNDS)),
        )

        assertEquals("local-valid", result.providerId)
        assertEquals(VisionAttemptDisposition.INVALID_RESULT, result.attempts.first().disposition)
        assertEquals(VisionFailureReason.MISSING_REQUIRED_EVIDENCE, result.attempts.first().failureReason)
        assertTrue(VisionWarning.PARTIAL_EVIDENCE_DISCARDED in result.warnings)
    }

    @Test
    fun `provider exception is sanitized to typed failure and can fall back`() {
        val throwing = FakeProvider("local-throw", VisionProviderLocation.ON_DEVICE_LOCAL, success(), priority = 0, throws = true)
        val valid = FakeProvider("local-valid", VisionProviderLocation.ON_DEVICE_LOCAL, success(), priority = 1)

        val result = VisionRouter(listOf(throwing, valid)).route(request())

        assertEquals(VisionAttemptDisposition.FAILED, result.attempts.first().disposition)
        assertEquals(VisionFailureReason.PROVIDER_EXCEPTION, result.attempts.first().failureReason)
        assertFalse(result.toString().contains("super secret provider failure"))
        assertEquals("local-valid", result.providerId)
    }

    @Test
    fun `router and provider contracts expose observation but no action authority`() {
        val forbidden = Regex("execute|propose|mutate|command", RegexOption.IGNORE_CASE)
        val routerMethods = VisionRouter::class.java.declaredMethods.map { it.name }
        val providerMethods = VisionProvider::class.java.declaredMethods.map { it.name }
        val resultFields = VisionResult::class.java.declaredFields.map { it.name }

        assertTrue(routerMethods.none(forbidden::containsMatchIn))
        assertTrue(providerMethods.none(forbidden::containsMatchIn))
        assertTrue(resultFields.none(forbidden::containsMatchIn))
        assertTrue(VisionEvidenceKind.values().none { forbidden.containsMatchIn(it.name) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate provider ids are rejected instead of registration-order selection`() {
        VisionRouter(
            listOf(
                FakeProvider("duplicate", VisionProviderLocation.ON_DEVICE_LOCAL, success()),
                FakeProvider("duplicate", VisionProviderLocation.REMOTE_SERVICE, success()),
            ),
        )
    }

    private fun request(
        structured: StructuredEvidenceState = StructuredEvidenceState(
            EvidenceSufficiency.INSUFFICIENT,
            EvidenceSufficiency.INSUFFICIENT,
            EvidenceSufficiency.INSUFFICIENT,
        ),
        latencyBudget: Long = 500,
        classification: DataClassification = DataClassification.INTERNAL,
        remote: RemoteVisionAuthorization = RemoteVisionAuthorization.DENIED,
        required: Set<VisionEvidenceKind> = setOf(VisionEvidenceKind.CONTROL_BOUNDS),
    ) = VisionRequest(
        requestId = "request-test",
        imageRef = VisionImageRef("0".repeat(64), 1080, 2400),
        purpose = VisionPurpose.CONTROL_DISCOVERY,
        region = VisionRegion(0, 0, 100, 100),
        requiredEvidence = required,
        privacyClassification = classification,
        latencyBudgetMillis = latencyBudget,
        structuredEvidence = structured,
        remoteAuthorization = remote,
    )

    private fun evidence(kind: VisionEvidenceKind = VisionEvidenceKind.CONTROL_BOUNDS) =
        VisionEvidence(kind, "1".repeat(64), .93, VisionRegion(1, 1, 10, 10))

    private fun success(latency: Long = 10) = ProviderVisionResponse.Success(
        evidence = listOf(evidence()),
        confidence = .91,
        latencyMillis = latency,
    )

    private class FakeProvider(
        providerId: String,
        location: VisionProviderLocation,
        private val response: ProviderVisionResponse,
        priority: Int = 0,
        maximumClassification: DataClassification = DataClassification.SENSITIVE,
        private val healthState: VisionProviderHealth = VisionProviderHealth.AVAILABLE,
        private val throws: Boolean = false,
    ) : VisionProvider {
        override val descriptor = VisionProviderDescriptor(
            providerId = providerId,
            location = location,
            priority = priority,
            supportedPurposes = setOf(VisionPurpose.CONTROL_DISCOVERY),
            maximumClassification = maximumClassification,
        )
        var invocations: Int = 0
        val receivedBudgets = mutableListOf<Long>()

        override fun health(): VisionProviderHealth = healthState

        override fun observe(request: VisionRequest, remainingBudgetMillis: Long): ProviderVisionResponse {
            invocations += 1
            receivedBudgets += remainingBudgetMillis
            if (throws) error("super secret provider failure")
            return response
        }
    }
}
